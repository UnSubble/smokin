package com.unsubble.smokin.parser;

import com.unsubble.smokin.transport.Transport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

public class HttpResponseFramerTest {

    private static final String CRLF = "\r\n";

    @Test
    public void testSimpleResponseWithContentLength() throws Exception {
        String raw = """
                HTTP/1.1 200 OK\r
                Content-Length: 11\r
                \r
                hello world""";
        byte[] rawBytes = raw.getBytes(StandardCharsets.ISO_8859_1);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 3);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        byte[] framed = framer.read();

        assertArrayEquals(rawBytes, framed);
    }

    @Test
    public void testContentLengthZero() throws Exception {
        String raw = """
                HTTP/1.1 200 OK\r
                Content-Length: 0\r
                Server: smokin\r
                \r
                """;
        byte[] rawBytes = raw.getBytes(StandardCharsets.ISO_8859_1);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 4);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        byte[] framed = framer.read();

        assertArrayEquals(rawBytes, framed);
    }

    @Test
    public void testMultipleHeaders() throws Exception {
        String raw = """
                HTTP/1.1 200 OK\r
                Server: smokin\r
                Content-Type: application/json\r
                Content-Length: 15\r
                Connection: keep-alive\r
                \r
                {"status":"ok"}""";
        byte[] rawBytes = raw.getBytes(StandardCharsets.ISO_8859_1);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 5);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        byte[] framed = framer.read();

        assertArrayEquals(rawBytes, framed);
    }

    @Test
    public void testHeaderValueContainingColon() throws Exception {
        String raw = """
                HTTP/1.1 200 OK\r
                Authorization: Bearer token:abc:123\r
                Server: foo:bar\r
                Content-Length: 4\r
                \r
                test""";
        byte[] rawBytes = raw.getBytes(StandardCharsets.ISO_8859_1);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 2);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        byte[] framed = framer.read();

        assertArrayEquals(rawBytes, framed);
    }

    @Test
    public void testBodyReturnedWithExactBytes() throws Exception {
        String body = "Hello, Smokin";
        byte[] bodyBytes = body.getBytes(StandardCharsets.ISO_8859_1);
        String headers = "HTTP/1.1 200 OK\r\nContent-Length: " + bodyBytes.length + CRLF + CRLF;
        byte[] headerBytes = headers.getBytes(StandardCharsets.ISO_8859_1);

        byte[] rawBytes = new byte[headerBytes.length + bodyBytes.length];
        System.arraycopy(headerBytes, 0, rawBytes, 0, headerBytes.length);
        System.arraycopy(bodyBytes, 0, rawBytes, headerBytes.length, bodyBytes.length);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 4);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        byte[] framed = framer.read();

        byte[] extractedBody = Arrays.copyOfRange(framed, headerBytes.length, framed.length);
        assertArrayEquals(bodyBytes, extractedBody);
    }

    @Test
    public void testBinaryBodyPreservation() throws Exception {
        byte[] binaryBody = new byte[] {
                0x00, (byte) 0xFF, 0x01, 0x02, (byte) 0x7F, (byte) 0x80,
                (byte) 0xFE, (byte) 0xAA, 0x55, 0x00, (byte) 0xFF
        };

        byte[] headerBytes = ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                + binaryBody.length + CRLF + CRLF).getBytes(StandardCharsets.ISO_8859_1);

        byte[] rawBytes = new byte[headerBytes.length + binaryBody.length];
        System.arraycopy(headerBytes, 0, rawBytes, 0, headerBytes.length);
        System.arraycopy(binaryBody, 0, rawBytes, headerBytes.length, binaryBody.length);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 3);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        byte[] framed = framer.read();

        assertArrayEquals(rawBytes, framed);
        byte[] extractedBody = Arrays.copyOfRange(framed, headerBytes.length, framed.length);
        assertArrayEquals(binaryBody, extractedBody);
    }

    @Test
    public void testBodyArrivingInMultipleReadCalls() throws Exception {
        String body = "0123456789ABCDEF"; // 16 bytes
        String raw = "HTTP/1.1 200 OK\r\nContent-Length: 16\r\n\r\n" + body;
        byte[] rawBytes = raw.getBytes(StandardCharsets.ISO_8859_1);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, new int[] { 2, 1, 5, 3, 4, 1 });
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        byte[] framed = framer.read();

        assertArrayEquals(rawBytes, framed);
    }

    @Test
    public void testHeaderAndBodySeparation() throws Exception {
        String headers = """
                HTTP/1.1 200 OK\r
                Content-Length: 5\r
                Server: test\r
                \r
                """;
        String body = "abcde";
        byte[] rawBytes = (headers + body).getBytes(StandardCharsets.ISO_8859_1);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 2);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        byte[] framed = framer.read();

        String framedStr = new String(framed, StandardCharsets.ISO_8859_1);
        int separatorIndex = framedStr.indexOf(CRLF + CRLF);
        assertTrue(separatorIndex > 0, "Response must contain header-body delimiter CRLFCRLF");

        String extractedHeaders = framedStr.substring(0, separatorIndex + 4);
        String extractedBody = framedStr.substring(separatorIndex + 4);

        assertEquals(headers, extractedHeaders);
        assertEquals(body, extractedBody);
    }

    @Test
    public void testCrlfBoundarySplitAcrossReads() throws Exception {
        String raw = """
                HTTP/1.1 200 OK\r
                Content-Length: 4\r
                \r
                data""";
        byte[] rawBytes = raw.getBytes(StandardCharsets.ISO_8859_1);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 1);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        byte[] framed = framer.read();

        assertArrayEquals(rawBytes, framed);
    }

    @Test
    public void testPrematureConnectionCloseDuringBodyThrowsException() {
        String raw = """
                HTTP/1.1 200 OK\r
                Content-Length: 20\r
                \r
                short""";
        byte[] rawBytes = raw.getBytes(StandardCharsets.ISO_8859_1);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 4);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        IOException thrown = assertThrows(IOException.class, framer::read);
        assertTrue(thrown.getMessage().contains("Connection closed before expected body bytes were received"),
                "Expected connection closed before expected body bytes message");
    }

    @Test
    public void testPrematureConnectionCloseDuringHeadersThrowsException() {
        String raw = """
                HTTP/1.1 200 OK\r
                Host: example.com""";
        byte[] rawBytes = raw.getBytes(StandardCharsets.ISO_8859_1);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 2);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        IOException thrown = assertThrows(IOException.class, framer::read);
        assertTrue(thrown.getMessage().contains("Connection closed before CRLF"));
    }

    @Test
    public void testInvalidContentLengthThrowsException() {
        String nonNumeric = """
                HTTP/1.1 200 OK\r
                Content-Length: abc\r
                \r
                """;
        HttpResponseFramer framer1 = new HttpResponseFramer(
                new FakeReadOnlyTransport(nonNumeric.getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(IOException.class, framer1::read);

        String negative = """
                HTTP/1.1 200 OK\r
                Content-Length: -5\r
                \r
                """;
        HttpResponseFramer framer2 = new HttpResponseFramer(
                new FakeReadOnlyTransport(negative.getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(IOException.class, framer2::read);

        String conflicting = """
                HTTP/1.1 200 OK\r
                Content-Length: 5\r
                Content-Length: 10\r
                \r
                hello""";
        HttpResponseFramer framer3 = new HttpResponseFramer(
                new FakeReadOnlyTransport(conflicting.getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(IOException.class, framer3::read);
    }

    @Test
    public void testMissingContentLengthReadsUntilClosed() throws Exception {
        String raw = """
                HTTP/1.1 200 OK\r
                \r
                Payload without Content-Length until EOF""";
        byte[] rawBytes = raw.getBytes(StandardCharsets.ISO_8859_1);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 7);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        byte[] framed = framer.read();

        assertArrayEquals(rawBytes, framed);
    }

    @Test
    public void testMalformedStatusLineThrowsException() {
        String incomplete = """
                HTTP/1.1\r
                \r
                """;
        HttpResponseFramer framer1 = new HttpResponseFramer(
                new FakeReadOnlyTransport(incomplete.getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(IOException.class, framer1::read);

        String nonDigit = """
                HTTP/1.1 20A OK\r
                \r
                """;
        HttpResponseFramer framer2 = new HttpResponseFramer(
                new FakeReadOnlyTransport(nonDigit.getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(IOException.class, framer2::read);

        String tooShort = """
                HTTP/1.1 20 OK\r
                \r
                """;
        HttpResponseFramer framer3 = new HttpResponseFramer(
                new FakeReadOnlyTransport(tooShort.getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(IOException.class, framer3::read);

        String tooLong = """
                HTTP/1.1 2000 OK\r
                \r
                """;
        HttpResponseFramer framer4 = new HttpResponseFramer(
                new FakeReadOnlyTransport(tooLong.getBytes(StandardCharsets.ISO_8859_1)));
        assertThrows(IOException.class, framer4::read);
    }

    @Test
    public void testMalformedHeaderLineThrowsException() {
        String raw = """
                HTTP/1.1 200 OK\r
                HeaderWithoutColon\r
                \r
                """;
        HttpResponseFramer framer = new HttpResponseFramer(
                new FakeReadOnlyTransport(raw.getBytes(StandardCharsets.ISO_8859_1)));

        assertThrows(IOException.class, framer::read);
    }

    @Test
    public void testAmbiguousBodyFramingThrowsException() {
        String raw = """
                HTTP/1.1 200 OK\r
                Content-Length: 5\r
                Transfer-Encoding: chunked\r
                \r
                5\r
                hello\r
                0\r
                \r
                """;
        HttpResponseFramer framer = new HttpResponseFramer(
                new FakeReadOnlyTransport(raw.getBytes(StandardCharsets.ISO_8859_1)));

        IOException thrown = assertThrows(IOException.class, framer::read);
        assertTrue(thrown.getMessage().contains("Ambiguous body framing"));
    }

    @Test
    public void testChunkedBodyFraming() throws Exception {
        String raw = """
                HTTP/1.1 200 OK\r
                Transfer-Encoding: chunked\r
                \r
                5\r
                hello\r
                6\r
                 world\r
                0\r
                \r
                """;
        byte[] rawBytes = raw.getBytes(StandardCharsets.ISO_8859_1);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 2);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        byte[] framed = framer.read();

        assertArrayEquals(rawBytes, framed);
    }

    @Test
    public void testHeadMethodForbidsBody() throws Exception {
        String raw = """
                HTTP/1.1 200 OK\r
                Content-Length: 50\r
                \r
                """;
        byte[] rawBytes = raw.getBytes(StandardCharsets.ISO_8859_1);

        FakeReadOnlyTransport transport = new FakeReadOnlyTransport(rawBytes, 10);
        HttpResponseFramer framer = new HttpResponseFramer(transport);

        byte[] framed = framer.read("HEAD");

        assertArrayEquals(rawBytes, framed);
    }

    @Test
    public void testStatus204And304ForbidsBody() throws Exception {
        String raw204 = """
                HTTP/1.1 204 No Content\r
                \r
                """;
        byte[] raw204Bytes = raw204.getBytes(StandardCharsets.ISO_8859_1);
        HttpResponseFramer framer204 = new HttpResponseFramer(new FakeReadOnlyTransport(raw204Bytes));
        assertArrayEquals(raw204Bytes, framer204.read());

        String raw304 = """
                HTTP/1.1 304 Not Modified\r
                ETag: "123"\r
                \r
                """;
        byte[] raw304Bytes = raw304.getBytes(StandardCharsets.ISO_8859_1);
        HttpResponseFramer framer304 = new HttpResponseFramer(new FakeReadOnlyTransport(raw304Bytes));
        assertArrayEquals(raw304Bytes, framer304.read());
    }

    static class FakeReadOnlyTransport implements Transport {
        private final byte[] data;
        private int position = 0;
        private int maxReadChunk = Integer.MAX_VALUE;
        private int[] chunkSizes = null;
        private int chunkSizeIndex = 0;
        private boolean closed = false;

        public FakeReadOnlyTransport(byte[] data) {
            this.data = data;
        }

        public FakeReadOnlyTransport(byte[] data, int maxReadChunk) {
            this.data = data;
            this.maxReadChunk = maxReadChunk;
        }

        public FakeReadOnlyTransport(byte[] data, int[] chunkSizes) {
            this.data = data;
            this.chunkSizes = chunkSizes;
        }

        @Override
        public void connect() {
            closed = false;
        }

        @Override
        public void write(byte[] data) {
        }

        @Override
        public byte[] read() throws IOException {
            if (closed) {
                throw new IOException("Transport is closed");
            }
            if (position >= data.length) {
                return new byte[0];
            }
            byte[] remaining = Arrays.copyOfRange(data, position, data.length);
            position = data.length;
            return remaining;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.requireNonNull(buffer);
            if (closed) {
                throw new IOException("Transport is closed");
            }
            if (position >= data.length) {
                return -1;
            }

            int allowed = maxReadChunk;
            if (chunkSizes != null && chunkSizeIndex < chunkSizes.length) {
                allowed = chunkSizes[chunkSizeIndex++];
            }

            int available = data.length - position;
            int toRead = Math.min(length, Math.min(available, allowed));
            if (toRead <= 0) {
                return 0;
            }

            System.arraycopy(data, position, buffer, offset, toRead);
            position += toRead;
            return toRead;
        }

        @Override
        public int readSingle() throws IOException {
            if (closed) {
                throw new IOException("Transport is closed");
            }
            if (position >= data.length) {
                return -1;
            }
            return data[position++] & 0xFF;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
