package com.looped.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class TelemetryRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(TelemetryRetentionJob.class);

    private final TelemetryRepository repo;
    private final TelemetryRetentionProperties props;

    public TelemetryRetentionJob(TelemetryRepository repo, TelemetryRetentionProperties props) {
        this.repo = repo;
        this.props = props;
    }

    @Scheduled(cron = "${telemetry.retention.cron:0 0 4 * * *}")
    public void run() {
        if (!props.isEnabled()) return;
        int days = Math.max(1, Math.min(props.getDays(), 3650));
        int batchSize = Math.max(100, Math.min(props.getBatchSize(), 50_000));
        int maxBatches = Math.max(1, Math.min(props.getMaxBatchesPerRun(), 200));

        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        int total = 0;
        int batches = 0;
        while (batches < maxBatches) {
            int deleted = repo.deleteBatchOlderThan(cutoff, batchSize);
            if (deleted <= 0) break;
            total += deleted;
            batches += 1;
        }
        if (total > 0) {
            log.info("telemetry_retention_deleted rows={} cutoff_days={} batches={}", total, days, batches);
        }
    }
}

