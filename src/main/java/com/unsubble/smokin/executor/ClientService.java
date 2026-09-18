package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class ClientService implements AutoCloseable {

    public static final int DEFAULT_THREAD_COUNT = 10;

    private final int defaultThreadCount;
    private final Supplier<? extends Client> defaultClientSupplier;
    private ExecutorService executorService;

    public ClientService() {
        this((Supplier<? extends Client>) null, DEFAULT_THREAD_COUNT);
    }

    public ClientService(Client client) {
        this(client != null ? () -> client : null, DEFAULT_THREAD_COUNT);
    }

    public ClientService(Client client, int defaultThreadCount) {
        this(client != null ? () -> client : null, defaultThreadCount);
    }

    public ClientService(Supplier<? extends Client> clientSupplier) {
        this(clientSupplier, DEFAULT_THREAD_COUNT);
    }

    public ClientService(Supplier<? extends Client> clientSupplier, int defaultThreadCount) {
        this.defaultClientSupplier = clientSupplier;
        this.defaultThreadCount = defaultThreadCount > 0 ? defaultThreadCount : DEFAULT_THREAD_COUNT;
    }

    public int getDefaultThreadCount() {
        return defaultThreadCount;
    }

    public synchronized ExecutorService getExecutorService() {
        return executorService;
    }

    public synchronized ExecutorService getOrCreateExecutor(int threadCount) {
        int targetThreads = threadCount > 0 ? threadCount : defaultThreadCount;

        if (executorService == null || executorService.isShutdown()) {
            executorService = Executors.newFixedThreadPool(
                    targetThreads,
                    new SmokinThreadFactory("smokin-worker-")
            );
        }

        return executorService;
    }

    public ClientCtl createController() {
        return new ClientCtl(this);
    }

    public ExecutionResult execute(ExecutionPlan plan) {
        Objects.requireNonNull(plan, "ExecutionPlan must not be null");

        List<Request> requests = plan.allRequests();
        if (requests.isEmpty()) {
            return new ExecutionResult(List.of());
        }

        Supplier<? extends Client> supplier = plan.getClientSupplier() != null
                ? plan.getClientSupplier()
                : this.defaultClientSupplier;

        if (supplier == null) {
            throw new IllegalStateException("No Client or clientSupplier configured for execution");
        }

        if (plan.isSynchronizeLastBytes() && !plan.isAsync() && requests.size() > 1) {
            throw new IllegalArgumentException("Last-byte synchronization " +
                    "across multiple requests requires async execution");
        }

        ExecutorService executor = null;
        if (plan.isAsync()) {
            int threads = plan.getThreadCount() > 0 ? plan.getThreadCount() : defaultThreadCount;
            executor = getOrCreateExecutor(threads);
        }

        List<CompletableFuture<Response>> futures = new ArrayList<>(requests.size());
        LastByteCoordinator coordinator = plan.isSynchronizeLastBytes()
                ? new LastByteCoordinator(requests.size())
                : null;

        for (Request req : requests) {
            Client client = supplier.get();
            if (plan.isAsync()) {
                CompletableFuture<Response> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return client.send(req, coordinator);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, executor);
                futures.add(future);
            } else {
                try {
                    Response resp = client.send(req, coordinator);
                    futures.add(CompletableFuture.completedFuture(resp));
                } catch (Exception e) {
                    futures.add(CompletableFuture.failedFuture(e));
                }
            }
        }

        return new ExecutionResult(futures);
    }

    public synchronized void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    public synchronized boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (executorService != null) {
            return executorService.awaitTermination(timeout, unit);
        }
        return true;
    }

    public synchronized boolean isShutdown() {
        return executorService != null && executorService.isShutdown();
    }

    @Override
    public void close() throws IOException {
        shutdown();
    }

    private static class SmokinThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String prefix;

        SmokinThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(Objects.requireNonNull(r), prefix + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
