package com.looped.communities;

import com.looped.email.EmailService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class CommunityRequestAvailabilityNotifier {
    private static final Logger log = LoggerFactory.getLogger(CommunityRequestAvailabilityNotifier.class);
    private final CommunityRequestsRepository requests;
    private final EmailService emailService;
    private final MeterRegistry meters;
    private final String shareBaseUrl;

    public CommunityRequestAvailabilityNotifier(CommunityRequestsRepository requests,
                                                EmailService emailService,
                                                MeterRegistry meters,
                                                @Value("${share.base-url:https://mylooped.app}") String shareBaseUrl) {
        this.requests = requests;
        this.emailService = emailService;
        this.meters = meters;
        this.shareBaseUrl = shareBaseUrl;
    }

    @Transactional
    public NotifySummary notifyForCreatedCommunity(String requestKind, String communityName, long communityId) {
        String normalizedKind = normalizeRequestKind(requestKind);
        String normalizedCommunityName = normalizeCommunityName(communityName);
        if (normalizedKind == null || normalizedCommunityName == null || communityId <= 0) {
            return new NotifySummary(0, 0);
        }
        if (!supportsAvailabilityNotifications(normalizedKind)) {
            return new NotifySummary(0, 0);
        }
        NameSignature communitySig = NameSignature.from(normalizedCommunityName);
        var candidates = requests.listPendingNotifiableByKindForUpdate(normalizedKind);
        int matched = 0;
        int sent = 0;
        String webUrl = buildWebUrl(communityId);
        String deepLink = "looped://communities/" + communityId;
        for (var row : candidates) {
            if (!namesMatch(communitySig, row.name)) continue;
            matched++;
            String contactEmail = resolveContactEmail(row);
            if (contactEmail == null) continue;
            boolean emailSent = emailService.sendCommunityRequestAvailableEmail(
                    contactEmail,
                    communityName,
                    webUrl,
                    deepLink
            );
            if (!emailSent) continue;
            if (requests.markNotified(row.id, communityId)) {
                sent++;
            }
        }
        meters.counter("community_requests.notifications.matched", "kind", normalizedKind).increment(matched);
        meters.counter("community_requests.notifications.sent", "kind", normalizedKind).increment(sent);
        log.info("community_request_notifications kind={} community_id={} matched={} sent={}",
                normalizedKind, communityId, matched, sent);
        return new NotifySummary(matched, sent);
    }

    private boolean supportsAvailabilityNotifications(String requestKind) {
        return "company".equals(requestKind);
    }

    private String resolveContactEmail(CommunityRequestsRepository.Row row) {
        String stored = CommunityRequestContactEmails.normalizeValidEmailOrNull(row.contactEmail);
        if (stored != null) return stored;
        var parsed = CommunityRequestContactEmails.parseLegacyContactEmailLine(row.description);
        return CommunityRequestContactEmails.normalizeValidEmailOrNull(parsed.extractedEmail());
    }

    private String normalizeRequestKind(String requestKind) {
        if (requestKind == null || requestKind.isBlank()) return null;
        String normalized = requestKind.trim().toLowerCase(Locale.ROOT);
        if ("workplace".equals(normalized)) return "company";
        return switch (normalized) {
            case "company", "field" -> normalized;
            default -> null;
        };
    }

    private String normalizeCommunityName(String communityName) {
        if (communityName == null) return null;
        String trimmed = communityName.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String buildWebUrl(long communityId) {
        String base = (shareBaseUrl == null || shareBaseUrl.isBlank()) ? "https://mylooped.app" : shareBaseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/app/communities/" + communityId;
    }

    private boolean namesMatch(NameSignature communitySig, String candidateNameRaw) {
        String candidateName = normalizeCommunityName(candidateNameRaw);
        if (candidateName == null) return false;
        NameSignature candidateSig = NameSignature.from(candidateName);
        if (candidateSig.compact.equals(communitySig.compact)) return true;
        if (candidateSig.acronym != null && communitySig.acronym != null && candidateSig.acronym.equals(communitySig.acronym)) {
            return true;
        }
        if (candidateSig.acronym != null && candidateSig.acronym.equals(communitySig.compact)) return true;
        if (communitySig.acronym != null && communitySig.acronym.equals(candidateSig.compact)) return true;
        if (!candidateSig.tokens.isEmpty() && !communitySig.tokens.isEmpty()) {
            Set<String> intersection = new HashSet<>(candidateSig.tokens);
            intersection.retainAll(communitySig.tokens);
            int minSize = Math.min(candidateSig.tokens.size(), communitySig.tokens.size());
            if (minSize > 0 && intersection.size() >= minSize) return true;
            double overlap = (double) intersection.size() / (double) Math.max(candidateSig.tokens.size(), communitySig.tokens.size());
            return overlap >= 0.8;
        }
        return false;
    }

    private record NameSignature(String compact, Set<String> tokens, String acronym) {
        static NameSignature from(String raw) {
            String normalized = raw.toLowerCase(Locale.ROOT)
                    .replace("&", " and ")
                    .replaceAll("[^a-z0-9\\s]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            String compact = normalized.replace(" ", "");
            Set<String> tokens = new HashSet<>();
            StringBuilder acronymBuilder = new StringBuilder();
            if (!normalized.isBlank()) {
                String[] parts = normalized.split(" ");
                for (String p : parts) {
                    if (p.isBlank()) continue;
                    if (isStopWord(p)) continue;
                    tokens.add(p);
                    acronymBuilder.append(p.charAt(0));
                }
            }
            String acronym = acronymBuilder.length() >= 3 ? acronymBuilder.toString() : null;
            return new NameSignature(compact, tokens, acronym);
        }

        private static boolean isStopWord(String token) {
            return "the".equals(token)
                    || "of".equals(token)
                    || "and".equals(token)
                    || "for".equals(token)
                    || "at".equals(token)
                    || "in".equals(token)
                    || "a".equals(token)
                    || "an".equals(token);
        }
    }

    public record NotifySummary(int matchedRequests, int sentEmails) {}
}
