package com.looped.posts;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepostsRepositoryTest {

    @Test
    void repostedPosts_binds_parameters_when_no_cursor() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var repo = new RepostsRepository(jdbc);

        repo.repostedPosts(10L, null, null, 20, 99L, false);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(anyString(), any(RowMapper.class), captor.capture());
        assertEquals(2, captor.getValue().length);
        assertEquals(10L, captor.getValue()[0]);
        assertEquals(20, captor.getValue()[1]);
    }

    @Test
    void repostedPosts_binds_parameters_when_cursor_present_and_hide_anon() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var repo = new RepostsRepository(jdbc);

        OffsetDateTime cursor = OffsetDateTime.parse("2026-01-11T00:00:00Z");
        repo.repostedPosts(10L, cursor, 123L, 20, 99L, true);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(anyString(), any(RowMapper.class), captor.capture());
        assertEquals(6, captor.getValue().length);
        assertEquals(10L, captor.getValue()[0]);
        assertEquals(99L, captor.getValue()[1]);
        assertEquals(cursor, captor.getValue()[2]);
        assertEquals(cursor, captor.getValue()[3]);
        assertEquals(123L, captor.getValue()[4]);
        assertEquals(20, captor.getValue()[5]);
    }
}

