package com.unsubble.smokin.parser;

import com.unsubble.smokin.model.Header;
import com.unsubble.smokin.model.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Http1ResponseParserTest {

    private final Http1ResponseParser parser = new Http1ResponseParser();

    @Test
    public void testBasic200OkResponse() {
        String raw = """
                HTTP/1.1 200 OK\r
                Content-Length: 11\r
                \r
                hello world""";

        Response response = parser.parse(raw.getBytes(StandardCharsets.ISO_8859_1));

        assertEquals("HTTP/1.1", response.version());
        assertEquals(200, response.statusCode());
        assertEquals("OK", response.reasonPhrase());
        assertEquals(1, response.headers().size());
        assertEquals("Content-Length", response.headers().getFirst().name());
        assertEquals("11", response.headers().getFirst().value());
        assertArrayEquals("hello world".getBytes(StandardCharsets.ISO_8859_1), response.body());
    }

    @Test
    public void testVariousStatusCodes() {
        int[] statusCodes = { 201, 204, 301, 400, 404, 500 };
        String[] reasonPhrases = { "Created", "No Content", "Moved Permanently",
                "Bad Request", "Not Found", "Internal Server Error" };

        for (int i = 0; i < statusCodes.length; i++) {
            String raw = String.format("HTTP/1.1 %d %s\r\n\r\n", statusCodes[i], reasonPhrases[i]);
            Response response = parser.parse(raw.getBytes(StandardCharsets.ISO_8859_1));

            assertEquals(statusCodes[i], response.statusCode());
            assertEquals(reasonPhrases[i], response.reasonPhrase());
        }
    }

    @Test
    public void testReasonPhraseWithSpacesAndEmpty() {
        String rawWithSpaces = """
                HTTP/1.1 418 I'm a teapot\r
                \r
                """;
        Response response1 = parser.parse(rawWithSpaces.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("I'm a teapot", response1.reasonPhrase());

        String rawEmptyReason = """
                HTTP/1.1 200 \r
                \r
                """;
        Response response2 = parser.parse(rawEmptyReason.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("", response2.reasonPhrase());
    }

    @Test
    public void testSingleAndMultipleHeaders() {
        String rawSingle = """
                HTTP/1.1 200 OK\r
                Content-Type: text/plain\r
                \r
                """;
        Response res1 = parser.parse(rawSingle.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(1, res1.headers().size());
        assertEquals("Content-Type", res1.headers().get(0).name());
        assertEquals("text/plain", res1.headers().get(0).value());

        String rawMultiple = """
                HTTP/1.1 200 OK\r
                Server: smokin\r
                Content-Type: application/json\r
                Connection: keep-alive\r
                \r
                """;
        Response res2 = parser.parse(rawMultiple.getBytes(StandardCharsets.ISO_8859_1));
        List<Header> headers = res2.headers();
        assertEquals(3, headers.size());
        assertEquals(new Header("Server", "smokin"), headers.get(0));
        assertEquals(new Header("Content-Type", "application/json"), headers.get(1));
        assertEquals(new Header("Connection", "keep-alive"), headers.get(2));
    }

    @Test
    public void testHeaderValueContainingColon() {
        String raw = """
                HTTP/1.1 200 OK\r
                Authorization: Bearer token:abc:123\r
                Server: smokin:test\r
                \r
                """;
        Response response = parser.parse(raw.getBytes(StandardCharsets.ISO_8859_1));

        assertEquals("Bearer token:abc:123", response.headers().get(0).value());
        assertEquals("smokin:test", response.headers().get(1).value());
    }

    @Test
    public void testHeaderValueWithWhitespace() {
        String raw = """
                HTTP/1.1 200 OK\r
                Custom-Header:   value with internal spaces and tabs\t \r
                \r
                """;
        Response response = parser.parse(raw.getBytes(StandardCharsets.ISO_8859_1));

        assertEquals("value with internal spaces and tabs", response.headers().get(0).value());
    }

    @Test
    public void testEmptyHeaderSection() {
        String raw = """
                HTTP/1.1 200 OK\r
                \r
                """;
        Response response = parser.parse(raw.getBytes(StandardCharsets.ISO_8859_1));

        assertTrue(response.headers().isEmpty());
        assertEquals(0, response.body().length);
    }

    @Test
    public void testResponseWithBody() {
        String rawWithContentLength = """
                HTTP/1.1 200 OK\r
                Content-Length: 5\r
                \r
                hello""";
        Response res1 = parser.parse(rawWithContentLength.getBytes(StandardCharsets.ISO_8859_1));
        assertArrayEquals("hello".getBytes(StandardCharsets.ISO_8859_1), res1.body());

        String rawEofBody = """
                HTTP/1.1 200 OK\r
                \r
                hello world from eof""";
        Response res2 = parser.parse(rawEofBody.getBytes(StandardCharsets.ISO_8859_1));
        assertArrayEquals("hello world from eof".getBytes(StandardCharsets.ISO_8859_1), res2.body());

        String rawChunked = """
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
        Response res3 = parser.parse(rawChunked.getBytes(StandardCharsets.ISO_8859_1));
        assertArrayEquals("hello world".getBytes(StandardCharsets.ISO_8859_1), res3.body());
    }

    @Test
    public void testEmptyBody() {
        String rawZeroLength = """
                HTTP/1.1 200 OK\r
                Content-Length: 0\r
                \r
                """;
        Response res1 = parser.parse(rawZeroLength.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(0, res1.body().length);

        String raw204NoContent = """
                HTTP/1.1 204 No Content\r
                \r
                """;
        Response res2 = parser.parse(raw204NoContent.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(0, res2.body().length);

        String raw304NotModified = """
                HTTP/1.1 304 Not Modified\r
                \r
                """;
        Response res3 = parser.parse(raw304NotModified.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(0, res3.body().length);
    }

    @Test
    public void testBodyWithTrailingWhitespace() {
        String raw = """
                HTTP/1.1 200 OK\r
                Content-Length: 9\r
                \r
                hello   \s""";
        Response response = parser.parse(raw.getBytes(StandardCharsets.ISO_8859_1));

        assertArrayEquals("hello    ".getBytes(StandardCharsets.ISO_8859_1), response.body());
    }

    @Test
    public void testBinaryBodyPreservation() {
        byte[] binaryBody = new byte[] {
                0x00, (byte) 0xFF, 0x01, 0x02, (byte) 0x7F, (byte) 0x80,
                (byte) 0xFE, (byte) 0xAA, 0x55, 0x00, (byte) 0xFF
        };

        byte[] headers = ("HTTP/1.1 200 OK\r\nContent-Length: " + binaryBody.length + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);

        byte[] raw = new byte[headers.length + binaryBody.length];
        System.arraycopy(headers, 0, raw, 0, headers.length);
        System.arraycopy(binaryBody, 0, raw, headers.length, binaryBody.length);

        Response response = parser.parse(raw);

        assertArrayEquals(binaryBody, response.body());
    }

    @Test
    public void testMalformedCrlf() {
        // Lone LF in status line
        String loneLfInStatusLine = """
                HTTP/1.1 200 OK
                
                """;
        assertThrows(RuntimeException.class, () ->
                parser.parse(loneLfInStatusLine.getBytes(StandardCharsets.ISO_8859_1)));

        // Lone LF in header line
        String loneLfInHeader = """
                HTTP/1.1 200 OK\r
                Server: smokin
                \r
                """;
        assertThrows(RuntimeException.class, () ->
                parser.parse(loneLfInHeader.getBytes(StandardCharsets.ISO_8859_1)));

        // Lone CR in header line
        String loneCrInHeader = """
                HTTP/1.1 200 OK\r
                Server: smokin\r\r
                """;
        assertThrows(RuntimeException.class, () ->
                parser.parse(loneCrInHeader.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    public void testMissingOrMalformedStatusLine() {
        // Empty response
        assertThrows(RuntimeException.class, () -> parser.parse(new byte[0]));

        // Missing version
        String missingVersion = """
                200 OK\r
                \r
                """;
        assertThrows(RuntimeException.class, () ->
                parser.parse(missingVersion.getBytes(StandardCharsets.ISO_8859_1)));

        // Incomplete status line
        String incompleteStatusLine = """
                HTTP/1.1\r
                \r
                """;
        assertThrows(RuntimeException.class, () ->
                parser.parse(incompleteStatusLine.getBytes(StandardCharsets.ISO_8859_1)));

        // Non-digit status code
        String nonDigitStatusCode = """
                HTTP/1.1 20A OK\r
                \r
                """;
        assertThrows(RuntimeException.class, () ->
                parser.parse(nonDigitStatusCode.getBytes(StandardCharsets.ISO_8859_1)));

        // Too short status code
        String shortStatusCode = """
                HTTP/1.1 20 OK\r
                \r
                """;
        assertThrows(RuntimeException.class, () ->
                parser.parse(shortStatusCode.getBytes(StandardCharsets.ISO_8859_1)));

        // Too long status code
        String longStatusCode = """
                HTTP/1.1 2000 OK\r
                \r
                """;
        assertThrows(RuntimeException.class, () ->
                parser.parse(longStatusCode.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    public void testOtherMalformedInputs() {
        // Header without colon
        String headerWithoutColon = """
                HTTP/1.1 200 OK\r
                InvalidHeader\r
                \r
                """;
        assertThrows(RuntimeException.class, () ->
                parser.parse(headerWithoutColon.getBytes(StandardCharsets.ISO_8859_1)));

        // Header name with spaces
        String headerWithSpacesInName = """
                HTTP/1.1 200 OK\r
                Bad Header: val\r
                \r
                """;
        assertThrows(RuntimeException.class, () ->
                parser.parse(headerWithSpacesInName.getBytes(StandardCharsets.ISO_8859_1)));

        // Truncated body when Content-Length expects more bytes
        String truncatedBody = """
                HTTP/1.1 200 OK\r
                Content-Length: 10\r
                \r
                hello""";
        assertThrows(RuntimeException.class, () ->
                parser.parse(truncatedBody.getBytes(StandardCharsets.ISO_8859_1)));

        // Negative Content-Length
        String negativeContentLength = """
                HTTP/1.1 200 OK\r
                Content-Length: -5\r
                \r
                """;
        assertThrows(RuntimeException.class, () ->
                parser.parse(negativeContentLength.getBytes(StandardCharsets.ISO_8859_1)));

        // Conflicting Content-Length headers
        String conflictingContentLength = """
                HTTP/1.1 200 OK\r
                Content-Length: 5\r
                Content-Length: 10\r
                \r
                hello""";
        assertThrows(RuntimeException.class, () ->
                parser.parse(conflictingContentLength.getBytes(StandardCharsets.ISO_8859_1)));

        // Both Content-Length and chunked Transfer-Encoding
        String ambiguousFraming = """
                HTTP/1.1 200 OK\r
                Content-Length: 5\r
                Transfer-Encoding: chunked\r
                \r
                5\r
                hello\r
                0\r
                \r
                """;
        assertThrows(RuntimeException.class, () ->
                parser.parse(ambiguousFraming.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    public void testHeadMethodForbidsBody() {
        String raw = """
                HTTP/1.1 200 OK\r
                Content-Length: 5\r
                \r
                hello""";
        Response response = parser.parse(raw.getBytes(StandardCharsets.ISO_8859_1), "HEAD");

        assertEquals(0, response.body().length);
    }
}
