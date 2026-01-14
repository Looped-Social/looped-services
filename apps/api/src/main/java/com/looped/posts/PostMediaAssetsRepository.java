package com.looped.posts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

@Repository
public class PostMediaAssetsRepository {
    private final JdbcTemplate jdbc;

    public PostMediaAssetsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long postId, List<Long> mediaAssetIds) {
        if (mediaAssetIds == null || mediaAssetIds.isEmpty()) return;
        jdbc.batchUpdate(
                "INSERT INTO post_media_assets(post_id, media_asset_id, sort_order) VALUES (?,?,?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ps.setLong(1, postId);
                        ps.setLong(2, mediaAssetIds.get(i));
                        ps.setInt(3, i);
                    }

                    @Override
                    public int getBatchSize() {
                        return mediaAssetIds.size();
                    }
                }
        );
    }
}
