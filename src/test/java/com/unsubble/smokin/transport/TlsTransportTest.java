package com.unsubble.smokin.transport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class TlsTransportTest {

    private static final int TIMEOUT_SECONDS = 5;

    private static SSLContext serverSslContext;
    private static SSLContext untrustedServerSslContext;
    private static SSLContext originalDefaultContext;

    @BeforeAll
    public static void setUpAll() throws Exception {
        serverSslContext = createSslContext("/test-keystore.p12", "password");
        SSLContext clientSslContext = createSslContext("/test-keystore.p12", "password");
        untrustedServerSslContext = createSslContext("/untrusted-keystore.p12", "password");

        originalDefaultContext = SSLContext.getDefault();
        SSLContext.setDefault(clientSslContext);
    }

    @AfterAll
    public static void tearDownAll() {
        if (originalDefaultContext != null) {
            SSLContext.setDefault(originalDefaultContext);
        }
    }

    private static SSLContext createSslContext(String keystoreResource, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = TlsTransportTest.class.getResourceAsStream(keystoreResource)) {
            if (is == null) {
                throw new IllegalStateException("Keystore resource not found: " + keystoreResource);
            }
            ks.load(is, password.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password.toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return context;
    }

    private SSLServerSocket createServer(SSLContext sslContext) throws IOException {
        SSLServerSocket server = (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket(0);
        server.setSoTimeout(TIMEOUT_SECONDS * 1000);
        return server;
    }

    @Test
    public void testConnectAndHandshakeSuccess() throws Exception {
        AtomicReference<SSLSocket> acceptedSocketRef = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverAcceptedLatch = new CountDownLatch(1);

        try (SSLServerSocket server = createServer(serverSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try {
                    SSLSocket accepted = (SSLSocket) server.accept();
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    accepted.startHandshake();
                    acceptedSocketRef.set(accepted);
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverAcceptedLatch.countDown();
                }
            });
            serverThread.start();

            try {
                transport.connect();
                assertTrue(serverAcceptedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "Server did not accept connection in time");
                serverThread.join(1000);

                assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());
                SSLSocket accepted = acceptedSocketRef.get();
                assertNotNull(accepted, "Accepted socket should not be null");
                assertTrue(accepted.isConnected(), "Accepted socket should be connected");
                assertFalse(accepted.isClosed(), "Accepted socket should be open");

                SSLSession session = accepted.getSession();
                assertNotNull(session, "SSLSession should not be null");
                assertTrue(session.isValid(), "SSLSession should be valid");
                assertTrue(session.getProtocol().startsWith("TLS"), "Protocol should be TLS");
                assertNotNull(session.getCipherSuite(), "Cipher suite should not be null");
            } finally {
                transport.close();
                SSLSocket accepted = acceptedSocketRef.get();
                if (accepted != null && !accepted.isClosed()) {
                    accepted.close();
                }
            }
        }
    }

    @Test
    public void testClientToServerWrite() throws Exception {
        byte[] expected = "Hello TLS Server".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (SSLServerSocket server = createServer(serverSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    byte[] data = accepted.getInputStream().readNBytes(expected.length);
                    receivedData.set(data);
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            try {
                transport.connect();
                transport.write(expected);
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server thread timed out");
            serverThread.join(1000);

            assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());
            assertArrayEquals(expected, receivedData.get());
        }
    }

    @Test
    public void testServerToClientRead() throws Exception {
        byte[] expected = "Hello TLS Client".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (SSLServerSocket server = createServer(serverSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    accepted.getOutputStream().write(expected);
                    accepted.getOutputStream().flush();
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            byte[] actual;
            try {
                transport.connect();
                actual = transport.read();
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server thread timed out");
            serverThread.join(1000);

            assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());
            assertNotNull(actual);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    public void testBinaryBytesPreservation() throws Exception {
        byte[] binaryData = new byte[] {
                0x00, (byte) 0xFF, 0x01, 0x02, (byte) 0x7F, (byte) 0x80,
                (byte) 0xFE, (byte) 0xAA, 0x55, 0x00, (byte) 0xFF
        };

        AtomicReference<byte[]> serverReceived = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (SSLServerSocket server = createServer(serverSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    byte[] data = accepted.getInputStream().readNBytes(binaryData.length);
                    serverReceived.set(data);

                    // Echo binary data back to client
                    accepted.getOutputStream().write(data);
                    accepted.getOutputStream().flush();
                    accepted.close();
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            byte[] clientReceived;
            try {
                transport.connect();
                transport.write(binaryData);
                clientReceived = transport.read();
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server thread timed out");
            serverThread.join(1000);

            assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());
            assertArrayEquals(binaryData, serverReceived.get(), "Server received binary bytes mismatch");
            assertArrayEquals(binaryData, clientReceived, "Client received binary bytes mismatch");
        }
    }

    @Test
    public void testReadWithBufferOffsetLength() throws Exception {
        byte[] expected = "0123456789".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (SSLServerSocket server = createServer(serverSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    accepted.getOutputStream().write(expected);
                    accepted.getOutputStream().flush();
                    accepted.close();
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            byte[] buffer = new byte[20];
            int bytesRead;
            try {
                transport.connect();
                bytesRead = transport.read(buffer, 3, expected.length);
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server thread timed out");
            serverThread.join(1000);

            assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());
            assertEquals(expected.length, bytesRead);

            byte[] extracted = Arrays.copyOfRange(buffer, 3, 3 + expected.length);
            assertArrayEquals(expected, extracted);
        }
    }

    @Test
    public void testReadSingle() throws Exception {
        byte[] expected = new byte[] { 'X', 'Y', 'Z' };
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (SSLServerSocket server = createServer(serverSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    accepted.getOutputStream().write(expected);
                    accepted.getOutputStream().flush();
                    accepted.close();
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            int b1, b2, b3, b4;
            try {
                transport.connect();
                b1 = transport.readSingle();
                b2 = transport.readSingle();
                b3 = transport.readSingle();
                b4 = transport.readSingle(); // EOF
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server thread timed out");
            serverThread.join(1000);

            assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());
            assertEquals('X', b1);
            assertEquals('Y', b2);
            assertEquals('Z', b3);
            assertEquals(-1, b4);
        }
    }

    @Test
    public void testPartialReads() throws Exception {
        byte[] expected = "0123456789ABCDEFGHIJKLMN".getBytes(StandardCharsets.UTF_8); // 24 bytes
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (SSLServerSocket server = createServer(serverSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    accepted.getOutputStream().write(expected);
                    accepted.getOutputStream().flush();
                    accepted.close();
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            ByteArrayOutputStream received = new ByteArrayOutputStream();
            try {
                transport.connect();
                byte[] buf = new byte[5]; // small buffer to enforce partial reads
                int n;
                while ((n = transport.read(buf, 0, buf.length)) != -1) {
                    received.write(buf, 0, n);
                }
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server thread timed out");
            serverThread.join(1000);

            assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());
            assertArrayEquals(expected, received.toByteArray());
        }
    }

    @Test
    public void testMultipleWritesAndReadsOnSameConnection() throws Exception {
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (SSLServerSocket server = createServer(serverSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);

                    // Round 1
                    byte[] r1 = accepted.getInputStream().readNBytes(5);
                    if (!"ping1".equals(new String(r1, StandardCharsets.UTF_8))) {
                        throw new IllegalStateException("Expected ping1");
                    }
                    accepted.getOutputStream().write("pong1".getBytes(StandardCharsets.UTF_8));
                    accepted.getOutputStream().flush();

                    // Round 2
                    byte[] r2 = accepted.getInputStream().readNBytes(5);
                    if (!"ping2".equals(new String(r2, StandardCharsets.UTF_8))) {
                        throw new IllegalStateException("Expected ping2");
                    }
                    accepted.getOutputStream().write("pong2".getBytes(StandardCharsets.UTF_8));
                    accepted.getOutputStream().flush();
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            byte[] resp1 = new byte[5];
            byte[] resp2 = new byte[5];
            try {
                transport.connect();

                // Round 1
                transport.write("ping1".getBytes(StandardCharsets.UTF_8));
                int n1 = transport.read(resp1, 0, 5);
                assertEquals(5, n1);
                assertEquals("pong1", new String(resp1, StandardCharsets.UTF_8));

                // Round 2
                transport.write("ping2".getBytes(StandardCharsets.UTF_8));
                int n2 = transport.read(resp2, 0, 5);
                assertEquals(5, n2);
                assertEquals("pong2", new String(resp2, StandardCharsets.UTF_8));
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server thread timed out");
            serverThread.join(1000);

            assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());
        }
    }

    @Test
    public void testCloseClosesTransport() throws Exception {
        AtomicInteger serverReadResult = new AtomicInteger();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (SSLServerSocket server = createServer(serverSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    int res = accepted.getInputStream().read();
                    serverReadResult.set(res);
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            transport.connect();
            transport.close();

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server thread timed out");
            serverThread.join(1000);

            assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());
            assertEquals(-1, serverReadResult.get(), "Server should detect EOF after client closes");
        }
    }

    @Test
    public void testOperationsAfterCloseThrowIOException() throws Exception {
        try (SSLServerSocket server = createServer(serverSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    accepted.startHandshake();
                } catch (IOException ignored) {
                }
            });
            serverThread.start();

            transport.connect();
            transport.close();
            serverThread.join(1000);

            assertThrows(IOException.class, () -> transport.write(new byte[] { 1, 2, 3 }));
            assertThrows(IOException.class, transport::read);
            assertThrows(IOException.class, () -> transport.read(new byte[10], 0, 10));
            assertThrows(IOException.class, transport::readSingle);
        }
    }

    @Test
    public void testConnectClosesExistingAndReconnects() throws Exception {
        AtomicReference<byte[]> firstRecv = new AtomicReference<>();
        AtomicReference<byte[]> secondRecv = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (SSLServerSocket server = createServer(serverSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try {
                    try (SSLSocket s1 = (SSLSocket) server.accept()) {
                        s1.setSoTimeout(TIMEOUT_SECONDS * 1000);
                        firstRecv.set(s1.getInputStream().readNBytes(5));
                    }

                    try (SSLSocket s2 = (SSLSocket) server.accept()) {
                        s2.setSoTimeout(TIMEOUT_SECONDS * 1000);
                        secondRecv.set(s2.getInputStream().readNBytes(6));
                    }
                } catch (Throwable t) {
                    serverError.set(t);
                } finally {
                    serverDone.countDown();
                }
            });
            serverThread.start();

            try {
                transport.connect();
                transport.write("first".getBytes(StandardCharsets.UTF_8));

                transport.connect();
                transport.write("second".getBytes(StandardCharsets.UTF_8));
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server thread timed out");
            serverThread.join(1000);

            assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());
            assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), firstRecv.get());
            assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), secondRecv.get());
        }
    }

    @Test
    public void testConnectToUnreachablePortThrowsIOException() throws Exception {
        int unreachablePort;
        try (SSLServerSocket tempServer = createServer(serverSslContext)) {
            unreachablePort = tempServer.getLocalPort();
        }

        try (TlsTransport transport = new TlsTransport("localhost", unreachablePort)) {
            assertThrows(IOException.class, transport::connect);
        }
    }

    @Test
    public void testUntrustedCertificateThrowsSSLHandshakeException() throws Exception {
        try (SSLServerSocket server = createServer(untrustedServerSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    accepted.startHandshake();
                } catch (Throwable ignored) {
                    // Handshake will fail on server side as well
                }
            });
            serverThread.start();

            try {
                assertThrows(SSLHandshakeException.class, transport::connect);
            } finally {
                transport.close();
                serverThread.join(1000);
            }
        }
    }

    @Test
    public void testOperationsBeforeConnect() throws Exception {
        try (TlsTransport transport = new TlsTransport("localhost", 8080)) {
            byte[] data = "test".getBytes(StandardCharsets.UTF_8);

            assertThrows(NullPointerException.class, () -> transport.write(data));
            assertThrows(NullPointerException.class, transport::read);
            assertThrows(NullPointerException.class, () -> transport.read(new byte[10], 0, 10));
            assertThrows(NullPointerException.class, transport::readSingle);

            assertDoesNotThrow(transport::close);
        }
    }

    @Test
    public void testWriteNullDataThrowsNullPointerException() throws Exception {
        try (SSLServerSocket server = createServer(serverSslContext)) {
            TlsTransport transport = new TlsTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.startHandshake();
                } catch (Throwable ignored) {
                }
            });
            serverThread.start();

            try {
                transport.connect();
                assertThrows(NullPointerException.class, () -> transport.write(null));
                assertThrows(NullPointerException.class, () -> transport.read(null, 0, 0));
            } finally {
                transport.close();
                serverThread.join(1000);
            }
        }
    }
}
