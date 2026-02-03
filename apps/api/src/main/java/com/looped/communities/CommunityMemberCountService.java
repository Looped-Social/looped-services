package com.looped.communities;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CommunityMemberCountService {
    private final CommunityVerificationsRepository verifications;
    private final SpecializationJoinsRepository specializationJoins;

    public CommunityMemberCountService(CommunityVerificationsRepository verifications,
                                       SpecializationJoinsRepository specializationJoins) {
        this.verifications = verifications;
        this.specializationJoins = specializationJoins;
    }

    public record Ref(long id, String kind) {}

    public int memberCount(long communityId, String kind) {
        if (isSpecialization(kind)) {
            return specializationJoins.countMembers(communityId);
        }
        return verifications.countActiveVerifiedMembers(communityId);
    }

    public Map<Long, Integer> memberCountsByCommunityRefs(Collection<Ref> refs) {
        if (refs == null || refs.isEmpty()) return Map.of();

        List<Long> specializationIds = new ArrayList<>();
        List<Long> verifiedCommunityIds = new ArrayList<>();
        for (Ref ref : refs) {
            if (ref == null) continue;
            if (isSpecialization(ref.kind)) {
                specializationIds.add(ref.id);
            } else {
                verifiedCommunityIds.add(ref.id);
            }
        }

        Map<Long, Integer> out = new HashMap<>();
        out.putAll(specializationJoins.countMembersBySpecializationIds(specializationIds));
        out.putAll(verifications.countActiveVerifiedMembersByCommunityIds(verifiedCommunityIds));
        return out;
    }

    private boolean isSpecialization(String kind) {
        if (kind == null) return false;
        String normalized = kind.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("specialization");
    }
}

