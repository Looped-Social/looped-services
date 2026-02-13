package com.looped.admin;

import com.looped.email.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Component
public class AdminOpsAlertsJob {
    private static final Logger log = LoggerFactory.getLogger(AdminOpsAlertsJob.class);
    private static final String REDIS_HOURLY_SNAPSHOT_KEY = "admin:ops-alerts:hourly:last";
    private static final String REDIS_HOURLY_COOLDOWN_KEY = "admin:ops-alerts:hourly:cooldown";

    private final AdminOpsAlertsProperties props;
    private final AdminOpsAlertsRepository metrics;
    private final AdminUsersRepository admins;
    private final EmailService emails;
    private final StringRedisTemplate redis;

    public AdminOpsAlertsJob(AdminOpsAlertsProperties props,
                             AdminOpsAlertsRepository metrics,
                             AdminUsersRepository admins,
                             EmailService emails,
                             StringRedisTemplate redis) {
        this.props = props;
        this.metrics = metrics;
        this.admins = admins;
        this.emails = emails;
        this.redis = redis;
    }

    @Scheduled(cron = "${admin.ops-alerts.daily-cron:0 0 17 * * *}", zone = "${admin.ops-alerts.time-zone:America/New_York}")
    public void sendDailyDigest() {
        if (!isRuntimeEnabled()) return;
        if (!emails.isEnabled()) return;

        List<String> recipients = adminRecipientEmails();
        if (recipients.isEmpty()) return;

        var snapshot = metrics.snapshot();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String subject = "[Looped Admin] Daily moderation backlog digest";
        String text = buildDailyText(now, snapshot);
        String html = buildDailyHtml(now, snapshot);

        for (String to : recipients) {
            emails.sendAdminOpsEmail(to, subject, text, html);
        }
        log.info("Admin ops daily digest sent recipients={} verificationsPending={} reportsOpen={} moderationQueueOpen={}",
                recipients.size(), snapshot.verificationsPending, snapshot.reportsOpen, snapshot.moderationQueueOpen);
    }

    @Scheduled(cron = "${admin.ops-alerts.hourly-cron:0 0 * * * *}", zone = "UTC")
    public void sendHourlyAlertIfNeeded() {
        if (!isRuntimeEnabled()) return;
        if (!emails.isEnabled()) return;

        List<String> recipients = adminRecipientEmails();
        if (recipients.isEmpty()) return;

        var snapshot = metrics.snapshot();
        HourlySnapshot previous = readHourlySnapshot();
        writeHourlySnapshot(snapshot);

        List<String> reasons = evaluateHourlyReasons(snapshot, previous);
        if (reasons.isEmpty()) return;

        if (!reserveHourlyCooldown()) {
            log.info("Admin ops hourly alert suppressed by cooldown verificationsPending={} reportsOpen={} moderationQueueOpen={}",
                    snapshot.verificationsPending, snapshot.reportsOpen, snapshot.moderationQueueOpen);
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String subject = "[Looped Admin] Hourly backlog alert";
        String text = buildHourlyText(now, snapshot, previous, reasons);
        String html = buildHourlyHtml(now, snapshot, previous, reasons);
        for (String to : recipients) {
            emails.sendAdminOpsEmail(to, subject, text, html);
        }
        log.warn("Admin ops hourly alert sent recipients={} reasons={} verificationsPending={} reportsOpen={} moderationQueueOpen={}",
                recipients.size(), reasons.size(), snapshot.verificationsPending, snapshot.reportsOpen, snapshot.moderationQueueOpen);
    }

    private List<String> adminRecipientEmails() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (var admin : admins.listAll()) {
            if (!"active".equalsIgnoreCase(admin.status)) continue;
            String email = normalize(admin.email);
            if (email == null) continue;
            out.add(email);
        }
        return List.copyOf(out);
    }

    private boolean isRuntimeEnabled() {
        if (!props.isEnabled()) return false;
        String required = normalize(props.getRequiredEnvironment());
        if (required == null) return true;
        String current = normalize(props.getEnvironment());
        boolean allowed = required.equals(current);
        if (!allowed) {
            log.debug("Admin ops alerts disabled for environment current={} required={}", current, required);
        }
        return allowed;
    }

    private List<String> evaluateHourlyReasons(AdminOpsAlertsRepository.BacklogSnapshot now, HourlySnapshot prev) {
        List<String> reasons = new ArrayList<>();

        if (now.verificationsPending >= props.getVerificationPendingThreshold()) {
            reasons.add("Pending verifications reached " + now.verificationsPending +
                    " (threshold " + props.getVerificationPendingThreshold() + ")");
        }
        if (now.reportsOpen >= props.getReportsOpenThreshold()) {
            reasons.add("Open reports reached " + now.reportsOpen +
                    " (threshold " + props.getReportsOpenThreshold() + ")");
        }
        if (now.moderationQueueOpen >= props.getModerationQueueOpenThreshold()) {
            reasons.add("Open moderation queue items reached " + now.moderationQueueOpen +
                    " (threshold " + props.getModerationQueueOpenThreshold() + ")");
        }

        if (prev != null) {
            addSpikeReason(reasons, "Pending verifications", prev.verificationsPending, now.verificationsPending);
            addSpikeReason(reasons, "Open reports", prev.reportsOpen, now.reportsOpen);
            addSpikeReason(reasons, "Open moderation queue items", prev.moderationQueueOpen, now.moderationQueueOpen);
        }

        return reasons;
    }

    private void addSpikeReason(List<String> reasons, String label, long previous, long current) {
        if (current <= previous) return;
        long delta = current - previous;
        if (delta < Math.max(1, props.getSpikeAbsoluteThreshold())) return;

        double fraction;
        if (previous <= 0) {
            fraction = 1.0d;
        } else {
            fraction = (double) delta / (double) previous;
        }
        if (fraction < Math.max(0.0d, props.getSpikeFractionThreshold())) return;

        reasons.add(label + " increased by " + percent(fraction) +
                " in the last hour (" + previous + " -> " + current + ")");
    }

    private String buildDailyText(OffsetDateTime now, AdminOpsAlertsRepository.BacklogSnapshot s) {
        StringBuilder out = new StringBuilder();
        out.append("Looped Admin Daily Backlog Digest\n");
        out.append("Generated UTC: ").append(fmt(now)).append("\n\n");

        out.append("Queue status\n");
        out.append("- Pending verifications: ").append(s.verificationsPending)
                .append(" (submitted 24h: ").append(s.verificationsSubmitted24h)
                .append(", reviewed 24h: ").append(s.verificationsReviewed24h)
                .append(", oldest: ").append(ageLabel(s.verificationsOldestPendingAt, now)).append(")\n");
        out.append("- Open reports: ").append(s.reportsOpen)
                .append(" (created 24h: ").append(s.reportsCreated24h)
                .append(", reviewed 24h: ").append(s.reportsReviewed24h)
                .append(", oldest: ").append(ageLabel(s.reportsOldestOpenAt, now)).append(")\n");
        out.append("- Open moderation queue: ").append(s.moderationQueueOpen)
                .append(" (created 24h: ").append(s.moderationQueueCreated24h)
                .append(", reviewed 24h: ").append(s.moderationQueueReviewed24h)
                .append(", oldest: ").append(ageLabel(s.moderationQueueOldestOpenAt, now)).append(")\n\n");

        out.append("Alert thresholds\n");
        out.append("- Pending verifications >= ").append(props.getVerificationPendingThreshold()).append("\n");
        out.append("- Open reports >= ").append(props.getReportsOpenThreshold()).append("\n");
        out.append("- Open moderation queue >= ").append(props.getModerationQueueOpenThreshold()).append("\n");
        out.append("- Hourly spike >= ").append(percent(Math.max(0.0d, props.getSpikeFractionThreshold())))
                .append(" and +").append(Math.max(1, props.getSpikeAbsoluteThreshold())).append(" items\n");

        appendLinks(out);
        return out.toString();
    }

    private String buildHourlyText(OffsetDateTime now,
                                   AdminOpsAlertsRepository.BacklogSnapshot s,
                                   HourlySnapshot prev,
                                   List<String> reasons) {
        StringBuilder out = new StringBuilder();
        out.append("Looped Admin Hourly Backlog Alert\n");
        out.append("Generated UTC: ").append(fmt(now)).append("\n\n");
        out.append("Why this alert fired\n");
        for (String reason : reasons) {
            out.append("- ").append(reason).append("\n");
        }
        out.append("\nCurrent queue status\n");
        out.append("- Pending verifications: ").append(s.verificationsPending).append("\n");
        out.append("- Open reports: ").append(s.reportsOpen).append("\n");
        out.append("- Open moderation queue: ").append(s.moderationQueueOpen).append("\n");
        if (prev != null) {
            out.append("\nPrevious hourly snapshot\n");
            out.append("- Pending verifications: ").append(prev.verificationsPending).append("\n");
            out.append("- Open reports: ").append(prev.reportsOpen).append("\n");
            out.append("- Open moderation queue: ").append(prev.moderationQueueOpen).append("\n");
        }

        appendLinks(out);
        return out.toString();
    }

    private String buildDailyHtml(OffsetDateTime now, AdminOpsAlertsRepository.BacklogSnapshot s) {
        StringBuilder out = new StringBuilder();
        out.append("<div style=\"font-size:12px;color:#6b7280;padding-bottom:16px;\">Generated UTC: ")
                .append(escape(fmt(now)))
                .append("</div>");

        out.append("<div style=\"font-size:14px;font-weight:700;color:#1f2937;padding-bottom:10px;\">Queue status</div>");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\">");
        appendDailyQueueCard(out,
                "Pending verifications",
                s.verificationsPending,
                s.verificationsSubmitted24h,
                s.verificationsReviewed24h,
                ageLabel(s.verificationsOldestPendingAt, now));
        appendDailyQueueCard(out,
                "Open reports",
                s.reportsOpen,
                s.reportsCreated24h,
                s.reportsReviewed24h,
                ageLabel(s.reportsOldestOpenAt, now));
        appendDailyQueueCard(out,
                "Open moderation queue",
                s.moderationQueueOpen,
                s.moderationQueueCreated24h,
                s.moderationQueueReviewed24h,
                ageLabel(s.moderationQueueOldestOpenAt, now));
        out.append("</table>");

        out.append("<div style=\"font-size:14px;font-weight:700;color:#1f2937;padding:14px 0 8px;\">Alert thresholds</div>");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"font-size:13px;color:#374151;line-height:1.55;\">");
        appendBulletRow(out, "Pending verifications >= " + props.getVerificationPendingThreshold());
        appendBulletRow(out, "Open reports >= " + props.getReportsOpenThreshold());
        appendBulletRow(out, "Open moderation queue >= " + props.getModerationQueueOpenThreshold());
        appendBulletRow(out, "Hourly spike >= " + percent(Math.max(0.0d, props.getSpikeFractionThreshold()))
                + " and +" + Math.max(1, props.getSpikeAbsoluteThreshold()) + " items");
        out.append("</table>");

        appendLinksHtml(out);
        return out.toString();
    }

    private String buildHourlyHtml(OffsetDateTime now,
                                   AdminOpsAlertsRepository.BacklogSnapshot s,
                                   HourlySnapshot prev,
                                   List<String> reasons) {
        StringBuilder out = new StringBuilder();
        out.append("<div style=\"font-size:12px;color:#6b7280;padding-bottom:16px;\">Generated UTC: ")
                .append(escape(fmt(now)))
                .append("</div>");

        out.append("<div style=\"font-size:14px;font-weight:700;color:#1f2937;padding-bottom:8px;\">Why this alert fired</div>");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"font-size:13px;color:#374151;line-height:1.55;\">");
        for (String reason : reasons) {
            appendBulletRow(out, reason);
        }
        out.append("</table>");

        out.append("<div style=\"font-size:14px;font-weight:700;color:#1f2937;padding:14px 0 8px;\">Current queue status</div>");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\">");
        appendSimpleMetricCard(out, "Pending verifications", s.verificationsPending);
        appendSimpleMetricCard(out, "Open reports", s.reportsOpen);
        appendSimpleMetricCard(out, "Open moderation queue", s.moderationQueueOpen);
        out.append("</table>");

        if (prev != null) {
            out.append("<div style=\"font-size:14px;font-weight:700;color:#1f2937;padding:14px 0 8px;\">Previous hourly snapshot</div>");
            out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\">");
            appendSimpleMetricCard(out, "Pending verifications", prev.verificationsPending);
            appendSimpleMetricCard(out, "Open reports", prev.reportsOpen);
            appendSimpleMetricCard(out, "Open moderation queue", prev.moderationQueueOpen);
            out.append("</table>");
        }

        appendLinksHtml(out);
        return out.toString();
    }

    private void appendDailyQueueCard(StringBuilder out,
                                      String label,
                                      long openCount,
                                      long created24h,
                                      long reviewed24h,
                                      String oldestAge) {
        out.append("<tr><td style=\"padding:0 0 10px 0;\">");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"border:1px solid #f3f4f6;border-radius:10px;background-color:#fafafa;\">");
        out.append("<tr><td style=\"padding:12px 14px 0 14px;font-size:13px;font-weight:600;color:#111827;\">")
                .append(escape(label))
                .append("</td></tr>");
        out.append("<tr><td style=\"padding:2px 14px 0 14px;font-size:22px;font-weight:700;color:#1f2937;\">")
                .append(openCount)
                .append("</td></tr>");
        out.append("<tr><td style=\"padding:6px 14px 12px 14px;font-size:12px;color:#6b7280;line-height:1.5;\">");
        out.append("Last 24h: ").append(created24h).append(" created, ").append(reviewed24h).append(" reviewed");
        out.append(" | Oldest pending: ").append(escape(oldestAge));
        out.append("</td></tr>");
        out.append("</table></td></tr>");
    }

    private void appendSimpleMetricCard(StringBuilder out, String label, long value) {
        out.append("<tr><td style=\"padding:0 0 8px 0;\">");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"border:1px solid #f3f4f6;border-radius:10px;background-color:#fafafa;\">");
        out.append("<tr>");
        out.append("<td style=\"padding:11px 14px;font-size:13px;font-weight:600;color:#111827;\">")
                .append(escape(label))
                .append("</td>");
        out.append("<td align=\"right\" style=\"padding:11px 14px;font-size:18px;font-weight:700;color:#1f2937;\">")
                .append(value)
                .append("</td>");
        out.append("</tr>");
        out.append("</table></td></tr>");
    }

    private void appendBulletRow(StringBuilder out, String line) {
        out.append("<tr><td style=\"padding:0 0 6px 0;\">")
                .append("&#8226; ")
                .append(escape(line))
                .append("</td></tr>");
    }

    private void appendLinksHtml(StringBuilder out) {
        String base = normalizeBaseUrl(props.getAdminBaseUrl());
        if (base == null) return;

        out.append("<div style=\"font-size:14px;font-weight:700;color:#1f2937;padding:14px 0 10px;\">Admin links</div>");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\">");
        appendLinkCard(out, "Verifications", joinUrl(base, "/verifications"));
        appendLinkCard(out, "Reports", joinUrl(base, "/reports"));
        appendLinkCard(out, "Moderation queue", joinUrl(base, "/moderation/queue"));
        out.append("</table>");
    }

    private void appendLinkCard(StringBuilder out, String label, String url) {
        if (url == null || url.isBlank()) return;
        out.append("<tr><td style=\"padding:0 0 8px 0;\">");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"border:1px solid #f3f4f6;border-radius:10px;background-color:#ffffff;\">");
        out.append("<tr>");
        out.append("<td style=\"padding:11px 14px;font-size:13px;font-weight:600;color:#111827;\">")
                .append(escape(label))
                .append("</td>");
        out.append("<td align=\"right\" style=\"padding:11px 14px;font-size:13px;\">");
        out.append("<a href=\"").append(escape(url)).append("\" style=\"color:#ea404a;text-decoration:none;font-weight:600;\">Open</a>");
        out.append("</td>");
        out.append("</tr>");
        out.append("</table></td></tr>");
    }

    private void appendLinks(StringBuilder out) {
        String base = normalizeBaseUrl(props.getAdminBaseUrl());
        if (base == null) return;
        out.append("\nAdmin links\n");
        out.append("- Verifications: ").append(joinUrl(base, "/verifications")).append("\n");
        out.append("- Reports: ").append(joinUrl(base, "/reports")).append("\n");
        out.append("- Moderation queue: ").append(joinUrl(base, "/moderation/queue")).append("\n");
    }

    private HourlySnapshot readHourlySnapshot() {
        try {
            String raw = redis.opsForValue().get(REDIS_HOURLY_SNAPSHOT_KEY);
            if (raw == null || raw.isBlank()) return null;
            String[] parts = raw.split("\\|");
            if (parts.length != 3) return null;
            long verifications = parseLong(parts[0]);
            long reports = parseLong(parts[1]);
            long queue = parseLong(parts[2]);
            return new HourlySnapshot(verifications, reports, queue);
        } catch (Exception e) {
            log.warn("Failed reading hourly admin ops snapshot from redis: {}", e.getMessage());
            return null;
        }
    }

    private void writeHourlySnapshot(AdminOpsAlertsRepository.BacklogSnapshot s) {
        try {
            String value = s.verificationsPending + "|" + s.reportsOpen + "|" + s.moderationQueueOpen;
            redis.opsForValue().set(REDIS_HOURLY_SNAPSHOT_KEY, value, Duration.ofDays(2));
        } catch (Exception e) {
            log.warn("Failed writing hourly admin ops snapshot to redis: {}", e.getMessage());
        }
    }

    private boolean reserveHourlyCooldown() {
        try {
            Duration cooldown = props.getHourlyCooldown() == null || props.getHourlyCooldown().isNegative()
                    ? Duration.ofHours(2)
                    : props.getHourlyCooldown();
            Boolean reserved = redis.opsForValue().setIfAbsent(
                    REDIS_HOURLY_COOLDOWN_KEY,
                    fmt(OffsetDateTime.now(ZoneOffset.UTC)),
                    cooldown
            );
            return Boolean.TRUE.equals(reserved);
        } catch (Exception e) {
            // Fail safe: if redis is unavailable, skip hourly alerts to avoid accidental alert storms.
            log.warn("Failed reserving hourly admin ops cooldown; skipping alert send: {}", e.getMessage());
            return false;
        }
    }

    private long parseLong(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private String fmt(OffsetDateTime dt) {
        if (dt == null) return "";
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dt);
    }

    private String ageLabel(OffsetDateTime ts, OffsetDateTime now) {
        if (ts == null || now == null) return "n/a";
        Duration d = Duration.between(ts, now);
        if (d.isNegative()) d = Duration.ZERO;
        long totalMinutes = d.toMinutes();
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes % (60 * 24)) / 60;
        long minutes = totalMinutes % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private String percent(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", fraction * 100.0d);
    }

    private String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isBlank()) return null;
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String normalizeBaseUrl(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isBlank()) return null;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isBlank() ? null : trimmed;
    }

    private String joinUrl(String base, String path) {
        if (base == null || path == null) return null;
        if (path.startsWith("/")) return base + path;
        return base + "/" + path;
    }

    private String escape(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record HourlySnapshot(long verificationsPending, long reportsOpen, long moderationQueueOpen) {}
}
