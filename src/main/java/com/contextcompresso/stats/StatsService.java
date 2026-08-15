package com.contextcompresso.stats;

import com.contextcompresso.config.UsageProperties;
import com.contextcompresso.usage.UsageRecord;
import com.contextcompresso.usage.UsageStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StatsService {

    private static final long LOOKBACK_MS = 24L * 60L * 60L * 1000L;

    private final UsageStore usageStore;
    private final UsageProperties usageProperties;

    public StatsService(UsageStore usageStore, UsageProperties usageProperties) {
        this.usageStore = usageStore;
        this.usageProperties = usageProperties;
    }

    public LiveStatsResponse liveStatsForSession(String sessionKey) {
        if (sessionKey == null) {
            return LiveStatsResponse.empty();
        }
        List<UsageRecord> records = usageStore.findBySession(sessionKey);
        if (records.isEmpty()) {
            return LiveStatsResponse.empty();
        }
        return buildLiveStats(sessionKey, records);
    }

    /** No session key supplied: fall back to whichever session most recently had activity. */
    public LiveStatsResponse liveStatsForMostRecentSession() {
        List<UsageRecord> recent = usageStore.findSince(System.currentTimeMillis() - LOOKBACK_MS);
        if (recent.isEmpty()) {
            return LiveStatsResponse.empty();
        }
        String latestSessionKey = recent.get(recent.size() - 1).sessionKey();
        if (latestSessionKey == null) {
            return LiveStatsResponse.empty();
        }
        List<UsageRecord> sessionRecords = recent.stream()
                .filter(r -> latestSessionKey.equals(r.sessionKey()))
                .toList();
        return buildLiveStats(latestSessionKey, sessionRecords);
    }

    private LiveStatsResponse buildLiveStats(String sessionKey, List<UsageRecord> records) {
        TokenTotals totals = TokenTotals.sumOf(records);
        double cacheReadWeight = usageProperties.cacheReadWeight();
        double cacheWriteWeight = usageProperties.cacheWriteWeight();

        LiveStatsResponse.LastTurn lastTurn = buildLastTurn(records, cacheReadWeight, cacheWriteWeight);

        long totalOriginal = records.stream().mapToLong(UsageRecord::originalChars).sum();
        long totalCompressed = records.stream().mapToLong(UsageRecord::compressedChars).sum();
        double ratio = totalOriginal == 0 ? 1.0 : (double) totalCompressed / (double) totalOriginal;

        return new LiveStatsResponse(
                sessionKey,
                records.size(),
                totals,
                totals.cacheHitRate(),
                totals.effectiveInput(cacheReadWeight, cacheWriteWeight),
                lastTurn,
                new LiveStatsResponse.CompressionSummary(totalOriginal, totalCompressed, ratio)
        );
    }

    private LiveStatsResponse.LastTurn buildLastTurn(List<UsageRecord> records, double cacheReadWeight,
                                                       double cacheWriteWeight) {
        UsageRecord last = records.get(records.size() - 1);
        long lastEffective = new TokenTotals(last.inputTokens(), last.outputTokens(),
                last.cacheReadTokens(), last.cacheWriteTokens()).effectiveInput(cacheReadWeight, cacheWriteWeight);

        double delta = 0.0;
        if (records.size() >= 2) {
            UsageRecord prev = records.get(records.size() - 2);
            long prevEffective = new TokenTotals(prev.inputTokens(), prev.outputTokens(),
                    prev.cacheReadTokens(), prev.cacheWriteTokens()).effectiveInput(cacheReadWeight, cacheWriteWeight);
            if (prevEffective > 0) {
                delta = (double) (lastEffective - prevEffective) / (double) prevEffective;
            }
        }
        return new LiveStatsResponse.LastTurn(last.inputTokens(), last.outputTokens(), last.cacheReadTokens(), delta);
    }

    public TodayStatsResponse todayStats() {
        long since = startOfTodayEpochMs();
        List<UsageRecord> records = usageStore.findSince(since);
        double cacheReadWeight = usageProperties.cacheReadWeight();
        double cacheWriteWeight = usageProperties.cacheWriteWeight();

        TokenTotals totals = TokenTotals.sumOf(records);

        Map<Integer, List<UsageRecord>> byHour = records.stream()
                .collect(Collectors.groupingBy(this::hourOf, LinkedHashMap::new, Collectors.toList()));
        List<TodayStatsResponse.HourBucket> hourBuckets = byHour.entrySet().stream()
                .map(e -> new TodayStatsResponse.HourBucket(
                        e.getKey(),
                        TokenTotals.sumOf(e.getValue()).effectiveInput(cacheReadWeight, cacheWriteWeight),
                        e.getValue().size()))
                .sorted(Comparator.comparingInt(TodayStatsResponse.HourBucket::hour))
                .toList();

        Map<String, List<UsageRecord>> byModel = records.stream()
                .collect(Collectors.groupingBy(r -> r.model() == null ? "unknown" : r.model()));
        List<TodayStatsResponse.ModelBreakdown> modelBreakdowns = byModel.entrySet().stream()
                .map(e -> {
                    TokenTotals modelTotals = TokenTotals.sumOf(e.getValue());
                    return new TodayStatsResponse.ModelBreakdown(
                            e.getKey(), e.getValue().size(), modelTotals,
                            modelTotals.effectiveInput(cacheReadWeight, cacheWriteWeight));
                })
                .sorted(Comparator.comparingLong(TodayStatsResponse.ModelBreakdown::effectiveInputTokens).reversed())
                .toList();

        return new TodayStatsResponse(
                since,
                records.size(),
                totals,
                totals.effectiveInput(cacheReadWeight, cacheWriteWeight),
                totals.cacheHitRate(),
                hourBuckets,
                modelBreakdowns
        );
    }

    public List<TopCostEntry> topCosts(int limit) {
        long since = startOfTodayEpochMs();
        double cacheReadWeight = usageProperties.cacheReadWeight();
        double cacheWriteWeight = usageProperties.cacheWriteWeight();

        return usageStore.topByEffectiveCost(since, Math.max(limit, 1)).stream()
                .map(r -> new TopCostEntry(
                        r.requestId(), r.provider(), r.model(),
                        new TokenTotals(r.inputTokens(), r.outputTokens(), r.cacheReadTokens(), r.cacheWriteTokens())
                                .effectiveInput(cacheReadWeight, cacheWriteWeight),
                        r.inputTokens(), r.outputTokens(), r.cacheReadTokens(), r.cacheWriteTokens(),
                        r.createdAt()))
                .sorted(Comparator.comparingLong(TopCostEntry::effectiveInputTokens).reversed())
                .limit(limit)
                .toList();
    }

    private int hourOf(UsageRecord record) {
        return Instant.ofEpochMilli(record.createdAt()).atZone(ZoneId.systemDefault()).getHour();
    }

    private long startOfTodayEpochMs() {
        ZonedDateTime startOfDay = ZonedDateTime.now(ZoneId.systemDefault())
                .toLocalDate().atStartOfDay(ZoneId.systemDefault());
        return startOfDay.toInstant().toEpochMilli();
    }
}
