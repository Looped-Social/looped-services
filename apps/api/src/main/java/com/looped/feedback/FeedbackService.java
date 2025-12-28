package com.looped.feedback;

import com.looped.users.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class FeedbackService {
    private final FeedbackRepository feedback;
    private final UserRepository users;

    public FeedbackService(FeedbackRepository feedback, UserRepository users) {
        this.feedback = feedback;
        this.users = users;
    }

    public CreateResult create(String firebaseUid, String fallbackEmail, String title, String message, String email) {
        Long userId = null;
        if (firebaseUid != null && !firebaseUid.isBlank()) {
            userId = users.findByFirebaseUidIncludingDeleted(firebaseUid)
                    .map(u -> u.id)
                    .orElse(null);
        }
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail == null) {
            normalizedEmail = normalizeEmail(fallbackEmail);
        }
        String safeTitle = title == null ? "" : title.trim();
        String safeMessage = message == null ? "" : message.trim();
        long id = feedback.insert(userId, normalizedEmail, safeTitle, safeMessage);
        return CreateResult.ok(id);
    }

    private String normalizeEmail(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.isBlank() ? null : trimmed;
    }

    public record CreateResult(long id) {
        static CreateResult ok(long id) { return new CreateResult(id); }
    }
}
