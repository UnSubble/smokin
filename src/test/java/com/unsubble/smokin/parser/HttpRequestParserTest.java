package com.unsubble.smokin.parser;

import com.unsubble.smokin.model.Header;
import com.unsubble.smokin.model.Request;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class HttpRequestParserTest {

    private static final String GET = "GET";
    private static final String POST = "POST";

    private static final String VERSION_1_1 = "HTTP/1.1";

    private static final String ROOT_PATH = "/";

    private static final String CRLF = "\r\n";

    private static String buildRequestString(String method, String path, String version,
                                             List<Header> headers, String body) {
        if (headers == null)
            headers = Collections.emptyList();
        if (body == null)
            body = "";

        String headersStr = headers.stream()
                .map(header -> header.name() + ": " + header.value())
                .collect(Collectors.joining(CRLF));

        if (headersStr.isEmpty()) {
            return String.format("%s %s %s%s%s%s", method, path, version, CRLF, CRLF, body);
        }

        return String.format("%s %s %s%s%s%s%s%s", method, path, version, CRLF, headersStr, CRLF, CRLF, body);
    }

    @Test
    public void testBasicHttpRequestParsing() {
        Request expected = Request.newBuilder()
                .method(GET)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .build();

        String request = buildRequestString(GET, ROOT_PATH, VERSION_1_1, new ArrayList<>(), "");

        HttpRequestParser requestParser = new HttpRequestParser();
        Request actual = requestParser.parse(request);

        assertEquals(expected, actual);
    }

    @Test
    public void testPostRequestParsing() {
        Request expected = Request.newBuilder()
                .method(POST)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .build();

        String request = buildRequestString(POST, ROOT_PATH, VERSION_1_1, Collections.emptyList(), "");

        HttpRequestParser requestParser = new HttpRequestParser();
        Request actual = requestParser.parse(request);

        assertEquals(expected, actual);
    }

    @Test
    public void testBasicHttpRequestWithTrailingWhitespacesParsing() {
        Request expected = Request.newBuilder()
                .method(GET)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .build();

        String request = buildRequestString(GET, ROOT_PATH, VERSION_1_1 + "    ", new ArrayList<>(), "");

        HttpRequestParser requestParser = new HttpRequestParser();
        Request actual = requestParser.parse(request);

        assertEquals(expected, actual);
    }

    @Test
    public void testBasicHttpRequestWithWhitespacesAroundParsing() {
        Request expected = Request.newBuilder()
                .method(GET)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .build();

        String request = buildRequestString("  " + GET, "    " +  ROOT_PATH,
                VERSION_1_1 + "    ", new ArrayList<>(), "");

        HttpRequestParser requestParser = new HttpRequestParser();
        Request actual = requestParser.parse(request);

        assertEquals(expected, actual);
    }

    @Test
    public void testPathParsing() {
        String path = "/api/users/123";

        Request expected = Request.newBuilder()
                .method(GET)
                .path(path)
                .version(VERSION_1_1)
                .build();

        String request = buildRequestString(GET, path, VERSION_1_1, Collections.emptyList(), "");

        HttpRequestParser requestParser = new HttpRequestParser();
        Request actual = requestParser.parse(request);

        assertEquals(expected, actual);
    }

    @Test
    public void testHeaderParsing() {
        List<Header> headers = List.of(
                new Header("Host", "example.com"),
                new Header("User-Agent", "Mozilla/5.0")
        );

        Request expected = Request.newBuilder()
                .method(GET)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .addHeader(headers.get(0))
                .addHeader(headers.get(1))
                .build();

        String request = buildRequestString(GET, ROOT_PATH, VERSION_1_1, headers, "");

        HttpRequestParser requestParser = new HttpRequestParser();
        Request actual = requestParser.parse(request);

        assertEquals(expected, actual);
    }

    @Test
    public void testSingleHeaderParsing() {
        Header header = new Header("Content-Type", "application/json");

        Request expected = Request.newBuilder()
                .method(GET)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .addHeader(header)
                .build();

        String request = buildRequestString(GET, ROOT_PATH, VERSION_1_1, List.of(header), "");

        HttpRequestParser requestParser = new HttpRequestParser();
        Request actual = requestParser.parse(request);

        assertEquals(expected, actual);
    }

    @Test
    public void testBodyParsing() {
        String body = "hello world";

        Request expected = Request.newBuilder()
                .method(POST)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .body(body.getBytes())
                .build();

        String request = buildRequestString(POST, ROOT_PATH, VERSION_1_1, Collections.emptyList(), body);

        HttpRequestParser requestParser = new HttpRequestParser();
        Request actual = requestParser.parse(request);

        assertArrayEquals(expected.body(), actual.body());
    }

    @Test
    public void testBodyWithTrailingWhitespaceParsing() {
        String body = "hello world   ";

        Request expected = Request.newBuilder()
                .method(POST)
                .path(ROOT_PATH)
                .version(VERSION_1_1)
                .body(body.getBytes())
                .build();

        String request = buildRequestString(POST, ROOT_PATH, VERSION_1_1, Collections.emptyList(), body);

        HttpRequestParser requestParser = new HttpRequestParser();
        Request actual = requestParser.parse(request);

        assertArrayEquals(expected.body(), actual.body());
    }

    @Test
    public void testMultipleHeadersParsing() {
        List<Header> headers = List.of(
                new Header("Host", "example.com"),
                new Header("Accept", "application/json"),
                new Header("User-Agent", "Mozilla/5.0"),
                new Header("Connection", "keep-alive")
        );

        Request expected = Request.newBuilder()
                .method(GET)
                .path("/api/test")
                .version(VERSION_1_1)
                .addHeader(headers.get(0))
                .addHeader(headers.get(1))
                .addHeader(headers.get(2))
                .addHeader(headers.get(3))
                .build();

        String request = buildRequestString(GET, "/api/test", VERSION_1_1, headers, "");

        HttpRequestParser requestParser = new HttpRequestParser();
        Request actual = requestParser.parse(request);

        assertEquals(expected, actual);
    }

    @Test
    public void testHeadersAndBodyParsing() {
        List<Header> headers = List.of(
                new Header("Host", "example.com"),
                new Header("Content-Type", "application/json")
        );

        String body = "{\"name\":\"test\"}";

        Request expected = Request.newBuilder()
                .method(POST)
                .path("/api/users")
                .version(VERSION_1_1)
                .addHeader(headers.get(0))
                .addHeader(headers.get(1))
                .body(body.getBytes())
                .build();

        String request = buildRequestString(POST, "/api/users", VERSION_1_1, headers, body);

        HttpRequestParser requestParser = new HttpRequestParser();
        Request actual = requestParser.parse(request);

        assertEquals(expected, actual);
        assertArrayEquals(expected.body(), actual.body());
    }

    @Test
    public void testInvalidRequest() {
        String request = "INVALID";

        HttpRequestParser requestParser = new HttpRequestParser();

        assertThrows(RuntimeException.class, () -> requestParser.parse(request));
    }

    @Test
    public void testInvalidHeader() {
        String request = """
                GET / HTTP/1.1\r
                InvalidHeader\r
                \r
                """;

        HttpRequestParser requestParser = new HttpRequestParser();

        assertThrows(RuntimeException.class, () -> requestParser.parse(request));
    }

    @Test
    public void testInvalidHeaderCrlf() {
        String request =
                """
                GET / HTTP/1.1\r
                Host: example.com\r
                
                """;

        HttpRequestParser requestParser = new HttpRequestParser();

        assertThrows(RuntimeException.class, () -> requestParser.parse(request));
    }

    @Test
    public void testHeaderWithoutColon() {
        String request =
                """
                GET / HTTP/1.1\r
                Host example.com\r
                \r
                """;

        HttpRequestParser requestParser = new HttpRequestParser();

        assertThrows(RuntimeException.class, () -> requestParser.parse(request));
    }

    @Test
    public void testMissingVersion() {
        String request =
                """
                        GET /\r
                        \r
                        """;

        HttpRequestParser requestParser = new HttpRequestParser();

        assertThrows(RuntimeException.class, () -> requestParser.parse(request));
    }

    @Test
    public void testMalformedVersionParsing() {
        String request = buildRequestString(GET, ROOT_PATH, "HTTP/1 .1", new ArrayList<>(), "");

        HttpRequestParser requestParser = new HttpRequestParser();

        assertThrows(RuntimeException.class, () -> requestParser.parse(request));
    }

    @Test
    public void testMalformedMethodParsing() {
        String request = buildRequestString("GE T", ROOT_PATH, VERSION_1_1, new ArrayList<>(), "");

        HttpRequestParser requestParser = new HttpRequestParser();

        assertThrows(RuntimeException.class, () -> requestParser.parse(request));
    }

    @Test
    public void testMalformedPathParsing() {
        String request = buildRequestString(GET, "/first /second", VERSION_1_1, new ArrayList<>(), "");

        HttpRequestParser requestParser = new HttpRequestParser();

        assertThrows(RuntimeException.class, () -> requestParser.parse(request));
    }
}
