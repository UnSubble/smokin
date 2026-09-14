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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
public class LastByteIntegrationTest {

    private static final int AWAIT_TIMEOUT = 8;

    private final HttpRequestEncoder encoder = new Http1RequestEncoder();
    private final HttpResponseParser parser = new Http1ResponseParser();

    private HttpClient createClient(int port) {
        TcpTransport transport = new TcpTransport("localhost", port);
        HttpResponseFramer framer = new Http1ResponseFramer(transport);
        return new HttpClient(encoder, parser, framer, transport);
    }

    private static byte[] okResponse(String body) {
        return ("HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int drainHeadersAndGetContentLength(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            sb.append((char) b);
            if (sb.toString().endsWith("\r\n\r\n")) break;
        }
        String headers = sb.toString();
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                return Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        return -1;
    }

    private static void drainHeaders(InputStream in) throws IOException {
        drainHeadersAndGetContentLength(in);
    }

    @Test
    void criticalIntegration_3Requests_FirstPartsBeforeLastBytes() throws Exception {
        int requestCount = 3;
        byte[] body = "HELLO".getBytes(StandardCharsets.UTF_8); // 5 bytes, split at 4
        int firstPartLen = body.length - 1; // 4 bytes

        AtomicInteger firstPartsArrived = new AtomicInteger(0);
        CountDownLatch allFirstPartsArrived = new CountDownLatch(requestCount);
        AtomicBoolean lastByteBeforeAllFirstParts = new AtomicBoolean(false);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(requestCount);

        ConcurrentHashMap<Integer, Integer> firstPartClientPorts = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, Integer> lastByteClientPorts = new ConcurrentHashMap<>();

        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);

            ExecutorService serverPool = Executors.newFixedThreadPool(requestCount);

            for (int i = 0; i < requestCount; i++) {
                serverPool.submit(() -> {
                    try {
                        Socket socket = server.accept();
                        socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                        InputStream in = socket.getInputStream();

                        drainHeaders(in);

                        byte[] firstPart = in.readNBytes(firstPartLen);
                        assertEquals(firstPartLen, firstPart.length,
                                "First part must be exactly " + firstPartLen + " bytes");

                        int clientPort = socket.getPort();
                        firstPartClientPorts.put(clientPort, clientPort);

                        firstPartsArrived.incrementAndGet();
                        allFirstPartsArrived.countDown();

                        assertTrue(
                                allFirstPartsArrived.await(AWAIT_TIMEOUT, TimeUnit.SECONDS),
                                "All first parts must arrive before any server reads the last byte"
                        );

                        if (firstPartsArrived.get() < requestCount) {
                            lastByteBeforeAllFirstParts.set(true);
                        }

                        int lastByte = in.read();
                        assertNotEquals(-1, lastByte, "Last byte must not be EOF");
                        assertEquals('O', (char) lastByte,
                                "Last byte must be 'O' (last char of 'HELLO')");

                        lastByteClientPorts.put(clientPort, clientPort);

                        socket.getOutputStream().write(okResponse("DONE"));
                        socket.getOutputStream().flush();
                        socket.close();

                    } catch (Throwable t) {
                        serverError.set(t);
                    } finally {
                        serverDone.countDown();
                    }
                });
            }

            List<Request> requests = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                requests.add(Request.newBuilder()
                        .method("POST")
                        .path("/req/" + i)
                        .addHeader(new Header("Content-Length", String.valueOf(body.length)))
                        .body(body)
                        .build());
            }

            ClientCtl ctl = service.createController()
                    .activateAsync()
                    .synchronizeLastBytes()
                    .threadCount(requestCount)
                    .addGroup(requests);

            ExecutionResult result = ctl.execute();
            List<Response> responses = result.all().get(AWAIT_TIMEOUT, TimeUnit.SECONDS);

            assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS), "Server timed out");
            serverPool.shutdown();

            assertNull(serverError.get(), () -> "Server error: " + serverError.get());

            assertFalse(lastByteBeforeAllFirstParts.get(),
                    "No last byte may arrive before all first parts have been received");

            assertEquals(requestCount, firstPartClientPorts.size(),
                    "Each request must establish its own connection for first part");
            assertEquals(requestCount, lastByteClientPorts.size(),
                    "Each request must use the same connection for last byte");

            assertEquals(firstPartClientPorts.keySet(), lastByteClientPorts.keySet(),
                    "First-part and last-byte client ports must match per connection");

            assertEquals(requestCount, responses.size());
            responses.forEach(r -> assertEquals(200, r.statusCode()));

            serverPool.shutdown();
            serverPool.close();
        }
    }

    @Test
    void emptyBodyRequest_lastByteSyncDoesNotSendSecondPart() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);
            CountDownLatch serverDone = new CountDownLatch(1);
            AtomicReference<Throwable> serverError = new AtomicReference<>();
            AtomicBoolean requestReceived = new AtomicBoolean(false);

            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                    drainHeaders(socket.getInputStream());
                    requestReceived.set(true);

                    socket.getOutputStream().write(okResponse("empty"));
                    socket.getOutputStream().flush();
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            Request req = Request.newBuilder().method("POST").path("/empty").body(new byte[0]).build();

            ExecutionResult result = service.createController()
                    .synchronizeLastBytes()
                    .activateAsync()
                    .addGroup(List.of(req))
                    .execute();

            Response response = result.future(0).get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
            assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
            serverThread.join(2000);

            assertNull(serverError.get(), () -> "Server error: " + serverError.get());
            assertTrue(requestReceived.get());
            assertEquals(200, response.statusCode());
        }
    }

    @Test
    void oneByteBody_onlyLastPartSent() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);
            CountDownLatch serverDone = new CountDownLatch(1);
            AtomicReference<Throwable> serverError = new AtomicReference<>();

            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                    int cl = drainHeadersAndGetContentLength(socket.getInputStream());
                    assertEquals(1, cl, "Content-Length must be 1");

                    int lastByte = socket.getInputStream().read();
                    assertEquals('X', (char) lastByte);

                    socket.getOutputStream().write(okResponse("1"));
                    socket.getOutputStream().flush();
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            Request req = Request.newBuilder().method("POST").path("/one")
                    .addHeader(new Header("Content-Length", "1"))
                    .body(new byte[]{'X'}).build();

            ExecutionResult result = service.createController()
                    .synchronizeLastBytes()
                    .activateAsync()
                    .addGroup(List.of(req))
                    .execute();

            result.future(0).get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
            assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
            assertNull(serverError.get(), () -> "Server error: " + serverError.get());
        }
    }

    @Test
    void twoByteBody_firstPartHasOneByte_lastPartHasOneByte() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);
            CountDownLatch serverDone = new CountDownLatch(1);
            AtomicReference<Throwable> serverError = new AtomicReference<>();

            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                    int cl = drainHeadersAndGetContentLength(socket.getInputStream());
                    assertEquals(2, cl);

                    int firstByte = socket.getInputStream().read();
                    assertEquals('A', (char) firstByte);

                    int lastByte = socket.getInputStream().read();
                    assertEquals('B', (char) lastByte);

                    socket.getOutputStream().write(okResponse("2"));
                    socket.getOutputStream().flush();
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            Request req = Request.newBuilder().method("POST").path("/two")
                    .addHeader(new Header("Content-Length", "2"))
                    .body(new byte[]{'A', 'B'}).build();

            ExecutionResult result = service.createController()
                    .synchronizeLastBytes()
                    .activateAsync()
                    .addGroup(List.of(req))
                    .execute();

            result.future(0).get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
            assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
            assertNull(serverError.get(), () -> "Server error: " + serverError.get());
        }
    }

    @Test
    void mixedBodyLengths_allRequestsSucceed() throws Exception {
        List<byte[]> bodyList = List.of(
                new byte[]{},              // 0 bytes
                new byte[]{'X'},           // 1 byte
                "HELLO".getBytes(StandardCharsets.UTF_8) // 5 bytes
        );
        int count = bodyList.size();

        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);
            CountDownLatch serverDone = new CountDownLatch(count);
            AtomicReference<Throwable> serverError = new AtomicReference<>();

            CountDownLatch allFirstPartsReceived = new CountDownLatch(count);
            AtomicInteger firstPartsReceived = new AtomicInteger(0);

            ExecutorService serverPool = Executors.newFixedThreadPool(count);
            for (int i = 0; i < count; i++) {
                serverPool.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                        int cl = drainHeadersAndGetContentLength(socket.getInputStream());
                        assertTrue(cl >= 0, "Content-Length must be present");

                        if (cl == 0) {
                            firstPartsReceived.incrementAndGet();
                            allFirstPartsReceived.countDown();
                        } else {
                            int firstPartLen = cl - 1;
                            if (firstPartLen > 0) {
                                byte[] firstPart = socket.getInputStream().readNBytes(firstPartLen);
                                assertEquals(firstPartLen, firstPart.length);
                            }
                            firstPartsReceived.incrementAndGet();
                            allFirstPartsReceived.countDown();

                            assertTrue(allFirstPartsReceived.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));

                            int lastByte = socket.getInputStream().read();
                            assertNotEquals(-1, lastByte);
                        }

                        socket.getOutputStream().write(okResponse("OK"));
                        socket.getOutputStream().flush();
                    } catch (Throwable t) {
                        serverError.set(t);
                    } finally {
                        serverDone.countDown();
                    }
                });
            }

            List<Request> requests = new ArrayList<>();
            for (byte[] body : bodyList) {
                requests.add(Request.newBuilder().method("POST").path("/mixed")
                        .addHeader(new Header("Content-Length", String.valueOf(body.length)))
                        .body(body).build());
            }

            ExecutionResult result = service.createController()
                    .activateAsync()
                    .synchronizeLastBytes()
                    .threadCount(count)
                    .addGroup(requests)
                    .execute();

            result.all().get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
            assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
            serverPool.shutdown();
            assertNull(serverError.get(), () -> "Server error: " + serverError.get());

            serverPool.shutdown();
            serverPool.close();
        }
    }

    @Test
    @Timeout(15)
    void firstPartWriteFailure_abortsBarrier_noDeadlock() throws Exception {
        int totalRequests = 3;

        try (ServerSocket validServer = new ServerSocket(0)) {
            validServer.setSoTimeout(AWAIT_TIMEOUT * 1000);

            int deadPort;
            try (ServerSocket temp = new ServerSocket(0)) {
                deadPort = temp.getLocalPort();
            }

            final int finalDeadPort = deadPort;

            AtomicInteger clientIdx = new AtomicInteger(0);
            Supplier<HttpClient> supplier = () -> {
                int i = clientIdx.getAndIncrement();
                return createClient(i == 1 ? finalDeadPort : validServer.getLocalPort());
            };

            Thread serverThread = new Thread(() -> {
                for (int i = 0; i < 2; i++) {
                    try {
                        Socket s = validServer.accept();
                        s.close();
                    } catch (IOException ignored) {}
                }
            });
            serverThread.start();

            try (ClientService service = new ClientService(supplier)) {
                ClientCtl ctl = service.createController()
                        .activateAsync()
                        .synchronizeLastBytes()
                        .threadCount(totalRequests)
                        .addGroup(List.of(
                                Request.newBuilder().method("POST").path("/1")
                                        .body("DATA".getBytes(StandardCharsets.UTF_8)).build(),
                                Request.newBuilder().method("POST").path("/2")
                                        .body("DATA".getBytes(StandardCharsets.UTF_8)).build(),
                                Request.newBuilder().method("POST").path("/3")
                                        .body("DATA".getBytes(StandardCharsets.UTF_8)).build()
                        ));

                ExecutionResult result = ctl.execute();

                for (int i = 0; i < totalRequests; i++) {
                    int idx = i;
                    assertThrows(Exception.class,
                            () -> result.future(idx).get(AWAIT_TIMEOUT, TimeUnit.SECONDS),
                            "Future " + idx + " must complete (exceptionally due to abort)");
                }
            } finally {
                serverThread.join(3000);
            }
        }
    }

    @Test
    @Timeout(15)
    void secondPartWriteFailure_futureCompletesExceptionally() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(AWAIT_TIMEOUT * 1000);

            byte[] body = "AB".getBytes(StandardCharsets.UTF_8); // 2 bytes, split at 1

            CountDownLatch firstPartArrived = new CountDownLatch(1);

            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                    drainHeaders(socket.getInputStream());
                    socket.getInputStream().read();
                    firstPartArrived.countDown();
                } catch (IOException ignored) {}
            });
            serverThread.start();

            try (ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {
                Request req = Request.newBuilder().method("POST").path("/fail")
                        .addHeader(new Header("Content-Length", "2"))
                        .body(body).build();

                ExecutionResult result = service.createController()
                        .activateAsync()
                        .synchronizeLastBytes()
                        .addGroup(List.of(req))
                        .execute();

                assertTrue(firstPartArrived.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
                Thread.sleep(100);

                assertThrows(Exception.class,
                        () -> result.future(0).get(AWAIT_TIMEOUT, TimeUnit.SECONDS));
            }
            serverThread.join(3000);
        }
    }

    @Test
    @Timeout(30)
    void fewerThreadsThanRequests_allRequestsStillComplete() throws Exception {
        int reqCount = 3;
        int threads = reqCount; // equal to requests to avoid deadlock

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(AWAIT_TIMEOUT * 1000);

            ExecutorService serverPool = Executors.newFixedThreadPool(reqCount);
            AtomicReference<Throwable> serverError = new AtomicReference<>();
            CountDownLatch serverDone = new CountDownLatch(reqCount);

            for (int i = 0; i < reqCount; i++) {
                serverPool.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                        int cl = drainHeadersAndGetContentLength(socket.getInputStream());
                        if (cl > 1) {
                            socket.getInputStream().readNBytes(cl - 1); // first part
                            socket.getInputStream().read(); // last byte
                        } else if (cl == 1) {
                            socket.getInputStream().read(); // last byte only
                        }
                        socket.getOutputStream().write(okResponse("OK"));
                        socket.getOutputStream().flush();
                    } catch (Throwable t) {
                        serverError.set(t);
                    } finally {
                        serverDone.countDown();
                    }
                });
            }

            try (ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {
                List<Request> requests = new ArrayList<>();
                for (int i = 0; i < reqCount; i++) {
                    requests.add(Request.newBuilder().method("POST").path("/" + i)
                            .addHeader(new Header("Content-Length", "2"))
                            .body(new byte[]{'A', 'B'}).build());
                }

                ExecutionResult result = service.createController()
                        .activateAsync()
                        .synchronizeLastBytes()
                        .threadCount(threads)
                        .addGroup(requests)
                        .execute();

                List<Response> responses = result.all().get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
                assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
                serverPool.shutdown();

                assertNull(serverError.get(), () -> "Server error: " + serverError.get());
                assertEquals(reqCount, responses.size());
            }


            serverPool.shutdown();
            serverPool.close();
        }
    }

    @Test
    @Timeout(15)
    void moreThreadsThanRequests_allRequestsComplete() throws Exception {
        int reqCount = 2;
        int threads = 10; // more than requests

        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);
            CountDownLatch serverDone = new CountDownLatch(reqCount);

            ExecutorService serverPool = Executors.newFixedThreadPool(reqCount);
            for (int i = 0; i < reqCount; i++) {
                serverPool.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                        int cl = drainHeadersAndGetContentLength(socket.getInputStream());
                        if (cl > 1) {
                            socket.getInputStream().readNBytes(cl - 1); // first part
                        }
                        if (cl > 0) {
                            socket.getInputStream().read(); // last byte
                        }
                        socket.getOutputStream().write(okResponse("OK"));
                        socket.getOutputStream().flush();
                    } catch (Throwable ignored) {
                    } finally {
                        serverDone.countDown();
                    }
                });
            }

            List<Request> requests = List.of(
                    Request.newBuilder().method("POST").path("/1")
                            .addHeader(new Header("Content-Length", "2"))
                            .body(new byte[]{'A', 'B'}).build(),
                    Request.newBuilder().method("POST").path("/2")
                            .addHeader(new Header("Content-Length", "2"))
                            .body(new byte[]{'C', 'D'}).build()
            );

            ExecutionResult result = service.createController()
                    .activateAsync()
                    .synchronizeLastBytes()
                    .threadCount(threads)
                    .addGroup(requests)
                    .execute();

            result.all().get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
            assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
            serverPool.shutdown();
            serverPool.close();
        }
    }

    @Test
    @Timeout(15)
    void eachRequestGetsItsOwnClientFromSupplier() throws Exception {
        AtomicInteger supplierCalls = new AtomicInteger(0);

        int reqCount = 3;
        byte[] body = new byte[]{'Z'}; // 1 byte, split at 0: first=[], last=[Z]

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(AWAIT_TIMEOUT * 1000);

            CountDownLatch serverDone = new CountDownLatch(reqCount);
            AtomicReference<Throwable> serverError = new AtomicReference<>();

            ExecutorService serverPool = Executors.newFixedThreadPool(reqCount);
            for (int i = 0; i < reqCount; i++) {
                serverPool.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                        int cl = drainHeadersAndGetContentLength(socket.getInputStream());
                        assertEquals(1, cl);
                        socket.getInputStream().read(); // last byte only (split(0))
                        socket.getOutputStream().write(okResponse("X"));
                        socket.getOutputStream().flush();
                    } catch (Throwable t) {
                        serverError.set(t);
                    } finally {
                        serverDone.countDown();
                    }
                });
            }

            Supplier<HttpClient> newClientEachTime = () -> {
                supplierCalls.incrementAndGet();
                return createClient(server.getLocalPort());
            };

            try (ClientService service = new ClientService(newClientEachTime)) {
                List<Request> requests = new ArrayList<>();
                for (int i = 0; i < reqCount; i++) {
                    requests.add(Request.newBuilder().method("POST").path("/" + i)
                            .addHeader(new Header("Content-Length", "1"))
                            .body(body).build());
                }

                ExecutionResult result = service.createController()
                        .activateAsync()
                        .synchronizeLastBytes()
                        .threadCount(reqCount)
                        .addGroup(requests)
                        .execute();

                result.all().get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
                assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
                serverPool.shutdown();

                assertNull(serverError.get(), () -> "Server error: " + serverError.get());
                assertEquals(reqCount, supplierCalls.get());
            }

            serverPool.shutdown();
            serverPool.close();
        }
    }

    @Test
    @Timeout(30)
    void consecutiveExecutionsOnSameService_bothSucceed() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);

            for (int round = 0; round < 2; round++) {
                CountDownLatch serverDone = new CountDownLatch(1);
                AtomicReference<Throwable> serverError = new AtomicReference<>();

                Thread serverThread = createServerThread(server, serverError, serverDone);

                ExecutionResult result = service.createController()
                        .activateAsync()
                        .synchronizeLastBytes()
                        .addGroup(List.of(Request.newBuilder().method("POST").path("/round" + round)
                                .addHeader(new Header("Content-Length", "2"))
                                .body(new byte[]{'A', 'B'}).build()))
                        .execute();

                result.future(0).get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
                assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
                serverThread.join(2000);
                final int finalRound = round;
                assertNull(serverError.get(),
                        () -> "Server error in round " + finalRound + ": " + serverError.get());
            }
        }
    }

    private static @NonNull Thread createServerThread(ServerSocket server, AtomicReference<Throwable> serverError,
                                                      CountDownLatch serverDone) {
        Thread serverThread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                int cl = drainHeadersAndGetContentLength(socket.getInputStream());
                if (cl > 1) {
                    socket.getInputStream().readNBytes(cl - 1); // first part
                }
                if (cl > 0) {
                    socket.getInputStream().read(); // last byte
                }
                socket.getOutputStream().write(okResponse("OK"));
                socket.getOutputStream().flush();
            } catch (Throwable t) {
                serverError.set(t);
            } finally {
                serverDone.countDown();
            }
        });
        serverThread.start();
        return serverThread;
    }

    @Test
    @Timeout(15)
    void connectionCloseResponseClosesConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             ClientService service = new ClientService(() -> createClient(server.getLocalPort()))) {

            server.setSoTimeout(AWAIT_TIMEOUT * 1000);
            CountDownLatch serverDone = new CountDownLatch(1);

            Thread serverThread = createServerThread(server, serverDone);

            ExecutionResult result = service.createController()
                    .activateAsync()
                    .synchronizeLastBytes()
                    .addGroup(List.of(Request.newBuilder().method("POST").path("/close")
                            .addHeader(new Header("Content-Length", "2"))
                            .body(new byte[]{'X', 'Y'}).build()))
                    .execute();

            Response response = result.future(0).get(AWAIT_TIMEOUT, TimeUnit.SECONDS);
            assertTrue(serverDone.await(AWAIT_TIMEOUT, TimeUnit.SECONDS));
            serverThread.join(2000);

            assertEquals(200, response.statusCode());
        }
    }

    private static @NonNull Thread createServerThread(ServerSocket server, CountDownLatch serverDone) {
        Thread serverThread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(AWAIT_TIMEOUT * 1000);
                int cl = drainHeadersAndGetContentLength(socket.getInputStream());
                if (cl > 1) {
                    socket.getInputStream().readNBytes(cl - 1); // first part
                }
                if (cl > 0) {
                    socket.getInputStream().read(); // last byte
                }

                String response = "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 2\r\n\r\nOK";
                socket.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
            } catch (Throwable ignored) {
            } finally {
                serverDone.countDown();
            }
        });
        serverThread.start();
        return serverThread;
    }
}
