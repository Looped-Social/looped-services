package com.looped.recommendations.people;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

final class PeopleRecommendationRequests {
    private PeopleRecommendationRequests() {}

    record FeedbackRequest(List<FeedbackEvent> events) {}

    record FeedbackEvent(
            @JsonAlias("event_id") String eventId,
            String type,
            @JsonAlias("recommendation_id") String recommendationId,
            @JsonAlias("tracking_token") String trackingToken,
            String rail,
            String surface,
            Integer position,
            @JsonAlias("client_ts") OffsetDateTime clientTs,
            Map<String, Object> metadata
    ) {}
}
