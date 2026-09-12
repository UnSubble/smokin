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
    private final Supplier<HttpClient> defaultClientSupplier;
    private ExecutorService executorService;

    public ClientService() {
        this((Supplier<HttpClient>) null, DEFAULT_THREAD_COUNT);
    }

    public ClientService(HttpClient client) {
        this(client != null ? () -> client : null, DEFAULT_THREAD_COUNT);
    }

    public ClientService(HttpClient client, int defaultThreadCount) {
        this(client != null ? () -> client : null, defaultThreadCount);
    }

    public ClientService(Supplier<HttpClient> clientSupplier) {
        this(clientSupplier, DEFAULT_THREAD_COUNT);
    }

    public ClientService(Supplier<HttpClient> clientSupplier, int defaultThreadCount) {
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

        Supplier<HttpClient> supplier = plan.getClientSupplier() != null
                ? plan.getClientSupplier()
                : this.defaultClientSupplier;

        if (supplier == null) {
            throw new IllegalStateException("No HttpClient or clientSupplier configured for execution");
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

        if (plan.isSynchronizeLastBytes()) {
            LastByteCoordinator coordinator = new LastByteCoordinator(requests.size());

            for (Request req : requests) {
                int splitIndex = Math.max(0, req.body().length - 1);
                Request[] parts = req.split(splitIndex);
                Request firstPart = parts[0];
                Request secondPart = parts[1];

                HttpClient client = supplier.get();
                if (plan.isAsync()) {
                    CompletableFuture<Response> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            try {
                                client.write(firstPart);
                            } catch (IOException e) {
                                coordinator.abort(e);
                                throw e;
                            }

                            coordinator.await();

                            if (secondPart.body().length > 0) {
                                client.write(secondPart.body());
                            }

                            return client.read(firstPart.method());
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    }, executor);
                    futures.add(future);
                } else {
                    try {
                        client.write(firstPart);
                        coordinator.await();
                        if (secondPart.body().length > 0) {
                            client.write(secondPart.body());
                        }
                        Response resp = client.read(firstPart.method());
                        futures.add(CompletableFuture.completedFuture(resp));
                    } catch (Exception e) {
                        futures.add(CompletableFuture.failedFuture(e));
                    }
                }
            }
        } else {
            for (Request req : requests) {
                HttpClient client = supplier.get();
                if (plan.isAsync()) {
                    DefaultAsyncHttpClient asyncClient = new DefaultAsyncHttpClient(client, executor);
                    futures.add(asyncClient.sendAsync(req));
                } else {
                    try {
                        Response resp = client.send(req);
                        futures.add(CompletableFuture.completedFuture(resp));
                    } catch (Exception e) {
                        futures.add(CompletableFuture.failedFuture(e));
                    }
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
