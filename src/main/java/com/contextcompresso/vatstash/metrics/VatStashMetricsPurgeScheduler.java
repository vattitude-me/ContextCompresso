package com.contextcompresso.vatstash.metrics;

import com.contextcompresso.config.VatStashProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VatStashMetricsPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(VatStashMetricsPurgeScheduler.class);

    private final SearchEventStore store;
    private final int retentionDays;

    public VatStashMetricsPurgeScheduler(SearchEventStore store, VatStashProperties properties) {
        this.store = store;
        this.retentionDays = properties.metricsRetentionDays();
    }

    @Scheduled(cron = "0 30 2 * * *")
    public void purgeExpiredEvents() {
        try {
            int deleted = store.purgeExpired(retentionDays);
            log.info("VatStash purge: removed {} search events older than {} days", deleted, retentionDays);
        } catch (RuntimeException e) {
            log.warn("VatStash purge failed: {}", e.getMessage());
        }
    }
}
