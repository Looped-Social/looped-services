package com.looped.admin;

import com.looped.email.EmailService;
import com.looped.feed.GlobalFypRequestMetricsService;
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
public class AdminFypScaleAlertsJob {
    private static final Logger log = LoggerFactory.getLogger(AdminFypScaleAlertsJob.class);
    private static final String REDIS_COOLDOWN_KEY = "admin:fyp-alerts:cooldown";

    private final AdminFypScaleAlertsProperties props;
    private final AdminFypScaleAlertsRepository metrics;
    private final GlobalFypRequestMetricsService globalFypMetrics;
    private final AdminUsersRepository admins;
    private final EmailService emails;
    private final StringRedisTemplate redis;

    public AdminFypScaleAlertsJob(AdminFypScaleAlertsProperties props,
                                 AdminFypScaleAlertsRepository metrics,
                                 GlobalFypRequestMetricsService globalFypMetrics,
                                 AdminUsersRepository admins,
                                 EmailService emails,
                                 StringRedisTemplate redis) {
        this.props = props;
        this.metrics = metrics;
        this.globalFypMetrics = globalFypMetrics;
        this.admins = admins;
        this.emails = emails;
        this.redis = redis;
    }

    @Scheduled(cron = "${admin.fyp-alerts.cron:0 30 * * * *}", zone = "${admin.fyp-alerts.time-zone:America/New_York}")
    public void sendIfNeeded() {
        if (!isRuntimeEnabled()) return;
        if (!emails.isEnabled()) return;

        List<String> recipients = adminRecipientEmails();
        if (recipients.isEmpty()) return;

        AdminFypScaleAlertsRepository.FypScaleSnapshot db = metrics.snapshot();
        GlobalFypRequestMetricsService.Snapshot perf = globalFypMetrics.snapshotLastMinutes(props.getPerfWindowMinutes());

        List<String> reasons = evaluateReasons(db, perf);
        if (reasons.isEmpty()) return;

        if (!reserveCooldown()) {
            log.info("Admin FYP scale alert suppressed by cooldown reasons={} telemetryEvents24h={} globalFypSampledReqs={}",
                    reasons.size(), db.telemetryEvents24h, perf.sampledRequests());
            return;
        }

        Severity severity = severity(db, perf);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String subject = severity == Severity.URGENT
                ? "[Looped Admin] FYP scaling alert (urgent)"
                : "[Looped Admin] FYP scaling alert (heads up)";
        String text = buildText(now, severity, reasons, db, perf);
        String html = buildHtml(now, severity, reasons, db, perf);

        for (String to : recipients) {
            emails.sendAdminOpsEmail(to, subject, text, html);
        }
        log.warn("Admin FYP scale alert sent recipients={} severity={} reasons={} telemetryEvents24h={} globalFypSampledReqs={}",
                recipients.size(), severity, reasons.size(), db.telemetryEvents24h, perf.sampledRequests());
    }

    private List<String> evaluateReasons(AdminFypScaleAlertsRepository.FypScaleSnapshot db,
                                         GlobalFypRequestMetricsService.Snapshot perf) {
        List<String> reasons = new ArrayList<>();

        boolean perfOkToUse = perf != null && perf.sampledRequests() >= Math.max(0, props.getPerfMinSampledRequests());
        if (perfOkToUse) {
            double estRps = estimatedRps(perf);
            if (estRps >= Math.max(0.0d, props.getGlobalFypEstimatedRpsThreshold())) {
                reasons.add("Global For You traffic reached ~" + fmt1(estRps) + " req/s over the last "
                        + perf.windowMinutes() + "m (threshold " + fmt1(props.getGlobalFypEstimatedRpsThreshold()) + " req/s)");
            }
            if (perf.p95UpperBoundMs() >= Math.max(0, props.getGlobalFypP95UpperBoundMsThreshold())) {
                reasons.add("Global For You latency p95 reached ~" + perf.p95UpperBoundMs() + "ms (upper-bound bucket) over the last "
                        + perf.windowMinutes() + "m (threshold " + props.getGlobalFypP95UpperBoundMsThreshold() + "ms)");
            }
            if (perf.sampled5xxRate() >= Math.max(0.0d, props.getGlobalFypSampled5xxRateThreshold())) {
                reasons.add("Global For You 5xx rate reached " + percent(perf.sampled5xxRate())
                        + " over the last " + perf.windowMinutes() + "m (threshold " + percent(props.getGlobalFypSampled5xxRateThreshold()) + ")");
            }
        }

        if (db.telemetryEvents24h >= Math.max(0L, props.getTelemetryEvents24hThreshold())) {
            reasons.add("Telemetry volume reached " + compact(db.telemetryEvents24h) + " events/24h (threshold "
                    + compact(props.getTelemetryEvents24hThreshold()) + ")");
        }
        if (db.feedImpressions24h >= Math.max(0L, props.getFeedImpressions24hThreshold())) {
            reasons.add("Feed impressions reached " + compact(db.feedImpressions24h) + " impressions/24h (threshold "
                    + compact(props.getFeedImpressions24hThreshold()) + ")");
        }
        if (db.telemetryTotalBytes >= Math.max(0L, props.getTelemetryTableBytesThreshold())) {
            reasons.add("Telemetry table size reached " + bytesLabel(db.telemetryTotalBytes) + " (threshold "
                    + bytesLabel(props.getTelemetryTableBytesThreshold()) + ")");
        }
        if (db.postsCreated24h >= Math.max(0L, props.getPostsCreated24hThreshold())) {
            reasons.add("Post volume reached " + compact(db.postsCreated24h) + " posts/24h (threshold "
                    + compact(props.getPostsCreated24hThreshold()) + ")");
        }

        // Optional quality tripwire: "interactable-majority" slipping.
        if (db.feedImpressions24h >= 1_000) {
            double share = db.interactableImpressionShare24h();
            if (share > 0.0d && share < clamp01(props.getMinInteractableImpressionShare())) {
                reasons.add("Interactable impression share dropped to " + percent(share)
                        + " (target >= " + percent(props.getMinInteractableImpressionShare()) + ")");
            }
        }

        return reasons;
    }

    private Severity severity(AdminFypScaleAlertsRepository.FypScaleSnapshot db,
                              GlobalFypRequestMetricsService.Snapshot perf) {
        boolean perfOkToUse = perf != null && perf.sampledRequests() >= Math.max(0, props.getPerfMinSampledRequests());
        if (perfOkToUse) {
            if (perf.p95UpperBoundMs() >= 1500) return Severity.URGENT;
            if (perf.sampled5xxRate() >= 0.02d) return Severity.URGENT;
            if (estimatedRps(perf) >= Math.max(1.0d, props.getGlobalFypEstimatedRpsThreshold()) * 2.0d) return Severity.URGENT;
        }
        if (db.telemetryTotalBytes >= Math.max(1L, props.getTelemetryTableBytesThreshold()) * 2L) return Severity.URGENT;
        return Severity.HEADS_UP;
    }

    private String buildText(OffsetDateTime now,
                             Severity severity,
                             List<String> reasons,
                             AdminFypScaleAlertsRepository.FypScaleSnapshot db,
                             GlobalFypRequestMetricsService.Snapshot perf) {
        StringBuilder out = new StringBuilder();
        out.append("Looped Admin — FYP Scaling Alert (").append(severity.name()).append(")\n");
        out.append("Generated UTC: ").append(fmt(now)).append("\n\n");
        out.append("Why this fired\n");
        for (String reason : reasons) {
            out.append("- ").append(reason).append("\n");
        }

        out.append("\nPerformance (global For You)\n");
        out.append("- Window: last ").append(perf.windowMinutes()).append("m | sample rate: ").append(percent(perf.sampleRate())).append("\n");
        out.append("- Estimated req/s: ~").append(fmt1(estimatedRps(perf))).append(" (sampled reqs: ").append(perf.sampledRequests()).append(")\n");
        out.append("- Latency (upper-bound buckets): p50 ").append(perf.p50UpperBoundMs()).append("ms, p95 ").append(perf.p95UpperBoundMs())
                .append("ms, p99 ").append(perf.p99UpperBoundMs()).append("ms, avg ").append(fmt1(perf.avgMs())).append("ms\n");
        out.append("- Sampled 5xx rate: ").append(percent(perf.sampled5xxRate())).append(" (").append(perf.sampled5xx()).append(" 5xx)\n");

        out.append("\nVolume (last 24h)\n");
        out.append("- Posts created: ").append(db.postsCreated24h).append("\n");
        out.append("- Telemetry events: ").append(db.telemetryEvents24h).append(" (users: ").append(db.telemetryUsers24h).append(")\n");
        out.append("- Feed impressions: ").append(db.feedImpressions24h)
                .append(" (interactable: ").append(percent(db.interactableImpressionShare24h()))
                .append(", avg visible: ").append(fmt1(db.avgVisibleMs24h)).append("ms)\n");
        out.append("- Feed request_ids: ").append(db.feedRequestIds24h).append(" (global For You: ").append(db.globalFypRequestIds24h).append(")\n");
        out.append("- Interaction blocked: ").append(db.interactionBlocked24h).append("\n");
        out.append("- Join intents: ").append(db.communityJoinIntent24h).append(" | Verify intents: ").append(db.communityVerifyIntent24h).append("\n");
        out.append("- Telemetry table size: ").append(bytesLabel(db.telemetryTotalBytes))
                .append(" | DB size: ").append(bytesLabel(db.databaseBytes)).append("\n");

        out.append("\nRecommended next steps (AWS-first)\n");
        for (String s : recommendedSteps(severity, reasons)) {
            out.append("- ").append(s).append("\n");
        }
        appendLinksText(out);
        return out.toString();
    }

    private String buildHtml(OffsetDateTime now,
                             Severity severity,
                             List<String> reasons,
                             AdminFypScaleAlertsRepository.FypScaleSnapshot db,
                             GlobalFypRequestMetricsService.Snapshot perf) {
        StringBuilder out = new StringBuilder();
        out.append("<div style=\"display:flex;align-items:center;gap:10px;padding-bottom:10px;\">");
        out.append("<div style=\"font-size:12px;color:#6b7280;\">Generated UTC: ").append(escape(fmt(now))).append("</div>");
        out.append("<div style=\"margin-left:auto;\">").append(severityPill(severity)).append("</div>");
        out.append("</div>");

        out.append("<div style=\"font-size:14px;font-weight:700;color:#1f2937;padding-bottom:8px;\">Why this alert fired</div>");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"font-size:13px;color:#374151;line-height:1.55;\">");
        for (String reason : reasons) {
            appendBulletRow(out, reason);
        }
        out.append("</table>");

        out.append("<div style=\"font-size:14px;font-weight:700;color:#1f2937;padding:14px 0 8px;\">Performance (global For You)</div>");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\">");
        appendMetricCard(out, "Window", "Last " + perf.windowMinutes() + "m", "Sample rate " + percent(perf.sampleRate()));
        appendMetricCard(out, "Estimated req/s", "~" + fmt1(estimatedRps(perf)),
                "Sampled reqs " + compact(perf.sampledRequests()));
        appendMetricCard(out, "Latency (upper-bound buckets)",
                "p50 " + perf.p50UpperBoundMs() + "ms | p95 " + perf.p95UpperBoundMs() + "ms | p99 " + perf.p99UpperBoundMs() + "ms",
                "avg " + fmt1(perf.avgMs()) + "ms");
        appendMetricCard(out, "Sampled 5xx rate", percent(perf.sampled5xxRate()), compact(perf.sampled5xx()) + " 5xx");
        out.append("</table>");

        out.append("<div style=\"font-size:14px;font-weight:700;color:#1f2937;padding:14px 0 8px;\">Volume (last 24h)</div>");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\">");
        appendMetricCard(out, "Posts created", compact(db.postsCreated24h), "Public & not removed");
        appendMetricCard(out, "Telemetry events", compact(db.telemetryEvents24h), compact(db.telemetryUsers24h) + " users");
        appendMetricCard(out, "Feed impressions", compact(db.feedImpressions24h),
                "Interactable " + percent(db.interactableImpressionShare24h()) + " | Avg visible " + fmt1(db.avgVisibleMs24h) + "ms");
        appendMetricCard(out, "Feed request_ids", compact(db.feedRequestIds24h), "Global FYP " + compact(db.globalFypRequestIds24h));
        appendMetricCard(out, "Blocked interactions", compact(db.interactionBlocked24h),
                "Join intents " + compact(db.communityJoinIntent24h) + " | Verify intents " + compact(db.communityVerifyIntent24h));
        appendMetricCard(out, "Telemetry table size", bytesLabel(db.telemetryTotalBytes), "DB size " + bytesLabel(db.databaseBytes));
        out.append("</table>");

        out.append("<div style=\"font-size:14px;font-weight:700;color:#1f2937;padding:14px 0 8px;\">Recommended next steps (AWS-first)</div>");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"font-size:13px;color:#374151;line-height:1.55;\">");
        for (String step : recommendedSteps(severity, reasons)) {
            appendBulletRow(out, step);
        }
        out.append("</table>");

        appendLinksHtml(out);
        return out.toString();
    }

    private List<String> recommendedSteps(Severity severity, List<String> reasons) {
        List<String> steps = new ArrayList<>();
        steps.add("Move telemetry out of Postgres: API -> Kinesis Firehose -> S3 (raw JSON), then Glue/Athena for queries (keep Postgres telemetry retention short).");
        steps.add("Add a feed-worker (SQS) to precompute a read model (fan-out) for per-user feeds; keep policy constraints (interactable-majority) as a final re-rank.");
        steps.add("Add hot aggregates for ranking (impressions/likes/comments rates) in Redis/DynamoDB; shift from count-based to rate-based scoring.");
        if (severity == Severity.URGENT) {
            steps.add("Immediate mitigation: add short-TTL Redis caching on global FYP pages (user_id+cursor) and reduce candidate window sizes temporarily.");
        } else {
            steps.add("Start with caching/aggregation first; introduce ML candidate generation (Personalize/custom) only once interaction volume is meaningful.");
        }
        return steps;
    }

    private void appendLinksText(StringBuilder out) {
        boolean any = false;
        if (props.getDashboardUrl() != null && !props.getDashboardUrl().isBlank()) {
            if (!any) out.append("\nLinks\n");
            out.append("- Dashboard: ").append(props.getDashboardUrl().trim()).append("\n");
            any = true;
        }
        if (props.getRunbookUrl() != null && !props.getRunbookUrl().isBlank()) {
            if (!any) out.append("\nLinks\n");
            out.append("- Runbook: ").append(props.getRunbookUrl().trim()).append("\n");
        }
    }

    private void appendLinksHtml(StringBuilder out) {
        String dashboard = safeUrl(props.getDashboardUrl());
        String runbook = safeUrl(props.getRunbookUrl());
        if (dashboard == null && runbook == null) return;

        out.append("<div style=\"font-size:14px;font-weight:700;color:#1f2937;padding:14px 0 10px;\">Links</div>");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\">");
        if (dashboard != null) appendLinkCard(out, "Dashboard", dashboard);
        if (runbook != null) appendLinkCard(out, "Runbook", runbook);
        out.append("</table>");
    }

    private void appendLinkCard(StringBuilder out, String label, String url) {
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

    private void appendMetricCard(StringBuilder out, String label, String primary, String secondary) {
        out.append("<tr><td style=\"padding:0 0 8px 0;\">");
        out.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"border:1px solid #f3f4f6;border-radius:10px;background-color:#fafafa;\">");
        out.append("<tr><td style=\"padding:12px 14px 0 14px;font-size:13px;font-weight:600;color:#111827;\">")
                .append(escape(label))
                .append("</td></tr>");
        out.append("<tr><td style=\"padding:2px 14px 0 14px;font-size:20px;font-weight:700;color:#1f2937;\">")
                .append(escape(primary))
                .append("</td></tr>");
        if (secondary != null && !secondary.isBlank()) {
            out.append("<tr><td style=\"padding:6px 14px 12px 14px;font-size:12px;color:#6b7280;line-height:1.5;\">")
                    .append(escape(secondary))
                    .append("</td></tr>");
        } else {
            out.append("<tr><td style=\"padding:10px 14px 12px 14px;\"></td></tr>");
        }
        out.append("</table></td></tr>");
    }

    private void appendBulletRow(StringBuilder out, String text) {
        out.append("<tr><td style=\"padding:3px 0;vertical-align:top;width:14px;color:#ea404a;font-weight:700;\">•</td>");
        out.append("<td style=\"padding:3px 0;\">").append(escape(text)).append("</td></tr>");
    }

    private String severityPill(Severity severity) {
        if (severity == Severity.URGENT) {
            return "<span style=\"display:inline-block;padding:4px 10px;border-radius:999px;background-color:#fee2e2;color:#991b1b;font-size:12px;font-weight:700;\">URGENT</span>";
        }
        return "<span style=\"display:inline-block;padding:4px 10px;border-radius:999px;background-color:#ffedd5;color:#9a3412;font-size:12px;font-weight:700;\">HEADS UP</span>";
    }

    private boolean reserveCooldown() {
        try {
            Duration cooldown = props.getCooldown() == null || props.getCooldown().isNegative()
                    ? Duration.ofHours(24)
                    : props.getCooldown();
            Boolean reserved = redis.opsForValue().setIfAbsent(
                    REDIS_COOLDOWN_KEY,
                    fmt(OffsetDateTime.now(ZoneOffset.UTC)),
                    cooldown
            );
            return Boolean.TRUE.equals(reserved);
        } catch (Exception e) {
            // Fail safe: if redis is unavailable, skip alerts to avoid accidental alert storms.
            log.warn("Failed reserving admin FYP scale cooldown; skipping alert send: {}", e.getMessage());
            return false;
        }
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
            log.debug("Admin FYP scale alerts disabled for environment current={} required={}", current, required);
        }
        return allowed;
    }

    private double estimatedRps(GlobalFypRequestMetricsService.Snapshot perf) {
        if (perf == null) return 0.0d;
        int mins = Math.max(1, perf.windowMinutes());
        double seconds = mins * 60.0d;
        return perf.estimatedRequests() / seconds;
    }

    private String compact(long n) {
        if (n < 1_000) return String.valueOf(n);
        if (n < 1_000_000) return fmt1(n / 1_000.0d) + "k";
        if (n < 1_000_000_000) return fmt1(n / 1_000_000.0d) + "m";
        return fmt1(n / 1_000_000_000.0d) + "b";
    }

    private String bytesLabel(long bytes) {
        if (bytes < 0) bytes = 0;
        double b = bytes;
        double kb = 1024.0d;
        double mb = kb * 1024.0d;
        double gb = mb * 1024.0d;
        double tb = gb * 1024.0d;
        if (b >= tb) return fmt1(b / tb) + " TB";
        if (b >= gb) return fmt1(b / gb) + " GB";
        if (b >= mb) return fmt1(b / mb) + " MB";
        if (b >= kb) return fmt1(b / kb) + " KB";
        return bytes + " B";
    }

    private String percent(double fraction) {
        double f = fraction;
        if (Double.isNaN(f) || Double.isInfinite(f)) f = 0.0d;
        if (f < 0.0d) f = 0.0d;
        return String.format(Locale.ROOT, "%.1f%%", f * 100.0d);
    }

    private double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0d;
        if (v < 0.0d) return 0.0d;
        if (v > 1.0d) return 1.0d;
        return v;
    }

    private String fmt1(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0.0";
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private String fmt(OffsetDateTime dt) {
        if (dt == null) return "";
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dt);
    }

    private String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isBlank()) return null;
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String safeUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isBlank()) return null;
        // Don't attempt to validate aggressively; just avoid accidental javascript:.
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:")) return null;
        return s;
    }

    private String escape(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    enum Severity { HEADS_UP, URGENT }
}

