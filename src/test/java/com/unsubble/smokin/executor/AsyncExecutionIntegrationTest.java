package com.unsubble.smokin.executor;

import com.unsubble.smokin.encoder.Http1RequestEncoder;
import com.unsubble.smokin.encoder.HttpRequestEncoder;
import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.Response;
import com.unsubble.smokin.parser.Http1ResponseFramer;
import com.unsubble.smokin.parser.Http1ResponseParser;
import com.unsubble.smokin.parser.HttpResponseFramer;
import com.unsubble.smokin.parser.HttpResponseParser;
import com.unsubble.smokin.transport.TcpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
public class AsyncExecutionIntegrationTest {

    private static final int AWAIT_TIMEOUT = 8;

    private final HttpRequestEncoder encoder = new Http1RequestEncoder();
    private final HttpResponseParser parser = new Http1ResponseParser();

    private HttpClient createClient(int port) {
        TcpTransport transport = new TcpTransport("localhost", port);
        HttpResponseFramer framer = new Http1ResponseFramer(transport);
        return new HttpClient(encoder, parser, framer, transport);
    }

    private static void drainHeaders(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
            if (sb.toString().endsWith("\r\n\r\n")) break;
        }
    }

    private static byte[] httpResponse(int status, String body) {
        return ("HTTP/1.1 " + status + " OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void multipleRequestsExecuteAsynchronously() throws Exception {
        int reqCount = 4;

        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);

            CountDownLatch serverDone = new CountDownLatch(reqCount);
            AtomicInteger maxConcurrent = new AtomicInteger(0);
            AtomicInteger currentActive = new AtomicInteger(0);
            AtomicReference<Throwable> serverError = new AtomicReference<>();

            ExecutorService serverPool = Executors.newFixedThreadPool(reqCount);
            for (int i = 0; i < reqCount; i++) {
                final int idx = i;
                serverPool.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                        drainHeaders(socket.getInputStream());

                        int cur = currentActive.incrementAndGet();
                        maxConcurrent.updateAndGet(m -> Math.max(m, cur));

                        Thread.sleep(30);
                        currentActive.decrementAndGet();

                        socket.getOutputStream().write(httpResponse(200, "resp_" + idx));
                        socket.getOutputStream().flush();
                    } catch (Throwable t) {
                        serverError.set(t);
                    } finally {
                        serverDone.countDown();
                    }
                });
            }

            List<Request> requests = new ArrayList<>();
            for (int i = 0; i < reqCount; i++) {
                requests.add(Request.newBuilder().path("/req/" + i).build());
            }

            ExecutionResult result = service.createController()
                    .activateAsync()
                    .threadCount(reqCount)
                    .addGroup(requests)
                    .execute();

            List<Response> responses = result.all().get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
            assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
            serverPool.shutdown();

            assertNull(serverError.get(), () -> "Server error: " + serverError.get());
            assertEquals(reqCount, responses.size());

            responses.forEach(r -> assertEquals(200, r.statusCode()));

            assertTrue(maxConcurrent.get() > 1,
                    "Expected concurrent execution; max concurrent = " + maxConcurrent.get());

            serverPool.shutdown();
            serverPool.close();
        }
    }

    @Test
    void allFuturesCompleteSuccessfully() throws Exception {
        int reqCount = 4;

        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);
            CountDownLatch serverDone = new CountDownLatch(reqCount);
            AtomicReference<Throwable> serverError = new AtomicReference<>();

            ExecutorService serverPool = Executors.newFixedThreadPool(reqCount);
            for (int i = 0; i < reqCount; i++) {
                serverPool.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                        drainHeaders(socket.getInputStream());
                        socket.getOutputStream().write(httpResponse(200, "OK"));
                        socket.getOutputStream().flush();
                    } catch (Throwable t) {
                        serverError.set(t);
                    } finally {
                        serverDone.countDown();
                    }
                });
            }

            List<Request> requests = new ArrayList<>();
            for (int i = 0; i < reqCount; i++) {
                requests.add(Request.newBuilder().path("/order/" + i).build());
            }

            ExecutionResult result = service.createController()
                    .activateAsync()
                    .threadCount(reqCount)
                    .addGroup(requests)
                    .execute();

            assertEquals(reqCount, result.size());
            List<Response> responses = result.all().get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
            assertEquals(reqCount, responses.size());
            responses.forEach(r -> assertEquals(200, r.statusCode()));

            assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
            serverPool.shutdown();
            assertNull(serverError.get(), () -> "Server error: " + serverError.get());


            serverPool.shutdown();
            serverPool.close();
        }
    }

    @Test
    @Timeout(15)
    void oneRequestFailsOthersContinue() throws Exception {
        int totalRequests = 3;
        int deadPort;
        try (ServerSocket temp = new ServerSocket(0)) {
            deadPort = temp.getLocalPort();
        }

        try (ServerSocket goodServer = new ServerSocket(0)) {
            goodServer.setSoTimeout(AWAIT_TIMEOUT * 1000);

            CountDownLatch serverDone = new CountDownLatch(totalRequests - 1); // 2 good servers

            ExecutorService serverPool = Executors.newFixedThreadPool(2);
            for (int i = 0; i < 2; i++) {
                serverPool.submit(() -> {
                    try (Socket socket = goodServer.accept()) {
                        socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                        drainHeaders(socket.getInputStream());
                        socket.getOutputStream().write(httpResponse(200, "OK"));
                        socket.getOutputStream().flush();
                    } catch (Throwable ignored) {
                    } finally {
                        serverDone.countDown();
                    }
                });
            }

            final int finalDeadPort = deadPort;
            AtomicInteger clientIdx = new AtomicInteger(0);

            java.util.function.Supplier<HttpClient> mixedSupplier = () -> {
                int i = clientIdx.getAndIncrement();
                return createClient(i == 1 ? finalDeadPort : goodServer.getLocalPort());
            };

            try (ClientService service = new ClientService(mixedSupplier)) {
                List<Request> requests = List.of(
                        Request.newBuilder().path("/good0").build(),
                        Request.newBuilder().path("/bad").build(),
                        Request.newBuilder().path("/good2").build()
                );

                ExecutionResult result = service.createController()
                        .activateAsync()
                        .threadCount(totalRequests)
                        .addGroup(requests)
                        .execute();

                assertEquals(200, result.future(0).get(AWAIT_TIMEOUT, TimeUnit.SECONDS).statusCode());
                assertEquals(200, result.future(2).get(AWAIT_TIMEOUT, TimeUnit.SECONDS).statusCode());

                assertThrows(ExecutionException.class,
                        () -> result.future(1).get(AWAIT_TIMEOUT, TimeUnit.SECONDS));

                assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
                serverPool.shutdown();
            }

            serverPool.shutdown();
            serverPool.close();
        }
    }

    @Test
    @Timeout(30)
    void executorConcurrencyLimitIsRespected() throws Exception {
        int boundedThreads = 2;
        int totalRequests = 6;

        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(AWAIT_TIMEOUT * 1000);

            ExecutorService serverPool = Executors.newFixedThreadPool(totalRequests);
            CountDownLatch serverDone = new CountDownLatch(totalRequests);

            for (int i = 0; i < totalRequests; i++) {
                serverPool.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                        drainHeaders(socket.getInputStream());
                        socket.getOutputStream().write(httpResponse(200, "OK"));
                        socket.getOutputStream().flush();
                    } catch (IOException ignored) {
                    } finally {
                        serverDone.countDown();
                    }
                });
            }

            java.util.function.Supplier<HttpClient> monitoringSupplier = () -> {
                TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());
                HttpResponseFramer framer = new Http1ResponseFramer(transport);
                return new HttpClient(encoder, parser, framer, transport) {
                    @Override
                    public Response send(Request request) throws IOException {
                        int cur = active.incrementAndGet();
                        maxConcurrent.updateAndGet(m -> Math.max(m, cur));
                        try {
                            Thread.sleep(50); // hold for overlap
                            return super.send(request);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        } finally {
                            active.decrementAndGet();
                        }
                    }
                };
            };

            try (ClientService service = new ClientService(monitoringSupplier)) {
                List<Request> requests = new ArrayList<>();
                for (int i = 0; i < totalRequests; i++) {
                    requests.add(Request.newBuilder().path("/bounded/" + i).build());
                }

                ExecutionResult result = service.createController()
                        .activateAsync()
                        .threadCount(boundedThreads)
                        .addGroup(requests)
                        .execute();

                result.all().get(20, TimeUnit.SECONDS);
                assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
                serverPool.shutdown();

                assertTrue(maxConcurrent.get() <= boundedThreads,
                        "Max concurrent (" + maxConcurrent.get() + ") must not exceed pool size (" + boundedThreads + ")");
            }

            serverPool.shutdown();
            serverPool.close();
        }
    }

    @Test
    void multipleGroupsAreFlattenedAndExecuted() throws Exception {
        int totalRequests = 4;

        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);
            CountDownLatch serverDone = new CountDownLatch(totalRequests);
            AtomicReference<Throwable> serverError = new AtomicReference<>();

            ExecutorService serverPool = Executors.newFixedThreadPool(totalRequests);
            for (int i = 0; i < totalRequests; i++) {
                serverPool.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                        drainHeaders(socket.getInputStream());
                        socket.getOutputStream().write(httpResponse(200, "OK"));
                        socket.getOutputStream().flush();
                    } catch (Throwable t) {
                        serverError.set(t);
                    } finally {
                        serverDone.countDown();
                    }
                });
            }

            ExecutionResult result = service.createController()
                    .activateAsync()
                    .threadCount(totalRequests)
                    .addGroup("group1", List.of(
                            Request.newBuilder().path("/g1/r1").build(),
                            Request.newBuilder().path("/g1/r2").build()))
                    .addGroup("group2", List.of(
                            Request.newBuilder().path("/g2/r1").build(),
                            Request.newBuilder().path("/g2/r2").build()))
                    .execute();

            List<Response> responses = result.all().get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
            assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
            serverPool.shutdown();

            assertNull(serverError.get(), () -> "Server error: " + serverError.get());
            assertEquals(totalRequests, responses.size());
            responses.forEach(r -> assertEquals(200, r.statusCode()));


            serverPool.shutdown();
            serverPool.close();
        }
    }

    @Test
    void syncExecutionHandlesMultipleRequests() throws Exception {
        int reqCount = 3;

        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);
            CountDownLatch serverDone = new CountDownLatch(reqCount);
            AtomicReference<Throwable> serverError = new AtomicReference<>();

            Thread serverThread = new Thread(() -> {
                for (int i = 0; i < reqCount; i++) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                        drainHeaders(socket.getInputStream());
                        socket.getOutputStream().write(httpResponse(200 + i, "resp" + i));
                        socket.getOutputStream().flush();
                    } catch (Throwable t) {
                        serverError.set(t);
                    } finally {
                        serverDone.countDown();
                    }
                }
            });
            serverThread.start();

            List<Request> requests = new ArrayList<>();
            for (int i = 0; i < reqCount; i++) {
                requests.add(Request.newBuilder().path("/sync/" + i).build());
            }

            ExecutionResult result = service.createController()
                    .addGroup(requests)
                    .execute(); // sync

            assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
            serverThread.join(2000);

            assertNull(serverError.get(), () -> "Server error: " + serverError.get());
            assertEquals(reqCount, result.size());
            for (int i = 0; i < reqCount; i++) {
                assertTrue(result.future(i).isDone());
                assertEquals(200 + i, result.future(i).get().statusCode());
            }
        }
    }

    @Test
    void shutdownAfterExecutionTerminatesCleanly() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                    drainHeaders(socket.getInputStream());
                    socket.getOutputStream().write(httpResponse(200, "OK"));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {}
            });
            serverThread.start();

            ExecutionResult result = service.createController()
                    .activateAsync()
                    .threadCount(1)
                    .addGroup(List.of(Request.newBuilder().path("/shutdown").build()))
                    .execute();

            result.future(0).get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
            serverThread.join(2000);

            service.shutdown();
            assertTrue(service.isShutdown());
            assertTrue(service.awaitTermination(AWAIT_TIMEOUT, TimeUnit.SECONDS));
        }
    }

    @Test
    void syncExecutionReusesConnectionForSameClient() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(AWAIT_TIMEOUT * 1000);

            AtomicInteger acceptCount = new AtomicInteger(0);
            CountDownLatch serverDone = new CountDownLatch(1);
            AtomicReference<Throwable> serverError = new AtomicReference<>();

            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    acceptCount.incrementAndGet();
                    socket.setSoTimeout(AWAIT_TIMEOUT * 1000);

                    drainHeaders(socket.getInputStream());
                    socket.getOutputStream().write(httpResponse(200, "first"));
                    socket.getOutputStream().flush();

                    drainHeaders(socket.getInputStream());
                    socket.getOutputStream().write(httpResponse(200, "second"));
                    socket.getOutputStream().flush();
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            HttpClient sharedClient = createClient(server.getLocalPort());
            try (ClientService service = new ClientService(() -> sharedClient)) {

                ExecutionResult r1 = service.createController()
                        .addGroup(List.of(Request.newBuilder().path("/first").build()))
                        .execute();
                assertEquals(200, r1.future(0).get().statusCode());

                ExecutionResult r2 = service.createController()
                        .addGroup(List.of(Request.newBuilder().path("/second").build()))
                        .execute();
                assertEquals(200, r2.future(0).get().statusCode());

                assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
                serverThread.join(2000);
                assertNull(serverError.get(), () -> "Server error: " + serverError.get());

                assertEquals(1, acceptCount.get(),
                        "HTTP/1.1 persistent connection: only one TCP accept() expected");
            }

            sharedClient.close();
        }
    }
}
