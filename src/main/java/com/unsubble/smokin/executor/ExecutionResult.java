package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Response;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public record ExecutionResult(List<CompletableFuture<Response>> futures) {

    public ExecutionResult(List<CompletableFuture<Response>> futures) {
        this.futures = futures != null ? List.copyOf(futures) : List.of();
    }

    public CompletableFuture<Response> future(int index) {
        return futures.get(index);
    }

    public int size() {
        return futures.size();
    }

    public CompletableFuture<List<Response>> all() {
        CompletableFuture<?>[] array = futures.toArray(new CompletableFuture[0]);
        return CompletableFuture.allOf(array)
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .toList());
    }

    public List<Response> joinAll() {
        return all().join();
    }
}
