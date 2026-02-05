package com.looped.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Service
public class FirebaseAdminService {
    private static final Logger log = LoggerFactory.getLogger(FirebaseAdminService.class);
    private static final String APP_NAME = "looped-admin";
    private final FirebaseAuth auth;
    private final boolean required;
    private final boolean enabled;
    private final String initError;

    public FirebaseAdminService(
            @Value("${firebase.admin.credentialsJson:}") String credentialsJson,
            @Value("${firebase.admin.credentialsPath:}") String credentialsPath,
            @Value("${firebase.admin.required:false}") boolean required
    ) {
        this.required = required;
        FirebaseAuth firebaseAuth = null;
        boolean configured = false;
        String error = null;
        try {
            InputStream stream = null;
            if (credentialsJson != null && !credentialsJson.isBlank()) {
                stream = new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8));
            } else if (credentialsPath != null && !credentialsPath.isBlank()) {
                stream = new FileInputStream(credentialsPath);
            }
            if (stream != null) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(stream);
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(credentials)
                        .build();
                FirebaseApp app;
                try {
                    app = FirebaseApp.getInstance(APP_NAME);
                } catch (IllegalStateException e) {
                    app = FirebaseApp.initializeApp(options, APP_NAME);
                }
                firebaseAuth = FirebaseAuth.getInstance(app);
                configured = true;
            }
        } catch (Exception e) {
            error = e.getMessage();
            log.warn("Firebase admin init failed: {}", e.getMessage());
        }
        this.auth = firebaseAuth;
        this.enabled = configured && firebaseAuth != null;
        this.initError = error;
        if (this.required && !this.enabled) {
            log.warn("Firebase admin required but not configured: {}", initError);
        }
    }

    public DeleteResult deleteUser(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            return DeleteResult.failed("invalid_uid");
        }
        if (!enabled) {
            return DeleteResult.skipped(initError != null ? initError : "not_configured");
        }
        try {
            auth.deleteUser(firebaseUid);
            return DeleteResult.ok();
        } catch (FirebaseAuthException e) {
            String code = e.getErrorCode() != null ? e.getErrorCode().name() : null;
            if ("user-not-found".equalsIgnoreCase(code)) {
                return DeleteResult.ok();
            }
            return DeleteResult.failed(code);
        } catch (Exception e) {
            return DeleteResult.failed(e.getMessage());
        }
    }

    public UnlinkProviderResult unlinkProvider(String firebaseUid, String providerId) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            return UnlinkProviderResult.failed("invalid_uid");
        }
        if (providerId == null || providerId.isBlank()) {
            return UnlinkProviderResult.failed("invalid_provider");
        }
        if (!enabled) {
            return UnlinkProviderResult.skipped(initError != null ? initError : "not_configured");
        }
        try {
            UserRecord user = auth.getUser(firebaseUid);
            boolean hasProvider = user.getProviderData() != null
                    && java.util.Arrays.stream(user.getProviderData()).anyMatch(p -> providerId.equalsIgnoreCase(p.getProviderId()));
            if (!hasProvider) {
                return UnlinkProviderResult.ok(false);
            }
            auth.updateUser(new UserRecord.UpdateRequest(firebaseUid).setProvidersToUnlink(java.util.List.of(providerId)));
            auth.revokeRefreshTokens(firebaseUid);
            return UnlinkProviderResult.ok(true);
        } catch (FirebaseAuthException e) {
            String code = e.getErrorCode() != null ? e.getErrorCode().name() : null;
            return UnlinkProviderResult.failed(code);
        } catch (Exception e) {
            return UnlinkProviderResult.failed(e.getMessage());
        }
    }

    public UpdateDisabledResult setDisabled(String firebaseUid, boolean disabled) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            return UpdateDisabledResult.failed("invalid_uid");
        }
        if (!enabled) {
            return UpdateDisabledResult.skipped(initError != null ? initError : "not_configured");
        }
        try {
            auth.updateUser(new UserRecord.UpdateRequest(firebaseUid).setDisabled(disabled));
            return UpdateDisabledResult.ok();
        } catch (FirebaseAuthException e) {
            String code = e.getErrorCode() != null ? e.getErrorCode().name() : null;
            if ("user-not-found".equalsIgnoreCase(code)) {
                return UpdateDisabledResult.ok();
            }
            return UpdateDisabledResult.failed(code);
        } catch (Exception e) {
            return UpdateDisabledResult.failed(e.getMessage());
        }
    }

    public RevokeTokensResult revokeRefreshTokens(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            return RevokeTokensResult.failed("invalid_uid");
        }
        if (!enabled) {
            return RevokeTokensResult.skipped(initError != null ? initError : "not_configured");
        }
        try {
            auth.revokeRefreshTokens(firebaseUid);
            return RevokeTokensResult.ok();
        } catch (FirebaseAuthException e) {
            String code = e.getErrorCode() != null ? e.getErrorCode().name() : null;
            if ("user-not-found".equalsIgnoreCase(code)) {
                return RevokeTokensResult.ok();
            }
            return RevokeTokensResult.failed(code);
        } catch (Exception e) {
            return RevokeTokensResult.failed(e.getMessage());
        }
    }

    public boolean isRequired() {
        return required;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public record DeleteResult(DeleteStatus status, String error) {
        static DeleteResult ok() { return new DeleteResult(DeleteStatus.OK, null); }
        static DeleteResult skipped(String error) { return new DeleteResult(DeleteStatus.SKIPPED, error); }
        static DeleteResult failed(String error) { return new DeleteResult(DeleteStatus.FAILED, error); }
    }

    public record UnlinkProviderResult(UnlinkProviderStatus status, boolean unlinked, String error) {
        static UnlinkProviderResult ok(boolean unlinked) { return new UnlinkProviderResult(UnlinkProviderStatus.OK, unlinked, null); }
        static UnlinkProviderResult skipped(String error) { return new UnlinkProviderResult(UnlinkProviderStatus.SKIPPED, false, error); }
        static UnlinkProviderResult failed(String error) { return new UnlinkProviderResult(UnlinkProviderStatus.FAILED, false, error); }
    }

    public record UpdateDisabledResult(UpdateDisabledStatus status, String error) {
        static UpdateDisabledResult ok() { return new UpdateDisabledResult(UpdateDisabledStatus.OK, null); }
        static UpdateDisabledResult skipped(String error) { return new UpdateDisabledResult(UpdateDisabledStatus.SKIPPED, error); }
        static UpdateDisabledResult failed(String error) { return new UpdateDisabledResult(UpdateDisabledStatus.FAILED, error); }
    }

    public record RevokeTokensResult(RevokeTokensStatus status, String error) {
        static RevokeTokensResult ok() { return new RevokeTokensResult(RevokeTokensStatus.OK, null); }
        static RevokeTokensResult skipped(String error) { return new RevokeTokensResult(RevokeTokensStatus.SKIPPED, error); }
        static RevokeTokensResult failed(String error) { return new RevokeTokensResult(RevokeTokensStatus.FAILED, error); }
    }

    public enum DeleteStatus { OK, SKIPPED, FAILED }

    public enum UnlinkProviderStatus { OK, SKIPPED, FAILED }

    public enum UpdateDisabledStatus { OK, SKIPPED, FAILED }

    public enum RevokeTokensStatus { OK, SKIPPED, FAILED }
}
