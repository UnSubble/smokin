package com.unsubble.smokin.transport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class TcpTransportTest {

    private static final int TIMEOUT_SECONDS = 5;

    @Test
    public void testConnectSuccess() throws Exception {
        AtomicReference<Socket> acceptedSocketRef = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverAcceptedLatch = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try {
                    Socket accepted = server.accept();
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
                Socket accepted = acceptedSocketRef.get();
                assertNotNull(accepted, "Accepted socket should not be null");
                assertTrue(accepted.isConnected(), "Accepted socket should be connected");
                assertFalse(accepted.isClosed(), "Accepted socket should be open");
            } finally {
                transport.close();
                Socket accepted = acceptedSocketRef.get();
                if (accepted != null && !accepted.isClosed()) {
                    accepted.close();
                }
            }
        }
    }

    @Test
    public void testWriteClientToServer() throws Exception {
        byte[] expected = "hello world".getBytes(StandardCharsets.UTF_8);
        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (Socket accepted = server.accept()) {
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
    public void testReadServerToClient() throws Exception {
        byte[] expected = "response from server".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.getOutputStream().write(expected);
                    accepted.getOutputStream().flush();
                    accepted.shutdownOutput();
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
    public void testMultipleWritesOnSameConnection() throws Exception {
        byte[] chunk1 = "FirstChunk|".getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = "SecondChunk|".getBytes(StandardCharsets.UTF_8);
        byte[] chunk3 = "ThirdChunk!".getBytes(StandardCharsets.UTF_8);

        byte[] expectedCombined = "FirstChunk|SecondChunk|ThirdChunk!".getBytes(StandardCharsets.UTF_8);

        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    byte[] data = accepted.getInputStream().readNBytes(expectedCombined.length);
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
                transport.write(chunk1);
                transport.write(chunk2);
                transport.write(chunk3);
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server thread timed out");
            serverThread.join(1000);

            assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());
            assertArrayEquals(expectedCombined, receivedData.get());
        }
    }

    @Test
    public void testWritePreservesExactBytes() throws Exception {
        String testString = "Test line 1\r\nTest line 2\twith tabs and symbols: !@#$%^&*()_+";
        byte[] expected = testString.getBytes(StandardCharsets.UTF_8);

        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (Socket accepted = server.accept()) {
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
    public void testBinaryBytesPreservedWithoutModification() throws Exception {
        byte[] binaryData = new byte[] {
                0x00, (byte) 0xFF, 0x01, 0x02, (byte) 0x7F, (byte) 0x80,
                (byte) 0xFE, (byte) 0xAA, 0x55, 0x00, 0x00, (byte) 0xFF
        };

        AtomicReference<byte[]> serverReceived = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    byte[] data = accepted.getInputStream().readNBytes(binaryData.length);
                    serverReceived.set(data);

                    accepted.getOutputStream().write(data);
                    accepted.getOutputStream().flush();
                    accepted.shutdownOutput();
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
    public void testReadReturnsCorrectByteCount() throws Exception {
        byte[] expected = new byte[1024];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i % 256);
        }

        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.getOutputStream().write(expected);
                    accepted.getOutputStream().flush();
                    accepted.shutdownOutput();
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
            assertEquals(expected.length, actual.length);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    public void testCloseClosesConnection() throws Exception {
        AtomicInteger serverReadResult = new AtomicInteger();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (Socket accepted = server.accept()) {
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

            assertThrows(IOException.class, () -> transport.write("test".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    public void testWriteBeforeConnectThrowsNullPointerException() {
        TcpTransport transport = new TcpTransport("localhost", 8080);
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);

        assertThrows(NullPointerException.class, () -> transport.write(data));
    }

    @Test
    public void testReadBeforeConnectThrowsNullPointerException() {
        TcpTransport transport = new TcpTransport("localhost", 8080);

        assertThrows(NullPointerException.class, transport::read);
    }

    @Test
    public void testCloseBeforeConnectDoesNotThrow() {
        TcpTransport transport = new TcpTransport("localhost", 8080);

        assertDoesNotThrow(transport::close);
    }

    @Test
    public void testWriteNullDataThrowsNullPointerException() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (Socket ignored = server.accept()) {
                    // connection accepted
                } catch (IOException ignored) {
                }
            });
            serverThread.start();

            try {
                transport.connect();
                assertThrows(NullPointerException.class, () -> transport.write(null));
            } finally {
                transport.close();
                serverThread.join(1000);
            }
        }
    }

    @Test
    public void testConnectToUnreachableTargetThrowsIOException() throws Exception {
        int unreachablePort;
        try (ServerSocket tempServer = new ServerSocket(0)) {
            unreachablePort = tempServer.getLocalPort();
        }

        TcpTransport transport = new TcpTransport("localhost", unreachablePort);

        assertThrows(IOException.class, transport::connect);
    }

    @Test
    public void testReadWithBufferOffsetLength() throws Exception {
        byte[] expected = "hello world".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.getOutputStream().write(expected);
                    accepted.getOutputStream().flush();
                    accepted.shutdownOutput();
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
                bytesRead = transport.read(buffer, 2, expected.length);
            } finally {
                transport.close();
            }

            assertTrue(serverDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Server thread timed out");
            serverThread.join(1000);

            assertNull(serverError.get(), () -> "Server encountered error: " + serverError.get());
            assertEquals(expected.length, bytesRead);

            byte[] extracted = new byte[expected.length];
            System.arraycopy(buffer, 2, extracted, 0, expected.length);
            assertArrayEquals(expected, extracted);
        }
    }

    @Test
    public void testReadSingle() throws Exception {
        byte[] expected = new byte[] { 'A', 'B', 'C' };
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        CountDownLatch serverDone = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.getOutputStream().write(expected);
                    accepted.getOutputStream().flush();
                    accepted.shutdownOutput();
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
            assertEquals('A', b1);
            assertEquals('B', b2);
            assertEquals('C', b3);
            assertEquals(-1, b4);
        }
    }

    @Test
    public void testReadBufferBeforeConnectThrowsNullPointerException() {
        TcpTransport transport = new TcpTransport("localhost", 8080);
        byte[] buf = new byte[10];

        assertThrows(NullPointerException.class, () -> transport.read(buf, 0, 10));
    }

    @Test
    public void testReadSingleBeforeConnectThrowsNullPointerException() {
        TcpTransport transport = new TcpTransport("localhost", 8080);

        assertThrows(NullPointerException.class, transport::readSingle);
    }

    @Test
    public void testReadNullBufferThrowsNullPointerException() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(TIMEOUT_SECONDS * 1000);
            TcpTransport transport = new TcpTransport("localhost", server.getLocalPort());

            Thread serverThread = new Thread(() -> {
                try (Socket ignored = server.accept()) {
                } catch (IOException ignored) {
                }
            });
            serverThread.start();

            try {
                transport.connect();
                assertThrows(NullPointerException.class, () -> transport.read(null, 0, 0));
            } finally {
                transport.close();
                serverThread.join(1000);
            }
        }
    }
}