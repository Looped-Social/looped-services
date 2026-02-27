package com.looped.widgets;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class WidgetSummaryRepository {
    private final JdbcTemplate jdbc;

    public WidgetSummaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public InboxCounts loadInboxCounts(long userId) {
        return jdbc.query(
                """
                WITH eligible_conversations AS (
                    SELECT c.id, cp.last_read_at
                    FROM conversations c
                    JOIN conversation_participants cp
                        ON cp.conversation_id = c.id AND cp.user_id = ?
                    JOIN conversation_participants cp2
                        ON cp2.conversation_id = c.id AND cp2.user_id <> ?
                    LEFT JOIN conversation_message_requests cmr
                        ON cmr.conversation_id = c.id
                        AND cmr.recipient_id = ?
                        AND cmr.status IN ('pending', 'rejected')
                        AND NOT EXISTS (
                            SELECT 1
                            FROM conversation_message_requests approved
                            WHERE approved.conversation_id = c.id
                            AND approved.status = 'approved'
                        )
                    WHERE cmr.id IS NULL
                    AND NOT EXISTS (
                        SELECT 1
                        FROM principal_blocks pb
                        JOIN principals p_blocker
                            ON p_blocker.id = pb.blocker_principal_id AND p_blocker.kind = 'user'
                        JOIN principals p_blocked
                            ON p_blocked.id = pb.blocked_principal_id AND p_blocked.kind = 'user'
                        WHERE (p_blocker.user_id = cp.user_id AND p_blocked.user_id = cp2.user_id)
                           OR (p_blocker.user_id = cp2.user_id AND p_blocked.user_id = cp.user_id)
                    )
                ),
                unread_messages AS (
                    SELECT COUNT(*) AS cnt
                    FROM conversation_messages cm
                    JOIN eligible_conversations ec ON ec.id = cm.conversation_id
                    WHERE cm.created_at > COALESCE(ec.last_read_at, to_timestamp(0))
                ),
                pending_requests AS (
                    SELECT COUNT(*) AS cnt
                    FROM conversation_message_requests cmr
                    WHERE cmr.recipient_id = ?
                    AND cmr.status = 'pending'
                    AND NOT EXISTS (
                        SELECT 1
                        FROM conversation_message_requests approved
                        WHERE approved.conversation_id = cmr.conversation_id
                        AND approved.status = 'approved'
                    )
                    AND NOT EXISTS (
                        SELECT 1
                        FROM principal_blocks pb
                        JOIN principals p_blocker
                            ON p_blocker.id = pb.blocker_principal_id AND p_blocker.kind = 'user'
                        JOIN principals p_blocked
                            ON p_blocked.id = pb.blocked_principal_id AND p_blocked.kind = 'user'
                        WHERE (p_blocker.user_id = cmr.recipient_id AND p_blocked.user_id = cmr.requester_id)
                           OR (p_blocker.user_id = cmr.requester_id AND p_blocked.user_id = cmr.recipient_id)
                    )
                ),
                unread_mentions AS (
                    SELECT COUNT(*) AS cnt
                    FROM notifications n
                    WHERE n.user_id = ?
                    AND n.type = 'mention'
                    AND n.read_at IS NULL
                    AND n.dismissed_at IS NULL
                )
                SELECT
                    COALESCE(um.cnt, 0) AS unread_messages,
                    COALESCE(pr.cnt, 0) AS message_requests,
                    COALESCE(mn.cnt, 0) AS unread_mentions
                FROM unread_messages um
                CROSS JOIN pending_requests pr
                CROSS JOIN unread_mentions mn
                """,
                rs -> {
                    if (!rs.next()) return InboxCounts.zero();
                    return new InboxCounts(
                            rs.getInt("unread_messages"),
                            rs.getInt("message_requests"),
                            rs.getInt("unread_mentions")
                    );
                },
                userId, userId, userId, userId, userId
        );
    }

    public ProfileStats loadProfileStats(long userId) {
        return jdbc.query(
                """
                SELECT
                    COALESCE((
                        SELECT COUNT(*)
                        FROM principal_follows f
                        JOIN principals target ON target.id = f.followee_principal_id
                        JOIN principals follower ON follower.id = f.follower_principal_id
                        LEFT JOIN users u ON u.id = follower.user_id AND u.deleted_at IS NULL
                        WHERE target.user_id = ?
                        AND (follower.kind = 'anon' OR u.id IS NOT NULL)
                    ), 0) AS followers,
                    COALESCE((
                        SELECT COUNT(*)
                        FROM principal_follows f
                        JOIN principals target ON target.id = f.follower_principal_id
                        JOIN principals followee ON followee.id = f.followee_principal_id
                        LEFT JOIN users u ON u.id = followee.user_id AND u.deleted_at IS NULL
                        WHERE target.user_id = ?
                        AND (followee.kind = 'anon' OR u.id IS NOT NULL)
                    ), 0) AS following,
                    COALESCE((
                        SELECT SUM(p.likes_count)
                        FROM posts p
                        WHERE p.author_id = ?
                        AND p.removed_at IS NULL
                    ), 0)
                    +
                    COALESCE((
                        SELECT SUM(c.likes_count)
                        FROM comments c
                        WHERE c.user_id = ?
                        AND c.deleted_at IS NULL
                    ), 0) AS likes_received
                """,
                rs -> {
                    if (!rs.next()) return ProfileStats.zero();
                    return new ProfileStats(
                            rs.getInt("followers"),
                            rs.getInt("following"),
                            rs.getLong("likes_received")
                    );
                },
                userId, userId, userId, userId
        );
    }

    public List<VerifiedCommunityRow> loadVerifiedCommunities(long userId) {
        return jdbc.query(
                """
                SELECT
                    c.id,
                    c.name,
                    c.short_name,
                    c.member_count,
                    COALESCE((
                        SELECT COUNT(*)
                        FROM posts p
                        WHERE p.community_id = c.id
                        AND p.removed_at IS NULL
                        AND p.visibility = 'public'
                        AND p.created_at > COALESCE(wcs.last_seen_at, cf.created_at, cv.verified_at, to_timestamp(0))
                    ), 0) AS new_activity_count
                FROM community_verifications cv
                JOIN communities c ON c.id = cv.community_id
                LEFT JOIN widget_community_state wcs
                    ON wcs.user_id = cv.user_id
                    AND wcs.community_id = cv.community_id
                LEFT JOIN community_follows cf
                    ON cf.user_id = cv.user_id
                    AND cf.community_id = cv.community_id
                WHERE cv.user_id = ?
                AND cv.verified = true
                AND (cv.expires_at IS NULL OR cv.expires_at > now())
                ORDER BY c.name ASC, c.id ASC
                """,
                (rs, rowNum) -> new VerifiedCommunityRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("short_name"),
                        rs.getInt("member_count"),
                        rs.getInt("new_activity_count")
                ),
                userId
        );
    }

    public boolean communityExists(long communityId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM communities WHERE id = ?)",
                Boolean.class,
                communityId
        );
        return Boolean.TRUE.equals(exists);
    }

    public boolean isActiveVerifiedCommunity(long userId, long communityId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (" +
                        "SELECT 1 FROM community_verifications " +
                        "WHERE user_id = ? AND community_id = ? " +
                        "AND verified = true " +
                        "AND (expires_at IS NULL OR expires_at > now())" +
                        ")",
                Boolean.class,
                userId,
                communityId
        );
        return Boolean.TRUE.equals(exists);
    }

    public OffsetDateTime upsertCommunitySeen(long userId, long communityId, OffsetDateTime seenAt) {
        var rows = jdbc.query(
                "INSERT INTO widget_community_state(user_id, community_id, last_seen_at, updated_at) " +
                        "VALUES (?,?,?, now()) " +
                        "ON CONFLICT (user_id, community_id) DO UPDATE SET " +
                        "last_seen_at = GREATEST(widget_community_state.last_seen_at, EXCLUDED.last_seen_at), " +
                        "updated_at = now() " +
                        "RETURNING last_seen_at",
                (rs, rowNum) -> rs.getObject("last_seen_at", OffsetDateTime.class),
                userId,
                communityId,
                seenAt
        );
        return rows.isEmpty() ? seenAt : rows.get(0);
    }

    public record InboxCounts(int unreadMessages, int messageRequests, int unreadMentions) {
        static InboxCounts zero() {
            return new InboxCounts(0, 0, 0);
        }
    }

    public record ProfileStats(int followers, int following, long likesReceived) {
        static ProfileStats zero() {
            return new ProfileStats(0, 0, 0);
        }
    }

    public record VerifiedCommunityRow(long id,
                                       String name,
                                       String shortName,
                                       int memberCount,
                                       int newActivityCount) {}
}
