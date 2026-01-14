package com.looped.messaging;

import com.looped.media.MediaRepository;
import com.looped.shared.Pagination;
import com.looped.users.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChannelService {
    private final ChannelRepository channels;
    private final UserRepository users;
    private final ChannelPreferencesRepository channelPreferences;
    private final MediaRepository media;
    private final String cloudfrontDomain;

    private static final Set<String> ALLOWED_CHANNEL_PHOTO = Set.of("image/jpeg", "image/png", "image/webp");

    public ChannelService(ChannelRepository channels,
                          UserRepository users,
                          ChannelPreferencesRepository channelPreferences,
                          MediaRepository media,
                          @Value("${cloudfront.domain:}") String cloudfrontDomain) {
        this.channels = channels;
        this.users = users;
        this.channelPreferences = channelPreferences;
        this.media = media;
        this.cloudfrontDomain = cloudfrontDomain == null ? "" : cloudfrontDomain.trim();
    }

    public ChannelListResult list(String firebaseUid, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ChannelListResult.userNotProvisioned();
        if (actor.get().isAnonymous) return ChannelListResult.anonymousNotAllowed();
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
        Map<Long, Boolean> mutedByChannelId = channelPreferences.mutedByChannelIds(
                actor.get().id,
                rows.stream().map(r -> r.id).toList()
        );
        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", row.id);
            map.put("name", row.name);
            map.put("member_count", row.memberCount);
            map.put("is_public", row.isPublic);
            if (row.ownerUserId != null) {
                map.put("owner_user_id", row.ownerUserId);
            }
            map.put("viewer_can_manage_members", row.viewerCanManageMembers);
            if (row.photoMediaAssetId != null) map.put("photo_media_asset_id", row.photoMediaAssetId);
            String photoUrl = channelPhotoUrl(row);
            if (photoUrl != null) map.put("photo_url", photoUrl);
            map.put("muted", mutedByChannelId.getOrDefault(row.id, false));
            return map;
        }).toList();
        return ChannelListResult.ok(items, next);
    }

    @Transactional
    public UpdateResult update(String firebaseUid, long channelId, String name, boolean namePresent,
                               Long photoMediaAssetId, boolean photoPresent) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return UpdateResult.userNotProvisioned();
        if (actor.get().isAnonymous) return UpdateResult.anonymousNotAllowed();
        var channel = channels.findById(channelId);
        if (channel.isEmpty()) return UpdateResult.notFound();
        if (channel.get().companyId != actor.get().companyId) return UpdateResult.forbidden();
        if (!canManageMembers(actor.get(), channel.get())) return UpdateResult.forbidden();

        if (namePresent) {
            String normalized = name == null ? "" : name.trim();
            if (normalized.isBlank()) return UpdateResult.badRequest("name_required");
            if (normalized.length() > 80) return UpdateResult.badRequest("name_too_long");
            channels.updateName(channelId, normalized);
        }

        if (photoPresent) {
            if (photoMediaAssetId == null) {
                channels.updatePhotoMediaAssetId(channelId, null);
            } else {
                var m = media.findById(photoMediaAssetId);
                if (m.isEmpty()) return UpdateResult.notFound("media_asset_not_found");
                Long ownerId = m.get().ownerId;
                if (ownerId == null || ownerId != actor.get().id) return UpdateResult.forbidden("media_asset_forbidden");
                if (m.get().mimeType == null || !ALLOWED_CHANNEL_PHOTO.contains(m.get().mimeType)) {
                    return UpdateResult.unprocessable("invalid_channel_photo");
                }
                if (m.get().s3Key == null || !m.get().s3Key.startsWith("media/")) {
                    return UpdateResult.unprocessable("invalid_channel_photo");
                }
                channels.updatePhotoMediaAssetId(channelId, photoMediaAssetId);
            }
        }

        var updated = channels.findById(channelId).orElseThrow();
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", updated.id);
        payload.put("name", updated.name);
        payload.put("member_count", updated.memberCount);
        payload.put("is_public", updated.isPublic);
        if (updated.ownerUserId != null) payload.put("owner_user_id", updated.ownerUserId);
        payload.put("viewer_can_manage_members", true);
        if (updated.photoMediaAssetId != null) payload.put("photo_media_asset_id", updated.photoMediaAssetId);
        String photoUrl = channelPhotoUrl(updated);
        if (photoUrl != null) payload.put("photo_url", photoUrl);
        payload.put("muted", channelPreferences.mutedByChannelIds(actor.get().id, List.of(channelId)).getOrDefault(channelId, false));
        return UpdateResult.ok(payload);
    }

    public PreferencesResult setPreferences(String firebaseUid, long channelId, boolean muted) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return PreferencesResult.userNotProvisioned();
        if (actor.get().isAnonymous) return PreferencesResult.anonymousNotAllowed();
        var channel = channels.findById(channelId);
        if (channel.isEmpty()) return PreferencesResult.notFound();
        if (channel.get().companyId != actor.get().companyId) return PreferencesResult.forbidden();
        boolean allowed = channel.get().isPublic || channels.isMember(channelId, actor.get().id);
        if (!allowed) return PreferencesResult.forbidden();
        channelPreferences.upsertMuted(channelId, actor.get().id, muted);
        return PreferencesResult.ok(muted);
    }

    public JoinResult join(String firebaseUid, long channelId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return JoinResult.userNotProvisioned();
        if (actor.get().isAnonymous) return JoinResult.anonymousNotAllowed();
        var channel = channels.findById(channelId);
        if (channel.isEmpty()) return JoinResult.notFound();
        if (channel.get().companyId != actor.get().companyId) return JoinResult.forbidden();
        if (!channel.get().isPublic) return JoinResult.forbidden();
        boolean changed = channels.addMember(channelId, actor.get().id, false);
        int memberCount = channels.findById(channelId).map(r -> r.memberCount).orElse(channel.get().memberCount);
        return JoinResult.ok(true, changed, memberCount);
    }

    public MessagesResult messages(String firebaseUid, long channelId, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return MessagesResult.userNotProvisioned();
        if (actor.get().isAnonymous) return MessagesResult.anonymousNotAllowed();
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

    public SendResult send(String firebaseUid, long channelId, String content, List<MessageAttachment> attachments) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return SendResult.userNotProvisioned();
        if (actor.get().isAnonymous) return SendResult.anonymousNotAllowed();
        var channel = channels.findById(channelId);
        if (channel.isEmpty()) return SendResult.notFound();
        if (channel.get().companyId != actor.get().companyId) return SendResult.forbidden();
        boolean allowed = channel.get().isPublic || channels.isMember(channelId, actor.get().id);
        if (!allowed) return SendResult.forbidden();
        if (!attachmentsValid(attachments)) return SendResult.invalidAttachments();

        // Auto-join public channels on send
        channels.addMember(channelId, actor.get().id, false);
        var message = channels.insertMessage(channelId, actor.get().id, content, attachments);
        return SendResult.ok(message);
    }

    private boolean attachmentsValid(List<MessageAttachment> attachments) {
        return MessageAttachments.validDmKeys(attachments);
    }

    @Transactional
    public CreateResult create(String firebaseUid, String name, List<Long> memberUserIds) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return CreateResult.userNotProvisioned();
        if (actor.get().isAnonymous) return CreateResult.anonymousNotAllowed();
        Set<Long> normalized = normalizeIds(memberUserIds);
        var validation = validateMemberIds(actor.get().companyId, normalized);
        if (validation.status() != Status.OK) return new CreateResult(validation.status(), null);

        long channelId = channels.insertChannel(actor.get().companyId, actor.get().id, name, false);
        channels.addMember(channelId, actor.get().id, true);
        for (Long memberId : normalized) {
            if (memberId == null || memberId == actor.get().id) continue;
            channels.addMember(channelId, memberId, false);
        }
        var created = channels.findById(channelId).orElseThrow();
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", created.id);
        payload.put("name", created.name);
        payload.put("member_count", created.memberCount);
        payload.put("is_public", created.isPublic);
        if (created.ownerUserId != null) {
            payload.put("owner_user_id", created.ownerUserId);
        }
        payload.put("viewer_can_manage_members", true);
        if (created.photoMediaAssetId != null) payload.put("photo_media_asset_id", created.photoMediaAssetId);
        String photoUrl = channelPhotoUrl(created);
        if (photoUrl != null) payload.put("photo_url", photoUrl);
        payload.put("muted", false);
        return new CreateResult(Status.OK, payload);
    }

    public MembersResult members(String firebaseUid, long channelId, String cursor, int limit) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return MembersResult.userNotProvisioned();
        if (actor.get().isAnonymous) return MembersResult.anonymousNotAllowed();
        var channel = channels.findById(channelId);
        if (channel.isEmpty()) return MembersResult.notFound();
        if (channel.get().companyId != actor.get().companyId) return MembersResult.forbidden();
        if (!channels.isMember(channelId, actor.get().id)) return MembersResult.forbidden();

        OffsetDateTime cTs = null; Long cId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                var decoded = Pagination.decode(cursor);
                cTs = decoded.timestamp();
                cId = decoded.id();
            } catch (IllegalArgumentException ignored) {}
        }
        var rows = channels.listMembers(channelId, cTs, cId, limit);
        String next = null;
        if (rows.size() == limit) {
            var last = rows.get(rows.size() - 1);
            next = Pagination.encode(last.createdAt, last.userId);
        }
        return MembersResult.ok(rows, next, channel.get().ownerUserId);
    }

    @Transactional
    public ModifyMembersResult addMembers(String firebaseUid, long channelId, List<Long> memberUserIds) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ModifyMembersResult.userNotProvisioned();
        if (actor.get().isAnonymous) return ModifyMembersResult.anonymousNotAllowed();
        var channel = channels.findById(channelId);
        if (channel.isEmpty()) return ModifyMembersResult.notFound();
        if (channel.get().companyId != actor.get().companyId) return ModifyMembersResult.forbidden();
        if (!canManageMembers(actor.get(), channel.get())) return ModifyMembersResult.forbidden();

        Set<Long> normalized = normalizeIds(memberUserIds);
        var validation = validateMemberIds(actor.get().companyId, normalized);
        if (validation.status() != Status.OK) return new ModifyMembersResult(validation.status(), 0);

        int added = 0;
        for (Long memberId : normalized) {
            if (memberId == null) continue;
            if (channels.addMember(channelId, memberId, false)) {
                added += 1;
            }
        }
        return new ModifyMembersResult(Status.OK, added);
    }

    @Transactional
    public ModifyMembersResult removeMember(String firebaseUid, long channelId, long targetUserId) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ModifyMembersResult.userNotProvisioned();
        if (actor.get().isAnonymous) return ModifyMembersResult.anonymousNotAllowed();
        var channel = channels.findById(channelId);
        if (channel.isEmpty()) return ModifyMembersResult.notFound();
        if (channel.get().companyId != actor.get().companyId) return ModifyMembersResult.forbidden();
        if (channel.get().ownerUserId != null && channel.get().ownerUserId == targetUserId) {
            return ModifyMembersResult.forbidden();
        }
        boolean isSelf = actor.get().id == targetUserId;
        if (!isSelf && !canManageMembers(actor.get(), channel.get())) return ModifyMembersResult.forbidden();
        if (!channels.removeMember(channelId, targetUserId)) return ModifyMembersResult.notFound();
        return new ModifyMembersResult(Status.OK, 1);
    }

    public ModifyMembersResult updateMemberPermission(String firebaseUid, long channelId, long targetUserId, boolean canManageMembers) {
        var actor = requireProvisionedUser(firebaseUid);
        if (actor.isEmpty()) return ModifyMembersResult.userNotProvisioned();
        if (actor.get().isAnonymous) return ModifyMembersResult.anonymousNotAllowed();
        var channel = channels.findById(channelId);
        if (channel.isEmpty()) return ModifyMembersResult.notFound();
        if (channel.get().companyId != actor.get().companyId) return ModifyMembersResult.forbidden();
        if (channel.get().ownerUserId == null || channel.get().ownerUserId != actor.get().id) {
            return ModifyMembersResult.forbidden();
        }
        if (!channels.updateMemberPermission(channelId, targetUserId, canManageMembers)) {
            return ModifyMembersResult.notFound();
        }
        return new ModifyMembersResult(Status.OK, 0);
    }

    private Optional<UserRepository.UserRow> requireProvisionedUser(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return Optional.empty();
        return user;
    }

    private boolean canManageMembers(UserRepository.UserRow actor, ChannelRepository.ChannelRow channel) {
        if (channel.ownerUserId != null && channel.ownerUserId == actor.id) return true;
        var member = channels.findMember(channel.id, actor.id);
        return member.isPresent() && member.get().canManageMembers;
    }

    private Set<Long> normalizeIds(List<Long> raw) {
        if (raw == null) return Set.of();
        return raw.stream().filter(id -> id != null && id > 0).collect(Collectors.toSet());
    }

    private ValidateMembersResult validateMemberIds(long companyId, Set<Long> memberUserIds) {
        for (Long memberId : memberUserIds) {
            var target = users.findById(memberId);
            if (target.isEmpty()) return new ValidateMembersResult(Status.NOT_FOUND);
            if (target.get().companyId == null || !target.get().companyId.equals(companyId)) {
                return new ValidateMembersResult(Status.FORBIDDEN);
            }
            if (target.get().isAnonymous) {
                return new ValidateMembersResult(Status.FORBIDDEN);
            }
        }
        return new ValidateMembersResult(Status.OK);
    }

    public enum Status { OK, USER_NOT_PROVISIONED, FORBIDDEN, NOT_FOUND, ANONYMOUS_NOT_ALLOWED, INVALID_ATTACHMENTS }

    public record ChannelListResult(Status status, List<Map<String, Object>> items, String nextCursor) {
        static ChannelListResult ok(List<Map<String, Object>> items, String next) { return new ChannelListResult(Status.OK, items, next); }
        static ChannelListResult userNotProvisioned() { return new ChannelListResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static ChannelListResult anonymousNotAllowed() { return new ChannelListResult(Status.ANONYMOUS_NOT_ALLOWED, List.of(), null); }
    }

    public record MessagesResult(Status status, List<ChannelRepository.ChannelMessageRow> messages, String nextCursor) {
        static MessagesResult ok(List<ChannelRepository.ChannelMessageRow> messages, String next) { return new MessagesResult(Status.OK, messages, next); }
        static MessagesResult userNotProvisioned() { return new MessagesResult(Status.USER_NOT_PROVISIONED, List.of(), null); }
        static MessagesResult forbidden() { return new MessagesResult(Status.FORBIDDEN, List.of(), null); }
        static MessagesResult notFound() { return new MessagesResult(Status.NOT_FOUND, List.of(), null); }
        static MessagesResult anonymousNotAllowed() { return new MessagesResult(Status.ANONYMOUS_NOT_ALLOWED, List.of(), null); }
    }

    public record SendResult(Status status, ChannelRepository.ChannelMessageRow message) {
        static SendResult ok(ChannelRepository.ChannelMessageRow message) { return new SendResult(Status.OK, message); }
        static SendResult userNotProvisioned() { return new SendResult(Status.USER_NOT_PROVISIONED, null); }
        static SendResult forbidden() { return new SendResult(Status.FORBIDDEN, null); }
        static SendResult notFound() { return new SendResult(Status.NOT_FOUND, null); }
        static SendResult anonymousNotAllowed() { return new SendResult(Status.ANONYMOUS_NOT_ALLOWED, null); }
        static SendResult invalidAttachments() { return new SendResult(Status.INVALID_ATTACHMENTS, null); }
    }

    public record CreateResult(Status status, Map<String, Object> channel) {
        static CreateResult userNotProvisioned() { return new CreateResult(Status.USER_NOT_PROVISIONED, null); }
        static CreateResult anonymousNotAllowed() { return new CreateResult(Status.ANONYMOUS_NOT_ALLOWED, null); }
        static CreateResult notFound() { return new CreateResult(Status.NOT_FOUND, null); }
        static CreateResult forbidden() { return new CreateResult(Status.FORBIDDEN, null); }
    }

    public record MembersResult(Status status, List<ChannelRepository.ChannelMemberRow> members, String nextCursor, Long ownerUserId) {
        static MembersResult ok(List<ChannelRepository.ChannelMemberRow> members, String nextCursor, Long ownerUserId) {
            return new MembersResult(Status.OK, members, nextCursor, ownerUserId);
        }
        static MembersResult userNotProvisioned() { return new MembersResult(Status.USER_NOT_PROVISIONED, List.of(), null, null); }
        static MembersResult forbidden() { return new MembersResult(Status.FORBIDDEN, List.of(), null, null); }
        static MembersResult notFound() { return new MembersResult(Status.NOT_FOUND, List.of(), null, null); }
        static MembersResult anonymousNotAllowed() { return new MembersResult(Status.ANONYMOUS_NOT_ALLOWED, List.of(), null, null); }
    }

    public record ModifyMembersResult(Status status, int changedCount) {
        static ModifyMembersResult userNotProvisioned() { return new ModifyMembersResult(Status.USER_NOT_PROVISIONED, 0); }
        static ModifyMembersResult forbidden() { return new ModifyMembersResult(Status.FORBIDDEN, 0); }
        static ModifyMembersResult notFound() { return new ModifyMembersResult(Status.NOT_FOUND, 0); }
        static ModifyMembersResult anonymousNotAllowed() { return new ModifyMembersResult(Status.ANONYMOUS_NOT_ALLOWED, 0); }
    }

    public enum UpdateStatus { OK, USER_NOT_PROVISIONED, FORBIDDEN, NOT_FOUND, BAD_REQUEST, UNPROCESSABLE_ENTITY }

    public record UpdateResult(UpdateStatus status, Map<String, Object> channel, String error) {
        static UpdateResult ok(Map<String, Object> channel) { return new UpdateResult(UpdateStatus.OK, channel, null); }
        static UpdateResult userNotProvisioned() { return new UpdateResult(UpdateStatus.USER_NOT_PROVISIONED, null, "user_not_provisioned"); }
        static UpdateResult forbidden() { return new UpdateResult(UpdateStatus.FORBIDDEN, null, "forbidden"); }
        static UpdateResult forbidden(String error) { return new UpdateResult(UpdateStatus.FORBIDDEN, null, error); }
        static UpdateResult notFound() { return new UpdateResult(UpdateStatus.NOT_FOUND, null, "not_found"); }
        static UpdateResult notFound(String error) { return new UpdateResult(UpdateStatus.NOT_FOUND, null, error); }
        static UpdateResult anonymousNotAllowed() { return new UpdateResult(UpdateStatus.FORBIDDEN, null, "anonymous_not_allowed"); }
        static UpdateResult badRequest(String error) { return new UpdateResult(UpdateStatus.BAD_REQUEST, null, error); }
        static UpdateResult unprocessable(String error) { return new UpdateResult(UpdateStatus.UNPROCESSABLE_ENTITY, null, error); }
    }

    public enum PreferencesStatus { OK, USER_NOT_PROVISIONED, FORBIDDEN, NOT_FOUND }

    public record PreferencesResult(PreferencesStatus status, Boolean muted) {
        static PreferencesResult ok(boolean muted) { return new PreferencesResult(PreferencesStatus.OK, muted); }
        static PreferencesResult userNotProvisioned() { return new PreferencesResult(PreferencesStatus.USER_NOT_PROVISIONED, null); }
        static PreferencesResult forbidden() { return new PreferencesResult(PreferencesStatus.FORBIDDEN, null); }
        static PreferencesResult notFound() { return new PreferencesResult(PreferencesStatus.NOT_FOUND, null); }
        static PreferencesResult anonymousNotAllowed() { return new PreferencesResult(PreferencesStatus.FORBIDDEN, null); }
    }

    public enum JoinStatus { OK, USER_NOT_PROVISIONED, FORBIDDEN, NOT_FOUND }

    public record JoinResult(JoinStatus status, boolean joined, boolean changed, Integer memberCount) {
        static JoinResult ok(boolean joined, boolean changed, int memberCount) {
            return new JoinResult(JoinStatus.OK, joined, changed, memberCount);
        }
        static JoinResult userNotProvisioned() { return new JoinResult(JoinStatus.USER_NOT_PROVISIONED, false, false, null); }
        static JoinResult forbidden() { return new JoinResult(JoinStatus.FORBIDDEN, false, false, null); }
        static JoinResult notFound() { return new JoinResult(JoinStatus.NOT_FOUND, false, false, null); }
        static JoinResult anonymousNotAllowed() { return new JoinResult(JoinStatus.FORBIDDEN, false, false, null); }
    }

    private String channelPhotoUrl(ChannelRepository.ChannelRow row) {
        if (row == null) return null;
        if (cloudfrontDomain == null || cloudfrontDomain.isBlank()) return null;
        if (row.photoS3Key == null || row.photoS3Key.isBlank()) return null;
        if (!row.photoS3Key.startsWith("media/")) return null;
        return "https://" + cloudfrontDomain + "/" + row.photoS3Key;
    }

    private record ValidateMembersResult(Status status) {}
}
