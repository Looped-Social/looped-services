package com.looped.users;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationMembershipService;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.verification.VerificationRequestsRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class OnboardingV2Service {
    private final UserRepository users;
    private final OnboardingV2Repository onboardingV2;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final SpecializationMembershipService specializationMemberships;
    private final SpecializationJoinsRepository specializationJoins;
    private final VerificationRequestsRepository verificationRequests;

    public OnboardingV2Service(UserRepository users,
                               OnboardingV2Repository onboardingV2,
                               CommunitiesRepository communities,
                               CommunityVerificationsRepository communityVerifications,
                               SpecializationMembershipService specializationMemberships,
                               SpecializationJoinsRepository specializationJoins,
                               VerificationRequestsRepository verificationRequests) {
        this.users = users;
        this.onboardingV2 = onboardingV2;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.specializationMemberships = specializationMemberships;
        this.specializationJoins = specializationJoins;
        this.verificationRequests = verificationRequests;
    }

    public Snapshot snapshot(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            return defaultSnapshot();
        }
        var userOpt = users.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty() || userOpt.get().companyId == null) {
            return defaultSnapshot();
        }
        var state = ensureState(userOpt.get());
        return snapshot(userOpt.get(), state);
    }

    public void syncFromLegacyStep(long userId, String legacyStep, OffsetDateTime completedAt) {
        var row = onboardingV2.ensureForUser(userId, legacyStep, completedAt);
        String targetStage = OnboardingV2Stages.stageForLegacy(legacyStep, completedAt);
        if (OnboardingV2Stages.COMPLETED.equals(row.stageV2) && !OnboardingV2Stages.COMPLETED.equals(targetStage)) {
            return;
        }
        row.stageV2 = targetStage;
        if (OnboardingV2Stages.COMPLETED.equals(targetStage)) {
            if (row.completionReason == null || row.completionReason.isBlank()) {
                row.completionReason = "legacy_backfill";
            }
            if (row.finalizedAt == null) {
                row.finalizedAt = completedAt != null ? completedAt : OffsetDateTime.now();
            }
        }
        onboardingV2.update(row);
    }

    public Result markInfoScreenViewed(String firebaseUid) {
        var bundleOpt = provisioned(firebaseUid);
        if (bundleOpt.isEmpty()) return Result.userNotProvisioned();
        var bundle = bundleOpt.get();
        if (isCompleted(bundle.state)) return Result.ok(snapshot(bundle.user, bundle.state));
        if (bundle.state.infoScreenViewedAt == null) {
            bundle.state.infoScreenViewedAt = OffsetDateTime.now();
        }
        if (OnboardingV2Stages.PROFILE_SETUP.equals(bundle.state.stageV2)) {
            bundle.state.stageV2 = OnboardingV2Stages.POSTING_INFO;
        }
        persistProgress(bundle);
        return Result.ok(snapshot(bundle.user, bundle.state));
    }

    public Result setSelectedOrg(String firebaseUid, long orgId) {
        if (orgId <= 0) return Result.invalidInput("invalid_org_id", defaultSnapshot());
        var bundleOpt = provisioned(firebaseUid);
        if (bundleOpt.isEmpty()) return Result.userNotProvisioned();
        var bundle = bundleOpt.get();
        if (isCompleted(bundle.state)) return Result.ok(snapshot(bundle.user, bundle.state));
        boolean canSetOrg = OnboardingV2Stages.POSTING_INFO.equals(bundle.state.stageV2)
                || OnboardingV2Stages.ORG_SELECTED.equals(bundle.state.stageV2)
                || (bundle.state.selectedOrgId == null && (bundle.state.verificationPath == null || bundle.state.verificationPath.isBlank()));
        if (!canSetOrg) {
            return Result.invalidStage(bundle);
        }
        var orgOpt = communities.findById(orgId);
        if (orgOpt.isEmpty()) {
            return Result.notFound("org_not_found", snapshot(bundle.user, bundle.state));
        }
        String orgKind = normalizeOrgKind(orgOpt.get().kind);
        if (orgKind == null) {
            return Result.invalidInput("invalid_org_kind", snapshot(bundle.user, bundle.state));
        }

        bundle.state.selectedOrgId = orgId;
        bundle.state.selectedOrgKind = orgKind;
        bundle.state.orgSelectedAt = OffsetDateTime.now();
        bundle.state.verificationPath = null;
        bundle.state.verificationStatus = "none";
        bundle.state.requiresSpecializationSelection = false;
        bundle.state.selectedSpecializationId = null;
        bundle.state.completionReason = null;
        bundle.state.verificationChoiceSetAt = null;
        bundle.state.emailVerifiedAt = null;
        bundle.state.specializationSelectedAt = null;
        bundle.state.skipExplainerAckAt = null;
        bundle.state.photoPendingExplainerAckAt = null;
        bundle.state.finalizedAt = null;
        bundle.state.stageV2 = OnboardingV2Stages.ORG_SELECTED;

        persistProgress(bundle);
        return Result.ok(snapshot(bundle.user, bundle.state));
    }

    public Result setVerificationChoice(String firebaseUid, String verificationPath) {
        String path = normalizeVerificationPath(verificationPath);
        if (path == null) return Result.invalidInput("invalid_verification_path", defaultSnapshot());

        var bundleOpt = provisioned(firebaseUid);
        if (bundleOpt.isEmpty()) return Result.userNotProvisioned();
        var bundle = bundleOpt.get();
        if (isCompleted(bundle.state)) return Result.ok(snapshot(bundle.user, bundle.state));

        String currentStage = OnboardingV2Stages.normalizeStage(bundle.state.stageV2);
        if (currentStage == null) {
            currentStage = bundle.state.stageV2;
        }
        boolean canSetChoice = OnboardingV2Stages.ORG_SELECTED.equals(currentStage);
        boolean canUndoSkipChoice = OnboardingV2Stages.SKIP_EXPLAINER.equals(currentStage)
                && ("email".equals(path) || "photo_id".equals(path));
        if ("skip".equals(path)) {
            canSetChoice = canSetChoice
                    || OnboardingV2Stages.EMAIL_VERIFICATION.equals(currentStage)
                    || OnboardingV2Stages.PHOTO_ID_VERIFICATION.equals(currentStage);
        }
        canSetChoice = canSetChoice || canUndoSkipChoice;
        if (!canSetChoice) {
            return Result.invalidStage(bundle);
        }
        if (bundle.state.selectedOrgId == null || bundle.state.selectedOrgKind == null) {
            return Result.conflict("org_not_selected", snapshot(bundle.user, bundle.state));
        }

        bundle.state.verificationPath = path;
        bundle.state.verificationChoiceSetAt = OffsetDateTime.now();
        bundle.state.verificationStatus = "none";
        bundle.state.selectedSpecializationId = null;
        bundle.state.specializationSelectedAt = null;
        bundle.state.completionReason = null;
        bundle.state.finalizedAt = null;

        switch (path) {
            case "skip" -> {
                bundle.state.requiresSpecializationSelection = false;
                bundle.state.stageV2 = OnboardingV2Stages.SKIP_EXPLAINER;
                bundle.state.skipExplainerAckAt = null;
                bundle.state.photoPendingExplainerAckAt = null;
            }
            case "email" -> {
                bundle.state.requiresSpecializationSelection = true;
                bundle.state.stageV2 = OnboardingV2Stages.EMAIL_VERIFICATION;
                bundle.state.emailVerifiedAt = null;
                bundle.state.skipExplainerAckAt = null;
                bundle.state.photoPendingExplainerAckAt = null;
            }
            case "photo_id" -> {
                bundle.state.requiresSpecializationSelection = false;
                bundle.state.stageV2 = OnboardingV2Stages.PHOTO_ID_VERIFICATION;
                bundle.state.photoPendingExplainerAckAt = null;
                bundle.state.skipExplainerAckAt = null;
            }
            default -> {
                return Result.invalidInput("invalid_verification_path", snapshot(bundle.user, bundle.state));
            }
        }

        persistProgress(bundle);
        return Result.ok(snapshot(bundle.user, bundle.state));
    }

    public Result markEmailVerificationSuccess(String firebaseUid) {
        var bundleOpt = provisioned(firebaseUid);
        if (bundleOpt.isEmpty()) return Result.userNotProvisioned();
        var bundle = bundleOpt.get();
        if (isCompleted(bundle.state)) return Result.ok(snapshot(bundle.user, bundle.state));

        if (!"email".equals(bundle.state.verificationPath)
                || !OnboardingV2Stages.EMAIL_VERIFICATION.equals(bundle.state.stageV2)) {
            return Result.invalidStage(bundle);
        }
        if (bundle.state.selectedOrgId == null) {
            return Result.conflict("org_not_selected", snapshot(bundle.user, bundle.state));
        }
        if (!communityVerifications.isVerified(bundle.user.id, bundle.state.selectedOrgId)) {
            return Result.conflict("selected_org_not_verified", snapshot(bundle.user, bundle.state));
        }

        bundle.state.verificationStatus = "approved";
        bundle.state.emailVerifiedAt = OffsetDateTime.now();
        bundle.state.requiresSpecializationSelection = true;
        bundle.state.stageV2 = OnboardingV2Stages.SPECIALIZATION_SELECTION;

        persistProgress(bundle);
        return Result.ok(snapshot(bundle.user, bundle.state));
    }

    public Result submitSpecializationSelection(String firebaseUid, long specializationId) {
        if (specializationId <= 0) return Result.invalidInput("invalid_specialization_id", defaultSnapshot());

        var bundleOpt = provisioned(firebaseUid);
        if (bundleOpt.isEmpty()) return Result.userNotProvisioned();
        var bundle = bundleOpt.get();
        if (isCompleted(bundle.state)) return Result.ok(snapshot(bundle.user, bundle.state));

        if (!"email".equals(bundle.state.verificationPath)
                || !OnboardingV2Stages.SPECIALIZATION_SELECTION.equals(bundle.state.stageV2)
                || !"approved".equals(bundle.state.verificationStatus)) {
            return Result.invalidStage(bundle);
        }

        var specialization = communities.findById(specializationId);
        if (specialization.isEmpty()) {
            return Result.notFound("specialization_not_found", snapshot(bundle.user, bundle.state));
        }
        String kind = specialization.get().kind == null ? "" : specialization.get().kind.trim().toLowerCase(Locale.ROOT);
        String specType = specialization.get().specializationType == null ? "" : specialization.get().specializationType.trim().toLowerCase(Locale.ROOT);
        if (!"specialization".equals(kind) || (!"major".equals(specType) && !"field".equals(specType))) {
            return Result.invalidInput("invalid_specialization", snapshot(bundle.user, bundle.state));
        }

        var join = specializationMemberships.join(firebaseUid, specializationId);
        if (join.status() == SpecializationMembershipService.Status.USER_NOT_PROVISIONED) {
            return Result.userNotProvisioned();
        }
        if (join.status() == SpecializationMembershipService.Status.NOT_FOUND) {
            return Result.notFound("specialization_not_found", snapshot(bundle.user, bundle.state));
        }
        if (join.status() == SpecializationMembershipService.Status.INVALID_SPECIALIZATION) {
            return Result.invalidInput("invalid_specialization", snapshot(bundle.user, bundle.state));
        }
        if (join.status() == SpecializationMembershipService.Status.VERIFICATION_REQUIRED) {
            return Result.forbidden("specialization_verification_required", snapshot(bundle.user, bundle.state));
        }
        if (join.status() == SpecializationMembershipService.Status.LIMIT_REACHED) {
            return Result.conflict("specialization_join_limit", snapshot(bundle.user, bundle.state));
        }
        if (join.status() == SpecializationMembershipService.Status.COOLDOWN) {
            return Result.conflict("specialization_join_cooldown", snapshot(bundle.user, bundle.state));
        }

        bundle.state.selectedSpecializationId = specializationId;
        bundle.state.specializationSelectedAt = OffsetDateTime.now();
        bundle.state.requiresSpecializationSelection = false;

        persistProgress(bundle);
        return Result.ok(snapshot(bundle.user, bundle.state));
    }

    public Result acknowledgeSkipExplainer(String firebaseUid) {
        var bundleOpt = provisioned(firebaseUid);
        if (bundleOpt.isEmpty()) return Result.userNotProvisioned();
        var bundle = bundleOpt.get();
        if (isCompleted(bundle.state)) return Result.ok(snapshot(bundle.user, bundle.state));

        if (!"skip".equals(bundle.state.verificationPath)
                || !OnboardingV2Stages.SKIP_EXPLAINER.equals(bundle.state.stageV2)) {
            return Result.invalidStage(bundle);
        }

        if (bundle.state.skipExplainerAckAt == null) {
            bundle.state.skipExplainerAckAt = OffsetDateTime.now();
        }
        persistProgress(bundle);
        return Result.ok(snapshot(bundle.user, bundle.state));
    }

    public Result acknowledgePhotoPendingExplainer(String firebaseUid) {
        var bundleOpt = provisioned(firebaseUid);
        if (bundleOpt.isEmpty()) return Result.userNotProvisioned();
        var bundle = bundleOpt.get();
        if (isCompleted(bundle.state)) return Result.ok(snapshot(bundle.user, bundle.state));

        if (!"photo_id".equals(bundle.state.verificationPath)
                || !OnboardingV2Stages.PHOTO_ID_VERIFICATION.equals(bundle.state.stageV2)) {
            return Result.invalidStage(bundle);
        }
        if (bundle.state.selectedOrgId == null) {
            return Result.conflict("org_not_selected", snapshot(bundle.user, bundle.state));
        }
        boolean hasPending = verificationRequests.existsPendingForUserAndMethodAndCommunityId(
                bundle.user.id,
                "photo_id",
                bundle.state.selectedOrgId
        );
        if (!hasPending) {
            return Result.conflict("photo_verification_not_pending", snapshot(bundle.user, bundle.state));
        }

        bundle.state.photoPendingExplainerAckAt = OffsetDateTime.now();
        bundle.state.verificationStatus = "pending";
        bundle.state.stageV2 = OnboardingV2Stages.PHOTO_PENDING_EXPLAINER;

        persistProgress(bundle);
        return Result.ok(snapshot(bundle.user, bundle.state));
    }

    public Result finalizeOnboarding(String firebaseUid) {
        var bundleOpt = provisioned(firebaseUid);
        if (bundleOpt.isEmpty()) return Result.userNotProvisioned();
        var bundle = bundleOpt.get();
        if (isCompleted(bundle.state)) return Result.ok(snapshot(bundle.user, bundle.state));

        String path = bundle.state.verificationPath;
        if (path == null) {
            return Result.invalidStage(bundle);
        }

        switch (path) {
            case "skip" -> {
                if (!OnboardingV2Stages.SKIP_EXPLAINER.equals(bundle.state.stageV2)
                        || bundle.state.skipExplainerAckAt == null) {
                    return Result.invalidStage(bundle);
                }
                bundle.state.completionReason = "skipped_verification";
            }
            case "email" -> {
                if (!OnboardingV2Stages.SPECIALIZATION_SELECTION.equals(bundle.state.stageV2)
                        || bundle.state.selectedSpecializationId == null) {
                    return Result.conflict("specialization_required", snapshot(bundle.user, bundle.state));
                }
                if (!"approved".equals(bundle.state.verificationStatus)) {
                    return Result.conflict("selected_org_not_verified", snapshot(bundle.user, bundle.state));
                }
                if (!specializationJoins.exists(bundle.user.id, bundle.state.selectedSpecializationId)) {
                    return Result.conflict("specialization_not_joined", snapshot(bundle.user, bundle.state));
                }
                bundle.state.completionReason = "email_verified_and_joined";
            }
            case "photo_id" -> {
                if (!OnboardingV2Stages.PHOTO_PENDING_EXPLAINER.equals(bundle.state.stageV2)
                        || bundle.state.photoPendingExplainerAckAt == null) {
                    return Result.invalidStage(bundle);
                }
                bundle.state.completionReason = "photo_pending";
            }
            default -> {
                return Result.invalidInput("invalid_verification_path", snapshot(bundle.user, bundle.state));
            }
        }

        bundle.state.stageV2 = OnboardingV2Stages.COMPLETED;
        bundle.state.finalizedAt = OffsetDateTime.now();
        bundle.state.requiresSpecializationSelection = false;
        onboardingV2.update(bundle.state);
        users.markOnboardingComplete(bundle.user.id);

        var refreshedUser = users.findById(bundle.user.id).orElse(bundle.user);
        var refreshedState = ensureState(refreshedUser);
        return Result.ok(snapshot(refreshedUser, refreshedState));
    }

    public String stageForUser(long userId, String legacyStep, OffsetDateTime onboardingCompletedAt) {
        return onboardingV2.findByUserId(userId)
                .map(row -> row.stageV2)
                .orElse(OnboardingV2Stages.stageForLegacy(legacyStep, onboardingCompletedAt));
    }

    private Optional<StateBundle> provisioned(String firebaseUid) {
        var userOpt = users.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty() || userOpt.get().companyId == null) return Optional.empty();
        var state = ensureState(userOpt.get());
        return Optional.of(new StateBundle(userOpt.get(), state));
    }

    private OnboardingV2Repository.Row ensureState(UserRepository.UserRow user) {
        var row = onboardingV2.findByUserId(user.id)
                .orElseGet(() -> onboardingV2.ensureForUser(user.id, user.onboardingStep, user.onboardingCompletedAt));
        if (user.onboardingCompletedAt != null && !OnboardingV2Stages.COMPLETED.equals(row.stageV2)) {
            row.stageV2 = OnboardingV2Stages.COMPLETED;
            if (row.completionReason == null || row.completionReason.isBlank()) {
                row.completionReason = "legacy_backfill";
            }
            if (row.finalizedAt == null) {
                row.finalizedAt = user.onboardingCompletedAt;
            }
            onboardingV2.update(row);
        }
        return row;
    }

    private void persistProgress(StateBundle bundle) {
        onboardingV2.update(bundle.state);
        users.updateOnboardingStep(bundle.user.id, OnboardingV2Stages.legacyStepForStage(bundle.state.stageV2));
    }

    private boolean isCompleted(OnboardingV2Repository.Row row) {
        return OnboardingV2Stages.COMPLETED.equals(row.stageV2);
    }

    private Snapshot snapshot(UserRepository.UserRow user, OnboardingV2Repository.Row row) {
        boolean complete = user.onboardingCompletedAt != null || OnboardingV2Stages.COMPLETED.equals(row.stageV2);
        String stage = OnboardingV2Stages.normalizeStage(row.stageV2);
        if (stage == null) {
            stage = OnboardingV2Stages.stageForLegacy(user.onboardingStep, user.onboardingCompletedAt);
        }
        String legacyStep = complete
                ? OnboardingV2Stages.LEGACY_VERIFICATION_NOTIFICATIONS
                : OnboardingV2Stages.legacyStepForStage(stage);

        Map<String, Object> milestones = new HashMap<>();
        milestones.put("info_screen_viewed_at", row.infoScreenViewedAt);
        milestones.put("org_selected_at", row.orgSelectedAt);
        milestones.put("verification_choice_set_at", row.verificationChoiceSetAt);
        milestones.put("email_verified_at", row.emailVerifiedAt);
        milestones.put("specialization_selected_at", row.specializationSelectedAt);
        milestones.put("skip_explainer_ack_at", row.skipExplainerAckAt);
        milestones.put("photo_pending_explainer_ack_at", row.photoPendingExplainerAckAt);
        milestones.put("finalized_at", row.finalizedAt);

        Map<String, Object> context = new HashMap<>();
        context.put("selected_org_id", row.selectedOrgId);
        context.put("selected_org_kind", row.selectedOrgKind);
        context.put("verification_path", row.verificationPath);
        context.put("verification_status", normalizeVerificationStatus(row.verificationStatus));
        context.put("requires_specialization_selection", row.requiresSpecializationSelection);
        context.put("selected_specialization_id", row.selectedSpecializationId);
        context.put("completion_reason", row.completionReason);
        context.put("milestones", milestones);

        return new Snapshot(complete, legacyStep, stage, context);
    }

    private Snapshot defaultSnapshot() {
        Map<String, Object> milestones = new HashMap<>();
        milestones.put("info_screen_viewed_at", null);
        milestones.put("org_selected_at", null);
        milestones.put("verification_choice_set_at", null);
        milestones.put("email_verified_at", null);
        milestones.put("specialization_selected_at", null);
        milestones.put("skip_explainer_ack_at", null);
        milestones.put("photo_pending_explainer_ack_at", null);
        milestones.put("finalized_at", null);

        Map<String, Object> context = new HashMap<>();
        context.put("selected_org_id", null);
        context.put("selected_org_kind", null);
        context.put("verification_path", null);
        context.put("verification_status", "none");
        context.put("requires_specialization_selection", false);
        context.put("selected_specialization_id", null);
        context.put("completion_reason", null);
        context.put("milestones", milestones);

        return new Snapshot(false, OnboardingV2Stages.LEGACY_PROFILE_SETUP, OnboardingV2Stages.PROFILE_SETUP, context);
    }

    private String normalizeOrgKind(String kind) {
        if (kind == null || kind.isBlank()) return null;
        String normalized = kind.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("company") && !normalized.equals("school")) return null;
        return normalized;
    }

    private String normalizeVerificationPath(String verificationPath) {
        if (verificationPath == null || verificationPath.isBlank()) return null;
        String normalized = verificationPath.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("skip") && !normalized.equals("email") && !normalized.equals("photo_id")) {
            return null;
        }
        return normalized;
    }

    private String normalizeVerificationStatus(String verificationStatus) {
        if (verificationStatus == null || verificationStatus.isBlank()) return "none";
        String normalized = verificationStatus.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("none") && !normalized.equals("pending")
                && !normalized.equals("approved") && !normalized.equals("rejected")) {
            return "none";
        }
        return normalized;
    }

    private record StateBundle(UserRepository.UserRow user, OnboardingV2Repository.Row state) {}

    public enum Status {
        OK,
        USER_NOT_PROVISIONED,
        INVALID_STAGE,
        INVALID_INPUT,
        NOT_FOUND,
        FORBIDDEN,
        CONFLICT
    }

    public record Snapshot(boolean onboardingComplete,
                           String onboardingStep,
                           String onboardingStageV2,
                           Map<String, Object> onboardingContext) {}

    public record Result(Status status,
                         String error,
                         Snapshot snapshot,
                         String currentStageV2,
                         List<String> allowedNextStagesV2,
                         String currentStep,
                         List<String> allowedNextSteps) {
        static Result ok(Snapshot snapshot) {
            List<String> allowed = OnboardingV2Stages.allowedNextStages(snapshot.onboardingStageV2());
            return new Result(
                    Status.OK,
                    null,
                    snapshot,
                    snapshot.onboardingStageV2(),
                    allowed,
                    snapshot.onboardingStep(),
                    OnboardingV2Stages.toLegacySteps(allowed)
            );
        }

        static Result userNotProvisioned() {
            Snapshot snapshot = new Snapshot(
                    false,
                    OnboardingV2Stages.LEGACY_PROFILE_SETUP,
                    OnboardingV2Stages.PROFILE_SETUP,
                    Map.of(
                            "selected_org_id", null,
                            "selected_org_kind", null,
                            "verification_path", null,
                            "verification_status", "none",
                            "requires_specialization_selection", false,
                            "selected_specialization_id", null,
                            "completion_reason", null,
                            "milestones", Map.of()
                    )
            );
            List<String> allowed = OnboardingV2Stages.allowedNextStages(OnboardingV2Stages.PROFILE_SETUP);
            return new Result(
                    Status.USER_NOT_PROVISIONED,
                    "user_not_provisioned",
                    snapshot,
                    OnboardingV2Stages.PROFILE_SETUP,
                    allowed,
                    OnboardingV2Stages.LEGACY_PROFILE_SETUP,
                    OnboardingV2Stages.toLegacySteps(allowed)
            );
        }

        static Result invalidStage(StateBundle bundle) {
            Snapshot snapshot = new Snapshot(
                    bundle.user.onboardingCompletedAt != null || OnboardingV2Stages.COMPLETED.equals(bundle.state.stageV2),
                    OnboardingV2Stages.legacyStepForStage(bundle.state.stageV2),
                    bundle.state.stageV2,
                    Map.of()
            );
            List<String> allowed = OnboardingV2Stages.allowedNextStages(bundle.state.stageV2);
            return new Result(
                    Status.INVALID_STAGE,
                    "invalid_onboarding_stage",
                    snapshot,
                    bundle.state.stageV2,
                    allowed,
                    OnboardingV2Stages.legacyStepForStage(bundle.state.stageV2),
                    OnboardingV2Stages.toLegacySteps(allowed)
            );
        }

        static Result invalidInput(String error, Snapshot snapshot) {
            String stage = snapshot == null ? OnboardingV2Stages.PROFILE_SETUP : snapshot.onboardingStageV2();
            List<String> allowed = OnboardingV2Stages.allowedNextStages(stage);
            return new Result(
                    Status.INVALID_INPUT,
                    normalizeError(error),
                    snapshot,
                    stage,
                    allowed,
                    snapshot == null ? OnboardingV2Stages.LEGACY_PROFILE_SETUP : snapshot.onboardingStep(),
                    OnboardingV2Stages.toLegacySteps(allowed)
            );
        }

        static Result notFound(String error, Snapshot snapshot) {
            String stage = snapshot == null ? OnboardingV2Stages.PROFILE_SETUP : snapshot.onboardingStageV2();
            List<String> allowed = OnboardingV2Stages.allowedNextStages(stage);
            return new Result(
                    Status.NOT_FOUND,
                    normalizeError(error),
                    snapshot,
                    stage,
                    allowed,
                    snapshot == null ? OnboardingV2Stages.LEGACY_PROFILE_SETUP : snapshot.onboardingStep(),
                    OnboardingV2Stages.toLegacySteps(allowed)
            );
        }

        static Result forbidden(String error, Snapshot snapshot) {
            String stage = snapshot == null ? OnboardingV2Stages.PROFILE_SETUP : snapshot.onboardingStageV2();
            List<String> allowed = OnboardingV2Stages.allowedNextStages(stage);
            return new Result(
                    Status.FORBIDDEN,
                    normalizeError(error),
                    snapshot,
                    stage,
                    allowed,
                    snapshot == null ? OnboardingV2Stages.LEGACY_PROFILE_SETUP : snapshot.onboardingStep(),
                    OnboardingV2Stages.toLegacySteps(allowed)
            );
        }

        static Result conflict(String error, Snapshot snapshot) {
            String stage = snapshot == null ? OnboardingV2Stages.PROFILE_SETUP : snapshot.onboardingStageV2();
            List<String> allowed = OnboardingV2Stages.allowedNextStages(stage);
            return new Result(
                    Status.CONFLICT,
                    normalizeError(error),
                    snapshot,
                    stage,
                    allowed,
                    snapshot == null ? OnboardingV2Stages.LEGACY_PROFILE_SETUP : snapshot.onboardingStep(),
                    OnboardingV2Stages.toLegacySteps(allowed)
            );
        }

        private static String normalizeError(String error) {
            if (error == null || error.isBlank()) return "invalid_request";
            return error.trim().toLowerCase(Locale.ROOT);
        }
    }
}
