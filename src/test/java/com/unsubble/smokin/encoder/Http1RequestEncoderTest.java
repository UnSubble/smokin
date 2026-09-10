package com.unsubble.smokin.encoder;

import com.unsubble.smokin.model.Header;
import com.unsubble.smokin.model.Request;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class Http1RequestEncoderTest {

    private static final String GET = "GET";
    private static final String POST = "POST";

    private static final String VERSION_1_1 = "HTTP/1.1";

    private static final String ROOT_PATH = "/";

    @Test
    public void testHttp1RequestEncoding() {
        Request request = Request.newBuilder()
                .method(GET)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .addHeader(new Header("Host", "example.com"))
                .build();

        HttpRequestEncoder encoder = new Http1RequestEncoder();

        byte[] actual = encoder.encode(request);

        byte[] expected =
                """
                        GET / HTTP/1.1\r
                        Host: example.com\r
                        \r
                        """
            .getBytes(StandardCharsets.ISO_8859_1);

        assertArrayEquals(expected, actual);
    }

    @Test
    public void testHttp1RequestEncodingWithBody() {
        byte[] body = new byte[] { 0x00, (byte) 0xFF, 0x01, 0x02 };

        Request request = Request.newBuilder()
                .method(POST)
                .path("/upload")
                .version(VERSION_1_1)
                .addHeader(new Header("Host", "example.com"))
                .body(body)
                .build();

        HttpRequestEncoder encoder = new Http1RequestEncoder();

        byte[] actual = encoder.encode(request);

        byte[] headers =
                """
                        POST /upload HTTP/1.1\r
                        Host: example.com\r
                        \r
                        """
            .getBytes(StandardCharsets.ISO_8859_1);

        byte[] expected = new byte[headers.length + body.length];

        System.arraycopy(headers, 0, expected, 0, headers.length);
        System.arraycopy(body, 0, expected, headers.length, body.length);

        assertArrayEquals(expected, actual);
    }

    @Test
    public void testHttp1RequestEncodingWithoutHeaders() {
        Request request = Request.newBuilder()
                .method(GET)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .build();

        HttpRequestEncoder encoder = new Http1RequestEncoder();

        byte[] actual = encoder.encode(request);

        byte[] expected =
                """
                        GET / HTTP/1.1\r
                        \r
                        """
                        .getBytes(StandardCharsets.ISO_8859_1);

        assertArrayEquals(expected, actual);
    }

    @Test
    public void testHttp1RequestEncodingHeaderValueContainingColon() {
        Request request = Request.newBuilder()
                .method(GET)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .addHeader(new Header("Authorization", "Bearer abc:def"))
                .build();

        HttpRequestEncoder encoder = new Http1RequestEncoder();

        byte[] actual = encoder.encode(request);

        byte[] expected =
                """
                        GET / HTTP/1.1\r
                        Authorization: Bearer abc:def\r
                        \r
                        """
                        .getBytes(StandardCharsets.ISO_8859_1);

        assertArrayEquals(expected, actual);
    }

    @Test
    public void testHttp1RequestEncodingHeaderValueContainingSpaces() {
        Request request = Request.newBuilder()
                .method(GET)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .addHeader(new Header("User-Agent", "Mozilla/5.0 Test Client"))
                .build();

        HttpRequestEncoder encoder = new Http1RequestEncoder();

        byte[] actual = encoder.encode(request);

        byte[] expected =
                """
                        GET / HTTP/1.1\r
                        User-Agent: Mozilla/5.0 Test Client\r
                        \r
                        """
                        .getBytes(StandardCharsets.ISO_8859_1);

        assertArrayEquals(expected, actual);
    }

    @Test
    public void testHttp1RequestEncodingWithQueryString() {
        Request request = Request.newBuilder()
                .method(GET)
                .path("/search?q=smokin&page=2")
                .version(VERSION_1_1)
                .addHeader(new Header("Host", "example.com"))
                .build();

        HttpRequestEncoder encoder = new Http1RequestEncoder();

        byte[] actual = encoder.encode(request);

        byte[] expected =
                """
                        GET /search?q=smokin&page=2 HTTP/1.1\r
                        Host: example.com\r
                        \r
                        """
                        .getBytes(StandardCharsets.ISO_8859_1);

        assertArrayEquals(expected, actual);
    }

    @Test
    public void testHttp1RequestEncodingWithEmptyBody() {
        Request request = Request.newBuilder()
                .method(POST)
                .path("/upload")
                .version(VERSION_1_1)
                .addHeader(new Header("Host", "example.com"))
                .body(new byte[0])
                .build();

        HttpRequestEncoder encoder = new Http1RequestEncoder();

        byte[] actual = encoder.encode(request);

        byte[] expected =
                """
                        POST /upload HTTP/1.1\r
                        Host: example.com\r
                        \r
                        """
                        .getBytes(StandardCharsets.ISO_8859_1);

        assertArrayEquals(expected, actual);
    }

    @Test
    public void testHttp1RequestEncodingPreservesTrailingWhitespaceInBody() {
        byte[] body = "hello   ".getBytes(StandardCharsets.ISO_8859_1);

        Request request = Request.newBuilder()
                .method(POST)
                .path("/upload")
                .version(VERSION_1_1)
                .addHeader(new Header("Host", "example.com"))
                .body(body)
                .build();

        HttpRequestEncoder encoder = new Http1RequestEncoder();

        byte[] actual = encoder.encode(request);

        byte[] headers =
                """
                        POST /upload HTTP/1.1\r
                        Host: example.com\r
                        \r
                        """
                        .getBytes(StandardCharsets.ISO_8859_1);

        byte[] expected = new byte[headers.length + body.length];

        System.arraycopy(headers, 0, expected, 0, headers.length);
        System.arraycopy(body, 0, expected, headers.length, body.length);

        assertArrayEquals(expected, actual);
    }

    @Test
    public void testHttp1RequestEncodingPreservesBinaryBody() {
        byte[] body = new byte[] {
                0x00,
                0x01,
                0x02,
                0x7F,
                (byte) 0x80,
                (byte) 0xFE,
                (byte) 0xFF
        };

        Request request = Request.newBuilder()
                .method(POST)
                .path("/binary")
                .version(VERSION_1_1)
                .body(body)
                .build();

        HttpRequestEncoder encoder = new Http1RequestEncoder();

        byte[] actual = encoder.encode(request);

        byte[] headers =
                """
                        POST /binary HTTP/1.1\r
                        \r
                        """
                        .getBytes(StandardCharsets.ISO_8859_1);

        byte[] expected = new byte[headers.length + body.length];

        System.arraycopy(headers, 0, expected, 0, headers.length);
        System.arraycopy(body, 0, expected, headers.length, body.length);

        assertArrayEquals(expected, actual);
    }

    @Test
    public void testHttp1RequestEncodingPreservesHeaderOrder() {
        Request request = Request.newBuilder()
                .method(GET)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .addHeader(new Header("Z-Header", "last"))
                .addHeader(new Header("A-Header", "first"))
                .addHeader(new Header("M-Header", "middle"))
                .build();

        HttpRequestEncoder encoder = new Http1RequestEncoder();

        byte[] actual = encoder.encode(request);

        byte[] expected =
                """
                        GET / HTTP/1.1\r
                        Z-Header: last\r
                        A-Header: first\r
                        M-Header: middle\r
                        \r
                        """
                        .getBytes(StandardCharsets.ISO_8859_1);

        assertArrayEquals(expected, actual);
    }
}
