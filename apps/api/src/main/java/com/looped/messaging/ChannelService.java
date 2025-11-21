package com.looped.messaging;

import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChannelService {
    private final ChannelRepository channels;
    private final UserRepository users;

    public ChannelService(ChannelRepository channels, UserRepository users) {
        this.channels = channels;
        this.users = users;
    }

    public ChannelListResult list(String firebaseUid, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ChannelListResult.userNotProvisioned();
        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = channels.listForUser(actor.get().companyId, actor.get().id, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", row.id);
            map.put("name", row.name);
            map.put("member_count", row.memberCount);
            map.put("is_public", row.isPublic);
            return map;
        }).toList();
        return ChannelListResult.ok(items, next);
    }

    public MessagesResult messages(String firebaseUid, long channelId, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return MessagesResult.userNotProvisioned();
        var channel = channels.findById(channelId);
        if (channel.isEmpty()) return MessagesResult.notFound();
        if (channel.get().companyId != actor.get().companyId) return MessagesResult.forbidden();
        boolean allowed = channel.get().isPublic || channels.isMember(channelId, actor.get().id);
        if (!allowed) return MessagesResult.forbidden();

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = channels.listMessages(channelId, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.id);
        }
        return MessagesResult.ok(rows, next);
    }

    public SendResult send(String firebaseUid, long channelId, String content, List<String> attachments) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return SendResult.userNotProvisioned();
        var channel = channels.findById(channelId);
        if (channel.isEmpty()) return SendResult.notFound();
        if (channel.get().companyId != actor.get().companyId) return SendResult.forbidden();
        boolean allowed = channel.get().isPublic || channels.isMember(channelId, actor.get().id);
        if (!allowed) return SendResult.forbidden();

        // Auto-join public channels on send
        channels.addMember(channelId, actor.get().id);
        var message = channels.insertMessage(channelId, actor.get().id, content, attachments);
        return SendResult.ok(message);
    }

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    public enum Status { OK, USER_NOT_PROVISIONED, FORBIDDEN, NOT_FOUND }

    public record ChannelListResult(Status status, List<Map<String, Object>> items, String nextCursor) {
        static ChannelListResult ok(List<Map<String, Object>> items, String next) { return new ChannelListResult(Status.OK, items, next); }
        static ChannelListResult userNotProvisioned() { return new ChannelListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
    }

    public record MessagesResult(Status status, List<ChannelRepository.ChannelMessageRow> messages, String nextCursor) {
        static MessagesResult ok(List<ChannelRepository.ChannelMessageRow> messages, String next) { return new MessagesResult(Status.OK, messages, next); }
        static MessagesResult userNotProvisioned() { return new MessagesResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static MessagesResult forbidden() { return new MessagesResult(Status.FORBIDDEN, List.of(), null); }
        static MessagesResult notFound() { return new MessagesResult(Status.NOT_FOUND, List.of(), null); }
    }

    public record SendResult(Status status, ChannelRepository.ChannelMessageRow message) {
        static SendResult ok(ChannelRepository.ChannelMessageRow message) { return new SendResult(Status.OK, message); }
        static SendResult userNotProvisioned() { return new SendResult(Status.USER_NOT_PROVISIONED, null); }
        static SendResult forbidden() { return new SendResult(Status.FORBIDDEN, null); }
        static SendResult notFound() { return new SendResult(Status.NOT_FOUND, null); }
    }
}
