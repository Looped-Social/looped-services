package com.looped.auth;

import com.looped.users.UserRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MeNoticesService {
    private static final Logger log = LoggerFactory.getLogger(MeNoticesService.class);

    private final UserRepository users;
    private final MeNoticeStateRepository notices;
    private final MeterRegistry meters;

    public MeNoticesService(UserRepository users, MeNoticeStateRepository notices, MeterRegistry meters) {
        this.users = users;
        this.notices = notices;
        this.meters = meters;
        registerGauges();
    }

    public List<Map<String, Object>> pendingNoticesForUserId(long userId) {
        if (userId <= 0) return List.of();
        List<MeNoticeStateRepository.StateRow> rows = notices.listPending(userId, MeNoticeCatalog.keys());
        if (rows.isEmpty()) return List.of();

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (MeNoticeStateRepository.StateRow row : rows) {
            var defOpt = MeNoticeCatalog.find(row.noticeKey());
            if (defOpt.isEmpty()) continue;
            var def = defOpt.get();
            out.add(Map.of(
                    "key", def.key(),
                    "title", def.title(),
                    "body", def.body(),
                    "dismissible", def.dismissible(),
                    "cta_label", def.ctaLabel(),
                    "ctaLabel", def.ctaLabel()
            ));
            meters.counter("user_notices.served", "notice_key", def.key()).increment();
            log.info("user_notice_served user_id={} notice_key={}", userId, def.key());
        }
        return out;
    }

    public AckResult acknowledge(String firebaseUid, String noticeKey, String action) {
        var defOpt = MeNoticeCatalog.find(noticeKey);
        if (defOpt.isEmpty()) {
            return AckResult.noticeNotFound();
        }

        String normalizedAction = normalizeAction(action);
        if (normalizedAction == null) {
            return AckResult.invalidAction();
        }

        var actor = users.findByFirebaseUid(firebaseUid);
        if (actor.isEmpty() || actor.get().companyId == null) {
            return AckResult.userNotProvisioned();
        }
        long userId = actor.get().id;
        String key = defOpt.get().key();

        boolean firstAck = notices.markAcknowledgedIfPending(
                userId,
                key,
                normalizedAction,
                OffsetDateTime.now()
        );
        if (firstAck) {
            meters.counter("user_notices.acknowledged", "notice_key", key, "action", normalizedAction).increment();
            log.info("user_notice_acknowledged user_id={} notice_key={} action={} first_ack=true",
                    userId, key, normalizedAction);
            return AckResult.ok(true);
        }

        var state = notices.findByUserAndKey(userId, key);
        boolean alreadyAcked = state.isPresent() && state.get().acknowledgedAt() != null;
        log.info("user_notice_acknowledged user_id={} notice_key={} action={} first_ack=false already_acked={} eligible_row_present={}",
                userId, key, normalizedAction, alreadyAcked, state.isPresent());
        return AckResult.ok(false);
    }

    private String normalizeAction(String action) {
        if (action == null) return null;
        String normalized = action.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        return switch (normalized) {
            case "dismiss", "cta" -> normalized;
            default -> null;
        };
    }

    private void registerGauges() {
        for (var def : MeNoticeCatalog.all()) {
            Gauge.builder("user_notices.eligible_users", notices, repo -> (double) repo.countEligibleUsers(def.key()))
                    .description("Users eligible for a given one-time notice")
                    .tag("notice_key", def.key())
                    .register(meters);

            Gauge.builder("user_notices.acknowledged_users", notices, repo -> (double) repo.countAcknowledgedUsers(def.key()))
                    .description("Eligible users who acknowledged a given one-time notice")
                    .tag("notice_key", def.key())
                    .register(meters);
        }
    }

    public enum Status {
        OK,
        USER_NOT_PROVISIONED,
        NOTICE_NOT_FOUND,
        INVALID_ACTION
    }

    public record AckResult(Status status, boolean firstAcknowledgement) {
        static AckResult ok(boolean firstAcknowledgement) {
            return new AckResult(Status.OK, firstAcknowledgement);
        }

        static AckResult userNotProvisioned() {
            return new AckResult(Status.USER_NOT_PROVISIONED, false);
        }

        static AckResult noticeNotFound() {
            return new AckResult(Status.NOTICE_NOT_FOUND, false);
        }

        static AckResult invalidAction() {
            return new AckResult(Status.INVALID_ACTION, false);
        }
    }
}
