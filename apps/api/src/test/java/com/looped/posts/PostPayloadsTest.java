package com.looped.posts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostPayloadsTest {
    @Test
    void includes_media_asset_ids_from_row_list() {
        PostRepository.PostRow row = new PostRepository.PostRow();
        row.id = 1;
        row.mediaAssetId = 10L;
        row.mediaAssetIds = List.of(10L, 11L, 12L);

        var payload = PostPayloads.from(row);
        assertThat(payload.get("media_asset_ids")).isEqualTo(List.of(10L, 11L, 12L));
        assertThat(payload.get("mediaAssetIds")).isEqualTo(List.of(10L, 11L, 12L));
        assertThat(payload.get("media_asset_id")).isEqualTo(10L);
    }

    @Test
    void falls_back_to_single_media_asset_id_when_list_missing() {
        PostRepository.PostRow row = new PostRepository.PostRow();
        row.id = 1;
        row.mediaAssetId = 10L;
        row.mediaAssetIds = null;

        var payload = PostPayloads.from(row);
        assertThat(payload.get("media_asset_ids")).isEqualTo(List.of(10L));
        assertThat(payload.get("mediaAssetIds")).isEqualTo(List.of(10L));
    }
}

