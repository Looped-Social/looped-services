package com.looped.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class ConversationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<MessageRow> messageMapper = new RowMapper<>() {
        @Override
        public MessageRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            MessageRow row = new MessageRow();
            row.id = rs.getLong("id");
            row.conversationId = rs.getLong("conversation_id");
            row.senderId = rs.getLong("sender_id");
            row.content = rs.getString("content");
            String attachmentsRaw = rs.getString("attachments");
            try {
                var node = attachmentsRaw == null ? null : mapper.readTree(attachmentsRaw);
                row.attachments = MessageAttachments.parse(node);
            } catch (Exception e) {
                row.attachments = List.of();
            }
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public Long findExistingDirect(long userA, long userB) {
        List<Long> ids = jdbc.query(
                "SELECT cp1.conversation_id FROM conversation_participants cp1 " +
                        "JOIN conversation_participants cp2 ON cp1.conversation_id = cp2.conversation_id " +
                        "WHERE cp1.user_id = ? AND cp2.user_id = ? LIMIT 1",
                (rs, rowNum) -> rs.getLong(1),
                userA, userB
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    public long insertConversation(long companyId) {
        Long id = jdbc.query(
                "INSERT INTO conversations(company_id) VALUES (?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                companyId
        );
        return Optional.ofNullable(id).orElseThrow();
    }

    public void addParticipant(long conversationId, long userId, OffsetDateTime lastReadAt) {
        jdbc.update("INSERT INTO conversation_participants(conversation_id, user_id, last_read_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                conversationId, userId, lastReadAt);
    }

    public Optional<Long> conversationCompany(long conversationId) {
        List<Long> ids = jdbc.query("SELECT company_id FROM conversations WHERE id=?", (rs, rowNum) -> rs.getLong(1), conversationId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    public boolean isParticipant(long conversationId, long userId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM conversation_participants WHERE conversation_id = ? AND user_id = ?)",
                Boolean.class, conversationId, userId
        );
        return Boolean.TRUE.equals(exists);
    }

    public List<ConversationSummary> listForUser(long userId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = "SELECT c.id, c.company_id, " +
                "COALESCE((SELECT created_at FROM conversation_messages cm WHERE cm.conversation_id = c.id ORDER BY cm.created_at DESC, cm.id DESC LIMIT 1), c.created_at) AS activity_at, " +
                "(SELECT content FROM conversation_messages cm WHERE cm.conversation_id = c.id ORDER BY cm.created_at DESC, cm.id DESC LIMIT 1) AS last_message, " +
                "(SELECT created_at FROM conversation_messages cm WHERE cm.conversation_id = c.id ORDER BY cm.created_at DESC, cm.id DESC LIMIT 1) AS last_message_at, " +
                "uo.id AS other_user_id, uo.handle AS other_user_handle, uo.display_name AS other_user_display_name, " +
                "uo.bio AS other_user_bio, uo.company_id AS other_user_company_id, uo.profile_image_url AS other_user_profile_image_url, " +
                "(SELECT COUNT(*) FROM conversation_messages cm WHERE cm.conversation_id = c.id AND cm.created_at > COALESCE(cp.last_read_at, to_timestamp(0))) AS unread_count " +
                "FROM conversations c " +
                "JOIN conversation_participants cp ON cp.conversation_id = c.id AND cp.user_id = ? " +
                "JOIN conversation_participants cp2 ON cp2.conversation_id = c.id AND cp2.user_id <> ? " +
                "JOIN users uo ON uo.id = cp2.user_id AND uo.deleted_at IS NULL " +
                "LEFT JOIN conversation_message_requests cmr " +
                "ON cmr.conversation_id = c.id AND cmr.recipient_id = ? AND cmr.status IN ('pending', 'rejected') " +
                "AND NOT EXISTS (" +
                "SELECT 1 FROM conversation_message_requests approved " +
                "WHERE approved.conversation_id = c.id AND approved.status = 'approved'" +
                ") " +
                "WHERE cmr.id IS NULL ";
        base +=
                "AND NOT EXISTS (" +
                        "SELECT 1 " +
                        "FROM principal_blocks pb " +
                        "JOIN principals p_blocker ON p_blocker.id = pb.blocker_principal_id AND p_blocker.kind = 'user' " +
                        "JOIN principals p_blocked ON p_blocked.id = pb.blocked_principal_id AND p_blocked.kind = 'user' " +
                        "WHERE (p_blocker.user_id = cp.user_id AND p_blocked.user_id = cp2.user_id) " +
                        "   OR (p_blocker.user_id = cp2.user_id AND p_blocked.user_id = cp.user_id)" +
                        ") ";
        if (cursorTs == null || cursorId == null) {
            base += "ORDER BY activity_at DESC, c.id DESC LIMIT " + limit;
            return jdbc.query(base, (rs, rowNum) -> mapConversationSummary(rs), userId, userId, userId);
        }
        base += "AND (COALESCE((SELECT created_at FROM conversation_messages cm WHERE cm.conversation_id = c.id ORDER BY cm.created_at DESC, cm.id DESC LIMIT 1), c.created_at) < ? " +
                "OR (COALESCE((SELECT created_at FROM conversation_messages cm WHERE cm.conversation_id = c.id ORDER BY cm.created_at DESC, cm.id DESC LIMIT 1), c.created_at) = ? AND c.id < ?)) " +
                "ORDER BY activity_at DESC, c.id DESC LIMIT " + limit;
        return jdbc.query(base, (rs, rowNum) -> mapConversationSummary(rs), userId, userId, userId, cursorTs, cursorTs, cursorId);
    }

    public Optional<ConversationSummary> findSummary(long conversationId, long userId) {
        String sql = "SELECT c.id, c.company_id, " +
                "COALESCE((SELECT created_at FROM conversation_messages cm WHERE cm.conversation_id = c.id ORDER BY cm.created_at DESC, cm.id DESC LIMIT 1), c.created_at) AS activity_at, " +
                "(SELECT content FROM conversation_messages cm WHERE cm.conversation_id = c.id ORDER BY cm.created_at DESC, cm.id DESC LIMIT 1) AS last_message, " +
                "(SELECT created_at FROM conversation_messages cm WHERE cm.conversation_id = c.id ORDER BY cm.created_at DESC, cm.id DESC LIMIT 1) AS last_message_at, " +
                "uo.id AS other_user_id, uo.handle AS other_user_handle, uo.display_name AS other_user_display_name, " +
                "uo.bio AS other_user_bio, uo.company_id AS other_user_company_id, uo.profile_image_url AS other_user_profile_image_url, " +
                "(SELECT COUNT(*) FROM conversation_messages cm WHERE cm.conversation_id = c.id AND cm.created_at > COALESCE(cp.last_read_at, to_timestamp(0))) AS unread_count " +
                "FROM conversations c " +
                "JOIN conversation_participants cp ON cp.conversation_id = c.id AND cp.user_id = ? " +
                "JOIN conversation_participants cp2 ON cp2.conversation_id = c.id AND cp2.user_id <> ? " +
                "JOIN users uo ON uo.id = cp2.user_id AND uo.deleted_at IS NULL " +
                "WHERE c.id = ? " +
                "AND NOT EXISTS (" +
                "SELECT 1 " +
                "FROM principal_blocks pb " +
                "JOIN principals p_blocker ON p_blocker.id = pb.blocker_principal_id AND p_blocker.kind = 'user' " +
                "JOIN principals p_blocked ON p_blocked.id = pb.blocked_principal_id AND p_blocked.kind = 'user' " +
                "WHERE (p_blocker.user_id = cp.user_id AND p_blocked.user_id = cp2.user_id) " +
                "   OR (p_blocker.user_id = cp2.user_id AND p_blocked.user_id = cp.user_id)" +
                ")";
        List<ConversationSummary> list = jdbc.query(sql, (rs, rowNum) -> mapConversationSummary(rs), userId, userId, conversationId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private ConversationSummary mapConversationSummary(ResultSet rs) throws SQLException {
        ConversationSummary summary = new ConversationSummary();
        summary.id = rs.getLong("id");
        summary.companyId = rs.getLong("company_id");
        summary.activityAt = rs.getObject("activity_at", OffsetDateTime.class);
        long other = rs.getLong("other_user_id");
        summary.otherUserId = rs.wasNull() ? null : other;
        summary.otherUserHandle = rs.getString("other_user_handle");
        summary.otherUserDisplayName = rs.getString("other_user_display_name");
        summary.otherUserBio = rs.getString("other_user_bio");
        long otherCompanyId = rs.getLong("other_user_company_id");
        summary.otherUserCompanyId = rs.wasNull() ? null : otherCompanyId;
        summary.otherUserProfileImageUrl = rs.getString("other_user_profile_image_url");
        summary.lastMessage = rs.getString("last_message");
        summary.lastMessageAt = rs.getObject("last_message_at", OffsetDateTime.class);
        summary.unreadCount = rs.getInt("unread_count");
        return summary;
    }

    public List<MessageRow> listMessages(long conversationId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, conversation_id, sender_id, content, attachments, created_at FROM (" +
                            "SELECT id, conversation_id, sender_id, content, attachments, created_at FROM conversation_messages " +
                            "WHERE conversation_id = ? ORDER BY created_at DESC, id DESC LIMIT ?" +
                            ") latest ORDER BY created_at ASC, id ASC",
                    messageMapper, conversationId, limit
            );
        }
        return jdbc.query(
                "SELECT id, conversation_id, sender_id, content, attachments, created_at FROM conversation_messages " +
                        "WHERE conversation_id = ? AND (created_at > ? OR (created_at = ? AND id > ?)) " +
                        "ORDER BY created_at ASC, id ASC LIMIT ?",
                messageMapper, conversationId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public MessageRow insertMessage(long conversationId, long senderId, String content, List<MessageAttachment> attachments) {
        String json;
        try {
            json = mapper.writeValueAsString(attachments == null ? Collections.emptyList() : attachments);
        } catch (Exception e) {
            json = "[]";
        }
        List<MessageRow> rows = jdbc.query(
                "INSERT INTO conversation_messages(conversation_id, sender_id, content, attachments) VALUES (?,?,?,?::jsonb) " +
                        "RETURNING id, conversation_id, sender_id, content, attachments, created_at",
                messageMapper,
                conversationId, senderId, content, json
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Long> listOtherParticipantIds(long conversationId, long userId) {
        return jdbc.query(
                "SELECT user_id FROM conversation_participants WHERE conversation_id = ? AND user_id <> ?",
                (rs, rowNum) -> rs.getLong("user_id"),
                conversationId, userId
        );
    }

    public void markRead(long conversationId, long userId, OffsetDateTime ts) {
        jdbc.update("UPDATE conversation_participants SET last_read_at = ? WHERE conversation_id = ? AND user_id = ?", ts, conversationId, userId);
    }

    public int countMessagesSince(long conversationId, OffsetDateTime since) {
        if (conversationId <= 0 || since == null) return 0;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = ? AND created_at >= ?",
                Integer.class,
                conversationId,
                since
        );
        return count == null ? 0 : count;
    }

    public static class ConversationSummary {
        public long id;
        public long companyId;
        public OffsetDateTime activityAt;
        public Long otherUserId;
        public String otherUserHandle;
        public String otherUserDisplayName;
        public String otherUserBio;
        public Long otherUserCompanyId;
        public String otherUserProfileImageUrl;
        public String lastMessage;
        public OffsetDateTime lastMessageAt;
        public int unreadCount;
    }

    public static class MessageRow {
        public long id;
        public long conversationId;
        public long senderId;
        public String content;
        public List<MessageAttachment> attachments = List.of();
        public OffsetDateTime createdAt;
    }
}
