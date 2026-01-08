package com.looped.verification;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PhotoIdVerificationCleanupJob {
    private final VerificationRequestsRepository requests;
    private final VerificationPrivateMediaService media;

    public PhotoIdVerificationCleanupJob(VerificationRequestsRepository requests, VerificationPrivateMediaService media) {
        this.requests = requests;
        this.media = media;
    }

    @Scheduled(cron = "${verification.photo-id.cleanup-cron:0 */5 * * * *}")
    public void run() {
        if (!media.isConfigured()) return;
        var due = requests.listDuePhotoIdDeletes(200);
        for (var row : due) {
            boolean ok = true;
            if (row.selfieKey != null && !row.selfieKey.isBlank()) ok = ok && media.deleteObjectQuietly(row.selfieKey);
            if (row.idFrontKey != null && !row.idFrontKey.isBlank()) ok = ok && media.deleteObjectQuietly(row.idFrontKey);
            if (row.idBackKey != null && !row.idBackKey.isBlank()) ok = ok && media.deleteObjectQuietly(row.idBackKey);
            if (ok) requests.markMediaDeleted(row.id);
        }
    }
}
