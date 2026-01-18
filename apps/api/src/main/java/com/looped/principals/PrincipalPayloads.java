package com.looped.principals;

import java.util.HashMap;
import java.util.Map;

public final class PrincipalPayloads {
    private PrincipalPayloads() {}

    public static Map<String, Object> directory(PrincipalProfilesRepository.PrincipalProfileRow row) {
        return directory(row, null);
    }

    public static Map<String, Object> directory(PrincipalProfilesRepository.PrincipalProfileRow row, String defaultProfileImageUrl) {
        Map<String, Object> out = new HashMap<>();
        out.put("principal_id", row.principalId);
        out.put("id", row.kind.equals("anon") ? row.anonProfileId : row.userId);
        out.put("handle", row.handle);
        out.put("display_name", row.displayName);
        out.put("profile_image_url", com.looped.users.ProfileImageUrls.resolve(row.profileImageUrl, defaultProfileImageUrl));
        out.put("company_id", row.companyId);
        out.put("is_anonymous", row.isAnonymous);
        return out;
    }
}
