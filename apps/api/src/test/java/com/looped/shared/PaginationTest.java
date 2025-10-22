package com.looped.shared;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PaginationTest {
    @Test
    void encode_decode_roundtrip() {
        OffsetDateTime ts = OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
        long id = 12345L;
        String c = Pagination.encode(ts, id);
        var d = Pagination.decode(c);
        assertThat(d.epochMillis()).isEqualTo(ts.toInstant().toEpochMilli());
        assertThat(d.id()).isEqualTo(id);
    }
}

