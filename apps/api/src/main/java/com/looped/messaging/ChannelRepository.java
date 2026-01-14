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
public class ChannelRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChannelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<ChannelRow> channelMapper = new RowMapper<>() {
        @Override
        public ChannelRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ChannelRow row = new ChannelRow();
            row.id = rs.getLong("id");
            row.companyId = rs.getLong("company_id");
            long owner = rs.getLong("owner_user_id");
            row.ownerUserId = rs.wasNull() ? null : owner;
            row.name = rs.getString("name");
            row.isPublic = rs.getBoolean("is_public");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.memberCount = rs.getInt("member_count");
            row.viewerCanManageMembers = rs.getBoolean("viewer_can_manage_members");
            long photoId = rs.getLong("photo_media_asset_id");
            row.photoMediaAssetId = rs.wasNull() ? null : photoId;
            row.photoS3Key = rs.getString("photo_s3_key");
            row.photoMimeType = rs.getString("photo_mime_type");
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
                var node = raw == null ? null : mapper.readTree(raw);
                row.attachments = MessageAttachments.parse(node);
            } catch (Exception e) {
                row.attachments = List.of();
            }
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    public Optional<ChannelRow> findById(long id) {
        var rows = jdbc.query(
                "SELECT channels.id, channels.company_id, channels.owner_user_id, channels.name, channels.is_public, channels.created_at, " +
                        "channels.photo_media_asset_id, ma.s3_key AS photo_s3_key, ma.mime_type AS photo_mime_type, " +
                        "(SELECT COUNT(*) FROM channel_members m WHERE m.channel_id = channels.id) AS member_count " +
                        ", false AS viewer_can_manage_members " +
                        "FROM channels " +
                        "LEFT JOIN media_assets ma ON ma.id = channels.photo_media_asset_id " +
                        "WHERE channels.id = ?",
                channelMapper, id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ChannelRow> listForUser(long companyId, long userId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = "SELECT channels.id, channels.company_id, channels.owner_user_id, channels.name, channels.is_public, " +
                "channels.created_at AS created_at, " +
                "channels.photo_media_asset_id, ma.s3_key AS photo_s3_key, ma.mime_type AS photo_mime_type, " +
                "(SELECT COUNT(*) FROM channel_members m WHERE m.channel_id = channels.id) AS member_count, " +
                "CASE WHEN channels.owner_user_id = ? THEN true ELSE COALESCE(cm.can_manage_members, false) END AS viewer_can_manage_members " +
                "FROM channels " +
                "LEFT JOIN media_assets ma ON ma.id = channels.photo_media_asset_id " +
                "LEFT JOIN channel_members cm ON cm.channel_id = channels.id AND cm.user_id = ? " +
                "WHERE channels.company_id = ? AND (channels.is_public = true " +
                "OR EXISTS (SELECT 1 FROM channel_members m WHERE m.channel_id = channels.id AND m.user_id = ?)) ";
        if (cursorTs == null || cursorId == null) {
            base += "ORDER BY channels.created_at DESC, channels.id DESC LIMIT " + limit;
            return jdbc.query(base, channelMapper, userId, userId, companyId, userId);
        }
        base += "AND (channels.created_at < ? OR (channels.created_at = ? AND channels.id < ?)) " +
                "ORDER BY channels.created_at DESC, channels.id DESC LIMIT " + limit;
        return jdbc.query(base, channelMapper, userId, userId, companyId, userId, cursorTs, cursorTs, cursorId);
    }

    public boolean isMember(long channelId, long userId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM channel_members WHERE channel_id = ? AND user_id = ?)",
                Boolean.class, channelId, userId
        );
        return Boolean.TRUE.equals(exists);
    }

    public Optional<ChannelMemberRow> findMember(long channelId, long userId) {
        var rows = jdbc.query(
                "SELECT cm.user_id, cm.can_manage_members, cm.created_at, u.handle, u.display_name, " +
                        "u.profile_image_url, u.company_id " +
                        "FROM channel_members cm " +
                        "JOIN users u ON u.id = cm.user_id AND u.deleted_at IS NULL " +
                        "WHERE cm.channel_id = ? AND cm.user_id = ?",
                memberMapper, channelId, userId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean addMember(long channelId, long userId, boolean canManageMembers) {
        int rows = jdbc.update(
                "INSERT INTO channel_members(channel_id, user_id, can_manage_members) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                channelId, userId, canManageMembers
        );
        return rows > 0;
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

    public ChannelMessageRow insertMessage(long channelId, long senderId, String content, List<MessageAttachment> attachments) {
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

    public List<ChannelMemberRow> listMembers(long channelId, OffsetDateTime cursorTs, Long cursorId, int limit) {
        String base = "SELECT cm.user_id, cm.can_manage_members, cm.created_at, u.handle, u.display_name, " +
                "u.profile_image_url, u.company_id " +
                "FROM channel_members cm " +
                "JOIN users u ON u.id = cm.user_id AND u.deleted_at IS NULL " +
                "WHERE cm.channel_id = ? ";
        Object[] params;
        if (cursorTs == null || cursorId == null) {
            base += "ORDER BY cm.created_at DESC, cm.user_id DESC LIMIT ?";
            params = new Object[]{channelId, limit};
        } else {
            base += "AND (cm.created_at < ? OR (cm.created_at = ? AND cm.user_id < ?)) " +
                    "ORDER BY cm.created_at DESC, cm.user_id DESC LIMIT ?";
            params = new Object[]{channelId, cursorTs, cursorTs, cursorId, limit};
        }
        return jdbc.query(base, memberMapper, params);
    }

    public boolean updateMemberPermission(long channelId, long userId, boolean canManageMembers) {
        int rows = jdbc.update(
                "UPDATE channel_members SET can_manage_members = ? WHERE channel_id = ? AND user_id = ?",
                canManageMembers, channelId, userId
        );
        return rows > 0;
    }

    public boolean removeMember(long channelId, long userId) {
        int rows = jdbc.update(
                "DELETE FROM channel_members WHERE channel_id = ? AND user_id = ?",
                channelId, userId
        );
        return rows > 0;
    }

    public boolean updateName(long channelId, String name) {
        int rows = jdbc.update(
                "UPDATE channels SET name = ? WHERE id = ?",
                name, channelId
        );
        return rows > 0;
    }

    public boolean updatePhotoMediaAssetId(long channelId, Long photoMediaAssetId) {
        int rows = jdbc.update(
                "UPDATE channels SET photo_media_asset_id = ? WHERE id = ?",
                photoMediaAssetId, channelId
        );
        return rows > 0;
    }

    public long insertChannel(long companyId, long ownerUserId, String name, boolean isPublic) {
        Long id = jdbc.query(
                "INSERT INTO channels(company_id, owner_user_id, name, is_public) VALUES (?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                companyId, ownerUserId, name, isPublic
        );
        return Optional.ofNullable(id).orElseThrow();
    }

    public static class ChannelRow {
        public long id;
        public long companyId;
        public Long ownerUserId;
        public String name;
        public boolean isPublic;
        public OffsetDateTime createdAt;
        public int memberCount;
        public boolean viewerCanManageMembers;
        public Long photoMediaAssetId;
        public String photoS3Key;
        public String photoMimeType;
    }

    private final RowMapper<ChannelMemberRow> memberMapper = new RowMapper<>() {
        @Override
        public ChannelMemberRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            ChannelMemberRow row = new ChannelMemberRow();
            row.userId = rs.getLong("user_id");
            row.canManageMembers = rs.getBoolean("can_manage_members");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.handle = rs.getString("handle");
            row.displayName = rs.getString("display_name");
            row.profileImageUrl = rs.getString("profile_image_url");
            long companyId = rs.getLong("company_id");
            row.companyId = rs.wasNull() ? null : companyId;
            return row;
        }
    };

    public static class ChannelMemberRow {
        public long userId;
        public String handle;
        public String displayName;
        public String profileImageUrl;
        public Long companyId;
        public boolean canManageMembers;
        public OffsetDateTime createdAt;
    }

    public static class ChannelMessageRow {
        public long id;
        public long channelId;
        public long senderId;
        public String content;
        public List<MessageAttachment> attachments = List.of();
        public OffsetDateTime createdAt;
    }
}
