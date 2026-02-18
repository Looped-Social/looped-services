package com.looped.users;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class OnboardingV2Stages {
    private OnboardingV2Stages() {}

    public static final String LEGACY_PROFILE_SETUP = "profile_setup";
    public static final String LEGACY_SELECT_COMPANY = "select_company";
    public static final String LEGACY_VERIFICATION = "verification";
    public static final String LEGACY_VERIFICATION_NOTIFICATIONS = "verification_notifications";

    public static final List<String> LEGACY_SEQUENCE = List.of(
            LEGACY_PROFILE_SETUP,
            LEGACY_SELECT_COMPANY,
            LEGACY_VERIFICATION,
            LEGACY_VERIFICATION_NOTIFICATIONS
    );

    public static final String PROFILE_SETUP = "profile_setup";
    public static final String POSTING_INFO = "posting_info";
    public static final String ORG_SELECTED = "org_selected";
    public static final String VERIFICATION_CHOICE = "verification_choice";
    public static final String EMAIL_VERIFICATION = "email_verification";
    public static final String SPECIALIZATION_SELECTION = "specialization_selection";
    public static final String SKIP_EXPLAINER = "skip_explainer";
    public static final String PHOTO_ID_VERIFICATION = "photo_id_verification";
    public static final String PHOTO_PENDING_EXPLAINER = "photo_pending_explainer";
    public static final String COMPLETED = "completed";

    public static final List<String> ALL_STAGES = List.of(
            PROFILE_SETUP,
            POSTING_INFO,
            ORG_SELECTED,
            EMAIL_VERIFICATION,
            SPECIALIZATION_SELECTION,
            SKIP_EXPLAINER,
            PHOTO_ID_VERIFICATION,
            PHOTO_PENDING_EXPLAINER,
            COMPLETED
    );

    public static String normalizeLegacyStep(String step) {
        if (step == null || step.isBlank()) return null;
        String normalized = step.trim().toLowerCase(Locale.ROOT);
        return LEGACY_SEQUENCE.contains(normalized) ? normalized : null;
    }

    public static String normalizeStage(String stage) {
        if (stage == null || stage.isBlank()) return null;
        String normalized = stage.trim().toLowerCase(Locale.ROOT);
        if (VERIFICATION_CHOICE.equals(normalized)) {
            return ORG_SELECTED;
        }
        return ALL_STAGES.contains(normalized) ? normalized : null;
    }

    public static String legacyStepForStage(String stage) {
        String normalized = normalizeStage(stage);
        if (normalized == null) return LEGACY_VERIFICATION;
        return switch (normalized) {
            case PROFILE_SETUP, POSTING_INFO -> LEGACY_PROFILE_SETUP;
            case ORG_SELECTED -> LEGACY_SELECT_COMPANY;
            case COMPLETED -> LEGACY_VERIFICATION_NOTIFICATIONS;
            default -> LEGACY_VERIFICATION;
        };
    }

    public static String stageForLegacy(String legacyStep, OffsetDateTime onboardingCompletedAt) {
        String normalizedStep = normalizeLegacyStep(legacyStep);
        if (onboardingCompletedAt != null || LEGACY_VERIFICATION_NOTIFICATIONS.equals(normalizedStep)) {
            return COMPLETED;
        }
        if (LEGACY_SELECT_COMPANY.equals(normalizedStep)) {
            return ORG_SELECTED;
        }
        if (LEGACY_VERIFICATION.equals(normalizedStep)) {
            return EMAIL_VERIFICATION;
        }
        return PROFILE_SETUP;
    }

    public static List<String> allowedNextStages(String stage) {
        String normalized = normalizeStage(stage);
        if (normalized == null) return ALL_STAGES;
        return switch (normalized) {
            case PROFILE_SETUP -> List.of(POSTING_INFO);
            case POSTING_INFO -> List.of(ORG_SELECTED);
            case ORG_SELECTED -> List.of(EMAIL_VERIFICATION, SKIP_EXPLAINER, PHOTO_ID_VERIFICATION);
            case EMAIL_VERIFICATION -> List.of(SPECIALIZATION_SELECTION, SKIP_EXPLAINER);
            case SPECIALIZATION_SELECTION -> List.of(COMPLETED);
            case SKIP_EXPLAINER -> List.of(COMPLETED, EMAIL_VERIFICATION, PHOTO_ID_VERIFICATION);
            case PHOTO_ID_VERIFICATION -> List.of(PHOTO_PENDING_EXPLAINER, SKIP_EXPLAINER);
            case PHOTO_PENDING_EXPLAINER -> List.of(COMPLETED);
            case COMPLETED -> List.of(COMPLETED);
            default -> ALL_STAGES;
        };
    }

    public static List<String> toLegacySteps(List<String> stages) {
        if (stages == null || stages.isEmpty()) return LEGACY_SEQUENCE;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String stage : stages) {
            out.add(legacyStepForStage(stage));
        }
        return List.copyOf(out);
    }
}
