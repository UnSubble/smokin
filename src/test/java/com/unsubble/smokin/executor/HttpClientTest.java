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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class HttpClientTest {

    private static final int TIMEOUT_SECONDS = 5;

    private final HttpRequestEncoder encoder = new Http1RequestEncoder();
    private final HttpResponseParser parser = new Http1ResponseParser();

    @Test
    public void testSendGetRequestAndReceiveOkResponse() throws Exception {
        Request request = Request.newBuilder()
                .method("GET")
                .path("/index.html")
                .version("HTTP/1.1")
                .addHeader(new Header("Host", "localhost"))
                .addHeader(new Header("Accept", "text/plain"))
                .build();

        byte[] expectedRequestBytes = encoder.encode(request);

        String httpResponseStr = """
                HTTP/1.1 200 OK\r
                Content-Type: text/plain\r
                Content-Length: 13\r
                Server: smokin-test\r
                \r
                Hello Client!""";
        byte[] responseBytes = httpResponseStr.getBytes(StandardCharsets.ISO_8859_1);

        AtomicReference<byte[]> receivedRequestRef = new AtomicReference<>();
        AtomicReference<Throwable> serverErrorRef = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());
            HttpResponseFramer framer = new Http1ResponseFramer(transport);
            HttpClient client = new HttpClient(encoder, parser, framer, transport);

            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    byte[] actualRequest = socket.getInputStream().readNBytes(expectedRequestBytes.length);
                    receivedRequestRef.set(actualRequest);

                    socket.getOutputStream().write(responseBytes);
                    socket.getOutputStream().flush();
                    socket.shutdownOutput();
                } catch (Throwable t) {
                    serverErrorRef.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            Response response;
            try {
                response = client.send(request);
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server timed out");
            serverThread.join(1000);

            assertNull(serverErrorRef.get(), () -> "Server encountered error: " + serverErrorRef.get());
            assertArrayEquals(expectedRequestBytes, receivedRequestRef.get(),
                    "Request bytes received by server mismatch");

            assertNotNull(response);
            assertEquals("HTTP/1.1", response.version());
            assertEquals(200, response.statusCode());
            assertEquals("OK", response.reasonPhrase());
            assertEquals(3, response.headers().size());
            assertEquals(new Header("Content-Type", "text/plain"), response.headers().get(0));
            assertEquals(new Header("Content-Length", "13"), response.headers().get(1));
            assertEquals(new Header("Server", "smokin-test"), response.headers().get(2));
            assertArrayEquals("Hello Client!".getBytes(StandardCharsets.ISO_8859_1), response.body());
        }
    }

    @Test
    public void testSendPostRequestWithBodyAndReceiveCreatedResponse() throws Exception {
        byte[] requestBody = "{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8);

        Request request = Request.newBuilder()
                .method("POST")
                .path("/api/messages")
                .version("HTTP/1.1")
                .addHeader(new Header("Host", "localhost"))
                .addHeader(new Header("Content-Type", "application/json"))
                .addHeader(new Header("Content-Length", String.valueOf(requestBody.length)))
                .body(requestBody)
                .build();

        byte[] expectedRequestBytes = encoder.encode(request);

        byte[] responseBody = "{\"status\":\"saved\"}".getBytes(StandardCharsets.ISO_8859_1);
        String httpResponseStr = "HTTP/1.1 201 Created\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + responseBody.length + "\r\n"
                + "\r\n"
                + "{\"status\":\"saved\"}";
        byte[] responseBytes = httpResponseStr.getBytes(StandardCharsets.ISO_8859_1);

        AtomicReference<byte[]> receivedRequestRef = new AtomicReference<>();
        AtomicReference<Throwable> serverErrorRef = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());
            HttpResponseFramer framer = new Http1ResponseFramer(transport);
            HttpClient client = new HttpClient(encoder, parser, framer, transport);

            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    byte[] actualRequest = socket.getInputStream().readNBytes(expectedRequestBytes.length);
                    receivedRequestRef.set(actualRequest);

                    socket.getOutputStream().write(responseBytes);
                    socket.getOutputStream().flush();
                    socket.shutdownOutput();
                } catch (Throwable t) {
                    serverErrorRef.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            Response response;
            try {
                response = client.send(request);
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server timed out");
            serverThread.join(1000);

            assertNull(serverErrorRef.get(), () -> "Server encountered error: " + serverErrorRef.get());
            assertArrayEquals(expectedRequestBytes, receivedRequestRef.get(),
                    "Request bytes received by server mismatch");

            assertNotNull(response);
            assertEquals("HTTP/1.1", response.version());
            assertEquals(201, response.statusCode());
            assertEquals("Created", response.reasonPhrase());
            assertEquals(new Header("Content-Type", "application/json"), response.headers().get(0));
            assertEquals(new Header("Content-Length", String.valueOf(responseBody.length)),
                    response.headers().get(1));
            assertArrayEquals(responseBody, response.body());
        }
    }

    @Test
    public void testReceiveBinaryResponseBody() throws Exception {
        Request request = Request.newBuilder()
                .method("GET")
                .path("/download/binary")
                .version("HTTP/1.1")
                .addHeader(new Header("Host", "localhost"))
                .build();

        byte[] expectedRequestBytes = encoder.encode(request);

        byte[] binaryResponseBody = new byte[] {
                0x00, (byte) 0xFF, 0x01, 0x02, (byte) 0x7F, (byte) 0x80, (byte) 0xFE,
                (byte) 0xAA, 0x55, 0x00, (byte) 0xFF
        };

        byte[] headers = ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                + binaryResponseBody.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);

        byte[] responseBytes = new byte[headers.length + binaryResponseBody.length];
        System.arraycopy(headers, 0, responseBytes, 0, headers.length);
        System.arraycopy(binaryResponseBody, 0, responseBytes, headers.length, binaryResponseBody.length);

        AtomicReference<byte[]> receivedRequestRef = new AtomicReference<>();
        AtomicReference<Throwable> serverErrorRef = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());
            HttpResponseFramer framer = new Http1ResponseFramer(transport);
            HttpClient client = new HttpClient(encoder, parser, framer, transport);

            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    byte[] actualRequest = socket.getInputStream().readNBytes(expectedRequestBytes.length);
                    receivedRequestRef.set(actualRequest);

                    socket.getOutputStream().write(responseBytes);
                    socket.getOutputStream().flush();
                    socket.shutdownOutput();
                } catch (Throwable t) {
                    serverErrorRef.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            Response response;
            try {
                response = client.send(request);
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server timed out");
            serverThread.join(1000);

            assertNull(serverErrorRef.get(), () -> "Server encountered error: " + serverErrorRef.get());
            assertArrayEquals(expectedRequestBytes, receivedRequestRef.get());

            assertNotNull(response);
            assertEquals(200, response.statusCode());
            assertArrayEquals(binaryResponseBody, response.body(), "Binary body received by client mismatch");
        }
    }

    @Test
    public void testSendMultipleRequestsSequentially() throws Exception {
        Request req1 = Request.newBuilder()
                .method("GET")
                .path("/first")
                .version("HTTP/1.1")
                .addHeader(new Header("Host", "localhost"))
                .build();

        Request req2 = Request.newBuilder()
                .method("GET")
                .path("/second")
                .version("HTTP/1.1")
                .addHeader(new Header("Host", "localhost"))
                .build();

        byte[] expectedReq1 = encoder.encode(req1);
        byte[] expectedReq2 = encoder.encode(req2);

        byte[] resp1 = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nfirst".getBytes(StandardCharsets.ISO_8859_1);
        byte[] resp2 = "HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nsecond".getBytes(StandardCharsets.ISO_8859_1);

        AtomicReference<byte[]> recReq1 = new AtomicReference<>();
        AtomicReference<byte[]> recReq2 = new AtomicReference<>();
        AtomicReference<Throwable> serverErrorRef = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());
            HttpResponseFramer framer = new Http1ResponseFramer(transport);
            HttpClient client = new HttpClient(encoder, parser, framer, transport);

            Thread serverThread = new Thread(() -> {
                try {
                    try (Socket socket1 = server.accept()) {
                        socket1.setSoTimeout(TIMEOUT_SECONDS * 1000);
                        recReq1.set(socket1.getInputStream().readNBytes(expectedReq1.length));
                        socket1.getOutputStream().write(resp1);
                        socket1.getOutputStream().flush();
                        socket1.shutdownOutput();
                    }

                    try (Socket socket2 = server.accept()) {
                        socket2.setSoTimeout(TIMEOUT_SECONDS * 1000);
                        recReq2.set(socket2.getInputStream().readNBytes(expectedReq2.length));
                        socket2.getOutputStream().write(resp2);
                        socket2.getOutputStream().flush();
                        socket2.shutdownOutput();
                    }
                } catch (Throwable t) {
                    serverErrorRef.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            Response r1;
            Response r2;
            try {
                r1 = client.send(req1);
                r2 = client.send(req2);
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server timed out");
            serverThread.join(1000);

            assertNull(serverErrorRef.get(), () -> "Server encountered error: " + serverErrorRef.get());
            assertArrayEquals(expectedReq1, recReq1.get());
            assertArrayEquals(expectedReq2, recReq2.get());

            assertArrayEquals("first".getBytes(StandardCharsets.ISO_8859_1), r1.body());
            assertArrayEquals("second".getBytes(StandardCharsets.ISO_8859_1), r2.body());
        }
    }

    @Test
    public void testSendWhenServerUnreachableThrowsIOException() throws Exception {
        int unusedPort;
        try (ServerSocket tempServer = new ServerSocket(0)) {
            unusedPort = tempServer.getLocalPort();
        }

        TcpTransport transport = new TcpTransport("localhost", unusedPort);
        HttpResponseFramer framer = new Http1ResponseFramer(transport);
        HttpClient client = new HttpClient(encoder, parser, framer, transport);

        Request request = Request.newBuilder()
                .method("GET")
                .path("/")
                .version("HTTP/1.1")
                .addHeader(new Header("Host", "localhost"))
                .build();

        assertThrows(IOException.class, () -> client.send(request));
    }

    @Test
    public void testSendWhenServerReturnsMalformedResponseThrowsIOException() throws Exception {
        Request request = Request.newBuilder()
                .method("GET")
                .path("/")
                .version("HTTP/1.1")
                .addHeader(new Header("Host", "localhost"))
                .build();

        byte[] expectedRequest = encoder.encode(request);
        byte[] malformedResponse = "NOT_AN_HTTP_RESPONSE\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

        AtomicReference<Throwable> serverErrorRef = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());
            HttpResponseFramer framer = new Http1ResponseFramer(transport);
            HttpClient client = new HttpClient(encoder, parser, framer, transport);

            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    socket.getInputStream().readNBytes(expectedRequest.length);

                    socket.getOutputStream().write(malformedResponse);
                    socket.getOutputStream().flush();
                    socket.shutdownOutput();
                } catch (Throwable t) {
                    serverErrorRef.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            try {
                assertThrows(IOException.class, () -> client.send(request));
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server timed out");
            serverThread.join(1000);

            assertNull(serverErrorRef.get(), () -> "Server encountered error: " + serverErrorRef.get());
        }
    }
}
