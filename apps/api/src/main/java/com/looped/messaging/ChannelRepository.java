package com.looped.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
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
public class ChannelRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    public ChannelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<ChannelRow> channelMapper = new RowMapper<>() {
        @Override
        public ChannelRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ChannelRow row = new ChannelRow();
            row.id = rs.getLong("id");
            row.companyId = rs.getLong("company_id");
            row.name = rs.getString("name");
            row.isPublic = rs.getBoolean("is_public");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.memberCount = rs.getInt("member_count");
            return row;
        }
    };

    private final RowMapper<ChannelMessageRow> messageMapper = new RowMapper<>() {
        @Override
        public ChannelMessageRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ChannelMessageRow row = new ChannelMessageRow();
            row.id = rs.getLong("id");
            row.channelId = rs.getLong("channel_id");
            row.senderId = rs.getLong("sender_id");
            row.content = rs.getString("content");
            String raw = rs.getString("attachments");
            try {
                row.attachments = raw == null ? List.of() : mapper.readValue(raw, STRING_LIST);
            } catch (Exception e) {
                row.attachments = List.of();
            }
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public Optional<ChannelRow> findById(long id) {
        var rows = jdbc.query(
                "SELECT id, company_id, name, is_public, created_at, " +
                        "(SELECT COUNT(*) FROM channel_members m WHERE m.channel_id = channels.id) AS member_count " +
                        "FROM channels WHERE id = ?",
                channelMapper, id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ChannelRow> listForUser(long companyId, long userId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = "SELECT id, company_id, name, is_public, created_at, " +
                "(SELECT COUNT(*) FROM channel_members m WHERE m.channel_id = channels.id) AS member_count " +
                "FROM channels WHERE company_id = ? AND (is_public = true OR EXISTS (SELECT 1 FROM channel_members m WHERE m.channel_id = channels.id AND m.user_id = ?)) ";
        if (cursorTs == null || cursorId == null) {
            base += "ORDER BY created_at DESC, id DESC LIMIT " + limit;
            return jdbc.query(base, channelMapper, companyId, userId);
        }
        base += "AND (created_at < ? OR (created_at = ? AND id < ?)) ORDER BY created_at DESC, id DESC LIMIT " + limit;
        return jdbc.query(base, channelMapper, companyId, userId, cursorTs, cursorTs, cursorId);
    }

    public boolean isMember(long channelId, long userId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM channel_members WHERE channel_id = ? AND user_id = ?)",
                Boolean.class, channelId, userId
        );
        return Boolean.TRUE.equals(exists);
    }

    public void addMember(long channelId, long userId) {
        jdbc.update("INSERT INTO channel_members(channel_id, user_id) VALUES (?,?) ON CONFLICT DO NOTHING", channelId, userId);
    }

    public List<ChannelMessageRow> listMessages(long channelId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        if (cursorTs == null || cursorId == null) {
            return jdbc.query(
                    "SELECT id, channel_id, sender_id, content, attachments, created_at FROM channel_messages " +
                            "WHERE channel_id = ? ORDER BY created_at ASC, id ASC LIMIT ?",
                    messageMapper, channelId, limit
            );
        }
        return jdbc.query(
                "SELECT id, channel_id, sender_id, content, attachments, created_at FROM channel_messages " +
                        "WHERE channel_id = ? AND (created_at > ? OR (created_at = ? AND id > ?)) " +
                        "ORDER BY created_at ASC, id ASC LIMIT ?",
                messageMapper, channelId, cursorTs, cursorTs, cursorId, limit
        );
    }

    public ChannelMessageRow insertMessage(long channelId, long senderId, String content, List<String> attachments) {
        String json;
        try {
            json = mapper.writeValueAsString(attachments == null ? Collections.emptyList() : attachments);
        } catch (Exception e) {
            json = "[]";
        }
        List<ChannelMessageRow> rows = jdbc.query(
                "INSERT INTO channel_messages(channel_id, sender_id, content, attachments) VALUES (?,?,?,?::jsonb) " +
                        "RETURNING id, channel_id, sender_id, content, attachments, created_at",
                messageMapper, channelId, senderId, content, json
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static class ChannelRow {
        public long id;
        public long companyId;
        public String name;
        public boolean isPublic;
        public OffsetDateTime createdAt;
        public int memberCount;
    }

    public static class ChannelMessageRow {
        public long id;
        public long channelId;
        public long senderId;
        public String content;
        public List<String> attachments = List.of();
        public OffsetDateTime createdAt;
    }
}
