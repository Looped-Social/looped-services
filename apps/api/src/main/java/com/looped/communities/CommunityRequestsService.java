package com.looped.communities;

import com.looped.users.UserRepository;
import com.looped.media.MediaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class CommunityRequestsService {
    private static final Logger log = LoggerFactory.getLogger(CommunityRequestsService.class);
    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityRequestsRepository requests;
    private final MediaRepository media;
    private final MeterRegistry meters;

    public CommunityRequestsService(UserRepository users,
                                    CommunitiesRepository communities,
                                    CommunityRequestsRepository requests,
                                    MediaRepository media,
                                    MeterRegistry meters) {
        this.users = users;
        this.communities = communities;
        this.requests = requests;
        this.media = media;
        this.meters = meters;
    }

    public CreateResult create(String firebaseUid,
                               String kind,
                               String name,
                               String description,
                               String imageKey,
                               String contactEmail,
                               Boolean notifyWhenAvailable) {
        var userOpt = users.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty() || userOpt.get().companyId == null) {
            return CreateResult.userNotProvisioned();
        }
        var kindInfo = normalizeKind(kind);
        if (kindInfo == null) return CreateResult.invalidKind();
        String normalizedName = normalizeName(name);
        if (normalizedName == null) return CreateResult.invalidName();
        String normalizedDescription = normalizeDescription(description);
        var parsed = CommunityRequestContactEmails.parseLegacyContactEmailLine(normalizedDescription);
        normalizedDescription = parsed.description();
        String resolvedContactEmailRaw = (contactEmail == null || contactEmail.isBlank()) ? parsed.extractedEmail() : contactEmail;
        String normalizedContactEmail = CommunityRequestContactEmails.normalizeValidEmailOrNull(resolvedContactEmailRaw);
        if (resolvedContactEmailRaw != null && !resolvedContactEmailRaw.isBlank() && normalizedContactEmail == null) {
            return CreateResult.invalidContactEmail();
        }
        boolean wantsNotify = Boolean.TRUE.equals(notifyWhenAvailable);
        if (wantsNotify && normalizedContactEmail == null) {
            return CreateResult.contactEmailRequired();
        }
        String normalizedImageKey = normalizeImageKey(imageKey);
        if (normalizedImageKey != null) {
            var mediaRowOpt = media.findByKey(normalizedImageKey);
            if (mediaRowOpt.isEmpty()) return CreateResult.invalidImage();
            var mediaRow = mediaRowOpt.get();
            if (mediaRow.ownerId == null || mediaRow.ownerId.longValue() != userOpt.get().id) {
                return CreateResult.imageNotOwned();
            }
            if (mediaRow.mimeType == null || !mediaRow.mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                return CreateResult.invalidImage();
            }
        }
        boolean exists = kindInfo.specializationType() != null
                ? communities.findByKindAndName(kindInfo.communityKind(), normalizedName, kindInfo.specializationType()).isPresent()
                : communities.findByKindAndName(kindInfo.communityKind(), normalizedName).isPresent();
        if (exists) {
            return CreateResult.communityExists();
        }
        try {
            long id = requests.insert(
                    userOpt.get().id,
                    kindInfo.requestKind(),
                    normalizedName,
                    normalizedDescription,
                    normalizedImageKey,
                    normalizedContactEmail,
                    wantsNotify
            );
            meters.counter("community_requests.created", "kind", kindInfo.requestKind()).increment();
            log.info("community_request_created request_id={} user_id={} kind={} notify_when_available={} has_contact_email={}",
                    id, userOpt.get().id, kindInfo.requestKind(), wantsNotify, normalizedContactEmail != null);
            return CreateResult.ok(id);
        } catch (DuplicateKeyException e) {
            return CreateResult.duplicate();
        }
    }

    public ListResult list(String firebaseUid, String status) {
        var userOpt = users.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty() || userOpt.get().companyId == null) {
            return ListResult.userNotProvisioned();
        }
        String normalizedStatus = normalizeStatus(status);
        List<CommunityRequestsRepository.Row> items = requests.listByUser(userOpt.get().id, normalizedStatus);
        return ListResult.ok(items);
    }

    private KindInfo normalizeKind(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return null;
        if (normalized.equals("workplace")) normalized = "company";
        if (!normalized.equals("company") && !normalized.equals("field")) {
            return null;
        }
        if (normalized.equals("field")) {
            return new KindInfo(normalized, "specialization", normalized);
        }
        return new KindInfo(normalized, normalized, null);
    }

    private record KindInfo(String requestKind, String communityKind, String specializationType) {}

    private String normalizeName(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeDescription(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeImageKey(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeStatus(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    public enum Status {
        OK,
        USER_NOT_PROVISIONED,
        INVALID_KIND,
        INVALID_NAME,
        INVALID_CONTACT_EMAIL,
        CONTACT_EMAIL_REQUIRED,
        COMMUNITY_EXISTS,
        INVALID_IMAGE,
        IMAGE_NOT_OWNED,
        DUPLICATE
    }

    public record CreateResult(Status status, Long id) {
        static CreateResult ok(long id) { return new CreateResult(Status.OK, id); }
        static CreateResult userNotProvisioned() { return new CreateResult(Status.USER_NOT_PROVISIONED, null); }
        static CreateResult invalidKind() { return new CreateResult(Status.INVALID_KIND, null); }
        static CreateResult invalidName() { return new CreateResult(Status.INVALID_NAME, null); }
        static CreateResult invalidContactEmail() { return new CreateResult(Status.INVALID_CONTACT_EMAIL, null); }
        static CreateResult contactEmailRequired() { return new CreateResult(Status.CONTACT_EMAIL_REQUIRED, null); }
        static CreateResult communityExists() { return new CreateResult(Status.COMMUNITY_EXISTS, null); }
        static CreateResult invalidImage() { return new CreateResult(Status.INVALID_IMAGE, null); }
        static CreateResult imageNotOwned() { return new CreateResult(Status.IMAGE_NOT_OWNED, null); }
        static CreateResult duplicate() { return new CreateResult(Status.DUPLICATE, null); }
    }

    public record ListResult(Status status, List<CommunityRequestsRepository.Row> items) {
        static ListResult ok(List<CommunityRequestsRepository.Row> items) {
            return new ListResult(Status.OK, items);
        }
        static ListResult userNotProvisioned() {
            return new ListResult(Status.USER_NOT_PROVISIONED, List.of());
        }
    }
}
