package com.looped.recommendations.people;

import com.looped.communities.CommunitiesRepository;
import com.looped.principals.PrincipalRepository;
import com.looped.users.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
class PeopleRecommendationService {
    private final UserRepository users;
    private final PrincipalRepository principals;
    private final CommunitiesRepository communities;
    private final PeopleRecommendationRepository repo;
    private final PeopleRecommendationProperties props;

    PeopleRecommendationService(UserRepository users,
                                PrincipalRepository principals,
                                CommunitiesRepository communities,
                                PeopleRecommendationRepository repo,
                                PeopleRecommendationProperties props) {
        this.users = users;
        this.principals = principals;
        this.communities = communities;
        this.repo = repo;
        this.props = props;
    }

    RailsResult rails(String firebaseUid,
                      PeopleRecommendationTypes.Surface surface,
                      Long requestedCommunityId,
                      List<PeopleRecommendationTypes.Rail> rails,
                      int limitPerRail) {
        var actor = resolveActor(firebaseUid);
        if (actor.status != Status.OK) return RailsResult.error(actor.status);

        CommunitySelection selection = resolveCommunity(actor.user, requestedCommunityId);
        if (selection.status != Status.OK) return RailsResult.error(selection.status);

        UUID requestId = UUID.randomUUID();
        Experiment experiment = assignExperiment(actor.user.id);

        List<PeopleRecommendationTypes.Rail> normalizedRails = normalizeRails(rails);
        List<RailPage> outRails = new ArrayList<>();
        boolean degraded = false;
        for (PeopleRecommendationTypes.Rail rail : normalizedRails) {
            RailPage page = loadRailPage(
                    actor,
                    rail,
                    surface,
                    selection.community,
                    null,
                    limitPerRail,
                    requestId,
                    experiment
            );
            degraded = degraded || page.degraded;
            outRails.add(page);
        }

        return RailsResult.ok(requestId.toString(), selection.community, outRails, experiment, degraded);
    }

    SingleRailResult rail(String firebaseUid,
                          PeopleRecommendationTypes.Rail rail,
                          PeopleRecommendationTypes.Surface surface,
                          Long requestedCommunityId,
                          String cursor,
                          int limit) {
        var actor = resolveActor(firebaseUid);
        if (actor.status != Status.OK) return SingleRailResult.error(actor.status);

        PeopleRecommendationCursor.Cursor decoded = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                decoded = PeopleRecommendationCursor.decode(cursor);
            } catch (IllegalArgumentException e) {
                return SingleRailResult.error(Status.INVALID_CURSOR);
            }
            if (!rail.wire().equals(decoded.rail())) {
                return SingleRailResult.error(Status.INVALID_CURSOR);
            }
        }

        CommunitySelection selection = resolveCommunity(actor.user, requestedCommunityId);
        if (selection.status != Status.OK) return SingleRailResult.error(selection.status);

        if (decoded != null) {
            if ((decoded.communityId() == null && selection.community != null)
                    || (decoded.communityId() != null && (selection.community == null || selection.community.id != decoded.communityId()))) {
                return SingleRailResult.error(Status.INVALID_CURSOR);
            }
        }

        UUID requestId = UUID.randomUUID();
        Experiment experiment = assignExperiment(actor.user.id);

        RailPage page = loadRailPage(
                actor,
                rail,
                surface,
                selection.community,
                decoded,
                limit,
                requestId,
                experiment
        );
        return SingleRailResult.ok(requestId.toString(), selection.community, page, experiment);
    }

    FeedbackResult feedback(String firebaseUid, PeopleRecommendationRequests.FeedbackRequest request) {
        var actor = resolveActor(firebaseUid);
        if (actor.status != Status.OK) return FeedbackResult.error(actor.status);
        if (request == null || request.events() == null || request.events().isEmpty()) {
            return FeedbackResult.error(Status.INVALID_BODY);
        }
        if (request.events().size() > props.getMaxFeedbackEventsPerRequest()) {
            return FeedbackResult.error(Status.PAYLOAD_TOO_LARGE);
        }

        int accepted = 0;
        int deduped = 0;
        int dropped = 0;
        Set<Long> suppressed = new HashSet<>();

        for (PeopleRecommendationRequests.FeedbackEvent event : request.events()) {
            if (event == null) {
                dropped++;
                continue;
            }
            String eventId = normalizeShort(event.eventId(), 128);
            PeopleRecommendationTypes.FeedbackType type = PeopleRecommendationTypes.FeedbackType.parse(event.type());
            String trackingToken = normalizeShort(event.trackingToken(), 256);
            String recommendationId = normalizeShort(event.recommendationId(), 256);

            if (eventId == null || type == null || trackingToken == null) {
                dropped++;
                continue;
            }

            Optional<PeopleRecommendationRepository.ServedLookup> served = repo.findServedByTrackingToken(
                    actor.user.id,
                    trackingToken,
                    recommendationId
            );
            if (served.isEmpty()) {
                dropped++;
                continue;
            }

            var servedRow = served.get();
            String rail = servedRow.rail();
            String surface = servedRow.surface();
            Integer position = normalizePosition(event.position());
            OffsetDateTime clientTs = event.clientTs();
            Map<String, Object> metadata = event.metadata() == null ? Map.of() : event.metadata();

            boolean inserted = repo.insertFeedbackEventIfAbsent(new PeopleRecommendationRepository.FeedbackInsert(
                    eventId,
                    actor.user.id,
                    servedRow.candidateUserId(),
                    servedRow.recommendationId(),
                    servedRow.trackingToken(),
                    rail,
                    surface,
                    type.wire(),
                    position,
                    metadata,
                    clientTs
            ));

            if (!inserted) {
                deduped++;
                continue;
            }

            accepted++;
            if (type == PeopleRecommendationTypes.FeedbackType.HIDE) {
                repo.upsertSuppression(
                        actor.user.id,
                        servedRow.candidateUserId(),
                        "hide",
                        OffsetDateTime.now().plus(props.getHideCooldown())
                );
                suppressed.add(servedRow.candidateUserId());
            }
            if (type == PeopleRecommendationTypes.FeedbackType.LESS_LIKE_THIS) {
                repo.upsertSuppression(
                        actor.user.id,
                        servedRow.candidateUserId(),
                        "less_like_this",
                        OffsetDateTime.now().plus(props.getLessLikeCooldown())
                );
                suppressed.add(servedRow.candidateUserId());
            }
        }

        return FeedbackResult.ok(UUID.randomUUID().toString(), accepted, deduped, dropped, suppressed.stream().sorted().toList());
    }

    private RailPage loadRailPage(Actor actor,
                                  PeopleRecommendationTypes.Rail rail,
                                  PeopleRecommendationTypes.Surface surface,
                                  CommunityInfo community,
                                  PeopleRecommendationCursor.Cursor cursor,
                                  int limit,
                                  UUID requestId,
                                  Experiment experiment) {
        int normalizedLimit = Math.max(1, Math.min(limit, 50));
        OffsetDateTime asOf = cursor == null ? OffsetDateTime.now() : cursor.asOf();

        if ((rail == PeopleRecommendationTypes.Rail.COMMUNITY || rail == PeopleRecommendationTypes.Rail.ACTIVE_COMMUNITY)
                && community == null) {
            return new RailPage(
                    rail,
                    railTitle(rail, null),
                    List.of(),
                    null,
                    false,
                    false
            );
        }

        Long communityId = community == null ? null : community.id;
        var context = new PeopleRecommendationRepository.ViewerContext(
                actor.user.id,
                actor.principalId,
                actor.user.companyId,
                communityId,
                asOf
        );
        var keyset = cursor == null
                ? null
                : new PeopleRecommendationRepository.CursorKeyset(cursor.score(), cursor.createdAt(), cursor.userId());

        int fetchLimit = Math.max(normalizedLimit + 1,
                Math.min(props.getMaxFetchPerRail(), normalizedLimit * Math.max(1, props.getCandidateFetchMultiplier())));

        List<PeopleRecommendationRepository.CandidateRow> candidates;
        boolean degraded = false;
        try {
            candidates = findCandidatesWithExposureFallback(rail, context, keyset, fetchLimit);
        } catch (DataAccessException e) {
            degraded = true;
            candidates = repo.findFallbackCandidates(
                    rail,
                    context,
                    fetchLimit,
                    props.getActiveWindowDays(),
                    props.getOpenReportExclusionThreshold()
            );
        }

        candidates = rerankWithDiversity(candidates, normalizedLimit);

        int outSize = Math.min(normalizedLimit, candidates.size());
        List<PeopleRecommendationRepository.CandidateRow> pageRows = candidates.subList(0, outSize);
        boolean hasMore = candidates.size() > normalizedLimit;

        String nextCursor = null;
        if (hasMore && !pageRows.isEmpty()) {
            var last = pageRows.get(pageRows.size() - 1);
            nextCursor = PeopleRecommendationCursor.encode(
                    rail.wire(),
                    asOf,
                    last.score,
                    last.createdAt,
                    last.userId,
                    communityId
            );
        }

        List<RecommendationItem> items = new ArrayList<>();
        List<PeopleRecommendationRepository.ServedAuditInsert> auditRows = new ArrayList<>();
        int position = 1;
        for (var row : pageRows) {
            List<Reason> reasons = buildReasons(row, rail, community);
            String recommendationId = "rec_" + shortUuid();
            String trackingToken = "trk_" + shortUuid();

            RecommendationItem item = new RecommendationItem(
                    recommendationId,
                    trackingToken,
                    position,
                    row,
                    reasons,
                    row.score
            );
            items.add(item);

            auditRows.add(new PeopleRecommendationRepository.ServedAuditInsert(
                    requestId,
                    actor.user.id,
                    row.userId,
                    rail.wire(),
                    surface.wire(),
                    recommendationId,
                    trackingToken,
                    reasons.stream().map(Reason::code).toList(),
                    reasons.stream().map(Reason::text).toList(),
                    row.score,
                    position,
                    props.getModelVersion(),
                    experiment.key,
                    experiment.bucket
            ));
            position++;
        }
        repo.insertServedAuditBatch(auditRows);

        return new RailPage(
                rail,
                railTitle(rail, community),
                items,
                nextCursor,
                hasMore,
                degraded
        );
    }

    private List<PeopleRecommendationRepository.CandidateRow> findCandidatesWithExposureFallback(
            PeopleRecommendationTypes.Rail rail,
            PeopleRecommendationRepository.ViewerContext context,
            PeopleRecommendationRepository.CursorKeyset keyset,
            int fetchLimit) {
        int configuredExposureCap = props.getMaxViewerExposurePerCandidate24h();
        List<PeopleRecommendationRepository.CandidateRow> candidates = repo.findCandidates(
                rail,
                context,
                keyset,
                fetchLimit,
                props.getActiveWindowDays(),
                props.getOpenReportExclusionThreshold(),
                configuredExposureCap
        );
        if (!candidates.isEmpty()) {
            return candidates;
        }

        // Prevent all-empty rails in sparse graphs when every candidate is at the exposure cap.
        return repo.findCandidates(
                rail,
                context,
                keyset,
                fetchLimit,
                props.getActiveWindowDays(),
                props.getOpenReportExclusionThreshold(),
                Integer.MAX_VALUE
        );
    }

    private List<PeopleRecommendationRepository.CandidateRow> rerankWithDiversity(List<PeopleRecommendationRepository.CandidateRow> input,
                                                                                   int pageLimit) {
        if (input == null || input.isEmpty()) return List.of();
        int communityCap = Math.max(1, props.getMaxPerCommunityPerPage());
        int specializationCap = Math.max(1, props.getMaxPerSpecializationPerPage());

        Map<Long, Integer> communityCounts = new HashMap<>();
        Map<Long, Integer> specializationCounts = new HashMap<>();
        List<PeopleRecommendationRepository.CandidateRow> primary = new ArrayList<>();
        List<PeopleRecommendationRepository.CandidateRow> overflow = new ArrayList<>();

        for (var row : input) {
            boolean overCommunity = row.displayCommunityId != null
                    && communityCounts.getOrDefault(row.displayCommunityId, 0) >= communityCap;
            boolean overSpecialization = row.displaySpecializationId != null
                    && specializationCounts.getOrDefault(row.displaySpecializationId, 0) >= specializationCap;

            if (overCommunity || overSpecialization) {
                overflow.add(row);
                continue;
            }

            primary.add(row);
            if (row.displayCommunityId != null) {
                communityCounts.put(row.displayCommunityId, communityCounts.getOrDefault(row.displayCommunityId, 0) + 1);
            }
            if (row.displaySpecializationId != null) {
                specializationCounts.put(row.displaySpecializationId, specializationCounts.getOrDefault(row.displaySpecializationId, 0) + 1);
            }
        }

        // Keep rail full when diversity caps are too strict in small communities.
        if (primary.size() < pageLimit && !overflow.isEmpty()) {
            primary.addAll(overflow);
        } else {
            primary.addAll(overflow);
        }
        primary.sort(Comparator
                .comparingLong((PeopleRecommendationRepository.CandidateRow r) -> r.score).reversed()
                .thenComparing((PeopleRecommendationRepository.CandidateRow r) -> r.createdAt, Comparator.reverseOrder())
                .thenComparingLong((PeopleRecommendationRepository.CandidateRow r) -> r.userId).reversed());
        return primary;
    }

    private List<Reason> buildReasons(PeopleRecommendationRepository.CandidateRow row,
                                      PeopleRecommendationTypes.Rail rail,
                                      CommunityInfo community) {
        List<Reason> reasons = new ArrayList<>();
        if (row.mutualCount > 0) {
            reasons.add(new Reason(
                    "MUTUAL_CONNECTIONS",
                    "You share " + row.mutualCount + (row.mutualCount == 1 ? " connection" : " connections")
            ));
        }
        if (row.inTargetCommunity && community != null) {
            String name = community.shortName != null && !community.shortName.isBlank() ? community.shortName : community.name;
            reasons.add(new Reason("SAME_COMMUNITY", "Verified in " + name));
        }
        if (row.sharedSpecializationCount > 0) {
            reasons.add(new Reason("SAME_FIELD", "People in your field"));
        }
        if (rail == PeopleRecommendationTypes.Rail.ACTIVE_COMMUNITY && row.targetRecentPosts > 0) {
            reasons.add(new Reason("ACTIVE_IN_COMMUNITY", "Active in your community recently"));
        }
        if (row.followsViewer) {
            reasons.add(new Reason("FOLLOWS_YOU", "Follows you"));
        }
        if (reasons.isEmpty() && row.recentPosts > 0) {
            reasons.add(new Reason("RECENTLY_ACTIVE", "Recently active"));
        }
        if (reasons.isEmpty()) {
            reasons.add(new Reason("DISCOVERY", "Suggested for you"));
        }
        if (reasons.size() > 2) {
            return reasons.subList(0, 2);
        }
        return reasons;
    }

    private List<PeopleRecommendationTypes.Rail> normalizeRails(List<PeopleRecommendationTypes.Rail> requested) {
        List<PeopleRecommendationTypes.Rail> rails = new ArrayList<>();
        if (requested != null) {
            for (var rail : requested) {
                if (rail == null) continue;
                if (rail == PeopleRecommendationTypes.Rail.ACTIVE_COMMUNITY && !props.isActiveCommunityRailEnabled()) {
                    continue;
                }
                if (!rails.contains(rail)) rails.add(rail);
            }
        }
        if (rails.isEmpty()) {
            rails.add(PeopleRecommendationTypes.Rail.PYMK);
            rails.add(PeopleRecommendationTypes.Rail.COMMUNITY);
            if (props.isActiveCommunityRailEnabled()) {
                rails.add(PeopleRecommendationTypes.Rail.ACTIVE_COMMUNITY);
            }
        }
        return rails;
    }

    private Experiment assignExperiment(long userId) {
        int percentB = Math.max(0, Math.min(100, props.getExperimentBucketBPercent()));
        int bucket = Math.floorMod(Long.hashCode(userId), 100);
        String assigned = bucket < percentB ? "B" : "A";
        return new Experiment(props.getExperimentKey(), assigned);
    }

    private String railTitle(PeopleRecommendationTypes.Rail rail, CommunityInfo community) {
        return switch (rail) {
            case PYMK -> "People You May Know";
            case COMMUNITY -> {
                String name = community == null ? "Your Community" : (community.shortName == null || community.shortName.isBlank() ? community.name : community.shortName);
                yield "People in " + name;
            }
            case ACTIVE_COMMUNITY -> "New/Active in Community";
        };
    }

    private Actor resolveActor(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) return Actor.error(Status.USER_NOT_PROVISIONED);
        var userOpt = users.findByFirebaseUid(firebaseUid);
        if (userOpt.isEmpty() || userOpt.get().companyId == null) {
            return Actor.error(Status.USER_NOT_PROVISIONED);
        }
        var principal = principals.createForUser(userOpt.get().id);
        return Actor.ok(userOpt.get(), principal.id);
    }

    private CommunitySelection resolveCommunity(UserRepository.UserRow user, Long requestedCommunityId) {
        Long targetId = requestedCommunityId != null ? requestedCommunityId : user.displayCommunityId;
        if (targetId == null) return CommunitySelection.none();
        var community = communities.findById(targetId);
        if (community.isEmpty()) return CommunitySelection.error(Status.COMMUNITY_NOT_FOUND);
        var row = community.get();
        return CommunitySelection.ok(new CommunityInfo(row.id, row.name, row.shortName));
    }

    private String normalizeShort(String raw, int maxLen) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isBlank()) return null;
        if (v.length() > maxLen) return v.substring(0, maxLen);
        return v;
    }

    private Integer normalizePosition(Integer position) {
        if (position == null) return null;
        if (position < 1 || position > 10_000) return null;
        return position;
    }

    private String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toLowerCase(Locale.ROOT);
    }

    enum Status {
        OK,
        USER_NOT_PROVISIONED,
        COMMUNITY_NOT_FOUND,
        INVALID_CURSOR,
        INVALID_BODY,
        PAYLOAD_TOO_LARGE
    }

    record Actor(Status status, UserRepository.UserRow user, long principalId) {
        static Actor ok(UserRepository.UserRow user, long principalId) {
            return new Actor(Status.OK, user, principalId);
        }

        static Actor error(Status status) {
            return new Actor(status, null, 0L);
        }
    }

    record CommunityInfo(long id, String name, String shortName) {}

    record CommunitySelection(Status status, CommunityInfo community) {
        static CommunitySelection ok(CommunityInfo community) {
            return new CommunitySelection(Status.OK, community);
        }

        static CommunitySelection none() {
            return new CommunitySelection(Status.OK, null);
        }

        static CommunitySelection error(Status status) {
            return new CommunitySelection(status, null);
        }
    }

    record Experiment(String key, String bucket) {}

    record Reason(String code, String text) {}

    record RecommendationItem(String recommendationId,
                              String trackingToken,
                              int position,
                              PeopleRecommendationRepository.CandidateRow row,
                              List<Reason> reasons,
                              long score) {}

    record RailPage(PeopleRecommendationTypes.Rail rail,
                    String title,
                    List<RecommendationItem> items,
                    String nextCursor,
                    boolean hasMore,
                    boolean degraded) {}

    record RailsResult(Status status,
                       String requestId,
                       CommunityInfo community,
                       List<RailPage> rails,
                       Experiment experiment,
                       boolean degraded) {
        static RailsResult ok(String requestId,
                              CommunityInfo community,
                              List<RailPage> rails,
                              Experiment experiment,
                              boolean degraded) {
            return new RailsResult(Status.OK, requestId, community, rails, experiment, degraded);
        }

        static RailsResult error(Status status) {
            return new RailsResult(status, null, null, List.of(), null, false);
        }
    }

    record SingleRailResult(Status status,
                            String requestId,
                            CommunityInfo community,
                            RailPage rail,
                            Experiment experiment) {
        static SingleRailResult ok(String requestId,
                                   CommunityInfo community,
                                   RailPage rail,
                                   Experiment experiment) {
            return new SingleRailResult(Status.OK, requestId, community, rail, experiment);
        }

        static SingleRailResult error(Status status) {
            return new SingleRailResult(status, null, null, null, null);
        }
    }

    record FeedbackResult(Status status,
                          String requestId,
                          int accepted,
                          int deduped,
                          int dropped,
                          List<Long> suppressedCandidateIds) {
        static FeedbackResult ok(String requestId, int accepted, int deduped, int dropped, List<Long> suppressedCandidateIds) {
            return new FeedbackResult(Status.OK, requestId, accepted, deduped, dropped, suppressedCandidateIds);
        }

        static FeedbackResult error(Status status) {
            return new FeedbackResult(status, null, 0, 0, 0, List.of());
        }
    }
}
