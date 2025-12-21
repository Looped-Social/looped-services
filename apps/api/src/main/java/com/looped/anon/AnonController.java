package com.looped.anon;

import com.looped.principals.PrincipalRepository;
import com.looped.users.UserRepository;
import com.looped.verification.VerificationRepository;
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
    private final VerificationRepository verifications;
    private final AnonymousProfilesRepository profiles;
    private final PrincipalRepository principals;
    private final AnonEnrollmentSanctionsRepository sanctions;
    private final AnonIssuerService issuer;
    private final AnonBackupRepository backups;

    public AnonController(UserRepository users,
                          VerificationRepository verifications,
                          AnonymousProfilesRepository profiles,
                          PrincipalRepository principals,
                          AnonEnrollmentSanctionsRepository sanctions,
                          AnonIssuerService issuer,
                          AnonBackupRepository backups) {
        this.users = users;
        this.verifications = verifications;
        this.profiles = profiles;
        this.principals = principals;
        this.sanctions = sanctions;
        this.issuer = issuer;
        this.backups = backups;
    }

    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EnrollRequest body) {
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
        var verification = verifications.findByUserId(user.get().id);
        if (verification.isEmpty() || !verification.get().verified) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "not_verified",
                    "message", "You must be verified before enrolling"
            ));
        }
        long companyId = user.get().companyId;
        byte[] pubkey;
        byte[] blindedMessage;
        try {
            pubkey = Base64.getDecoder().decode(body.personaPubkey());
            blindedMessage = Base64.getDecoder().decode(body.blindedMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "invalid_base64"
            ));
        }

        var existingProfile = profiles.findByPublicKey(pubkey);
        if (existingProfile.isPresent()) {
            if (existingProfile.get().companyId != companyId) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "company_mismatch",
                        "message", "Anonymous profile scope mismatch"
                ));
            }
            principals.createForAnon(existingProfile.get().id);
            byte[] blindedSignature = issuer.signBlinded(blindedMessage);
            Map<String, Object> out = Map.of(
                    "anon_profile_id", existingProfile.get().id,
                    "handle", existingProfile.get().handle,
                    "company_id", existingProfile.get().companyId,
                    "anon_cert_kid", issuer.kid(),
                    "blinded_signature", Base64.getEncoder().encodeToString(blindedSignature),
                    "expires_at", issuer.expiresAt()
            );
            return ResponseEntity.ok(out);
        }

        if (sanctions.existsActive(user.get().id, "company", companyId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "anon_enrollment_blocked",
                    "message", "Anonymous enrollment is blocked for this account"
            ));
        }

        String handle = profiles.nextHandle(companyId);
        var profile = profiles.create(companyId, pubkey, handle);
        principals.createForAnon(profile.id);
        sanctions.addActive(user.get().id, "company", companyId, "enrolled");

        byte[] blindedSignature = issuer.signBlinded(blindedMessage);

        Map<String, Object> out = Map.of(
                "anon_profile_id", profile.id,
                "handle", profile.handle,
                "company_id", profile.companyId,
                "anon_cert_kid", issuer.kid(),
                "blinded_signature", Base64.getEncoder().encodeToString(blindedSignature),
                "expires_at", issuer.expiresAt()
        );
        return new ResponseEntity<>(out, HttpStatus.CREATED);
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

    public record EnrollRequest(
            @NotBlank String personaPubkey,
            @NotBlank String blindedMessage
    ) {}

    public record BackupRequest(
            @NotNull UUID blobId,
            @NotBlank String salt,
            @NotBlank String ciphertext,
            OffsetDateTime expiresAt
    ) {}
}
