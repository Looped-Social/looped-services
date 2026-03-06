package com.looped.auth;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class MeNoticeCatalog {
    static final String WORKPLACE_FIELDS_MIGRATION_V1 = "workplace_fields_migration_v1";

    private static final Map<String, NoticeDefinition> DEFINITIONS = Map.of(
            WORKPLACE_FIELDS_MIGRATION_V1,
            new NoticeDefinition(
                    WORKPLACE_FIELDS_MIGRATION_V1,
                    "Looped now supports workplaces + fields",
                    "School and major communities are no longer available. If needed, update your workplace/field in Settings.",
                    true,
                    "Got it"
            )
    );

    private MeNoticeCatalog() {}

    static Optional<NoticeDefinition> find(String noticeKey) {
        if (noticeKey == null || noticeKey.isBlank()) return Optional.empty();
        return Optional.ofNullable(DEFINITIONS.get(noticeKey.trim()));
    }

    static Collection<NoticeDefinition> all() {
        return DEFINITIONS.values();
    }

    static Set<String> keys() {
        return DEFINITIONS.keySet();
    }

    record NoticeDefinition(
            String key,
            String title,
            String body,
            boolean dismissible,
            String ctaLabel
    ) {}
}
