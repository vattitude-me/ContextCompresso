package com.contextcompresso.ccr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CcrPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(CcrPurgeScheduler.class);

    private final CcrStore store;
    private final int retentionDays;

    public CcrPurgeScheduler(CcrStore store, @Value("${contextcompresso.ccr.retention-days}") int retentionDays) {
        this.store = store;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void purgeExpiredEntries() {
        int deleted = store.purgeExpired(retentionDays);
        log.info("CCR purge: removed {} expired entries older than {} days", deleted, retentionDays);
    }
}
