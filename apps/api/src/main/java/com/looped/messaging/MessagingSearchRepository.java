package com.looped.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

@Repository
public class MessagingSearchRepository {
    private static final long KIND_OFFSET_CONVERSATION = 1_000_000_000_000L;
    private static final long KIND_OFFSET_CHANNEL = 2_000_000_000_000L;

    private final JdbcTemplate jdbc;

    public MessagingSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<SearchRow> mapper = new RowMapper<>() {
        @Override
        public SearchRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            SearchRow row = new SearchRow();
            row.type = rs.getString("type");
            row.threadId = rs.getLong("thread_id");
            row.globalId = rs.getLong("global_id");
            row.score = rs.getLong("score");
            row.activityAt = rs.getObject("activity_at", OffsetDateTime.class);

            long otherUserId = rs.getLong("other_user_id");
            row.otherUserId = rs.wasNull() ? null : otherUserId;
            row.otherUserHandle = rs.getString("other_user_handle");
            row.otherUserDisplayName = rs.getString("other_user_display_name");
            row.otherUserBio = rs.getString("other_user_bio");
            long otherUserCompanyId = rs.getLong("other_user_company_id");
            row.otherUserCompanyId = rs.wasNull() ? null : otherUserCompanyId;
            row.otherUserProfileImageUrl = rs.getString("other_user_profile_image_url");

            row.channelName = rs.getString("channel_name");
            Boolean isPublic = (Boolean) rs.getObject("channel_is_public");
            row.channelIsPublic = isPublic;

            long matchedMessageId = rs.getLong("matched_message_id");
            row.matchedMessageId = rs.wasNull() ? null : matchedMessageId;
            long matchedMessageSenderId = rs.getLong("matched_message_sender_id");
            row.matchedMessageSenderId = rs.wasNull() ? null : matchedMessageSenderId;
            row.matchedMessageContent = rs.getString("matched_message_content");
            row.matchedMessageCreatedAt = rs.getObject("matched_message_created_at", OffsetDateTime.class);

            row.lastMessage = rs.getString("last_message");
            row.lastMessageAt = rs.getObject("last_message_at", OffsetDateTime.class);
            return row;
        }
    };

    public List<SearchRow> search(
            long companyId,
            long userId,
            String query,
            String prefixQuery,
            OffsetDateTime asOf,
            Long cursorScore,
            OffsetDateTime cursorActivityAt,
            Long cursorGlobalId,
            int limit
    ) {
        String msgLike = "%" + (query == null ? "" : query.trim().toLowerCase(Locale.ROOT)) + "%";

        String nameVectorUser = "to_tsvector('simple', " +
                "COALESCE(u.handle,'') || ' ' || COALESCE(u.display_name,'') || ' ' || COALESCE(u.first_name,'') || ' ' || COALESCE(u.last_name,''))";
        String nameVectorChannel = "to_tsvector('simple', COALESCE(ch.name,''))";

        String msgVectorEnConv = "to_tsvector('english', COALESCE(cm.content,''))";
        String msgVectorSimpleConv = "to_tsvector('simple', COALESCE(cm.content,''))";
        String msgMatchConv = "(" + msgVectorEnConv + " @@ q.q_web OR (q.q_prefix IS NOT NULL AND " + msgVectorSimpleConv + " @@ q.q_prefix) " +
                "OR LOWER(COALESCE(cm.content,'')) LIKE ?)";
        String msgRankConv = "GREATEST(" +
                "ts_rank_cd(" + msgVectorEnConv + ", q.q_web), " +
                "COALESCE(ts_rank_cd(" + msgVectorSimpleConv + ", q.q_prefix), 0)" +
                ")";

        String msgVectorEnChan = "to_tsvector('english', COALESCE(chm.content,''))";
        String msgVectorSimpleChan = "to_tsvector('simple', COALESCE(chm.content,''))";
        String msgMatchChan = "(" + msgVectorEnChan + " @@ q.q_web OR (q.q_prefix IS NOT NULL AND " + msgVectorSimpleChan + " @@ q.q_prefix) " +
                "OR LOWER(COALESCE(chm.content,'')) LIKE ?)";
        String msgRankChan = "GREATEST(" +
                "ts_rank_cd(" + msgVectorEnChan + ", q.q_web), " +
                "COALESCE(ts_rank_cd(" + msgVectorSimpleChan + ", q.q_prefix), 0)" +
                ")";

        String convActivityAt = "COALESCE(best_msg.created_at, last_msg.created_at, c.created_at)";
        String chanActivityAt = "COALESCE(best_msg.created_at, last_msg.created_at, ch.created_at)";
        String recencyConv = "LEAST(200000, (1.0 / (1.0 + EXTRACT(EPOCH FROM (t.as_of - " + convActivityAt + ")) / 86400.0)) * 200000)";
        String recencyChan = "LEAST(200000, (1.0 / (1.0 + EXTRACT(EPOCH FROM (t.as_of - " + chanActivityAt + ")) / 86400.0)) * 200000)";

        String nameRankUser = "CASE WHEN q.q_prefix IS NULL THEN 0 ELSE ts_rank_cd(" + nameVectorUser + ", q.q_prefix) END";
        String nameMatchUser = "(q.q_prefix IS NOT NULL AND " + nameVectorUser + " @@ q.q_prefix)";
        String convScore = "CAST((GREATEST((" + nameRankUser + ") * 2, COALESCE(best_msg.rank, 0)) * 1000000 + " + recencyConv + ") AS BIGINT)";

        String nameRankChannel = "CASE WHEN q.q_prefix IS NULL THEN 0 ELSE ts_rank_cd(" + nameVectorChannel + ", q.q_prefix) END";
        String nameMatchChannel = "(q.q_prefix IS NOT NULL AND " + nameVectorChannel + " @@ q.q_prefix)";
        String chanScore = "CAST((GREATEST((" + nameRankChannel + ") * 2, COALESCE(best_msg.rank, 0)) * 1000000 + " + recencyChan + ") AS BIGINT)";

        String dmWhere =
                "WHERE c.company_id = ? AND cmr.id IS NULL " +
                        "AND (" + nameMatchUser + " OR best_msg.id IS NOT NULL OR LOWER(COALESCE(last_msg.content,'')) LIKE ?)";

        String dmSelect =
                "SELECT " +
                        "'conversation' AS type, " +
                        "c.id AS thread_id, " +
                        "(" + KIND_OFFSET_CONVERSATION + "::bigint + c.id) AS global_id, " +
                        "u.id AS other_user_id, u.handle AS other_user_handle, u.display_name AS other_user_display_name, u.bio AS other_user_bio, " +
                        "u.company_id AS other_user_company_id, u.profile_image_url AS other_user_profile_image_url, " +
                        "NULL::text AS channel_name, NULL::boolean AS channel_is_public, " +
                        "best_msg.id AS matched_message_id, best_msg.sender_id AS matched_message_sender_id, " +
                        "best_msg.content AS matched_message_content, best_msg.created_at AS matched_message_created_at, " +
                        "last_msg.content AS last_message, last_msg.created_at AS last_message_at, " +
                        convActivityAt + " AS activity_at, " +
                        convScore + " AS score " +
                        "FROM conversations c " +
                        "CROSS JOIN q " +
                        "CROSS JOIN t " +
                        "JOIN conversation_participants cp ON cp.conversation_id = c.id AND cp.user_id = ? " +
                        "LEFT JOIN conversation_message_requests cmr " +
                        "ON cmr.conversation_id = c.id AND cmr.recipient_id = ? AND cmr.status IN ('pending', 'rejected') " +
                        "JOIN LATERAL (" +
                        "  SELECT cp2.user_id FROM conversation_participants cp2 " +
                        "  WHERE cp2.conversation_id = c.id AND cp2.user_id <> ? LIMIT 1" +
                        ") other ON true " +
                        "JOIN users u ON u.id = other.user_id AND u.deleted_at IS NULL " +
                        "LEFT JOIN LATERAL (" +
                        "  SELECT cm.id, cm.sender_id, cm.content, cm.created_at, " + msgRankConv + " AS rank " +
                        "  FROM conversation_messages cm " +
                        "  WHERE cm.conversation_id = c.id AND " + msgMatchConv + " " +
                        "  ORDER BY rank DESC, cm.created_at DESC, cm.id DESC LIMIT 1" +
                        ") best_msg ON true " +
                        "LEFT JOIN LATERAL (" +
                        "  SELECT cm.content, cm.created_at FROM conversation_messages cm " +
                        "  WHERE cm.conversation_id = c.id ORDER BY cm.created_at DESC, cm.id DESC LIMIT 1" +
                        ") last_msg ON true " +
                        dmWhere;

        String channelWhere =
                "WHERE ch.company_id = ? " +
                        "AND (ch.is_public = true OR EXISTS (SELECT 1 FROM channel_members m WHERE m.channel_id = ch.id AND m.user_id = ?)) " +
                        "AND (" + nameMatchChannel + " OR best_msg.id IS NOT NULL OR LOWER(COALESCE(last_msg.content,'')) LIKE ?)";

        String channelSelect =
                "SELECT " +
                        "'channel' AS type, " +
                        "ch.id AS thread_id, " +
                        "(" + KIND_OFFSET_CHANNEL + "::bigint + ch.id) AS global_id, " +
                        "NULL::bigint AS other_user_id, NULL::text AS other_user_handle, NULL::text AS other_user_display_name, NULL::text AS other_user_bio, " +
                        "NULL::bigint AS other_user_company_id, NULL::text AS other_user_profile_image_url, " +
                        "ch.name AS channel_name, ch.is_public AS channel_is_public, " +
                        "best_msg.id AS matched_message_id, best_msg.sender_id AS matched_message_sender_id, " +
                        "best_msg.content AS matched_message_content, best_msg.created_at AS matched_message_created_at, " +
                        "last_msg.content AS last_message, last_msg.created_at AS last_message_at, " +
                        chanActivityAt + " AS activity_at, " +
                        chanScore + " AS score " +
                        "FROM channels ch " +
                        "CROSS JOIN q " +
                        "CROSS JOIN t " +
                        "LEFT JOIN LATERAL (" +
                        "  SELECT chm.id, chm.sender_id, chm.content, chm.created_at, " + msgRankChan + " AS rank " +
                        "  FROM channel_messages chm " +
                        "  WHERE chm.channel_id = ch.id AND " + msgMatchChan + " " +
                        "  ORDER BY rank DESC, chm.created_at DESC, chm.id DESC LIMIT 1" +
                        ") best_msg ON true " +
                        "LEFT JOIN LATERAL (" +
                        "  SELECT chm.content, chm.created_at FROM channel_messages chm " +
                        "  WHERE chm.channel_id = ch.id ORDER BY chm.created_at DESC, chm.id DESC LIMIT 1" +
                        ") last_msg ON true " +
                        channelWhere;

        String base =
                "WITH q AS (" +
                        "SELECT websearch_to_tsquery('english', ?) AS q_web, " +
                        "to_tsquery('simple', NULLIF(?, '')) AS q_prefix" +
                        "), t AS (SELECT ?::timestamptz AS as_of) " +
                        dmSelect + " UNION ALL " + channelSelect;

        if (cursorScore == null || cursorActivityAt == null || cursorGlobalId == null) {
            return jdbc.query(
                    "SELECT * FROM (" + base + ") s ORDER BY score DESC, activity_at DESC, global_id DESC LIMIT ?",
                    mapper,
                    query, prefixQuery, asOf,
                    userId, userId, userId, msgLike, companyId, msgLike,
                    msgLike, companyId, userId, msgLike,
                    limit
            );
        }

        return jdbc.query(
                "SELECT * FROM (" + base + ") s " +
                        "WHERE (score < ? OR (score = ? AND (activity_at < ? OR (activity_at = ? AND global_id < ?)))) " +
                        "ORDER BY score DESC, activity_at DESC, global_id DESC LIMIT ?",
                mapper,
                query, prefixQuery, asOf,
                userId, userId, userId, msgLike, companyId, msgLike,
                msgLike, companyId, userId, msgLike,
                cursorScore, cursorScore, cursorActivityAt, cursorActivityAt, cursorGlobalId,
                limit
        );
    }

    public static class SearchRow {
        public String type; // conversation | channel
        public long threadId;
        public long globalId;
        public long score;
        public OffsetDateTime activityAt;

        public Long otherUserId;
        public String otherUserHandle;
        public String otherUserDisplayName;
        public String otherUserBio;
        public Long otherUserCompanyId;
        public String otherUserProfileImageUrl;

        public String channelName;
        public Boolean channelIsPublic;

        public Long matchedMessageId;
        public Long matchedMessageSenderId;
        public String matchedMessageContent;
        public OffsetDateTime matchedMessageCreatedAt;

        public String lastMessage;
        public OffsetDateTime lastMessageAt;
    }
}
