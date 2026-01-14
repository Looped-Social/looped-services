package com.looped.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MessageMediaRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public MessageMediaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean userCanAccessMessageMedia(long userId, long companyId, String key) {
        if (key == null || key.isBlank()) return false;
        String needle;
        String needleObj;
        try {
            needle = mapper.writeValueAsString(List.of(key));
            needleObj = mapper.writeValueAsString(List.of(java.util.Map.of("url", key)));
        } catch (Exception e) {
            return false;
        }
        return userCanAccessConversationAttachment(userId, needle, needleObj)
                || userCanAccessChannelAttachment(userId, companyId, needle, needleObj);
    }

    private boolean userCanAccessConversationAttachment(long userId, String attachmentsNeedleJsonArray, String attachmentsNeedleJsonArrayObj) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (" +
                        "SELECT 1 FROM conversation_messages cm " +
                        "JOIN conversation_participants cp ON cp.conversation_id = cm.conversation_id " +
                        "WHERE cp.user_id = ? AND (cm.attachments @> ?::jsonb OR cm.attachments @> ?::jsonb)" +
                        ")",
                Boolean.class, userId, attachmentsNeedleJsonArray, attachmentsNeedleJsonArrayObj
        );
        return Boolean.TRUE.equals(exists);
    }

    private boolean userCanAccessChannelAttachment(long userId, long companyId, String attachmentsNeedleJsonArray, String attachmentsNeedleJsonArrayObj) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (" +
                        "SELECT 1 FROM channel_messages m " +
                        "JOIN channels c ON c.id = m.channel_id " +
                        "LEFT JOIN channel_members mem ON mem.channel_id = c.id AND mem.user_id = ? " +
                        "WHERE c.company_id = ? " +
                        "AND (m.attachments @> ?::jsonb OR m.attachments @> ?::jsonb) " +
                        "AND (c.is_public = true OR mem.user_id IS NOT NULL)" +
                        ")",
                Boolean.class, userId, companyId, attachmentsNeedleJsonArray, attachmentsNeedleJsonArrayObj
        );
        return Boolean.TRUE.equals(exists);
    }
}
