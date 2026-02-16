package com.looped.anon;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.anon.crypto.PemKeyUtils;
import com.looped.users.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.security.SecureRandom;

@RestController
@RequestMapping("/anon")
@Validated
public class AnonController {
    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final SpecializationJoinsRepository specializationJoins;
    private final AnonymousProfilesRepository profiles;
    private final PrincipalRepository principals;
    private final AnonEnrollmentSanctionsRepository sanctions;
    private final AnonIssuerService issuer;
    private final AnonIssuerProperties issuerProps;
    private final AnonBackupRepository backups;
    private final AnonProofService proofs;
    private final AnonRevocationsRepository revocations;
    private final AnonIssueTokenRepository issueTokens;
    private final AnonCertEntitlementsRepository certEntitlements;
    private final SecureRandom issueTokenRandom = new SecureRandom();

    public AnonController(UserRepository users,
                          CommunitiesRepository communities,
                          CommunityVerificationsRepository communityVerifications,
                          SpecializationJoinsRepository specializationJoins,
                          AnonymousProfilesRepository profiles,
                          PrincipalRepository principals,
                          AnonEnrollmentSanctionsRepository sanctions,
                          AnonIssuerService issuer,
                          AnonIssuerProperties issuerProps,
                          AnonBackupRepository backups,
                          AnonProofService proofs,
                          AnonRevocationsRepository revocations,
                          AnonIssueTokenRepository issueTokens,
                          AnonCertEntitlementsRepository certEntitlements) {
        this.users = users;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.specializationJoins = specializationJoins;
        this.profiles = profiles;
        this.principals = principals;
        this.sanctions = sanctions;
        this.issuer = issuer;
        this.issuerProps = issuerProps;
        this.backups = backups;
        this.proofs = proofs;
        this.revocations = revocations;
        this.issueTokens = issueTokens;
        this.certEntitlements = certEntitlements;
    }

    @PostMapping("/issue")
    public ResponseEntity<?> issue(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody IssueRequest body) {
        var user = users.findByFirebaseUid(jwt.getSubject());
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before enrolling"
            ));
        }
        if (user.get().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before enrolling"
            ));
        }
        var community = communities.findById(body.communityId());
        if (community.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "Community not found"
            ));
        }
        if (requiresVerification(community.get())
                && !communityVerifications.isVerified(user.get().id, body.communityId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_not_verified",
                    "message", "You must be verified before enrolling"
            ));
        }
        if (requiresSpecializationJoin(community.get())
                && !specializationJoins.exists(user.get().id, body.communityId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "specialization_not_joined",
                    "message", "You must join this specialization before enrolling"
            ));
        }
        byte[] blindedMessage;
        try {
            blindedMessage = Base64.getDecoder().decode(body.blindedMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_base64"
            ));
        }
        if (sanctions.existsActive(user.get().id, "community", body.communityId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "anon_enrollment_blocked",
                    "message", "Anonymous enrollment is blocked for this community"
            ));
        }
        byte[] blindedSignature = issuer.signBlinded(body.communityId(), user.get().companyId, blindedMessage);
        var info = issuer.issuerInfo(body.communityId(), user.get().companyId);
        String issueToken = AnonIssueTokenCodec.generateToken(issueTokenRandom);
        byte[] issueTokenHash = AnonIssueTokenCodec.hash(issueToken);
        OffsetDateTime issueTokenExpiresAt = OffsetDateTime.now().plus(
                issuerProps.getIssueTokenTtl() != null ? issuerProps.getIssueTokenTtl() : java.time.Duration.ofMinutes(10)
        );
        issueTokens.create(issueTokenHash, user.get().id, body.communityId(), issueTokenExpiresAt);

        Map<String, Object> out = new HashMap<>();
        out.put("anon_cert_kid", info.kid());
        out.put("blinded_signature", Base64.getEncoder().encodeToString(blindedSignature));
        out.put("expires_at", info.expiresAt());
        out.put("issue_token", issueToken);
        out.put("issueToken", issueToken);
        out.put("issue_token_expires_at", issueTokenExpiresAt);
        out.put("issueTokenExpiresAt", issueTokenExpiresAt);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/backup")
    public ResponseEntity<?> backup(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BackupRequest body) {
        byte[] salt;
        byte[] ciphertext;
        try {
            salt = Base64.getDecoder().decode(body.salt());
            ciphertext = Base64.getDecoder().decode(body.ciphertext());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_base64"
            ));
        }
        backups.upsert(body.blobId(), salt, ciphertext, body.expiresAt());
        return new ResponseEntity<>(Map.of("status", "ok"), HttpStatus.CREATED);
    }

    @GetMapping("/issuer")
    public ResponseEntity<?> issuer(@AuthenticationPrincipal Jwt jwt, @RequestParam("communityId") long communityId) {
        var user = users.findByFirebaseUid(jwt.getSubject());
        if (user.isEmpty() || user.get().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before requesting issuer"
            ));
        }
        if (communities.findById(communityId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "Community not found"
            ));
        }
        var info = issuer.issuerInfo(communityId, user.get().companyId);
        Map<String, Object> out = Map.of(
                "kid", info.kid(),
                "alg", "RSABSSA",
                "public_key_pem", PemKeyUtils.encodePublicKeyPem(info.publicKey()),
                "expires_at", info.expiresAt()
        );
        return ResponseEntity.ok(out);
    }

    private boolean requiresVerification(CommunitiesRepository.CommunityRow community) {
        return community != null && !"specialization".equalsIgnoreCase(community.kind);
    }

    private boolean requiresSpecializationJoin(CommunitiesRepository.CommunityRow community) {
        if (community == null || community.kind == null) return false;
        if (!"specialization".equalsIgnoreCase(community.kind)) return false;
        if (community.specializationType == null) return false;
        String type = community.specializationType.trim().toLowerCase(java.util.Locale.ROOT);
        return "major".equals(type) || "field".equals(type);
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody RegisterRequest body
    ) {
        if (authHeader != null && !authHeader.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        byte[] pubkey;
        try {
            pubkey = Base64.getDecoder().decode(body.personaPubkey());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_base64"
            ));
        }
        if (revocations.isRevokedByPubkey(pubkey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "anon_revoked",
                    "message", "Anonymous profile is revoked"
            ));
        }
        var cert = proofs.verifyCert(body.anonCert(), body.anonCertKid(), pubkey);
        if (cert.status() == AnonProofService.Status.INVALID_SIGNATURE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_proof",
                    "message", "Invalid anonymous proof"
            ));
        }
        if (cert.status() == AnonProofService.Status.INVALID_CERT || cert.issuer() == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_cert",
                    "message", "Invalid anonymous certificate"
            ));
        }
        if (!"community".equals(cert.issuer().scopeKind) || cert.issuer().scopeId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "invalid_anon_cert",
                    "message", "Anonymous certificate scope invalid"
            ));
        }
        if (body.communityId() != null && !body.communityId().equals(cert.issuer().scopeId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "anon_scope_mismatch",
                    "message", "Anonymous certificate is not scoped to requested community",
                    "requested_community_id", body.communityId(),
                    "cert_community_id", cert.issuer().scopeId
            ));
        }
        if (body.issueToken() == null || body.issueToken().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "issue_token_required",
                    "message", "issueToken is required"
            ));
        }
        byte[] issueTokenHash = AnonIssueTokenCodec.hash(body.issueToken());
        var issued = issueTokens.consumeActive(issueTokenHash);
        if (issued.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "issue_token_invalid",
                    "message", "Issue token is invalid, expired, or already used"
            ));
        }
        long issuedCommunityId = issued.get().communityId();
        if (body.communityId() != null && !body.communityId().equals(issuedCommunityId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "anon_scope_mismatch",
                    "message", "Anonymous certificate is not scoped to requested community",
                    "requested_community_id", body.communityId(),
                    "cert_community_id", cert.issuer().scopeId
            ));
        }
        if (!Long.valueOf(issuedCommunityId).equals(cert.issuer().scopeId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "anon_scope_mismatch",
                    "message", "Anonymous certificate is not scoped to requested community",
                    "requested_community_id", issuedCommunityId,
                    "cert_community_id", cert.issuer().scopeId
            ));
        }
        var issuedCommunity = communities.findById(issuedCommunityId);
        if (issuedCommunity.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "community_not_found",
                    "message", "Community not found"
            ));
        }
        if (requiresVerification(issuedCommunity.get())
                && !communityVerifications.isVerified(issued.get().userId(), issuedCommunityId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "community_not_verified",
                    "message", "You must be verified before enrolling"
            ));
        }
        if (requiresSpecializationJoin(issuedCommunity.get())
                && !specializationJoins.exists(issued.get().userId(), issuedCommunityId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "specialization_not_joined",
                    "message", "You must join this specialization before enrolling"
            ));
        }
        if (cert.issuer().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "issuer_not_ready",
                    "message", "Issuer missing company scope"
            ));
        }
        byte[] certBytes;
        try {
            certBytes = Base64.getDecoder().decode(body.anonCert());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_base64"
            ));
        }
        byte[] certFingerprint = AnonCertFingerprint.sha256(body.anonCertKid(), certBytes);
        certEntitlements.upsert(certFingerprint, body.anonCertKid(), issued.get().userId(), issuedCommunityId, cert.issuer().expiresAt);

        var existing = profiles.findByPublicKey(pubkey);
        if (existing.isPresent()) {
            principals.createForAnon(existing.get().id);
            return ResponseEntity.ok(registerResponse(existing.get().id, existing.get().handle, issuedCommunityId, body.anonCertKid(), cert.issuer().expiresAt));
        }

        var profile = profiles.create(null, pubkey);
        principals.createForAnon(profile.id);
        return new ResponseEntity<>(registerResponse(profile.id, profile.handle, issuedCommunityId, body.anonCertKid(), cert.issuer().expiresAt), HttpStatus.CREATED);
    }

    @GetMapping("/backup/{blobId}")
    public ResponseEntity<?> getBackup(@PathVariable("blobId") UUID blobId) {
        var row = backups.find(blobId);
        if (row.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        Map<String, Object> out = Map.of(
                "blob_id", row.get().blobId,
                "salt", Base64.getEncoder().encodeToString(row.get().salt),
                "ciphertext", Base64.getEncoder().encodeToString(row.get().ciphertext),
                "expires_at", row.get().expiresAt
        );
        return ResponseEntity.ok(out);
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset(@AuthenticationPrincipal Jwt jwt) {
        var user = users.findByFirebaseUid(jwt.getSubject());
        if (user.isEmpty() || user.get().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "user_not_provisioned",
                    "message", "Complete onboarding before resetting anonymous enrollment"
            ));
        }
        boolean cleared = sanctions.clearAllForUser(user.get().id, "reset");
        return ResponseEntity.ok(Map.of("reset", true, "cleared", cleared));
    }

    @PostMapping("/revoke")
    public ResponseEntity<?> revoke(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody RevokeRequest body
    ) {
        if (authHeader != null && !authHeader.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "anon_jwt_not_allowed",
                    "message", "Do not send Authorization for anonymous actions"
            ));
        }
        var proof = new AnonProofService.AnonActionProof(body.anonProfileId(), body.anonCert(), body.anonCertKid(), body.anonSig());
        var verified = proofs.verifyAction(proof, "revoke", body.anonProfileId());
        if (verified.status() == AnonProofService.Status.NOT_FOUND) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        if (verified.status() == AnonProofService.Status.INVALID_CERT || verified.status() == AnonProofService.Status.INVALID_SIGNATURE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "invalid_anon_proof"));
        }
        if (verified.status() == AnonProofService.Status.REVOKED) {
            return ResponseEntity.ok(Map.of("revoked", true, "already_revoked", true));
        }

        revocations.revokeByPubkey(verified.actor().personaPubkey(), "self_revoke");
        return ResponseEntity.ok(Map.of("revoked", true));
    }

    public record IssueRequest(
            @NotNull Long communityId,
            @NotBlank String blindedMessage
    ) {}

    public record RegisterRequest(
            @NotBlank String personaPubkey,
            @NotBlank String anonCert,
            @NotBlank String anonCertKid,
            @JsonAlias("community_id") Long communityId,
            @JsonAlias("issue_token") String issueToken
    ) {}

    public record BackupRequest(
            @NotNull UUID blobId,
            @NotBlank String salt,
            @NotBlank String ciphertext,
            OffsetDateTime expiresAt
    ) {}

    public record RevokeRequest(
            @NotNull Long anonProfileId,
            @NotBlank String anonCert,
            @NotBlank String anonCertKid,
            @NotBlank String anonSig
    ) {}

    private Map<String, Object> registerResponse(long anonProfileId,
                                                 String handle,
                                                 long communityId,
                                                 String anonCertKid,
                                                 OffsetDateTime expiresAt) {
        return Map.of(
                "anon_profile_id", anonProfileId,
                "handle", handle,
                "community_id", communityId,
                "communityId", communityId,
                "anon_cert_kid", anonCertKid,
                "expires_at", expiresAt,
                "expiresAt", expiresAt
        );
    }
}
