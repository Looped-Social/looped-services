package com.looped.users;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityRequestsRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.communities.SpecializationMembershipService;
import com.looped.verification.VerificationRequestsRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingV2ServiceTest {

    @Test
    void complete_after_community_request_requires_pending_org_request() {
        UserRepository users = Mockito.mock(UserRepository.class);
        OnboardingV2Repository onboardingV2 = Mockito.mock(OnboardingV2Repository.class);
        CommunityRequestsRepository communityRequests = Mockito.mock(CommunityRequestsRepository.class);
        var service = newService(users, onboardingV2, communityRequests);

        long userId = 41L;
        UserRepository.UserRow user = userRow(userId, "uid-missing-request", 2L, null, "profile_setup");
        OnboardingV2Repository.Row state = stateRow(userId, "profile_setup", null, null);

        Mockito.when(users.findByFirebaseUid("uid-missing-request")).thenReturn(Optional.of(user));
        Mockito.when(onboardingV2.findByUserId(userId)).thenReturn(Optional.of(state));
        Mockito.when(communityRequests.existsPendingOrgRequestByUserId(userId)).thenReturn(false);

        var result = service.completeAfterCommunityRequest("uid-missing-request");

        assertThat(result.status()).isEqualTo(OnboardingV2Service.Status.CONFLICT);
        assertThat(result.error()).isEqualTo("community_request_required");
        assertThat(result.currentStageV2()).isEqualTo("profile_setup");
        Mockito.verify(onboardingV2, Mockito.never()).update(Mockito.any());
        Mockito.verify(users, Mockito.never()).markOnboardingComplete(Mockito.anyLong());
    }

    @Test
    void complete_after_community_request_marks_completed_and_is_snapshotted() {
        UserRepository users = Mockito.mock(UserRepository.class);
        OnboardingV2Repository onboardingV2 = Mockito.mock(OnboardingV2Repository.class);
        CommunityRequestsRepository communityRequests = Mockito.mock(CommunityRequestsRepository.class);
        var service = newService(users, onboardingV2, communityRequests);

        long userId = 42L;
        OffsetDateTime completedAt = OffsetDateTime.now();
        UserRepository.UserRow before = userRow(userId, "uid-has-request", 3L, null, "profile_setup");
        UserRepository.UserRow after = userRow(userId, "uid-has-request", 3L, completedAt, "verification_notifications");
        OnboardingV2Repository.Row initialState = stateRow(userId, "profile_setup", null, null);
        OnboardingV2Repository.Row completedState = stateRow(userId, "completed", "skip", "skipped_verification");
        completedState.finalizedAt = completedAt;
        completedState.skipExplainerAckAt = completedAt;

        Mockito.when(users.findByFirebaseUid("uid-has-request")).thenReturn(Optional.of(before));
        Mockito.when(communityRequests.existsPendingOrgRequestByUserId(userId)).thenReturn(true);
        Mockito.when(onboardingV2.findByUserId(userId))
                .thenReturn(Optional.of(initialState))
                .thenReturn(Optional.of(completedState));
        Mockito.when(users.findById(userId)).thenReturn(Optional.of(after));

        var result = service.completeAfterCommunityRequest("uid-has-request");

        assertThat(result.status()).isEqualTo(OnboardingV2Service.Status.OK);
        assertThat(result.snapshot()).isNotNull();
        assertThat(result.snapshot().onboardingComplete()).isTrue();
        assertThat(result.snapshot().onboardingStageV2()).isEqualTo("completed");
        assertThat(result.snapshot().onboardingStep()).isEqualTo("verification_notifications");
        assertThat(result.snapshot().onboardingContext().get("completion_reason")).isEqualTo("skipped_verification");
        Mockito.verify(onboardingV2).update(Mockito.any(OnboardingV2Repository.Row.class));
        Mockito.verify(users).markOnboardingComplete(userId);
    }

    private OnboardingV2Service newService(UserRepository users,
                                           OnboardingV2Repository onboardingV2,
                                           CommunityRequestsRepository communityRequests) {
        CommunitiesRepository communities = Mockito.mock(CommunitiesRepository.class);
        CommunityVerificationsRepository communityVerifications = Mockito.mock(CommunityVerificationsRepository.class);
        SpecializationMembershipService specializationMemberships = Mockito.mock(SpecializationMembershipService.class);
        SpecializationJoinsRepository specializationJoins = Mockito.mock(SpecializationJoinsRepository.class);
        VerificationRequestsRepository verificationRequests = Mockito.mock(VerificationRequestsRepository.class);
        return new OnboardingV2Service(
                users,
                onboardingV2,
                communities,
                communityRequests,
                communityVerifications,
                specializationMemberships,
                specializationJoins,
                verificationRequests
        );
    }

    private UserRepository.UserRow userRow(long id,
                                           String firebaseUid,
                                           Long companyId,
                                           OffsetDateTime onboardingCompletedAt,
                                           String onboardingStep) {
        UserRepository.UserRow row = new UserRepository.UserRow();
        row.id = id;
        row.firebaseUid = firebaseUid;
        row.companyId = companyId;
        row.onboardingCompletedAt = onboardingCompletedAt;
        row.onboardingStep = onboardingStep;
        return row;
    }

    private OnboardingV2Repository.Row stateRow(long userId,
                                                String stageV2,
                                                String verificationPath,
                                                String completionReason) {
        OnboardingV2Repository.Row row = new OnboardingV2Repository.Row();
        row.userId = userId;
        row.stageV2 = stageV2;
        row.verificationPath = verificationPath;
        row.verificationStatus = "none";
        row.completionReason = completionReason;
        row.requiresSpecializationSelection = false;
        return row;
    }
}
