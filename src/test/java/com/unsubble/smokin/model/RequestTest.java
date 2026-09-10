package com.unsubble.smokin.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RequestTest {

    private static final String GET = "GET";
    private static final String POST = "POST";

    private static final String VERSION_1_1 = "HTTP/1.1";

    private static final String ROOT_PATH = "/";

    @Test
    public void testRequestSplit() {
        byte[] body = "hello world".getBytes(StandardCharsets.ISO_8859_1);

        Request request = Request.newBuilder()
                .method(POST)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .addHeader(new Header("Host", "example.com"))
                .body(body)
                .build();

        Request[] split = request.split(5);

        assertEquals(2, split.length);

        assertArrayEquals(
                "hello".getBytes(StandardCharsets.UTF_8),
                split[0].body()
        );

        assertArrayEquals(
                " world".getBytes(StandardCharsets.UTF_8),
                split[1].body()
        );

        assertEquals(request.method(), split[0].method());
        assertEquals(request.path(), split[0].path());
        assertEquals(request.version(), split[0].version());
        assertEquals(request.headers(), split[0].headers());

        assertEquals(request.method(), split[1].method());
        assertEquals(request.path(), split[1].path());
        assertEquals(request.version(), split[1].version());
        assertEquals(request.headers(), split[1].headers());
    }

    @Test
    public void testRequestSplitAtBeginning() {
        Request request = Request.newBuilder()
                .body("hello".getBytes(StandardCharsets.UTF_8))
                .build();

        Request[] split = request.split(0);

        assertEquals(0, split[0].body().length);
        assertArrayEquals(
                "hello".getBytes(StandardCharsets.UTF_8),
                split[1].body()
        );
    }

    @Test
    public void testRequestSplitAtEnd() {
        Request request = Request.newBuilder()
                .body("hello".getBytes(StandardCharsets.UTF_8))
                .build();

        Request[] split = request.split(5);

        assertArrayEquals(
                "hello".getBytes(StandardCharsets.UTF_8),
                split[0].body()
        );
        assertEquals(0, split[1].body().length);
    }
}
