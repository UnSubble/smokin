package com.unsubble.smokin.executor;

import com.unsubble.smokin.encoder.Http1RequestEncoder;
import com.unsubble.smokin.encoder.HttpRequestEncoder;
import com.unsubble.smokin.model.Header;
import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.Response;
import com.unsubble.smokin.parser.Http1ResponseFramer;
import com.unsubble.smokin.parser.Http1ResponseParser;
import com.unsubble.smokin.parser.HttpResponseFramer;
import com.unsubble.smokin.parser.HttpResponseParser;
import com.unsubble.smokin.transport.TcpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class ClientServiceTest {

    private static final int TIMEOUT_SECONDS = 5;

    private final HttpRequestEncoder encoder = new Http1RequestEncoder();
    private final HttpResponseParser parser = new Http1ResponseParser();

    private HttpClient createClient(int port) {
        TcpTransport transport = new TcpTransport("localhost", port);
        HttpResponseFramer framer = new Http1ResponseFramer(transport);
        return new HttpClient(encoder, parser, framer, transport);
    }

    @Test
    public void testExecutorServiceLifecycleManagedByClientService() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(TIMEOUT_SECONDS * 1000);

            assertNull(service.getExecutorService());

            ClientCtl ctl = service.createController()
                    .activateAsync()
                    .threadCount(4)
                    .addGroup(List.of(Request.newBuilder().path("/test").build()));

            Thread serverThread = createServerThread(server);

            ExecutionResult result = ctl.execute();
            assertNotNull(service.getExecutorService(),
                    "ClientService should own and create the ExecutorService");
            assertFalse(service.isShutdown(), "Executor should be active");

            Response resp = result.future(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(200, resp.statusCode());

            service.shutdown();
            assertTrue(service.isShutdown());
            assertTrue(service.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            serverThread.join(1000);
        }
    }

    private Thread createServerThread(ServerSocket server) {
        Thread serverThread = new Thread(() -> {
            try (Socket s = server.accept()) {
                s.getInputStream().readNBytes(encoder.encode(Request.newBuilder().path("/test").build()).length);
                s.getOutputStream().write("""
                        HTTP/1.1 200 OK\r
                        Content-Length: 2\r
                        \r
                        OK""".getBytes(StandardCharsets.ISO_8859_1));
                s.getOutputStream().flush();
            } catch (IOException ignored) {}
        });
        serverThread.start();
        return serverThread;
    }

    @Test
    public void testAsyncExecutionWithBoundedConcurrency() throws Exception {
        int boundedThreads = 2;
        int totalRequests = 6;

        AtomicInteger activeCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch finishLatch = new CountDownLatch(totalRequests);

        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService()) {

            server.setSoTimeout(TIMEOUT_SECONDS * 1000);

            Supplier<HttpClient> monitoringSupplier = () -> {
                TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());
                HttpResponseFramer framer = new Http1ResponseFramer(transport);
                return new HttpClient(encoder, parser, framer, transport) {
                    @Override
                    public Response send(Request request) throws IOException {
                        int current = activeCount.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, current));
                        try {
                            Thread.sleep(60); // Hold thread briefly to test concurrency limit
                            return super.send(request);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        } finally {
                            activeCount.decrementAndGet();
                            finishLatch.countDown();
                        }
                    }
                };
            };

            Thread serverThread = createServerThread(totalRequests, server);

            List<Request> requests = new ArrayList<>();
            for (int i = 0; i < totalRequests; i++) {
                requests.add(Request.newBuilder().path("/item/" + i).build());
            }

            ClientCtl ctl = service.createController()
                    .clientSupplier(monitoringSupplier)
                    .activateAsync()
                    .threadCount(boundedThreads)
                    .addGroup(requests);

            ExecutionResult result = ctl.execute();
            List<Response> responses = result.joinAll();

            assertEquals(totalRequests, responses.size());
            assertTrue(maxConcurrent.get() <= boundedThreads,
                    "Max concurrent tasks (" + maxConcurrent.get() +
                            ") must not exceed bounded pool size (" + boundedThreads + ")"
            );

            serverThread.join(1000);
        }
    }

    private static Thread createServerThread(int totalRequests, ServerSocket server) {
        Thread serverThread = new Thread(() -> {
            for (int i = 0; i < totalRequests; i++) {
                try {
                    Socket socket = server.accept();
                    socket.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    byte[] buffer = new byte[1024];
                    int read = socket.getInputStream().read(buffer);
                    if (read > 0) {
                        socket.getOutputStream().write("""
                                HTTP/1.1 200 OK\r
                                Content-Length: 2\r
                                \r
                                OK""".getBytes(StandardCharsets.ISO_8859_1));
                        socket.getOutputStream().flush();
                    }
                    socket.close();
                } catch (IOException ignored) {}
            }
        });
        serverThread.start();
        return serverThread;
    }

    @Test
    public void testAsyncExceptionsPropagatedToCaller() throws Exception {
        int unusedPort;
        try (ServerSocket temp = new ServerSocket(0)) {
            unusedPort = temp.getLocalPort();
        }

        try (ClientService service = new ClientService(() -> createClient(unusedPort))) {
            ClientCtl ctl = service.createController()
                    .activateAsync()
                    .addGroup(List.of(Request.newBuilder().path("/").build()));

            ExecutionResult result = ctl.execute();
            CompletableFuture<Response> future = result.future(0);

            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof IOException ||
                            ex.getCause() instanceof CompletionException,
                    "Cause should be IO-related: " + ex.getCause());
        }
    }

    @Test
    public void testLastByteExecutionSendsFirstPartsBeforeBarrierAndSecondPartsAfterBarrier() throws Exception {
        int requestCount = 3;

        AtomicInteger firstPartsReceived = new AtomicInteger(0);
        AtomicInteger secondPartsReceived = new AtomicInteger(0);
        AtomicBoolean secondPartBeforeAllFirstParts = new AtomicBoolean(false);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(requestCount);

        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(TIMEOUT_SECONDS * 1000);

            List<Request> requests = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                byte[] body = ("BODY_" + i).getBytes(StandardCharsets.UTF_8); // 6 bytes
                requests.add(Request.newBuilder()
                        .method("POST")
                        .path("/last-byte/" + i)
                        .version("HTTP/1.1")
                        .addHeader(new Header("Host", "localhost"))
                        .addHeader(new Header("Content-Length", String.valueOf(body.length)))
                        .body(body)
                        .build());
            }

            ExecutorService serverExecutor = Executors.newFixedThreadPool(requestCount);
                for (int i = 0; i < requestCount; i++) {
                    serverExecutor.submit(() -> {
                        try {
                            Socket socket = server.accept();
                            socket.setSoTimeout(TIMEOUT_SECONDS * 1000);

                            StringBuilder headers = new StringBuilder();
                            int b;
                            while ((b = socket.getInputStream().read()) != -1) {
                                headers.append((char) b);
                                if (headers.toString().endsWith("\r\n\r\n")) {
                                    break;
                                }
                            }

                            byte[] firstPart = socket.getInputStream().readNBytes(5);
                            firstPartsReceived.incrementAndGet();

                            Thread.sleep(80);

                            if (firstPartsReceived.get() < requestCount && secondPartsReceived.get() > 0) {
                                secondPartBeforeAllFirstParts.set(true);
                            }

                            int lastByte = socket.getInputStream().read();
                            secondPartsReceived.incrementAndGet();

                            assertArrayEquals("BODY_".getBytes(StandardCharsets.ISO_8859_1), firstPart);
                            assertNotEquals(-1, lastByte);

                            if (firstPartsReceived.get() < requestCount) {
                                secondPartBeforeAllFirstParts.set(true);
                            }

                            byte[] resp = """
                                    HTTP/1.1 200 OK\r
                                    Content-Length: 4\r
                                    \r
                                    DONE""".getBytes(StandardCharsets.ISO_8859_1);
                            socket.getOutputStream().write(resp);
                            socket.getOutputStream().flush();
                            socket.close();

                        } catch (Throwable t) {
                            serverError.set(t);
                        } finally {
                            serverDone.countDown();
                        }
                    });

            }

            ClientCtl ctl = service.createController()
                    .activateAsync()
                    .synchronizeLastBytes()
                    .threadCount(requestCount)
                    .addGroup(requests);

            ExecutionResult result = ctl.execute();
            List<Response> responses = result.joinAll();

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server timed out");
            assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());

            assertFalse(secondPartBeforeAllFirstParts.get(),
                    "No second part should be received before all first parts arrive at the barrier");
            assertEquals(requestCount, firstPartsReceived.get());
            assertEquals(requestCount, secondPartsReceived.get());

            assertEquals(requestCount, responses.size());
            for (Response r : responses) {
                assertEquals(200, r.statusCode());
                assertArrayEquals("DONE".getBytes(StandardCharsets.ISO_8859_1), r.body());
            }

            serverExecutor.shutdown();
            serverExecutor.close();
        }
    }

    @Test
    public void testLastByteFailureInFirstPartAbortsBarrierAndAvoidsDeadlock() {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            try (ServerSocket validServer = new ServerSocket(0)) {
                validServer.setSoTimeout(TIMEOUT_SECONDS * 1000);

                int unreachablePort;
                try (ServerSocket temp = new ServerSocket(0)) {
                    unreachablePort = temp.getLocalPort();
                }

                Request req1 = Request.newBuilder()
                        .method("POST")
                        .path("/1")
                        .body("DATA1".getBytes(StandardCharsets.UTF_8))
                        .build();
                Request req2 = Request.newBuilder()
                        .method("POST")
                        .path("/2")
                        .body("DATA2".getBytes(StandardCharsets.UTF_8))
                        .build();
                Request req3 = Request.newBuilder()
                        .method("POST")
                        .path("/3")
                        .body("DATA3".getBytes(StandardCharsets.UTF_8))
                        .build();

                AtomicInteger clientIndex = new AtomicInteger(0);
                Supplier<HttpClient> supplier = () -> {
                    int idx = clientIndex.getAndIncrement();
                    if (idx == 2) {
                        return createClient(unreachablePort);
                    }
                    return createClient(validServer.getLocalPort());
                };

                Thread serverThread = new Thread(() -> {
                    try {
                        Socket s1 = validServer.accept();
                        s1.close();
                    } catch (IOException ignored) {}
                });
                serverThread.start();

                try (ClientService service = new ClientService(supplier)) {
                    ClientCtl ctl = service.createController()
                            .activateAsync()
                            .synchronizeLastBytes()
                            .threadCount(3)
                            .addGroup(List.of(req1, req2, req3));

                    ExecutionResult result = ctl.execute();

                    for (int i = 0; i < 3; i++) {
                        CompletableFuture<Response> f = result.future(i);
                        assertThrows(ExecutionException.class, () -> f.get(3, TimeUnit.SECONDS));
                    }
                } finally {
                    serverThread.join(1000);
                }
            }
        });
    }

    @Test
    public void testSameRequestPartsSentOnSameConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(TIMEOUT_SECONDS * 1000);

            byte[] body = "HELLO_WORLD".getBytes(StandardCharsets.UTF_8); // 11 bytes
            Request req = Request.newBuilder()
                    .method("POST")
                    .path("/check-connection")
                    .version("HTTP/1.1")
                    .addHeader(new Header("Host", "localhost"))
                    .addHeader(new Header("Content-Length", String.valueOf(body.length)))
                    .body(body)
                    .build();

            AtomicInteger clientPortForFirstPart = new AtomicInteger(-1);
            AtomicInteger clientPortForSecondPart = new AtomicInteger(-2);
            AtomicReference<Throwable> serverError = new AtomicReference<>();
            CountDownLatch serverDone = new CountDownLatch(1);

            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(TIMEOUT_SECONDS * 1000);

                    StringBuilder headers = new StringBuilder();
                    int b;
                    while ((b = socket.getInputStream().read()) != -1) {
                        headers.append((char) b);
                        if (headers.toString().endsWith("\r\n\r\n")) {
                            break;
                        }
                    }

                    byte[] firstPart = socket.getInputStream().readNBytes(10);
                    clientPortForFirstPart.set(socket.getPort());

                    int lastByte = socket.getInputStream().read();
                    clientPortForSecondPart.set(socket.getPort());

                    assertArrayEquals("HELLO_WORL".getBytes(StandardCharsets.ISO_8859_1), firstPart);
                    assertEquals('D', lastByte);

                    socket.getOutputStream().write("""
                            HTTP/1.1 200 OK\r
                            Content-Length: 2\r
                            \r
                            OK""".getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            ClientCtl ctl = service.createController()
                    .activateAsync()
                    .synchronizeLastBytes()
                    .addGroup(List.of(req));

            ExecutionResult result = ctl.execute();
            Response response = result.future(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            serverThread.join(1000);

            assertNull(serverError.get());
            assertEquals(200, response.statusCode());

            assertTrue(clientPortForFirstPart.get() > 0, "First part client port should be valid");
            assertEquals(clientPortForFirstPart.get(), clientPortForSecondPart.get(),
                    "Both first part and second part must be transmitted over the EXACT SAME socket connection");
        }
    }

    @Test
    public void testMultipleGroupsExecution() throws Exception {
        int totalRequests = 4;
        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(TIMEOUT_SECONDS * 1000);

            Thread serverThread = new Thread(() -> {
                for (int i = 0; i < totalRequests; i++) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(TIMEOUT_SECONDS * 1000);
                        byte[] buf = new byte[1024];
                        int r = socket.getInputStream().read(buf);
                        if (r > 0) {
                            socket.getOutputStream().write("""
                                    HTTP/1.1 200 OK\r
                                    Content-Length: 2\r
                                    \r
                                    OK""".getBytes(StandardCharsets.ISO_8859_1));
                            socket.getOutputStream().flush();
                        }
                    } catch (IOException ignored) {}
                }
            });
            serverThread.start();

            Request r1 = Request.newBuilder().path("/g1/1").build();
            Request r2 = Request.newBuilder().path("/g1/2").build();
            Request r3 = Request.newBuilder().path("/g2/1").build();
            Request r4 = Request.newBuilder().path("/g2/2").build();

            ClientCtl ctl = service.createController()
                    .activateAsync()
                    .addGroup("group-1", List.of(r1, r2))
                    .addGroup("group-2", List.of(r3, r4));

            ExecutionResult result = ctl.execute();
            List<Response> responses = result.joinAll();

            assertEquals(4, responses.size());
            for (Response resp : responses) {
                assertEquals(200, resp.statusCode());
            }

            serverThread.join(1000);
        }
    }

    @Test
    public void testDefaultAsyncHttpClientSendAsync() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);

            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    socket.getInputStream().readNBytes(encoder.encode(Request.newBuilder()
                            .path("/async")
                            .build()).length);
                    socket.getOutputStream().write("""
                            HTTP/1.1 200 OK\r
                            Content-Length: 5\r
                            \r
                            HELLO""".getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {}
            });
            serverThread.start();

            ExecutorService externalExecutor = Executors.newSingleThreadExecutor();
            try {
                HttpClient httpClient = createClient(server.getLocalPort());
                DefaultAsyncHttpClient asyncClient = new DefaultAsyncHttpClient(httpClient, externalExecutor);

                Request req = Request.newBuilder().path("/async").build();
                CompletableFuture<Response> future = asyncClient.sendAsync(req);

                Response response = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertNotNull(response);
                assertEquals(200, response.statusCode());
                assertArrayEquals("HELLO".getBytes(StandardCharsets.ISO_8859_1), response.body());

                assertFalse(externalExecutor.isShutdown());
            } finally {
                externalExecutor.shutdown();
                serverThread.join(1000);
            }
        }
    }

    @Test
    public void testLastByteReachesBarrierWithoutWaitingForFirstPartResponse() throws Exception {
        int count = 2;
        AtomicInteger firstPartsArrived = new AtomicInteger(0);
        CountDownLatch bothFirstPartsArrived = new CountDownLatch(count);
        CountDownLatch bothSecondPartsArrived = new CountDownLatch(count);

        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(TIMEOUT_SECONDS * 1000);

            ExecutorService serverExecutor = Executors.newFixedThreadPool(count);
            for (int i = 0; i < count; i++) {
                serverExecutor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(TIMEOUT_SECONDS * 1000);

                        StringBuilder sb = new StringBuilder();
                        int b;
                        while ((b = socket.getInputStream().read()) != -1) {
                            sb.append((char) b);
                            if (sb.toString().endsWith("\r\n\r\n")) break;
                        }

                        socket.getInputStream().readNBytes(4);
                        firstPartsArrived.incrementAndGet();
                        bothFirstPartsArrived.countDown();

                        int lastByte = socket.getInputStream().read();
                        if (lastByte != -1) {
                            bothSecondPartsArrived.countDown();
                        }

                        socket.getOutputStream().write("""
                                HTTP/1.1 200 OK\r
                                Content-Length: 2\r
                                \r
                                OK""".getBytes(StandardCharsets.ISO_8859_1));
                        socket.getOutputStream().flush();
                    } catch (IOException ignored) {}
                });
            }

            Request r1 = Request.newBuilder()
                    .method("POST")
                    .path("/1")
                    .body("HELLO".getBytes(StandardCharsets.UTF_8))
                    .build();
            Request r2 = Request.newBuilder()
                    .method("POST")
                    .path("/2")
                    .body("WORLD".getBytes(StandardCharsets.UTF_8))
                    .build();

            ClientCtl ctl = service.createController()
                    .activateAsync()
                    .synchronizeLastBytes()
                    .addGroup(List.of(r1, r2));

            ExecutionResult result = ctl.execute();

            assertTrue(bothFirstPartsArrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(count, firstPartsArrived.get());

            assertTrue(bothSecondPartsArrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            List<Response> responses = result.joinAll();
            assertEquals(count, responses.size());
            for (Response r : responses) {
                assertEquals(200, r.statusCode());
            }

            serverExecutor.shutdown();
            serverExecutor.close();
        }
    }
}
