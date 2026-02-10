package com.looped.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MessageRequestRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public MessageRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<RequestRow> mapperRow = new RowMapper<>() {
        @Override
        public RequestRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            RequestRow row = new RequestRow();
            row.id = rs.getLong("id");
            row.conversationId = rs.getLong("conversation_id");
            row.requesterId = rs.getLong("requester_id");
            row.recipientId = rs.getLong("recipient_id");
            row.messageId = rs.getLong("message_id");
            row.status = rs.getString("status");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            row.messageContent = rs.getString("message_content");
            String attachmentsRaw = rs.getString("message_attachments");
            try {
                var node = attachmentsRaw == null ? null : mapper.readTree(attachmentsRaw);
                row.messageAttachments = MessageAttachments.parse(node);
            } catch (Exception e) {
                row.messageAttachments = List.of();
            }
            row.messageCreatedAt = rs.getObject("message_created_at", OffsetDateTime.class);
            return row;
        }
    };

    public Optional<RequestRow> findByConversationRecipient(long conversationId, long recipientId) {
        String sql = "SELECT cmr.id, cmr.conversation_id, cmr.requester_id, cmr.recipient_id, cmr.message_id, cmr.status, " +
                "cmr.created_at, cmr.updated_at, cm.content AS message_content, cm.attachments AS message_attachments, " +
                "cm.created_at AS message_created_at " +
                "FROM conversation_message_requests cmr " +
                "JOIN conversation_messages cm ON cm.id = cmr.message_id " +
                "WHERE cmr.conversation_id = ? AND cmr.recipient_id = ? LIMIT 1";
        List<RequestRow> rows = jdbc.query(sql, mapperRow, conversationId, recipientId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<RequestRow> findById(long requestId) {
        String sql = "SELECT cmr.id, cmr.conversation_id, cmr.requester_id, cmr.recipient_id, cmr.message_id, cmr.status, " +
                "cmr.created_at, cmr.updated_at, cm.content AS message_content, cm.attachments AS message_attachments, " +
                "cm.created_at AS message_created_at " +
                "FROM conversation_message_requests cmr " +
                "JOIN conversation_messages cm ON cm.id = cmr.message_id " +
                "WHERE cmr.id = ? LIMIT 1";
        List<RequestRow> rows = jdbc.query(sql, mapperRow, requestId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<RequestRow> listPending(long recipientId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = "SELECT cmr.id, cmr.conversation_id, cmr.requester_id, cmr.recipient_id, cmr.message_id, cmr.status, " +
                "cmr.created_at, cmr.updated_at, cm.content AS message_content, cm.attachments AS message_attachments, " +
                "cm.created_at AS message_created_at " +
                "FROM conversation_message_requests cmr " +
                "JOIN conversation_messages cm ON cm.id = cmr.message_id " +
                "WHERE cmr.recipient_id = ? AND cmr.status = 'pending' " +
                "AND NOT EXISTS (" +
                "SELECT 1 FROM conversation_message_requests approved " +
                "WHERE approved.conversation_id = cmr.conversation_id AND approved.status = 'approved'" +
                ")";
        if (cursorTs == null || cursorId == null) {
            base += " ORDER BY cmr.updated_at DESC, cmr.id DESC LIMIT " + limit;
            return jdbc.query(base, mapperRow, recipientId);
        }
        base += " AND (cmr.updated_at < ? OR (cmr.updated_at = ? AND cmr.id < ?)) " +
                "ORDER BY cmr.updated_at DESC, cmr.id DESC LIMIT " + limit;
        return jdbc.query(base, mapperRow, recipientId, cursorTs, cursorTs, cursorId);
    }

    public boolean insertPending(long conversationId, long requesterId, long recipientId, long messageId) {
        int rows = jdbc.update(
                "INSERT INTO conversation_message_requests(conversation_id, requester_id, recipient_id, message_id) " +
                        "VALUES (?,?,?,?) ON CONFLICT (conversation_id, recipient_id) DO NOTHING",
                conversationId, requesterId, recipientId, messageId
        );
        return rows > 0;
    }

    public boolean updatePendingMessage(long conversationId, long recipientId, long messageId) {
        int rows = jdbc.update(
                "UPDATE conversation_message_requests SET message_id = ?, updated_at = now() " +
                        "WHERE conversation_id = ? AND recipient_id = ? AND status = 'pending'",
                messageId, conversationId, recipientId
        );
        return rows > 0;
    }

    public boolean updateStatus(long requestId, long recipientId, String status) {
        int rows = jdbc.update(
                "UPDATE conversation_message_requests SET status = ?, updated_at = now() WHERE id = ? AND recipient_id = ?",
                status, requestId, recipientId
        );
        return rows > 0;
    }

    public boolean updateConversationStatus(long conversationId, String status) {
        int rows = jdbc.update(
                "UPDATE conversation_message_requests SET status = ?, updated_at = now() WHERE conversation_id = ?",
                status, conversationId
        );
        return rows > 0;
    }

    public boolean hasApprovedForConversation(long conversationId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM conversation_message_requests WHERE conversation_id = ? AND status = 'approved')",
                Boolean.class,
                conversationId
        );
        return Boolean.TRUE.equals(exists);
    }

    public static class RequestRow {
        public long id;
        public long conversationId;
        public long requesterId;
        public long recipientId;
        public long messageId;
        public String status;
        public OffsetDateTime createdAt;
        public OffsetDateTime updatedAt;
        public String messageContent;
        public List<MessageAttachment> messageAttachments = List.of();
        public OffsetDateTime messageCreatedAt;
    }
}
