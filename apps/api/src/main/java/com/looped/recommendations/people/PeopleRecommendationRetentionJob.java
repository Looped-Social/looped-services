package com.looped.recommendations.people;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
class PeopleRecommendationRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(PeopleRecommendationRetentionJob.class);

    private final PeopleRecommendationRepository repo;
    private final PeopleRecommendationProperties props;

    PeopleRecommendationRetentionJob(PeopleRecommendationRepository repo,
                                     PeopleRecommendationProperties props) {
        this.repo = repo;
        this.props = props;
    }

    @Scheduled(cron = "${recommendations.people.retention.cron:0 30 4 * * *}")
    void runRetention() {
        if (!props.isRetentionEnabled()) return;

        int maxBatches = Math.max(1, props.getRetentionMaxBatchesPerRun());
        int batchSize = Math.max(1, props.getRetentionBatchSize());

        int suppressedDeleted = drainSuppressions(maxBatches, batchSize);
        int auditDeleted = drainAudit(maxBatches, batchSize);
        int feedbackDeleted = drainFeedback(maxBatches, batchSize);

        if (suppressedDeleted > 0 || auditDeleted > 0 || feedbackDeleted > 0) {
            log.info("people_reco_retention deleted_suppressions={} deleted_audit={} deleted_feedback={} batch_size={} max_batches={}",
                    suppressedDeleted,
                    auditDeleted,
                    feedbackDeleted,
                    batchSize,
                    maxBatches);
        }
    }

    private int drainSuppressions(int maxBatches, int batchSize) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted = repo.deleteExpiredSuppressions(batchSize);
            total += deleted;
            if (deleted < batchSize) break;
        }
        return total;
    }

    private int drainAudit(int maxBatches, int batchSize) {
        int total = 0;
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(Math.max(1, props.getAuditRetentionDays()));
        for (int i = 0; i < maxBatches; i++) {
            int deleted = repo.deleteOldAudit(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) break;
        }
        return total;
    }

    private int drainFeedback(int maxBatches, int batchSize) {
        int total = 0;
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(Math.max(1, props.getFeedbackRetentionDays()));
        for (int i = 0; i < maxBatches; i++) {
            int deleted = repo.deleteOldFeedback(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) break;
        }
        return total;
    }
}
