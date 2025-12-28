package com.looped.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AccountRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(AccountRetentionJob.class);
    private final UsersService usersService;
    private final boolean enabled;

    public AccountRetentionJob(UsersService usersService,
                               @Value("${retention.deactivated-purge-enabled:true}") boolean enabled) {
        this.usersService = usersService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${retention.deactivated-purge-cron:0 15 3 * * *}")
    public void purgeDeactivatedAccounts() {
        if (!enabled) return;
        int purged = usersService.purgeDeactivated();
        if (purged > 0) {
            log.info("Purged {} deactivated accounts", purged);
        }
    }
}
