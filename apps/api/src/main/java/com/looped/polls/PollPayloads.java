package com.looped.polls;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PollPayloads {
    private PollPayloads() {}

    public static Map<String, Object> from(PollsService.PollView poll) {
        if (poll == null) return null;
        Map<String, Object> out = new HashMap<>();
        out.put("id", poll.id());
        out.put("post_id", poll.postId());
        out.put("postId", poll.postId());
        out.put("question", poll.question());
        out.put("max_selections", poll.maxSelections());
        out.put("maxSelections", poll.maxSelections());
        out.put("closes_at", poll.closesAt());
        out.put("closesAt", poll.closesAt());
        out.put("status", poll.status());
        out.put("total_votes", poll.totalVotes());
        out.put("totalVotes", poll.totalVotes());
        out.put("updated_at", poll.updatedAt());
        out.put("updatedAt", poll.updatedAt());

        List<Map<String, Object>> opts = poll.options().stream().map(o -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", o.id());
            m.put("text", o.text());
            m.put("vote_count", o.voteCount());
            m.put("voteCount", o.voteCount());
            m.put("vote_percent", o.votePercent());
            m.put("votePercent", o.votePercent());
            return m;
        }).toList();
        out.put("options", opts);

        if (poll.viewer() != null) {
            Map<String, Object> viewer = new HashMap<>();
            viewer.put("has_voted", poll.viewer().hasVoted());
            viewer.put("hasVoted", poll.viewer().hasVoted());
            viewer.put("selected_option_ids", poll.viewer().selectedOptionIds());
            viewer.put("selectedOptionIds", poll.viewer().selectedOptionIds());
            viewer.put("can_change_vote", poll.viewer().canChangeVote());
            viewer.put("canChangeVote", poll.viewer().canChangeVote());
            out.put("viewer", viewer);
        } else {
            out.put("viewer", null);
        }

        return out;
    }
}

