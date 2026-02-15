package com.looped.telemetry;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TelemetryRequests {
    public record EventsRequest(
            @JsonAlias("session_id") UUID sessionId,
            @JsonAlias("sent_at_ms") Long sentAtMs,
            List<Event> events
    ) {}

    public record Event(
            @JsonAlias("event_id") UUID eventId,
            String type,
            @JsonAlias("occurred_at_ms") Long occurredAtMs,
            @JsonAlias("post_id") Long postId,
            @JsonAlias("comment_id") Long commentId,
            @JsonAlias("community_id") Long communityId,
            Feed feed,
            Map<String, Object> data
    ) {}

    public record Feed(
            String mode,
            @JsonAlias("community_id") Long communityId,
            @JsonAlias("request_id") UUID requestId,
            Integer position
    ) {}
}

