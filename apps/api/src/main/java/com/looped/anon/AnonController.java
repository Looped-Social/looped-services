package com.looped.anon;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/anon")
@Validated
public class AnonController {
    private final UserRepository users;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final AnonymousProfilesRepository profiles;
    private final PrincipalRepository principals;
    private final AnonEnrollmentSanctionsRepository sanctions;
    private final AnonIssuerService issuer;
    private final AnonBackupRepository backups;
    private final AnonProofService proofs;
    private final AnonRevocationsRepository revocations;

    public AnonController(UserRepository users,
                          CommunitiesRepository communities,
                          CommunityVerificationsRepository communityVerifications,
                          AnonymousProfilesRepository profiles,
                          PrincipalRepository principals,
                          AnonEnrollmentSanctionsRepository sanctions,
                          AnonIssuerService issuer,
                          AnonBackupRepository backups,
                          AnonProofService proofs,
                          AnonRevocationsRepository revocations) {
        this.users = users;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.profiles = profiles;
        this.principals = principals;
        this.sanctions = sanctions;
        this.issuer = issuer;
        this.backups = backups;
        this.proofs = proofs;
        this.revocations = revocations;
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
        Map<String, Object> out = Map.of(
                "anon_cert_kid", info.kid(),
                "blinded_signature", Base64.getEncoder().encodeToString(blindedSignature),
                "expires_at", info.expiresAt()
        );
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
        if (cert.issuer().companyId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "issuer_not_ready",
                    "message", "Issuer missing company scope"
            ));
        }

        var existing = profiles.findByPublicKey(pubkey);
        if (existing.isPresent()) {
            principals.createForAnon(existing.get().id);
            return ResponseEntity.ok(Map.of(
                    "anon_profile_id", existing.get().id,
                    "handle", existing.get().handle,
                    "community_id", cert.issuer().scopeId,
                    "anon_cert_kid", body.anonCertKid(),
                    "expires_at", cert.issuer().expiresAt
            ));
        }

        var profile = profiles.create(null, pubkey);
        principals.createForAnon(profile.id);
        return new ResponseEntity<>(Map.of(
                "anon_profile_id", profile.id,
                "handle", profile.handle,
                "community_id", cert.issuer().scopeId,
                "anon_cert_kid", body.anonCertKid(),
                "expires_at", cert.issuer().expiresAt
        ), HttpStatus.CREATED);
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
            @NotBlank String anonCertKid
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
}
