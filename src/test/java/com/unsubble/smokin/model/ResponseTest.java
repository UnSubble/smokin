package com.unsubble.smokin.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ResponseTest {

    @Test
    public void testCustomVersion() {
        Response response = Response.newBuilder()
                .version("HTTP/1.1")
                .build();

        assertEquals("HTTP/1.1", response.version());
    }

    @Test
    public void testCustomStatusCode() {
        Response response = Response.newBuilder()
                .statusCode(404)
                .build();

        assertEquals(404, response.statusCode());
    }

    @Test
    public void testCustomReasonPhrase() {
        Response response = Response.newBuilder()
                .reasonPhrase("Not Found")
                .build();

        assertEquals("Not Found", response.reasonPhrase());
    }

    @Test
    public void testHeadersManagement() {
        Header h1 = new Header("Content-Type", "application/json");
        Header h2 = new Header("Server", "smokin");

        Response response = Response.newBuilder()
                .addHeader(h1)
                .addHeader(h2)
                .build();

        assertEquals(2, response.headers().size());
        assertEquals(h1, response.headers().get(0));
        assertEquals(h2, response.headers().get(1));
    }

    @Test
    public void testSetHeaderReplacesExistingCaseInsensitively() {
        Header original = new Header("content-type", "text/plain");
        Header replacement = new Header("Content-Type", "application/json");

        Response response = Response.newBuilder()
                .addHeader(original)
                .setHeader(replacement)
                .build();

        assertEquals(1, response.headers().size());
        assertEquals(replacement, response.headers().get(0));
    }

    @Test
    public void testSetHeaderAppendsWhenNotFound() {
        Header header = new Header("X-Custom", "value");

        Response response = Response.newBuilder()
                .setHeader(header)
                .build();

        assertEquals(1, response.headers().size());
        assertEquals(header, response.headers().get(0));
    }

    @Test
    public void testHeadersListImmutability() {
        Response response = Response.newBuilder()
                .addHeader(new Header("Host", "example.com"))
                .build();

        List<Header> headers = response.headers();
        assertThrows(UnsupportedOperationException.class, () -> headers.add(new Header("X", "Y")));
    }

    @Test
    public void testEmptyBody() {
        Response response = Response.newBuilder()
                .body(new byte[0])
                .build();

        assertNotNull(response.body());
        assertEquals(0, response.body().length);
    }

    @Test
    public void testTextBody() {
        byte[] body = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        Response response = Response.newBuilder()
                .body(body)
                .build();

        assertArrayEquals(body, response.body());
    }

    @Test
    public void testBinaryBodyPreservation() {
        byte[] binaryBody = new byte[] {
                0x00, (byte) 0xFF, 0x01, (byte) 0x7F, (byte) 0x80, (byte) 0xFE, (byte) 0xAA, 0x55
        };

        Response response = Response.newBuilder()
                .body(binaryBody)
                .build();

        assertArrayEquals(binaryBody, response.body());
    }

    @Test
    public void testBodyGetterReturnsDefensiveCopy() {
        byte[] original = new byte[] { 1, 2, 3 };

        Response response = Response.newBuilder()
                .body(original)
                .build();

        byte[] returned = response.body();
        returned[0] = 99;

        assertArrayEquals(new byte[] { 1, 2, 3 }, response.body());
    }

    @Test
    public void testBuilderNullChecks() {
        Response.Builder builder = Response.newBuilder();

        assertThrows(NullPointerException.class, () -> builder.addHeader(null));
        assertThrows(NullPointerException.class, () -> builder.setHeader(null));
        assertThrows(NullPointerException.class, () -> builder.body(null));
        assertThrows(NullPointerException.class, () -> builder.version(null).build());
        assertThrows(NullPointerException.class, () -> builder.reasonPhrase(null).build());
    }

    @Test
    public void testEqualsAndHashCode() {
        byte[] body = "test body".getBytes(StandardCharsets.UTF_8);
        Header header = new Header("Content-Type", "text/plain");

        Response r1 = Response.newBuilder()
                .version("HTTP/1.1")
                .statusCode(200)
                .reasonPhrase("OK")
                .addHeader(header)
                .body(body)
                .build();

        Response r2 = Response.newBuilder()
                .version("HTTP/1.1")
                .statusCode(200)
                .reasonPhrase("OK")
                .addHeader(header)
                .body(body)
                .build();

        Response rDifferentStatus = Response.newBuilder()
                .version("HTTP/1.1")
                .statusCode(404)
                .reasonPhrase("OK")
                .addHeader(header)
                .body(body)
                .build();

        Response rDifferentBody = Response.newBuilder()
                .version("HTTP/1.1")
                .statusCode(200)
                .reasonPhrase("OK")
                .addHeader(header)
                .body("other".getBytes(StandardCharsets.UTF_8))
                .build();

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, rDifferentStatus);
        assertNotEquals(r1, rDifferentBody);
        assertNotEquals(null, r1);
        assertNotEquals("string", r1);
    }
}
