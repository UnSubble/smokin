package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClientTest {

    @Test
    void autoCloseableContractIsSatisfied() throws Exception {
        AtomicBoolean closed = new AtomicBoolean(false);

        Client client = new Client() {
            @Override
            public void connect() {}

            @Override
            public Response send(Request request) {
                return Response.newBuilder().statusCode(200).build();
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        try (client) {
            assertFalse(closed.get(), "Client should not be closed inside try block");
        }

        assertTrue(closed.get(), "Client must be closed upon exiting try-with-resources block");
    }

    @Test
    void ioExceptionsPropagateCorrectly() {
        Client throwingClient = new Client() {
            @Override
            public void connect() throws IOException {
                throw new IOException("Connection failed");
            }

            @Override
            public Response send(Request request) throws IOException {
                throw new IOException("Send failed");
            }

            @Override
            public void close() throws IOException {
                throw new IOException("Close failed");
            }
        };

        assertThrows(IOException.class, throwingClient::connect, "connect() must throw IOException");
        assertThrows(IOException.class, () -> throwingClient.send(Request.newBuilder().build()),
                "send() must throw IOException");
        assertThrows(IOException.class, throwingClient::close, "close() must throw IOException");
    }

    @Test
    void supportsSequentialHttp1StyleExecution() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        AtomicBoolean connected = new AtomicBoolean(false);

        Client http1Client = new Client() {
            @Override
            public void connect() {
                connected.set(true);
            }

            @Override
            public Response send(Request request) {
                requestCount.incrementAndGet();
                return Response.newBuilder()
                        .statusCode(200)
                        .body("HTTP/1.1 Response".getBytes())
                        .build();
            }

            @Override
            public void close() {
                connected.set(false);
            }
        };

        http1Client.connect();
        assertTrue(connected.get());

        Response response = http1Client.send(Request.newBuilder().path("/test").build());
        assertNotNull(response);
        assertEquals(200, response.statusCode());
        assertEquals("HTTP/1.1 Response", new String(response.body()));
        assertEquals(1, requestCount.get());

        http1Client.close();
        assertFalse(connected.get());
    }

    @Test
    void supportsMultiplexedHttp2StyleExecution() throws Exception {
        AtomicInteger activeStreams = new AtomicInteger(0);
        AtomicBoolean sessionActive = new AtomicBoolean(false);

        Client http2Client = new Client() {
            @Override
            public void connect() {
                sessionActive.set(true);
            }

            @Override
            public Response send(Request request) {
                int streamId = activeStreams.incrementAndGet();
                return Response.newBuilder()
                        .statusCode(200)
                        .body(("HTTP/2 Stream " + streamId + " Response").getBytes())
                        .build();
            }

            @Override
            public void close() {
                sessionActive.set(false);
                activeStreams.set(0);
            }
        };

        http2Client.connect();
        assertTrue(sessionActive.get());

        Request req1 = Request.newBuilder().path("/stream1").build();
        Request req2 = Request.newBuilder().path("/stream2").build();

        Response res1 = http2Client.send(req1);
        Response res2 = http2Client.send(req2);

        assertEquals("HTTP/2 Stream 1 Response", new String(res1.body()));
        assertEquals("HTTP/2 Stream 2 Response", new String(res2.body()));
        assertEquals(2, activeStreams.get());

        http2Client.close();
        assertFalse(sessionActive.get());
    }

    @Test
    void defaultSendWithCoordinatorWorks() throws Exception {
        AtomicBoolean sendCalled = new AtomicBoolean(false);

        Client client = new Client() {
            @Override
            public void connect() {}

            @Override
            public Response send(Request request) {
                sendCalled.set(true);
                return Response.newBuilder().statusCode(200).build();
            }

            @Override
            public void close() {}
        };

        Response r1 = client.send(Request.newBuilder().build(), null);
        assertNotNull(r1);
        assertTrue(sendCalled.get());

        sendCalled.set(false);
        LastByteCoordinator coordinator = new LastByteCoordinator(1);
        Response r2 = client.send(Request.newBuilder().build(), coordinator);
        assertNotNull(r2);
        assertTrue(sendCalled.get());
        assertEquals(1, coordinator.getArrived());
    }
}
