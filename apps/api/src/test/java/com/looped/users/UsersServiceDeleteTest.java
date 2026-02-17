package com.looped.users;

import com.looped.auth.FirebaseAdminService;
import com.looped.comments.CommentsRepository;
import com.looped.companies.CompanyRepository;
import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.communities.SpecializationJoinsRepository;
import com.looped.media.MediaRepository;
import com.looped.polls.PollsService;
import com.looped.posts.PostRepository;
import com.looped.posts.PostStateService;
import com.looped.posts.PostViewerCapabilitiesService;
import com.looped.principals.PrincipalRepository;
import com.looped.settings.AppConfigService;
import com.looped.verification.VerificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UsersServiceDeleteTest {

    @Test
    void hard_delete_falls_back_to_self_deleted_when_local_delete_fails() {
        UserRepository users = mock(UserRepository.class);
        FirebaseAdminService firebaseAdmin = mock(FirebaseAdminService.class);
        UsersService service = newService(users, firebaseAdmin);

        var row = userRow(7L, "uid-zombie");
        when(users.findByFirebaseUidIncludingDeleted("uid-zombie")).thenReturn(Optional.of(row));
        when(firebaseAdmin.deleteUser("uid-zombie")).thenReturn(
                new FirebaseAdminService.DeleteResult(FirebaseAdminService.DeleteStatus.OK, null)
        );
        when(users.deleteById(7L)).thenThrow(new DataAccessResourceFailureException("fk failure"));

        var res = service.deleteMe("uid-zombie", UsersService.DeleteMode.HARD);

        assertThat(res.status()).isEqualTo(UsersService.DeleteStatus.OK);
        assertThat(res.error()).isEqualTo("local_delete_pending");
        verify(users).repairMissingAuthorIdsForUser(7L);
        verify(users).markDeletedSelf(7L, 7L, "hard_delete_failed");
        verify(users, never()).insertTombstone(any());
    }

    @Test
    void on_login_keeps_self_deleted_accounts_in_terminal_state() {
        UserRepository users = mock(UserRepository.class);
        FirebaseAdminService firebaseAdmin = mock(FirebaseAdminService.class);
        UsersService service = newService(users, firebaseAdmin);

        var row = userRow(9L, "uid-self");
        row.deletedAt = OffsetDateTime.now().minusDays(1);
        row.deletedSource = "self";
        when(users.findByFirebaseUidIncludingDeleted("uid-self")).thenReturn(Optional.of(row));

        var status = service.onLogin("uid-self", "self@example.com", true);

        assertThat(status).isEqualTo(UsersService.LoginStatus.PURGED);
        verify(users, never()).reactivate(anyLong());
    }

    private UsersService newService(UserRepository users, FirebaseAdminService firebaseAdmin) {
        VerificationRepository verifications = mock(VerificationRepository.class);
        PostRepository posts = mock(PostRepository.class);
        PrincipalRepository principals = mock(PrincipalRepository.class);
        BlocksRepository blocks = mock(BlocksRepository.class);
        PostStateService postState = mock(PostStateService.class);
        PostViewerCapabilitiesService viewerCapabilities = mock(PostViewerCapabilitiesService.class);
        PollsService pollsService = mock(PollsService.class);
        CommentsRepository comments = mock(CommentsRepository.class);
        UserContentRepository content = mock(UserContentRepository.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        CommunitiesRepository communities = mock(CommunitiesRepository.class);
        CommunityVerificationsRepository communityVerifications = mock(CommunityVerificationsRepository.class);
        SpecializationJoinsRepository specializationJoins = mock(SpecializationJoinsRepository.class);
        MediaRepository media = mock(MediaRepository.class);
        AppConfigService appConfig = mock(AppConfigService.class);

        return new UsersService(
                users,
                verifications,
                posts,
                principals,
                blocks,
                postState,
                viewerCapabilities,
                pollsService,
                comments,
                content,
                companies,
                communities,
                communityVerifications,
                specializationJoins,
                media,
                firebaseAdmin,
                appConfig,
                90,
                14,
                "looped.global",
                ""
        );
    }

    private UserRepository.UserRow userRow(long id, String firebaseUid) {
        var row = new UserRepository.UserRow();
        row.id = id;
        row.firebaseUid = firebaseUid;
        row.handle = "user-" + id;
        return row;
    }
}
