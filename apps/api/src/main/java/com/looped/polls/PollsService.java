package com.looped.polls;

import com.looped.communities.CommunitiesRepository;
import com.looped.communities.CommunityVerificationsRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.users.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class PollsService {
    private static final Duration MAX_CLOSES_AT = Duration.ofDays(30);

    private final PollRepository polls;
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final CommunitiesRepository communities;
    private final CommunityVerificationsRepository communityVerifications;
    private final ApplicationEventPublisher events;

    public PollsService(PollRepository polls,
                        UserRepository users,
                        PrincipalRepository principals,
                        CommunitiesRepository communities,
                        CommunityVerificationsRepository communityVerifications,
                        ApplicationEventPublisher events) {
        this.polls = polls;
        this.users = users;
        this.principals = principals;
        this.communities = communities;
        this.communityVerifications = communityVerifications;
        this.events = events;
    }

    public Long viewerPrincipalId(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) return null;
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return null;
        return principals.createForUser(u.get().id).id;
    }

    public Optional<String> validateCreate(PollRequests.PostPollCreate poll) {
        if (poll == null) return Optional.empty();

        String question = poll.question() == null ? null : poll.question().trim();
        if (question == null || question.isBlank()) return Optional.of("question_required");

        List<String> options = normalizedOptions(poll.options());
        if (options.size() < 2) return Optional.of("options_min_2");
        if (options.size() > 20) return Optional.of("options_max_20");

        Set<String> unique = new HashSet<>();
        for (String opt : options) {
            String key = opt.trim().toLowerCase();
            if (!unique.add(key)) return Optional.of("duplicate_options");
        }

        int maxSelections = poll.maxSelections() == null ? 1 : poll.maxSelections();
        if (maxSelections < 1 || maxSelections > 5) return Optional.of("max_selections_out_of_range");
        if (maxSelections > options.size()) return Optional.of("max_selections_exceeds_options");

        OffsetDateTime closesAt = poll.closesAt();
        if (closesAt != null) {
            OffsetDateTime now = nowUtc();
            if (!now.isBefore(closesAt)) return Optional.of("closes_at_must_be_future");
            if (closesAt.isAfter(now.plus(MAX_CLOSES_AT))) return Optional.of("closes_at_too_far");
        }

        return Optional.empty();
    }

    @Transactional
    public OptionalLong createForPost(long postId, PollRequests.PostPollCreate poll) {
        if (poll == null) return OptionalLong.empty();
        Optional<String> validation = validateCreate(poll);
        if (validation.isPresent()) {
            return OptionalLong.empty();
        }
        String question = poll.question().trim();
        List<String> options = normalizedOptions(poll.options());
        int maxSelections = poll.maxSelections() == null ? 1 : poll.maxSelections();
        long pollId = polls.insertPoll(postId, question, maxSelections, poll.closesAt());
        if (pollId <= 0) throw new DataRetrievalFailureException("poll_insert_failed");
        polls.insertOptions(pollId, options);
        return OptionalLong.of(pollId);
    }

    public Map<Long, PollView> viewsByPostId(Long viewerPrincipalId, List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return Map.of();
        Map<Long, PollRepository.PollRow> byPostId = polls.findByPostIds(postIds);
        if (byPostId.isEmpty()) return Map.of();

        List<Long> pollIds = byPostId.values().stream().map(r -> r.id).distinct().toList();
        var options = polls.listOptionsByPollIds(pollIds);
        Map<Long, Integer> totalVotesByPollId = polls.countTotalVotesByPollIds(pollIds);
        Map<Long, Integer> votesByOptionId = polls.countVotesByOptionIds(pollIds);
        boolean viewerKnown = viewerPrincipalId != null;
        Map<Long, Set<Long>> viewerSelections = viewerKnown
                ? polls.viewerSelectionsByPollId(viewerPrincipalId, pollIds)
                : Map.of();

        Map<Long, List<PollRepository.PollOptionRow>> optionsByPollId = new HashMap<>();
        for (var o : options) {
            optionsByPollId.computeIfAbsent(o.pollId, ignored -> new ArrayList<>()).add(o);
        }

        OffsetDateTime now = nowUtc();
        Map<Long, PollView> out = new HashMap<>();
        for (var entry : byPostId.entrySet()) {
            long postId = entry.getKey();
            PollRepository.PollRow row = entry.getValue();
            PollView view = assemble(row,
                    optionsByPollId.getOrDefault(row.id, List.of()),
                    totalVotesByPollId.getOrDefault(row.id, 0),
                    votesByOptionId,
                    viewerKnown ? viewerSelections.getOrDefault(row.id, Set.of()) : null,
                    now
            );
            out.put(postId, view);
        }
        return out;
    }

    public Optional<PollView> viewByPollId(long pollId, Long viewerPrincipalId) {
        var row = polls.findById(pollId);
        if (row.isEmpty()) return Optional.empty();
        var options = polls.listOptionsByPollIds(List.of(pollId));
        int totalVotes = polls.countTotalVotesByPollIds(List.of(pollId)).getOrDefault(pollId, 0);
        Map<Long, Integer> votesByOptionId = polls.countVotesByOptionIds(List.of(pollId));
        Set<Long> viewerSelections = viewerPrincipalId == null ? null
                : polls.viewerSelectionsByPollId(viewerPrincipalId, List.of(pollId)).getOrDefault(pollId, Set.of());
        PollView view = assemble(row.get(), options, totalVotes, votesByOptionId, viewerSelections, nowUtc());
        return Optional.of(view);
    }

    public VoteResult vote(String firebaseUid, long pollId, List<Long> selectedOptionIds) {
        if (firebaseUid == null || firebaseUid.isBlank()) return VoteResult.userNotProvisioned();
        var u = users.findByFirebaseUid(firebaseUid);
        if (u.isEmpty()) return VoteResult.userNotProvisioned();
        long userId = u.get().id;
        Long companyId = u.get().companyId;
        if (companyId == null) return VoteResult.userNotProvisioned();
        long principalId = principals.createForUser(userId).id;

        var pollWithPost = polls.findPollWithPost(pollId);
        if (pollWithPost.isEmpty() || pollWithPost.get().postRemovedAt != null) return VoteResult.notFound();
        if (pollWithPost.get().companyId != companyId) return VoteResult.forbidden();

        if (pollWithPost.get().communityId != null) {
            var community = communities.findById(pollWithPost.get().communityId);
            if (community.isEmpty()) return VoteResult.forbidden();
            boolean requiresVerification = !"specialization".equalsIgnoreCase(community.get().kind);
            if (requiresVerification && !communityVerifications.isVerified(userId, pollWithPost.get().communityId)) {
                return VoteResult.forbidden();
            }
        }

        OffsetDateTime now = nowUtc();
        if (isClosed(pollWithPost.get(), now)) return VoteResult.pollClosed();

        List<Long> normalizedSelection = normalizedSelection(selectedOptionIds);
        if (normalizedSelection.isEmpty()) return VoteResult.invalidSelection();
        if (normalizedSelection.size() > pollWithPost.get().maxSelections) return VoteResult.invalidSelection();

        Set<Long> existingOptionIds = polls.findExistingOptionIds(pollId, normalizedSelection);
        if (existingOptionIds.size() != normalizedSelection.size()) return VoteResult.invalidSelection();

        var existingVote = polls.findVote(pollId, principalId);
        if (existingVote.isPresent()) {
            Set<Long> prev = new LinkedHashSet<>(existingVote.get().optionIds());
            Set<Long> next = new LinkedHashSet<>(normalizedSelection);
            if (prev.equals(next)) {
                return viewByPollId(pollId, principalId)
                        .map(VoteResult::ok)
                        .orElseGet(VoteResult::notFound);
            }
        }

        PollView updated = upsertVoteTransactional(pollId, principalId, normalizedSelection);
        if (updated == null) return VoteResult.notFound();

        var scope = polls.findPollWithPost(pollId).orElse(null);
        if (scope != null) {
            events.publishEvent(new PollUpdatedEvent(
                    pollId,
                    scope.postId,
                    scope.companyId,
                    scope.communityId,
                    updated
            ));
        }

        return VoteResult.ok(updated);
    }

    @Transactional
    protected PollView upsertVoteTransactional(long pollId, long principalId, List<Long> normalizedSelection) {
        var pollRow = polls.findById(pollId);
        if (pollRow.isEmpty()) return null;
        long voteId = polls.upsertVote(pollId, principalId);
        if (voteId <= 0) return null;
        polls.deleteVoteOptions(voteId);
        polls.insertVoteOptions(voteId, normalizedSelection);
        polls.touchPoll(pollId);
        return viewByPollId(pollId, principalId).orElse(null);
    }

    private PollView assemble(PollRepository.PollRow poll,
                              List<PollRepository.PollOptionRow> optionRows,
                              int totalVotes,
                              Map<Long, Integer> voteCountsByOptionId,
                              Set<Long> viewerSelection,
                              OffsetDateTime now) {
        boolean open = !isClosed(poll, now);
        String status = open ? "OPEN" : "CLOSED";

        List<PollOptionView> opts = optionRows.stream().map(o -> {
            int count = voteCountsByOptionId.getOrDefault(o.id, 0);
            double percent = percent(count, totalVotes);
            return new PollOptionView(o.id, o.text, count, percent);
        }).toList();

        PollViewerView viewer = null;
        if (viewerSelection != null) {
            List<Long> selected = viewerSelection.stream().sorted().toList();
            boolean hasVoted = !selected.isEmpty();
            viewer = new PollViewerView(hasVoted, selected, open);
        }

        return new PollView(
                poll.id,
                poll.postId,
                poll.question,
                poll.maxSelections,
                poll.closesAt,
                status,
                opts,
                totalVotes,
                viewer,
                poll.updatedAt
        );
    }

    private boolean isClosed(PollRepository.PollRow poll, OffsetDateTime now) {
        if (poll.closesAt == null) return false;
        return !now.isBefore(poll.closesAt);
    }

    private OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private List<String> normalizedOptions(List<String> options) {
        if (options == null) return List.of();
        return options.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private List<Long> normalizedSelection(List<Long> optionIds) {
        if (optionIds == null) return List.of();
        List<Long> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long id : optionIds) {
            if (id == null || id <= 0) continue;
            if (!seen.add(id)) return List.of();
            out.add(id);
        }
        return out;
    }

    private double percent(int count, int totalVotes) {
        if (totalVotes <= 0) return 0.0;
        BigDecimal pct = BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalVotes), 1, RoundingMode.HALF_UP);
        return pct.doubleValue();
    }

    public enum VoteStatus {
        OK,
        USER_NOT_PROVISIONED,
        NOT_FOUND,
        FORBIDDEN,
        POLL_CLOSED,
        INVALID_SELECTION
    }

    public record VoteResult(VoteStatus status, PollView poll) {
        static VoteResult ok(PollView poll) { return new VoteResult(VoteStatus.OK, poll); }
        static VoteResult userNotProvisioned() { return new VoteResult(VoteStatus.USER_NOT_PROVISIONED, null); }
        static VoteResult notFound() { return new VoteResult(VoteStatus.NOT_FOUND, null); }
        static VoteResult forbidden() { return new VoteResult(VoteStatus.FORBIDDEN, null); }
        static VoteResult pollClosed() { return new VoteResult(VoteStatus.POLL_CLOSED, null); }
        static VoteResult invalidSelection() { return new VoteResult(VoteStatus.INVALID_SELECTION, null); }
    }

    public record PollView(
            long id,
            long postId,
            String question,
            int maxSelections,
            OffsetDateTime closesAt,
            String status,
            List<PollOptionView> options,
            int totalVotes,
            PollViewerView viewer,
            OffsetDateTime updatedAt
    ) {}

    public record PollOptionView(long id, String text, int voteCount, double votePercent) {}

    public record PollViewerView(boolean hasVoted, List<Long> selectedOptionIds, boolean canChangeVote) {}

    public record PollUpdatedEvent(long pollId, long postId, long companyId, Long communityId, PollView poll) {}
}
