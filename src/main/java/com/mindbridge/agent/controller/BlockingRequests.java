package com.mindbridge.agent.controller;

import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * WebFlux 下把阻塞的 JPA / Redis / WebClient.block 调度到 boundedElastic，
 * 避免在 reactor-http-nio 线程上调用 block()。
 */
final class BlockingRequests {

    private BlockingRequests() {
    }

    static <T> Mono<T> supply(Supplier<T> action) {
        return Mono.fromCallable(action::get).subscribeOn(Schedulers.boundedElastic());
    }

    static Mono<Void> run(Runnable action) {
        return Mono.fromRunnable(action).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
