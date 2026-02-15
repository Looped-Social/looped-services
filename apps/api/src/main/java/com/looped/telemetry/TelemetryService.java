package com.looped.telemetry;

import com.looped.principals.PrincipalRepository;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class TelemetryService {
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final TelemetryRepository telemetry;

    public TelemetryService(UserRepository users, PrincipalRepository principals, TelemetryRepository telemetry) {
        this.users = users;
        this.principals = principals;
        this.telemetry = telemetry;
    }

    public IngestResult ingest(String firebaseUid, TelemetryRequests.EventsRequest req) {
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) {
            return IngestResult.userNotProvisioned();
        }
        long userId = u.get().id;
        long principalId = principals.createForUser(userId).id;

        OffsetDateTime sentAt = toOffsetDateTime(req.sentAtMs());
        UUID sessionId = req.sessionId();

        int droppedInvalid = 0;
        List<TelemetryRepository.TelemetryEventInsert> inserts = new ArrayList<>();
        for (TelemetryRequests.Event e : req.events()) {
            if (e == null) {
                droppedInvalid++;
                continue;
            }
            UUID eventId = e.eventId();
            TelemetryEventType type = TelemetryEventType.parse(e.type());
            OffsetDateTime occurredAt = toOffsetDateTime(e.occurredAtMs());
            if (eventId == null || type == null || occurredAt == null) {
                droppedInvalid++;
                continue;
            }

            Long postId = e.postId();
            Long commentId = e.commentId();
            Long communityId = e.communityId();

            TelemetryRequests.Feed feed = e.feed();
            String feedMode = feed == null ? null : sanitizeShort(feed.mode());
            Long feedCommunityId = feed == null ? null : feed.communityId();
            UUID feedRequestId = feed == null ? null : feed.requestId();
            Integer feedPosition = normalizePosition(feed == null ? null : feed.position());

            Map<String, Object> data = e.data() == null ? Map.of() : e.data();
            Map<String, Object> payload = new HashMap<>();

            boolean ok = switch (type) {
                case FEED_IMPRESSION -> {
                    Integer visibleMs = intFrom(data.get("visible_ms"));
                    if (visibleMs == null) visibleMs = intFrom(data.get("visibleMs"));
                    Boolean canInteract = boolFrom(data.get("can_interact"));
                    if (canInteract == null) canInteract = boolFrom(data.get("canInteract"));
                    String lockReason = stringFrom(data.get("lock_reason"));
                    if (lockReason == null) lockReason = stringFrom(data.get("lockReason"));

                    if (postId == null || visibleMs == null || visibleMs <= 0) yield false;
                    payload.put("visible_ms", visibleMs);
                    if (canInteract != null) payload.put("can_interact", canInteract);
                    if (lockReason != null && !lockReason.isBlank()) payload.put("lock_reason", sanitizeShort(lockReason));
                    yield true;
                }
                case POST_OPEN -> {
                    if (postId == null) yield false;
                    String entryPoint = stringFrom(data.get("entry_point"));
                    if (entryPoint == null) entryPoint = stringFrom(data.get("entryPoint"));
                    if (entryPoint != null && !entryPoint.isBlank()) payload.put("entry_point", sanitizeShort(entryPoint));
                    yield true;
                }
                case COMMENTS_OPEN -> postId != null;
                case VIDEO_WATCH -> {
                    Integer watchMs = intFrom(data.get("watch_ms"));
                    if (watchMs == null) watchMs = intFrom(data.get("watchMs"));
                    Integer durationMs = intFrom(data.get("duration_ms"));
                    if (durationMs == null) durationMs = intFrom(data.get("durationMs"));
                    Boolean completed = boolFrom(data.get("completed"));
                    Boolean autoplay = boolFrom(data.get("autoplay"));

                    if (postId == null || watchMs == null || watchMs < 0 || durationMs == null || durationMs <= 0) yield false;
                    payload.put("watch_ms", watchMs);
                    payload.put("duration_ms", durationMs);
                    if (completed != null) payload.put("completed", completed);
                    if (autoplay != null) payload.put("autoplay", autoplay);
                    yield true;
                }
                case INTERACTION_BLOCKED -> {
                    if (postId == null) yield false;
                    String action = stringFrom(data.get("action"));
                    String lockReason = stringFrom(data.get("lock_reason"));
                    if (lockReason == null) lockReason = stringFrom(data.get("lockReason"));
                    if (action == null || action.isBlank() || lockReason == null || lockReason.isBlank()) yield false;
                    payload.put("action", sanitizeShort(action));
                    payload.put("lock_reason", sanitizeShort(lockReason));
                    yield true;
                }
                case COMMUNITY_JOIN_INTENT, COMMUNITY_VERIFY_INTENT -> {
                    Long cId = communityId;
                    if (cId == null) cId = longFrom(data.get("community_id"));
                    if (cId == null) cId = longFrom(data.get("communityId"));
                    if (cId == null || cId <= 0) yield false;
                    communityId = cId;
                    payload.put("community_id", cId);
                    yield true;
                }
            };

            if (!ok) {
                droppedInvalid++;
                continue;
            }

            inserts.add(new TelemetryRepository.TelemetryEventInsert(
                    sessionId,
                    eventId,
                    type.wire(),
                    occurredAt,
                    postId,
                    commentId,
                    communityId,
                    feedMode,
                    feedCommunityId,
                    feedRequestId,
                    feedPosition,
                    payload
            ));
        }

        int inserted = telemetry.insertBatch(userId, principalId, sentAt, inserts);
        int attempted = inserts.size();
        int dropped = droppedInvalid + Math.max(0, attempted - inserted);
        return IngestResult.ok(inserted, dropped);
    }

    private Integer normalizePosition(Integer position) {
        if (position == null) return null;
        if (position < 0 || position > 10_000) return null;
        return position;
    }

    private OffsetDateTime toOffsetDateTime(Long epochMs) {
        if (epochMs == null) return null;
        if (epochMs <= 0) return null;
        try {
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String sanitizeShort(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isBlank()) return null;
        if (s.length() <= 128) return s;
        return s.substring(0, 128);
    }

    private Integer intFrom(Object o) {
        if (o == null) return null;
        if (o instanceof Integer i) return i;
        if (o instanceof Long l) return l > Integer.MAX_VALUE ? Integer.MAX_VALUE : l.intValue();
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Long longFrom(Object o) {
        if (o == null) return null;
        if (o instanceof Long l) return l;
        if (o instanceof Integer i) return i.longValue();
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean boolFrom(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (v.equals("true")) return true;
            if (v.equals("false")) return false;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        return null;
    }

    private String stringFrom(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        return null;
    }

    enum TelemetryEventType {
        FEED_IMPRESSION("feed_impression"),
        POST_OPEN("post_open"),
        COMMENTS_OPEN("comments_open"),
        VIDEO_WATCH("video_watch"),
        INTERACTION_BLOCKED("interaction_blocked"),
        COMMUNITY_JOIN_INTENT("community_join_intent"),
        COMMUNITY_VERIFY_INTENT("community_verify_intent");

        private final String wire;

        TelemetryEventType(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }

        static TelemetryEventType parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String t = raw.trim().toLowerCase(Locale.ROOT);
            return switch (t) {
                case "feed_impression" -> FEED_IMPRESSION;
                case "post_open" -> POST_OPEN;
                case "comments_open" -> COMMENTS_OPEN;
                case "video_watch" -> VIDEO_WATCH;
                case "interaction_blocked" -> INTERACTION_BLOCKED;
                case "community_join_intent" -> COMMUNITY_JOIN_INTENT;
                case "community_verify_intent" -> COMMUNITY_VERIFY_INTENT;
                default -> null;
            };
        }
    }

    public enum Status { OK, USER_NOT_PROVISIONED }

    public record IngestResult(Status status, int accepted, int dropped) {
        static IngestResult ok(int accepted, int dropped) { return new IngestResult(Status.OK, accepted, dropped); }
        static IngestResult userNotProvisioned() { return new IngestResult(Status.USER_NOT_PROVISIONED, 0, 0); }
    }
}

