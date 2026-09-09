package com.unsubble.smokin.parser;

import com.unsubble.smokin.model.Header;
import com.unsubble.smokin.model.Request;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class HttpRequestParser {

    public HttpRequestParser() {

    }

    public Request parse(String requestStr) {
        Request.Builder builder = Request.newBuilder();
        int idx = skipLeadingBlankLines(requestStr, 0);
        parse(requestStr, builder, idx);
        return builder.build();
    }

    private int skipLeadingBlankLines(String requestStr, int idx) {
        while (idx < requestStr.length()) {
            char c = requestStr.charAt(idx);

            if (c == '\r' && idx + 1 < requestStr.length() && requestStr.charAt(idx + 1) == '\n') {
                idx += 2;
                continue;
            }

            if (c == '\n') {
                idx++;
                continue;
            }

            break;
        }

        return idx;
    }

    private int parse(String requestStr, Request.Builder builder, int idx) {
        StringBuilder strBuilder = new StringBuilder();

        idx = parseBlock(requestStr, resetStringBuilder(strBuilder), idx);
        if (strBuilder.isEmpty()) {
            throw new RuntimeException("Invalid method");
        }
        builder.method(strBuilder.toString());

        idx = parseBlock(requestStr, resetStringBuilder(strBuilder), idx);
        if (strBuilder.isEmpty()) {
            throw new RuntimeException("Invalid path");
        }
        builder.path(strBuilder.toString());

        idx = parseVersion(requestStr, resetStringBuilder(strBuilder), idx);
        if (strBuilder.isEmpty()) {
            throw new RuntimeException("Invalid version");
        }
        builder.version(strBuilder.toString());

        BodyFraming framing = new BodyFraming();

        if (idx >= requestStr.length()) {
            return parseBody(requestStr, builder, idx, framing);
        }

        if (requestStr.charAt(idx) == '\r') {
            if (idx + 1 >= requestStr.length()
                    || requestStr.charAt(idx + 1) != '\n') {
                throw new RuntimeException("Invalid CRLF");
            }

            idx += 2;
            return parseBody(requestStr, builder, idx, framing);
        }

        idx = parseHeaders(requestStr, builder, idx, framing);

        return parseBody(requestStr, builder, idx, framing);
    }

    private int parseHeaders(String requestStr, Request.Builder builder, int idx, BodyFraming framing) {
        while (idx < requestStr.length()) {

            if (requestStr.charAt(idx) == '\r') {
                if (idx + 1 >= requestStr.length()
                        || requestStr.charAt(idx + 1) != '\n') {
                    throw new RuntimeException("Invalid CRLF");
                }

                return idx + 2;
            }

            StringBuilder name = new StringBuilder();

            while (idx < requestStr.length()) {
                char c = requestStr.charAt(idx);

                if (c == ':') {
                    break;
                }

                if (!isTokenChar(c)) {
                    throw new RuntimeException("Invalid header name");
                }

                name.append(c);
                idx++;
            }

            if (name.isEmpty() || idx >= requestStr.length()) {
                throw new RuntimeException("Invalid header name");
            }

            idx++; // ':'

            StringBuilder value = new StringBuilder();

            while (idx < requestStr.length()) {
                char c = requestStr.charAt(idx);

                if (c == '\r') {
                    if (idx + 1 >= requestStr.length()
                            || requestStr.charAt(idx + 1) != '\n') {
                        throw new RuntimeException("Invalid CRLF");
                    }

                    break;
                }

                if (c == '\n') {
                    throw new RuntimeException("Invalid CRLF");
                }

                value.append(c);
                idx++;
            }

            if (idx >= requestStr.length()) {
                throw new RuntimeException("Invalid header");
            }

            String headerName = name.toString();
            String headerValue = trimOWS(value.toString());

            builder.addHeader(new Header(headerName, headerValue));
            recordFraming(framing, headerName, headerValue);

            idx += 2; // CRLF
        }

        return idx;
    }

    private void recordFraming(BodyFraming framing, String name, String value) {
        if (name.equalsIgnoreCase("Content-Length")) {
            long len;
            try {
                len = Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new RuntimeException("Invalid Content-Length");
            }

            if (len < 0) {
                throw new RuntimeException("Invalid Content-Length");
            }

            if (framing.contentLength != -1 && framing.contentLength != len) {
                throw new RuntimeException("Conflicting Content-Length headers");
            }

            framing.contentLength = len;
        } else if (name.equalsIgnoreCase("Transfer-Encoding")) {
            if (containsToken(value, "chunked")) {
                framing.chunked = true;
            }
        }
    }

    private static boolean containsToken(String headerValue, String token) {
        for (String part : headerValue.split(",")) {
            if (part.trim().equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    private int parseVersion(String requestStr, StringBuilder result, int idx) {
        while (idx < requestStr.length()) {
            char c = requestStr.charAt(idx);

            if (c == '\r') {
                if (idx + 1 >= requestStr.length() || requestStr.charAt(idx + 1) != '\n') {
                    throw new RuntimeException("Invalid CRLF");
                }

                return idx + 2;
            }

            if (isOWS(c)) {
                throw new RuntimeException("Invalid version");
            }

            result.append(c);
            idx++;
        }

        return idx;
    }

    private int parseBlock(String requestStr, StringBuilder result, int idx) {
        while (idx < requestStr.length()) {
            char c = requestStr.charAt(idx);

            if (isOWS(c)) {
                while (idx < requestStr.length() && isOWS(requestStr.charAt(idx))) {
                    idx++;
                }

                return idx;
            }

            if (c == '\r' || c == '\n') {
                throw new RuntimeException("Invalid request line");
            }

            result.append(c);
            idx++;
        }

        return idx;
    }

    private int parseBody(String requestStr, Request.Builder builder, int idx, BodyFraming framing) {
        if (framing.chunked && framing.contentLength != -1) {
            throw new RuntimeException("Ambiguous body framing: both Content-Length and chunked Transfer-Encoding present");
        }

        if (framing.chunked) {
            return parseChunkedBody(requestStr, builder, idx);
        }

        if (framing.contentLength != -1) {
            long len = framing.contentLength;

            if (idx + len > requestStr.length()) {
                throw new RuntimeException("Truncated body: expected " + len + " bytes");
            }

            int end = (int) (idx + len);
            builder.body(requestStr.substring(idx, end).getBytes(StandardCharsets.ISO_8859_1));
            return end;
        }

        if (idx >= requestStr.length()) {
            return idx;
        }

        String remaining = requestStr.substring(idx);
        builder.body(remaining.getBytes(StandardCharsets.ISO_8859_1));
        return requestStr.length();
    }

    private int parseChunkedBody(String requestStr, Request.Builder builder, int idx) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        while (true) {
            int lineEnd = requestStr.indexOf("\r\n", idx);
            long chunkSize = getChunkSize(requestStr, idx, lineEnd);

            idx = lineEnd + 2;

            if (chunkSize == 0) {
                idx = parseHeaders(requestStr, builder, idx, new BodyFraming());
                break;
            }

            int size = (int) chunkSize;
            if (idx + size > requestStr.length()) {
                throw new RuntimeException("Truncated chunk data");
            }

            out.writeBytes(requestStr.substring(idx, idx + size).getBytes(StandardCharsets.ISO_8859_1));
            idx += size;

            if (idx + 2 > requestStr.length()
                    || requestStr.charAt(idx) != '\r'
                    || requestStr.charAt(idx + 1) != '\n') {
                throw new RuntimeException("Invalid chunk terminator");
            }
            idx += 2;
        }

        builder.body(out.toByteArray());
        return idx;
    }

    private static long getChunkSize(String requestStr, int idx, int lineEnd) {
        if (lineEnd < 0) {
            throw new RuntimeException("Invalid chunk size line");
        }

        String sizeLine = requestStr.substring(idx, lineEnd);
        int semicolon = sizeLine.indexOf(';'); // ignore chunk extensions
        String sizeHex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();

        long chunkSize;
        try {
            chunkSize = Long.parseLong(sizeHex, 16);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid chunk size");
        }

        if (chunkSize < 0 || chunkSize > Integer.MAX_VALUE) {
            throw new RuntimeException("Invalid chunk size");
        }
        return chunkSize;
    }

    private StringBuilder resetStringBuilder(StringBuilder builder) {
        Objects.requireNonNull(builder);
        if (!builder.isEmpty())
            builder.setLength(0);
        return builder;
    }

    private static boolean isOWS(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean isTokenChar(char c) {
        if (c <= 32 || c == 127) {
            return false;
        }

        return switch (c) {
            case '(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', '{', '}' -> false;
            default -> c < 128;
        };
    }

    private static String trimOWS(String value) {
        int start = 0;
        int end = value.length();

        while (start < end && isOWS(value.charAt(start))) {
            start++;
        }

        while (end > start && isOWS(value.charAt(end - 1))) {
            end--;
        }

        return value.substring(start, end);
    }

    private static final class BodyFraming {
        long contentLength = -1;
        boolean chunked = false;
    }
}