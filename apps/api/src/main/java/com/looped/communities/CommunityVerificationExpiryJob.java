package com.looped.communities;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CommunityVerificationExpiryJob {
    private final CommunityVerificationsRepository verifications;

    public CommunityVerificationExpiryJob(CommunityVerificationsRepository verifications) {
        this.verifications = verifications;
    }

    @Scheduled(cron = "${communities.verification-expiry-cron:0 0 * * * *}")
    public void run() {
        verifications.expireAllExpiredNow();
    }
}

