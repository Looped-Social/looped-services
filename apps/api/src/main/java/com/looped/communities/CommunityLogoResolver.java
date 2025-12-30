package com.looped.communities;

import com.looped.media.LogoDevService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CommunityLogoResolver {
    private final CommunityDomainsRepository domains;
    private final LogoDevService logoDev;

    public CommunityLogoResolver(CommunityDomainsRepository domains, LogoDevService logoDev) {
        this.domains = domains;
        this.logoDev = logoDev;
    }

    public String resolve(long communityId, String kind, String imageUrl) {
        if (imageUrl != null && !imageUrl.isBlank()) return imageUrl;
        if (!isLogoDevEligible(kind)) return null;
        String domain = domains.firstDomain(communityId).orElse(null);
        return logoDev.urlForDomain(domain);
    }

    public Map<Long, String> resolveFallbacks(List<CommunityRef> communities) {
        if (communities == null || communities.isEmpty()) return Map.of();
        List<Long> ids = communities.stream()
                .filter(ref -> ref.imageUrl == null || ref.imageUrl.isBlank())
                .filter(ref -> isLogoDevEligible(ref.kind))
                .map(ref -> ref.id)
                .toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> domainsByCommunity = domains.firstDomainsForCommunities(ids);
        Map<Long, String> out = new HashMap<>();
        for (var entry : domainsByCommunity.entrySet()) {
            String url = logoDev.urlForDomain(entry.getValue());
            if (url != null) out.put(entry.getKey(), url);
        }
        return out;
    }

    private boolean isLogoDevEligible(String kind) {
        if (kind == null) return false;
        String normalized = kind.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("company") || normalized.equals("school");
    }

    public record CommunityRef(long id, String kind, String imageUrl) {}
}
