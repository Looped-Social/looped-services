package com.looped.users;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class OnboardingV2Repository {
    private final JdbcTemplate jdbc;

    public OnboardingV2Repository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = "user_id, stage_v2, selected_org_id, selected_org_kind, verification_path, " +
            "verification_status, requires_specialization_selection, selected_specialization_id, completion_reason, " +
            "info_screen_viewed_at, org_selected_at, verification_choice_set_at, email_verified_at, " +
            "specialization_selected_at, skip_explainer_ack_at, photo_pending_explainer_ack_at, finalized_at, updated_at";

    private static final RowMapper<Row> MAPPER = new RowMapper<>() {
        @Override
        public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
            Row row = new Row();
            row.userId = rs.getLong("user_id");
            row.stageV2 = rs.getString("stage_v2");
            long orgId = rs.getLong("selected_org_id");
            row.selectedOrgId = rs.wasNull() ? null : orgId;
            row.selectedOrgKind = rs.getString("selected_org_kind");
            row.verificationPath = rs.getString("verification_path");
            row.verificationStatus = rs.getString("verification_status");
            row.requiresSpecializationSelection = rs.getBoolean("requires_specialization_selection");
            long specializationId = rs.getLong("selected_specialization_id");
            row.selectedSpecializationId = rs.wasNull() ? null : specializationId;
            row.completionReason = rs.getString("completion_reason");
            row.infoScreenViewedAt = rs.getObject("info_screen_viewed_at", OffsetDateTime.class);
            row.orgSelectedAt = rs.getObject("org_selected_at", OffsetDateTime.class);
            row.verificationChoiceSetAt = rs.getObject("verification_choice_set_at", OffsetDateTime.class);
            row.emailVerifiedAt = rs.getObject("email_verified_at", OffsetDateTime.class);
            row.specializationSelectedAt = rs.getObject("specialization_selected_at", OffsetDateTime.class);
            row.skipExplainerAckAt = rs.getObject("skip_explainer_ack_at", OffsetDateTime.class);
            row.photoPendingExplainerAckAt = rs.getObject("photo_pending_explainer_ack_at", OffsetDateTime.class);
            row.finalizedAt = rs.getObject("finalized_at", OffsetDateTime.class);
            row.updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
            return row;
        }
    };

    public Optional<Row> findByUserId(long userId) {
        var list = jdbc.query(
                "SELECT " + COLUMNS + " FROM user_onboarding_v2 WHERE user_id = ? LIMIT 1",
                MAPPER,
                userId
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Row ensureForUser(long userId, String legacyStep, OffsetDateTime onboardingCompletedAt) {
        String stage = OnboardingV2Stages.stageForLegacy(legacyStep, onboardingCompletedAt);
        String completionReason = OnboardingV2Stages.COMPLETED.equals(stage) ? "legacy_backfill" : null;
        jdbc.update(
                "INSERT INTO user_onboarding_v2(" +
                        "user_id, stage_v2, verification_status, requires_specialization_selection, completion_reason, finalized_at, updated_at" +
                        ") VALUES (?,?,?,?,?,?, now()) ON CONFLICT (user_id) DO NOTHING",
                userId,
                stage,
                "none",
                false,
                completionReason,
                onboardingCompletedAt
        );
        return findByUserId(userId).orElseThrow(() -> new IllegalStateException("Failed to initialize onboarding v2 state"));
    }

    public void update(Row row) {
        jdbc.update(
                "UPDATE user_onboarding_v2 SET " +
                        "stage_v2 = ?, selected_org_id = ?, selected_org_kind = ?, verification_path = ?, " +
                        "verification_status = ?, requires_specialization_selection = ?, selected_specialization_id = ?, " +
                        "completion_reason = ?, info_screen_viewed_at = ?, org_selected_at = ?, verification_choice_set_at = ?, " +
                        "email_verified_at = ?, specialization_selected_at = ?, skip_explainer_ack_at = ?, " +
                        "photo_pending_explainer_ack_at = ?, finalized_at = ?, updated_at = now() " +
                        "WHERE user_id = ?",
                row.stageV2,
                row.selectedOrgId,
                row.selectedOrgKind,
                row.verificationPath,
                row.verificationStatus,
                row.requiresSpecializationSelection,
                row.selectedSpecializationId,
                row.completionReason,
                row.infoScreenViewedAt,
                row.orgSelectedAt,
                row.verificationChoiceSetAt,
                row.emailVerifiedAt,
                row.specializationSelectedAt,
                row.skipExplainerAckAt,
                row.photoPendingExplainerAckAt,
                row.finalizedAt,
                row.userId
        );
    }

    public static class Row {
        public long userId;
        public String stageV2;
        public Long selectedOrgId;
        public String selectedOrgKind;
        public String verificationPath;
        public String verificationStatus;
        public boolean requiresSpecializationSelection;
        public Long selectedSpecializationId;
        public String completionReason;
        public OffsetDateTime infoScreenViewedAt;
        public OffsetDateTime orgSelectedAt;
        public OffsetDateTime verificationChoiceSetAt;
        public OffsetDateTime emailVerifiedAt;
        public OffsetDateTime specializationSelectedAt;
        public OffsetDateTime skipExplainerAckAt;
        public OffsetDateTime photoPendingExplainerAckAt;
        public OffsetDateTime finalizedAt;
        public OffsetDateTime updatedAt;
    }
}
