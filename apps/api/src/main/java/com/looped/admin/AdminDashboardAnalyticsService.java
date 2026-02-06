package com.looped.admin;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminDashboardAnalyticsService {
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final AdminAnalyticsRepository analytics;

    public AdminDashboardAnalyticsService(AdminAnalyticsRepository analytics) {
        this.analytics = analytics;
    }

    public Map<String, Object> dashboard(LocalDate toUtcDayInclusive, Long communityId, AdminDashboardAudience audience) {
        OffsetDateTime toExclusive = toUtcDayInclusive.plusDays(1).atStartOfDay().atOffset(UTC);
        OffsetDateTime dayFrom = toUtcDayInclusive.atStartOfDay().atOffset(UTC);
        OffsetDateTime weekFrom = toUtcDayInclusive.minusDays(6).atStartOfDay().atOffset(UTC);
        OffsetDateTime monthFrom = toUtcDayInclusive.minusDays(29).atStartOfDay().atOffset(UTC);
        OffsetDateTime retentionCohortFrom = toUtcDayInclusive.minusDays(89).atStartOfDay().atOffset(UTC);

        String communityKind = communityId != null ? analytics.communityKind(communityId) : null;

        var activeUsers = analytics.dashboardActiveUsersSummary(dayFrom, monthFrom, toExclusive, communityId, audience);
        var content = analytics.dashboardContentVolumeSummary(dayFrom, weekFrom, monthFrom, toExclusive, communityId, audience);
        var newUsers = analytics.dashboardNewUsersSummary(dayFrom, weekFrom, toExclusive, communityId, communityKind);
        var retention = analytics.dashboardRetentionSummary(retentionCohortFrom, toExclusive, communityId, communityKind, audience);
        var verified = analytics.dashboardVerifiedActiveUsersSummary(monthFrom, toExclusive, communityId, communityKind, audience);
        var uniqueParticipants = analytics.uniqueParticipantsPerPostOptional(communityId, monthFrom, toExclusive, audience);
        var antiGrowth = analytics.dashboardAntiGrowthSummary(weekFrom, monthFrom, toExclusive, communityId, communityKind, audience);

        Map<String, Object> growth = new HashMap<>();
        growth.put("active_users", Map.of(
                "dau", activeUsers.dau,
                "mau_30d", activeUsers.mau30d,
                "dau_mau_ratio", activeUsers.mau30d <= 0 ? 0.0 : (double) activeUsers.dau / (double) activeUsers.mau30d
        ));

        Map<String, Object> sessions = new HashMap<>();
        sessions.put("sessions_per_user", null);
        sessions.put("avg_session_length_seconds", null);
        growth.put("sessions", sessions);

        var timeTo = communityId == null
                ? analytics.timeToFirstActions(monthFrom, toExclusive)
                : analytics.dashboardTimeToFirstActionsCommunity(monthFrom, toExclusive, communityId, communityKind);
        growth.put("time_to_verification_seconds", Map.of(
                "p50", timeTo.verifyP50Sec,
                "p90", timeTo.verifyP90Sec,
                "users_with_verification", timeTo.usersWithVerification,
                "cohort_size", timeTo.cohortSize
        ));

        double verifiedRate = verified.activeUsers <= 0 ? 0.0 : (double) verified.verifiedActiveUsers / (double) verified.activeUsers;
        growth.put("verified_user_rate", Map.of(
                "rate", verifiedRate,
                "verified_users", verified.verifiedActiveUsers,
                "active_users", verified.activeUsers,
                "definition", communityId == null
                        ? "active user in 30d window with global verification (verifications.verified=true)"
                        : (("specialization".equals(communityKind))
                        ? "active user in 30d window with a specialization join (specialization_joins)"
                        : "active user in 30d window with an active community verification (community_verifications.verified=true and not expired)")
        ));

        growth.put("posts_count", Map.of(
                "day", content.postsDay,
                "week", content.postsWeek,
                "month", content.postsMonth
        ));

        long creatorsMonth = content.creatorsMonth;
        long activeUsersMonth = activeUsers.mau30d;
        double creationRate = activeUsersMonth <= 0 ? 0.0 : (double) creatorsMonth / (double) activeUsersMonth;
        growth.put("content_creation_rate_month", Map.of(
                "rate", creationRate,
                "creators_month", creatorsMonth,
                "active_users_month", activeUsersMonth,
                "creator_definition", "distinct users with >=1 post in the 30d window (posts.author_id)"
        ));

        double commentToPostMonth = content.postsMonth <= 0 ? 0.0 : (double) content.commentsMonth / (double) content.postsMonth;
        growth.put("comment_to_post_ratio_month", Map.of(
                "ratio", commentToPostMonth,
                "comments_month", content.commentsMonth,
                "posts_month", content.postsMonth
        ));

        growth.put("retention_rate", Map.of(
                "d1", retention.cohortSize <= 0 ? 0.0 : (double) retention.retainedD1 / (double) retention.cohortSize,
                "d7", retention.cohortSize <= 0 ? 0.0 : (double) retention.retainedD7 / (double) retention.cohortSize,
                "d30", retention.cohortSize <= 0 ? 0.0 : (double) retention.retainedD30 / (double) retention.cohortSize,
                "cohort_size", retention.cohortSize,
                "retained_d1", retention.retainedD1,
                "retained_d7", retention.retainedD7,
                "retained_d30", retention.retainedD30,
                "definition", retention.definition
        ));

        growth.put("new_users", Map.of(
                "day", newUsers.day,
                "week", newUsers.week,
                "definition", newUsers.definition
        ));

        growth.put("unique_participants_per_post", Map.of(
                "avg", uniqueParticipants.avgParticipants,
                "p50", uniqueParticipants.p50Participants,
                "p90", uniqueParticipants.p90Participants,
                "posts_count", uniqueParticipants.postsCount
        ));

        growth.put("virality_coefficient_month", null);
        growth.put("sentiment_per_100_posts_per_day", null);

        Map<String, Object> anti = new HashMap<>();
        anti.put("norm_compliance", Map.of(
                "violations_per_user_action_week", ratioObj(antiGrowth.violationActionsWeek, antiGrowth.userActionsWeek, "violations=user bans + community bans + post/comment removals"),
                "violations_per_user_action_month", ratioObj(antiGrowth.violationActionsMonth, antiGrowth.userActionsMonth, "violations=user bans + community bans + post/comment removals")
        ));
        anti.put("report_to_action_ratio", Map.of(
                "week", ratioObj(antiGrowth.reportsWeek, antiGrowth.moderationActionsWeek, "moderation_actions=post/comment removals + bans + report resolves/dismisses + moderation queue decisions"),
                "month", ratioObj(antiGrowth.reportsMonth, antiGrowth.moderationActionsMonth, "moderation_actions=post/comment removals + bans + report resolves/dismisses + moderation queue decisions")
        ));
        anti.put("appeal_success_rate", ratioObj(antiGrowth.appealsApprovedMonth, antiGrowth.appealsReviewedMonth, "approved/reviewed by reviewed_at in 30d window"));
        anti.put("actions_per_moderator_per_day_month", Map.of(
                "actions_per_moderator_per_day", antiGrowth.activeModeratorsMonth <= 0 ? 0.0 : (double) antiGrowth.moderatorActionsMonth / (double) antiGrowth.activeModeratorsMonth / 30.0,
                "active_moderators", antiGrowth.activeModeratorsMonth,
                "total_actions", antiGrowth.moderatorActionsMonth
        ));
        anti.put("repeat_offender_rate", Map.of(
                "rate", antiGrowth.uniqueViolatorsMonth <= 0 ? 0.0 : (double) antiGrowth.repeatOffendersMonth / (double) antiGrowth.uniqueViolatorsMonth,
                "repeat_offenders", antiGrowth.repeatOffendersMonth,
                "unique_violators", antiGrowth.uniqueViolatorsMonth
        ));
        anti.put("same_user_action_rate", Map.of(
                "rate", antiGrowth.violationActionsMonth <= 0 ? 0.0 : (double) antiGrowth.violationActionsAgainstRepeatOffendersMonth / (double) antiGrowth.violationActionsMonth,
                "same_user_actions", antiGrowth.violationActionsAgainstRepeatOffendersMonth,
                "total_actions", antiGrowth.violationActionsMonth
        ));
        anti.put("posters_moderated_rate_30d", Map.of(
                "rate", antiGrowth.postersMonth <= 0 ? 0.0 : (double) antiGrowth.postersModeratedMonth / (double) antiGrowth.postersMonth,
                "posters", antiGrowth.postersMonth,
                "posters_moderated", antiGrowth.postersModeratedMonth
        ));
        anti.put("moderation_density", Map.of(
                "month", Map.of(
                        "density", (content.postsMonth + content.commentsMonth) <= 0 ? 0.0 : (double) antiGrowth.moderationActionsMonth / (double) (content.postsMonth + content.commentsMonth),
                        "moderation_actions", antiGrowth.moderationActionsMonth,
                        "posts_plus_comments", content.postsMonth + content.commentsMonth
                )
        ));

        Map<String, Object> both = new HashMap<>();
        both.put("time_to_first_actions_seconds", Map.of(
                "first_action_p50", timeTo.meaningfulP50Sec,
                "first_action_p90", timeTo.meaningfulP90Sec,
                "first_verification_p50", timeTo.verifyP50Sec,
                "first_verification_p90", timeTo.verifyP90Sec,
                "cohort_size", timeTo.cohortSize
        ));

        var verifyTo = communityId == null
                ? analytics.timeFromVerificationToFirstActions(monthFrom, toExclusive)
                : analytics.dashboardVerificationToFirstActionsCommunity(monthFrom, toExclusive, communityId, communityKind);
        both.put("verification_to_first_actions_seconds", Map.of(
                "like_p50", verifyTo.likeP50Sec,
                "like_p90", verifyTo.likeP90Sec,
                "comment_p50", verifyTo.commentP50Sec,
                "comment_p90", verifyTo.commentP90Sec,
                "post_p50", verifyTo.postP50Sec,
                "post_p90", verifyTo.postP90Sec,
                "cohort_size", verifyTo.cohortSize
        ));

        both.put("invites_by_source", null);
        both.put("invites_sent_accepted", null);
        both.put("invite_conversion_rate_excluding_organic", null);

        Map<String, Object> meta = new HashMap<>();
        meta.put("utc_semantics", "to is a UTC day inclusive; windows are day(to..to), week(to-6..to), month(to-29..to), retention_cohorts(to-89..to)");
        meta.put("to", toUtcDayInclusive);
        meta.put("audience", audience.wireValue());
        if (communityId != null) {
            meta.put("community_id", communityId);
            meta.put("community_kind", communityKind);
        }
        meta.put("windows", Map.of(
                "day", Map.of("from", toUtcDayInclusive, "to", toUtcDayInclusive),
                "week", Map.of("from", toUtcDayInclusive.minusDays(6), "to", toUtcDayInclusive),
                "month", Map.of("from", toUtcDayInclusive.minusDays(29), "to", toUtcDayInclusive),
                "retention_cohorts", Map.of("from", toUtcDayInclusive.minusDays(89), "to", toUtcDayInclusive)
        ));
        meta.put("unavailable_metrics", List.of(
                "growth.sessions",
                "growth.virality_coefficient_month",
                "growth.sentiment_per_100_posts_per_day",
                "both.invites_by_source",
                "both.invites_sent_accepted",
                "both.invite_conversion_rate_excluding_organic"
        ));

        Map<String, Object> out = new HashMap<>();
        out.put("meta", meta);
        out.put("growth", growth);
        out.put("anti_growth", anti);
        out.put("both", both);
        return out;
    }

    private Map<String, Object> ratioObj(long numerator, long denominator, String definition) {
        double rate = denominator <= 0 ? 0.0 : (double) numerator / (double) denominator;
        return Map.of(
                "rate", rate,
                "numerator", numerator,
                "denominator", denominator,
                "definition", definition
        );
    }
}

