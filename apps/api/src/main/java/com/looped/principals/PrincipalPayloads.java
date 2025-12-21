package com.looped.principals;

import java.util.HashMap;
import java.util.Map;

public final class PrincipalPayloads {
    private PrincipalPayloads() {}

    public static Map<String, Object> directory(PrincipalProfilesRepository.PrincipalProfileRow row) {
        Map<String, Object> out = new HashMap<>();
        out.put("principal_id", row.principalId);
        out.put("id", row.kind.equals("anon") ? row.anonProfileId : row.userId);
        out.put("handle", row.handle);
        out.put("display_name", row.displayName);
        out.put("profile_image_url", row.profileImageUrl);
        out.put("company_id", row.companyId);
        out.put("is_anonymous", row.isAnonymous);
        return out;
    }
}
