package com.contextcompresso.controller;

import com.contextcompresso.stats.LiveStatsResponse;
import com.contextcompresso.stats.StatsService;
import com.contextcompresso.stats.TodayStatsResponse;
import com.contextcompresso.stats.TopCostEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

@RestController
@RequestMapping("/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/live")
    public Mono<ResponseEntity<LiveStatsResponse>> live(
            @RequestParam(name = "sessionKey", required = false) String sessionKey) {
        return Mono.fromCallable(() -> sessionKey != null
                        ? statsService.liveStatsForSession(sessionKey)
                        : statsService.liveStatsForMostRecentSession())
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/session/{sessionKey}")
    public Mono<ResponseEntity<LiveStatsResponse>> session(@PathVariable String sessionKey) {
        return Mono.fromCallable(() -> statsService.liveStatsForSession(sessionKey))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/today")
    public Mono<ResponseEntity<TodayStatsResponse>> today() {
        return Mono.fromCallable(statsService::todayStats)
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/top-costs")
    public Mono<ResponseEntity<List<TopCostEntry>>> topCosts(
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return Mono.fromCallable(() -> statsService.topCosts(bounded))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/version")
    public ResponseEntity<VersionResponse> version() {
        String version = getClass().getPackage().getImplementationVersion();
        return ResponseEntity.ok(new VersionResponse(
                version != null ? version : "dev",
                readBuildNumber()));
    }

    /**
     * Implementation-Version is exposed via Package#getImplementationVersion(), but there's no
     * standard accessor for arbitrary custom manifest entries, so Build-Number (set by build.sh
     * via -Dbuild.number, see pom.xml) has to be read straight off the running jar's manifest.
     */
    private String readBuildNumber() {
        try (InputStream in = getClass().getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in == null) {
                return "0";
            }
            Manifest manifest = new Manifest(in);
            Attributes attrs = manifest.getMainAttributes();
            String buildNumber = attrs.getValue("Build-Number");
            return buildNumber != null ? buildNumber : "0";
        } catch (IOException e) {
            return "0";
        }
    }

    public record VersionResponse(String version, String buildNumber) {
    }
}
