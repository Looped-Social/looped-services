package com.looped.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
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

    public enum DeleteStatus { OK, SKIPPED, FAILED }
}
