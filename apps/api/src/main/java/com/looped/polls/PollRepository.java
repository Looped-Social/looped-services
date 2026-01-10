package com.looped.polls;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class PollRepository {
    private final JdbcTemplate jdbc;

    public PollRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertPoll(long postId, String question, int maxSelections, OffsetDateTime closesAt) {
        Long id = jdbc.query(
                "INSERT INTO polls(post_id, question, max_selections, closes_at) VALUES (?,?,?,?) RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                postId, question, maxSelections, closesAt
        );
        return id == null ? 0L : id;
    }

    public void insertOptions(long pollId, List<String> options) {
        if (options == null || options.isEmpty()) return;
        List<OptionInsert> inserts = new ArrayList<>();
        int sort = 0;
        for (String opt : options) {
            sort++;
            inserts.add(new OptionInsert(pollId, opt, sort));
        }
        jdbc.batchUpdate(
                "INSERT INTO poll_options(poll_id, text, sort_order) VALUES (?,?,?)",
                inserts,
                100,
                (ps, item) -> {
                    ps.setLong(1, item.pollId());
                    ps.setString(2, item.text());
                    ps.setInt(3, item.sortOrder());
                }
        );
    }

    public Optional<PollRow> findByPostId(long postId) {
        var rows = jdbc.query(
                "SELECT id, post_id, question, max_selections, closes_at, created_at, updated_at FROM polls WHERE post_id = ?",
                POLL_MAPPER,
                postId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<PollRow> findById(long pollId) {
        var rows = jdbc.query(
                "SELECT id, post_id, question, max_selections, closes_at, created_at, updated_at FROM polls WHERE id = ?",
                POLL_MAPPER,
                pollId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Map<Long, PollRow> findByPostIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return Map.of();
        List<Long> distinct = postIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Map.of();
        String in = placeholders(distinct.size());
        List<PollRow> rows = jdbc.query(
                "SELECT id, post_id, question, max_selections, closes_at, created_at, updated_at FROM polls WHERE post_id IN (" + in + ")",
                POLL_MAPPER,
                distinct.toArray()
        );
        Map<Long, PollRow> byPostId = new HashMap<>();
        for (PollRow row : rows) {
            byPostId.put(row.postId, row);
        }
        return byPostId;
    }

    public Optional<PollWithPostRow> findPollWithPost(long pollId) {
        var rows = jdbc.query(
                "SELECT po.id, po.post_id, po.question, po.max_selections, po.closes_at, po.created_at, po.updated_at, " +
                        "p.company_id, p.community_id, p.removed_at " +
                        "FROM polls po " +
                        "JOIN posts p ON p.id = po.post_id " +
                        "WHERE po.id = ?",
                POLL_WITH_POST_MAPPER,
                pollId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<PollOptionRow> listOptionsByPollIds(List<Long> pollIds) {
        if (pollIds == null || pollIds.isEmpty()) return List.of();
        List<Long> distinct = pollIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return List.of();
        String in = placeholders(distinct.size());
        return jdbc.query(
                "SELECT id, poll_id, text, sort_order, created_at FROM poll_options " +
                        "WHERE poll_id IN (" + in + ") ORDER BY poll_id ASC, sort_order ASC, id ASC",
                OPTION_MAPPER,
                distinct.toArray()
        );
    }

    public Map<Long, Integer> countTotalVotesByPollIds(List<Long> pollIds) {
        if (pollIds == null || pollIds.isEmpty()) return Map.of();
        List<Long> distinct = pollIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Map.of();
        String in = placeholders(distinct.size());
        var rows = jdbc.query(
                "SELECT poll_id, COUNT(*) AS total_votes FROM poll_votes WHERE poll_id IN (" + in + ") GROUP BY poll_id",
                (rs, ignored) -> new AbstractMap.SimpleEntry<>(rs.getLong("poll_id"), rs.getInt("total_votes")),
                distinct.toArray()
        );
        Map<Long, Integer> out = new HashMap<>();
        for (var e : rows) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    public Map<Long, Integer> countVotesByOptionIds(List<Long> pollIds) {
        if (pollIds == null || pollIds.isEmpty()) return Map.of();
        List<Long> distinct = pollIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Map.of();
        String in = placeholders(distinct.size());
        var rows = jdbc.query(
                "SELECT pvo.option_id, COUNT(*) AS vote_count " +
                        "FROM poll_vote_options pvo " +
                        "JOIN poll_votes pv ON pv.id = pvo.vote_id " +
                        "WHERE pv.poll_id IN (" + in + ") " +
                        "GROUP BY pvo.option_id",
                (rs, ignored) -> new AbstractMap.SimpleEntry<>(rs.getLong("option_id"), rs.getInt("vote_count")),
                distinct.toArray()
        );
        Map<Long, Integer> out = new HashMap<>();
        for (var e : rows) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    public Map<Long, Set<Long>> viewerSelectionsByPollId(long viewerPrincipalId, List<Long> pollIds) {
        if (viewerPrincipalId <= 0) return Map.of();
        if (pollIds == null || pollIds.isEmpty()) return Map.of();
        List<Long> distinct = pollIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Map.of();
        String in = placeholders(distinct.size());
        var rows = jdbc.query(
                "SELECT pv.poll_id, pvo.option_id " +
                        "FROM poll_votes pv " +
                        "JOIN poll_vote_options pvo ON pvo.vote_id = pv.id " +
                        "WHERE pv.principal_id = ? AND pv.poll_id IN (" + in + ")",
                (rs, ignored) -> new ViewerSelection(rs.getLong("poll_id"), rs.getLong("option_id")),
                prepend(viewerPrincipalId, distinct).toArray()
        );
        Map<Long, Set<Long>> out = new HashMap<>();
        for (var r : rows) {
            out.computeIfAbsent(r.pollId(), ignored -> new LinkedHashSet<>()).add(r.optionId());
        }
        return out;
    }

    public Optional<VoteRow> findVote(long pollId, long principalId) {
        var rows = jdbc.query(
                "SELECT id, poll_id, principal_id, created_at, updated_at FROM poll_votes WHERE poll_id = ? AND principal_id = ?",
                VOTE_MAPPER,
                pollId, principalId
        );
        if (rows.isEmpty()) return Optional.empty();
        VoteRow vote = rows.get(0);
        List<Long> optionIds = jdbc.query(
                "SELECT pvo.option_id FROM poll_vote_options pvo WHERE pvo.vote_id = ? ORDER BY pvo.option_id ASC",
                (rs, ignored) -> rs.getLong(1),
                vote.id
        );
        return Optional.of(vote.withOptionIds(optionIds));
    }

    public long upsertVote(long pollId, long principalId) {
        Long id = jdbc.query(
                "INSERT INTO poll_votes(poll_id, principal_id) VALUES (?,?) " +
                        "ON CONFLICT (poll_id, principal_id) DO UPDATE SET updated_at = now() " +
                        "RETURNING id",
                rs -> rs.next() ? rs.getLong(1) : null,
                pollId, principalId
        );
        return id == null ? 0L : id;
    }

    public void deleteVoteOptions(long voteId) {
        jdbc.update("DELETE FROM poll_vote_options WHERE vote_id = ?", voteId);
    }

    public void insertVoteOptions(long voteId, List<Long> optionIds) {
        if (optionIds == null || optionIds.isEmpty()) return;
        List<VoteOptionInsert> inserts = optionIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(optId -> new VoteOptionInsert(voteId, optId))
                .toList();
        if (inserts.isEmpty()) return;
        jdbc.batchUpdate(
                "INSERT INTO poll_vote_options(vote_id, option_id) VALUES (?,?)",
                inserts,
                100,
                (ps, item) -> {
                    ps.setLong(1, item.voteId());
                    ps.setLong(2, item.optionId());
                }
        );
    }

    public void touchPoll(long pollId) {
        jdbc.update("UPDATE polls SET updated_at = now() WHERE id = ?", pollId);
    }

    public Set<Long> findExistingOptionIds(long pollId, List<Long> optionIds) {
        if (optionIds == null || optionIds.isEmpty()) return Set.of();
        List<Long> distinct = optionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Set.of();
        String in = placeholders(distinct.size());
        List<Long> found = jdbc.query(
                "SELECT id FROM poll_options WHERE poll_id = ? AND id IN (" + in + ")",
                (rs, ignored) -> rs.getLong(1),
                prepend(pollId, distinct).toArray()
        );
        return new HashSet<>(found);
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private List<Object> prepend(Object first, List<Long> rest) {
        List<Object> out = new ArrayList<>(rest.size() + 1);
        out.add(first);
        out.addAll(rest);
        return out;
    }

    private static final RowMapper<PollRow> POLL_MAPPER = new RowMapper<>() {
        @Override
        public PollRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            PollRow row = new PollRow();
            row.id = rs.getLong("id");
            row.postId = rs.getLong("post_id");
            row.question = rs.getString("question");
            row.maxSelections = rs.getInt("max_selections");
            row.closesAt = rs.getObject("closes_at", OffsetDateTime.class);
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            return row;
        }
    };

    private static final RowMapper<PollWithPostRow> POLL_WITH_POST_MAPPER = new RowMapper<>() {
        @Override
        public PollWithPostRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            PollWithPostRow row = new PollWithPostRow();
            row.id = rs.getLong("id");
            row.postId = rs.getLong("post_id");
            row.question = rs.getString("question");
            row.maxSelections = rs.getInt("max_selections");
            row.closesAt = rs.getObject("closes_at", OffsetDateTime.class);
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            row.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            row.companyId = rs.getLong("company_id");
            long communityId = rs.getLong("community_id");
            row.communityId = rs.wasNull() ? null : communityId;
            row.postRemovedAt = rs.getObject("removed_at", OffsetDateTime.class);
            return row;
        }
    };

    private static final RowMapper<PollOptionRow> OPTION_MAPPER = new RowMapper<>() {
        @Override
        public PollOptionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            PollOptionRow row = new PollOptionRow();
            row.id = rs.getLong("id");
            row.pollId = rs.getLong("poll_id");
            row.text = rs.getString("text");
            row.sortOrder = rs.getInt("sort_order");
            row.createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return row;
        }
    };

    private static final RowMapper<VoteRow> VOTE_MAPPER = new RowMapper<>() {
        @Override
        public VoteRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            VoteRow row = new VoteRow(
                    rs.getLong("id"),
                    rs.getLong("poll_id"),
                    rs.getLong("principal_id"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class),
                    List.of()
            );
            return row;
        }
    };

    public record VoteRow(long id, long pollId, long principalId, OffsetDateTime createdAt, OffsetDateTime updatedAt, List<Long> optionIds) {
        VoteRow withOptionIds(List<Long> ids) {
            return new VoteRow(id, pollId, principalId, createdAt, updatedAt, ids == null ? List.of() : ids);
        }
    }

    public record ViewerSelection(long pollId, long optionId) {}

    public record OptionInsert(long pollId, String text, int sortOrder) {}

    public record VoteOptionInsert(long voteId, long optionId) {}

    public static class PollRow {
        public long id;
        public long postId;
        public String question;
        public int maxSelections;
        public OffsetDateTime closesAt;
        public OffsetDateTime createdAt;
        public OffsetDateTime updatedAt;
    }

    public static class PollWithPostRow extends PollRow {
        public long companyId;
        public Long communityId;
        public OffsetDateTime postRemovedAt;
    }

    public static class PollOptionRow {
        public long id;
        public long pollId;
        public String text;
        public int sortOrder;
        public OffsetDateTime createdAt;
    }
}
