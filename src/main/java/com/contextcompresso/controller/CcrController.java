package com.contextcompresso.controller;

import com.contextcompresso.ccr.CcrEntry;
import com.contextcompresso.ccr.CcrStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/ccr")
public class CcrController {

    private final CcrStore store;

    public CcrController(CcrStore store) {
        this.store = store;
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<CcrEntry>> getById(@PathVariable String id) {
        return Mono.fromCallable(() -> store.findById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .map(opt -> opt.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build()));
    }

    @GetMapping("/request/{requestId}")
    public Mono<ResponseEntity<List<CcrEntry>>> getByRequestId(@PathVariable String requestId) {
        return Mono.fromCallable(() -> store.findByRequestId(requestId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }
}
