package com.looped.posts;

import com.looped.polls.PollsService;
import com.looped.settings.AppConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserRepostsControllerTest {

    @Test
    void reposts_returns_401_when_jwt_missing() {
        PostCollectionsService service = mock(PostCollectionsService.class);
        PollsService polls = mock(PollsService.class);
        AppConfigService appConfig = mock(AppConfigService.class);
        PostViewerCapabilitiesService viewerCapabilities = mock(PostViewerCapabilitiesService.class);
        PostViewCountsService postViewCounts = mock(PostViewCountsService.class);
        var controller = new UserRepostsController(service, polls, appConfig, viewerCapabilities, postViewCounts);

        var res = controller.reposts(null, 123L, null, 20);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        Map<?, ?> body = assertInstanceOf(Map.class, res.getBody());
        assertEquals("unauthorized", body.get("error"));
    }

    @Test
    void my_reposts_returns_401_when_jwt_missing() {
        PostCollectionsService service = mock(PostCollectionsService.class);
        PollsService polls = mock(PollsService.class);
        AppConfigService appConfig = mock(AppConfigService.class);
        PostViewerCapabilitiesService viewerCapabilities = mock(PostViewerCapabilitiesService.class);
        PostViewCountsService postViewCounts = mock(PostViewCountsService.class);
        var controller = new UserRepostsController(service, polls, appConfig, viewerCapabilities, postViewCounts);

        var res = controller.myReposts(null, null, 20);

        assertEquals(HttpStatus.UNAUTHORIZED, res.getStatusCode());
        Map<?, ?> body = assertInstanceOf(Map.class, res.getBody());
        assertEquals("unauthorized", body.get("error"));
    }

    @Test
    void reposts_returns_503_when_db_unavailable() {
        PostCollectionsService service = mock(PostCollectionsService.class);
        PollsService polls = mock(PollsService.class);
        AppConfigService appConfig = mock(AppConfigService.class);
        PostViewerCapabilitiesService viewerCapabilities = mock(PostViewerCapabilitiesService.class);
        PostViewCountsService postViewCounts = mock(PostViewCountsService.class);
        when(appConfig.defaultProfileImageUrl()).thenReturn(null);
        var controller = new UserRepostsController(service, polls, appConfig, viewerCapabilities, postViewCounts);

        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .claim("sub", "uid-1")
                .build();

        when(service.repostedForUser("uid-1", 123L, null, 20))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        var res = controller.reposts(jwt, 123L, null, 20);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        Map<?, ?> body = assertInstanceOf(Map.class, res.getBody());
        assertEquals("reposts_unavailable", body.get("error"));
    }
}
