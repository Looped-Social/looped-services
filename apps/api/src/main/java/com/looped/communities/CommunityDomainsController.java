package com.looped.communities;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@RestController
@RequestMapping("/v1/communities")
public class CommunityDomainsController {
    private final CommunitiesRepository communities;
    private final CommunityDomainsRepository domains;
    private final CommunitySectorLinksRepository sectorLinks;

    public CommunityDomainsController(CommunitiesRepository communities,
                                      CommunityDomainsRepository domains,
                                      CommunitySectorLinksRepository sectorLinks) {
        this.communities = communities;
        this.domains = domains;
        this.sectorLinks = sectorLinks;
    }

    @GetMapping("/{id}/domains")
    public ResponseEntity<?> listDomains(@PathVariable("id") long id) {
        var communityOpt = communities.findById(id);
        if (communityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "community_not_found"));
        }
        var community = communityOpt.get();
        Set<String> unique = new TreeSet<>();
        unique.addAll(domains.listDomains(id));
        if ("sector".equalsIgnoreCase(community.kind)) {
            var companyIds = sectorLinks.listCompanyIds(id);
            if (!companyIds.isEmpty()) {
                unique.addAll(domains.listDomainsForCommunities(companyIds));
            }
        }
        return ResponseEntity.ok(Map.of("items", List.copyOf(unique)));
    }
}
