package com.contextcompresso.usage;

import com.contextcompresso.config.UsageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UsagePurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(UsagePurgeScheduler.class);

    private final UsageStore store;
    private final int retentionDays;

    public UsagePurgeScheduler(UsageStore store, UsageProperties properties) {
        this.store = store;
        this.retentionDays = properties.retentionDays();
    }

    @Scheduled(cron = "0 15 2 * * *")
    public void purgeExpiredRecords() {
        int deleted = store.purgeExpired(retentionDays);
        log.info("Usage purge: removed {} expired records older than {} days", deleted, retentionDays);
    }
}
