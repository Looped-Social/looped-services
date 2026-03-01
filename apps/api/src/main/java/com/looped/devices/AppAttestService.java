package com.looped.devices;

import com.looped.users.UserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class AppAttestService {
    private static final String REDIS_PREFIX = "device:app_attest:challenge:";

    private final UserRepository users;
    private final DeviceAppAttestRepository attestations;
    private final StringRedisTemplate redis;
    private final AppAttestProperties properties;
    private final SecureRandom random = new SecureRandom();

    public AppAttestService(UserRepository users,
                            DeviceAppAttestRepository attestations,
                            StringRedisTemplate redis,
                            AppAttestProperties properties) {
        this.users = users;
        this.attestations = attestations;
        this.redis = redis;
        this.properties = properties;
    }

    public StartResult start(String firebaseUid) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return StartResult.userNotProvisioned(mode());
        if (mode() == AppAttestProperties.Mode.DISABLED) return StartResult.disabled(mode());

        String challengeId = UUID.randomUUID().toString();
        String challenge = randomToken(32);
        Duration ttl = properties.getChallengeTtl() != null ? properties.getChallengeTtl() : Duration.ofMinutes(5);
        redis.opsForValue().set(redisKey(challengeId), Long.toString(user.get().id), ttl);
        return StartResult.ok(mode(), challengeId, challenge, OffsetDateTime.now().plus(ttl));
    }

    public CompleteResult complete(String firebaseUid,
                                   String challengeId,
                                   String keyId,
                                   String attestationObject,
                                   String assertionObject) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return CompleteResult.userNotProvisioned(mode());
        if (mode() == AppAttestProperties.Mode.DISABLED) return CompleteResult.disabled(mode());

        if (challengeId == null || challengeId.isBlank() || keyId == null || keyId.isBlank()) {
            return CompleteResult.invalidInput(mode(), "invalid_input");
        }
        String key = redisKey(challengeId);
        String value = redis.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return CompleteResult.invalidChallenge(mode());
        }
        redis.delete(key);
        long expectedUserId;
        try {
            expectedUserId = Long.parseLong(value);
        } catch (NumberFormatException e) {
            return CompleteResult.invalidChallenge(mode());
        }
        if (expectedUserId != user.get().id) {
            return CompleteResult.invalidChallenge(mode());
        }

        // Placeholder rollout scaffolding only: we persist observed proofs now and can swap in
        // Apple's cryptographic verifier later without changing the client contract.
        boolean opaqueProofPresent = attestationObject != null && !attestationObject.isBlank();
        boolean trusted = properties.isAllowInsecureObservedTrust() && opaqueProofPresent;
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime trustedUntil = trusted
                ? now.plus(properties.getTrustTtl() != null ? properties.getTrustTtl() : Duration.ofDays(30))
                : null;
        String status = trusted ? "trusted" : "observed";
        String error = trusted ? null : "verification_unavailable";
        var row = attestations.upsert(
                user.get().id,
                keyId.trim(),
                "ios",
                status,
                now,
                trusted ? now : null,
                trustedUntil,
                error
        );
        return CompleteResult.ok(mode(), row, trusted, requiredForAnonEnrollment());
    }

    public StatusResult status(String firebaseUid, String keyId) {
        var user = users.findByFirebaseUid(firebaseUid);
        if (user.isEmpty() || user.get().companyId == null) return StatusResult.userNotProvisioned(mode());
        Optional<DeviceAppAttestRepository.Row> row = keyId == null || keyId.isBlank()
                ? attestations.findLatestByUserId(user.get().id)
                : attestations.findByUserIdAndKeyId(user.get().id, keyId.trim());
        if (row.isEmpty()) {
            return StatusResult.missing(mode(), requiredForAnonEnrollment(), keyId);
        }
        boolean trusted = "trusted".equalsIgnoreCase(row.get().status)
                && (row.get().trustedUntil == null || row.get().trustedUntil.isAfter(OffsetDateTime.now()));
        return StatusResult.ok(mode(), requiredForAnonEnrollment(), row.get(), trusted);
    }

    public AttestDecision anonEnrollmentDecision(long userId, String keyId) {
        boolean required = requiredForAnonEnrollment();
        if (keyId == null || keyId.isBlank()) {
            return new AttestDecision(mode(), required, false, null);
        }
        var row = attestations.findByUserIdAndKeyId(userId, keyId.trim()).orElse(null);
        boolean trusted = row != null && attestations.hasActiveTrustedKey(userId, keyId.trim());
        return new AttestDecision(mode(), required, trusted, row);
    }

    private boolean requiredForAnonEnrollment() {
        return mode() == AppAttestProperties.Mode.ENFORCE;
    }

    private AppAttestProperties.Mode mode() {
        return properties.mode();
    }

    private String redisKey(String challengeId) {
        return REDIS_PREFIX + challengeId;
    }

    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public record AttestDecision(AppAttestProperties.Mode mode,
                                 boolean required,
                                 boolean trusted,
                                 DeviceAppAttestRepository.Row row) {}

    public record StartResult(Status status,
                              AppAttestProperties.Mode mode,
                              String challengeId,
                              String challenge,
                              OffsetDateTime expiresAt,
                              String error) {
        static StartResult ok(AppAttestProperties.Mode mode, String challengeId, String challenge, OffsetDateTime expiresAt) {
            return new StartResult(Status.OK, mode, challengeId, challenge, expiresAt, null);
        }

        static StartResult disabled(AppAttestProperties.Mode mode) {
            return new StartResult(Status.DISABLED, mode, null, null, null, "app_attest_disabled");
        }

        static StartResult userNotProvisioned(AppAttestProperties.Mode mode) {
            return new StartResult(Status.USER_NOT_PROVISIONED, mode, null, null, null, "user_not_provisioned");
        }
    }

    public record CompleteResult(Status status,
                                 AppAttestProperties.Mode mode,
                                 DeviceAppAttestRepository.Row row,
                                 boolean trusted,
                                 boolean requiredForAnonEnrollment,
                                 String error) {
        static CompleteResult ok(AppAttestProperties.Mode mode,
                                 DeviceAppAttestRepository.Row row,
                                 boolean trusted,
                                 boolean requiredForAnonEnrollment) {
            return new CompleteResult(Status.OK, mode, row, trusted, requiredForAnonEnrollment, null);
        }

        static CompleteResult disabled(AppAttestProperties.Mode mode) {
            return new CompleteResult(Status.DISABLED, mode, null, false, false, "app_attest_disabled");
        }

        static CompleteResult userNotProvisioned(AppAttestProperties.Mode mode) {
            return new CompleteResult(Status.USER_NOT_PROVISIONED, mode, null, false, false, "user_not_provisioned");
        }

        static CompleteResult invalidChallenge(AppAttestProperties.Mode mode) {
            return new CompleteResult(Status.INVALID_CHALLENGE, mode, null, false, mode == AppAttestProperties.Mode.ENFORCE, "invalid_challenge");
        }

        static CompleteResult invalidInput(AppAttestProperties.Mode mode, String error) {
            return new CompleteResult(Status.INVALID_INPUT, mode, null, false, mode == AppAttestProperties.Mode.ENFORCE, error);
        }
    }

    public record StatusResult(Status status,
                               AppAttestProperties.Mode mode,
                               DeviceAppAttestRepository.Row row,
                               boolean trusted,
                               boolean requiredForAnonEnrollment,
                               String keyId,
                               String error) {
        static StatusResult ok(AppAttestProperties.Mode mode,
                               boolean requiredForAnonEnrollment,
                               DeviceAppAttestRepository.Row row,
                               boolean trusted) {
            return new StatusResult(Status.OK, mode, row, trusted, requiredForAnonEnrollment, row.keyId, null);
        }

        static StatusResult missing(AppAttestProperties.Mode mode,
                                    boolean requiredForAnonEnrollment,
                                    String keyId) {
            return new StatusResult(Status.MISSING, mode, null, false, requiredForAnonEnrollment, keyId, null);
        }

        static StatusResult userNotProvisioned(AppAttestProperties.Mode mode) {
            return new StatusResult(Status.USER_NOT_PROVISIONED, mode, null, false, false, null, "user_not_provisioned");
        }
    }

    public enum Status {
        OK,
        DISABLED,
        MISSING,
        INVALID_INPUT,
        INVALID_CHALLENGE,
        USER_NOT_PROVISIONED
    }
}
