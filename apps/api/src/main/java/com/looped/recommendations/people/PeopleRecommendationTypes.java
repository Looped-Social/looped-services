package com.looped.recommendations.people;

import java.util.Locale;

final class PeopleRecommendationTypes {
    private PeopleRecommendationTypes() {}

    enum Rail {
        PYMK("pymk"),
        COMMUNITY("community"),
        ACTIVE_COMMUNITY("active_community");

        private final String wire;

        Rail(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }

        static Rail parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String v = raw.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "pymk" -> PYMK;
                case "community" -> COMMUNITY;
                case "active_community" -> ACTIVE_COMMUNITY;
                default -> null;
            };
        }
    }

    enum Surface {
        SEARCH("search"),
        ONBOARDING("onboarding"),
        FEED_CARD("feed_card"),
        PROFILE_SIMILAR("profile_similar"),
        INBOX_EMPTY("inbox_empty");

        private final String wire;

        Surface(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }

        static Surface parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String v = raw.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "search" -> SEARCH;
                case "onboarding" -> ONBOARDING;
                case "feed_card" -> FEED_CARD;
                case "profile_similar" -> PROFILE_SIMILAR;
                case "inbox_empty" -> INBOX_EMPTY;
                default -> null;
            };
        }
    }

    enum FeedbackType {
        IMPRESSION("impression"),
        PROFILE_OPEN("profile_open"),
        CONNECT_REQUEST_SENT("connect_request_sent"),
        CONNECT_ACCEPTED("connect_accepted"),
        HIDE("hide"),
        LESS_LIKE_THIS("less_like_this");

        private final String wire;

        FeedbackType(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }

        static FeedbackType parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String v = raw.trim().toLowerCase(Locale.ROOT);
            if ("click".equals(v)) v = "profile_open";
            return switch (v) {
                case "impression" -> IMPRESSION;
                case "profile_open" -> PROFILE_OPEN;
                case "connect_request_sent" -> CONNECT_REQUEST_SENT;
                case "connect_accepted" -> CONNECT_ACCEPTED;
                case "hide" -> HIDE;
                case "less_like_this" -> LESS_LIKE_THIS;
                default -> null;
            };
        }
    }
}
