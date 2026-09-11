package com.unsubble.smokin.parser;

import com.unsubble.smokin.model.Header;
import com.unsubble.smokin.model.Response;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class Http1ResponseParser implements HttpResponseParser {

    public Http1ResponseParser() {

    }

    @Override
    public Response parse(byte[] data) {
        return parse(data, null);
    }

    public Response parse(byte[] data, String requestMethod) {
        String responseStr = new String(data, StandardCharsets.ISO_8859_1);
        responseStr = responseStr.stripLeading();

        Response.Builder builder = Response.newBuilder();
        int idx = skipLeadingBlankLines(responseStr, 0);
        parse(responseStr, builder, idx, requestMethod);
        return builder.build();
    }

    private int skipLeadingBlankLines(String responseStr, int idx) {
        while (idx < responseStr.length()) {
            char c = responseStr.charAt(idx);

            if (c == '\r' && idx + 1 < responseStr.length() && responseStr.charAt(idx + 1) == '\n') {
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

    private int parse(String responseStr, Response.Builder builder, int idx, String requestMethod) {
        StringBuilder strBuilder = new StringBuilder();

        idx = parseBlock(responseStr, resetStringBuilder(strBuilder), idx);
        if (strBuilder.isEmpty()) {
            throw new RuntimeException("Invalid version");
        }
        builder.version(strBuilder.toString());

        idx = parseBlock(responseStr, resetStringBuilder(strBuilder), idx);
        int statusCode = parseStatusCode(strBuilder.toString());
        builder.statusCode(statusCode);

        idx = parseReasonPhrase(responseStr, resetStringBuilder(strBuilder), idx);
        builder.reasonPhrase(strBuilder.toString());

        BodyFraming framing = new BodyFraming();
        boolean bodyForbidden = isBodyForbidden(statusCode, requestMethod);

        if (idx >= responseStr.length()) {
            return parseBody(responseStr, builder, idx, framing, bodyForbidden);
        }

        if (responseStr.charAt(idx) == '\r') {
            if (idx + 1 >= responseStr.length()
                    || responseStr.charAt(idx + 1) != '\n') {
                throw new RuntimeException("Invalid CRLF");
            }

            idx += 2;
            return parseBody(responseStr, builder, idx, framing, bodyForbidden);
        }

        idx = parseHeaders(responseStr, builder, idx, framing);

        return parseBody(responseStr, builder, idx, framing, bodyForbidden);
    }

    private static boolean isBodyForbidden(int statusCode, String requestMethod) {
        if (requestMethod != null && requestMethod.equalsIgnoreCase("HEAD")) {
            return true;
        }

        if (statusCode >= 100 && statusCode < 200) {
            return true;
        }

        return statusCode == 204 || statusCode == 304;
    }

    private int parseStatusCode(String statusCode) {
        if (statusCode.length() != 3) {
            throw new RuntimeException("Invalid status code");
        }

        for (int i = 0; i < 3; i++) {
            if (!Character.isDigit(statusCode.charAt(i))) {
                throw new RuntimeException("Invalid status code");
            }
        }

        return Integer.parseInt(statusCode);
    }

    private int parseReasonPhrase(String responseStr, StringBuilder result, int idx) {
        while (idx < responseStr.length()) {
            char c = responseStr.charAt(idx);

            if (c == '\r') {
                if (idx + 1 >= responseStr.length()
                        || responseStr.charAt(idx + 1) != '\n') {
                    throw new RuntimeException("Invalid CRLF");
                }

                return idx + 2;
            }

            if (c == '\n') {
                throw new RuntimeException("Invalid CRLF");
            }

            result.append(c);
            idx++;
        }

        return idx;
    }

    private int parseHeaders(String responseStr, Response.Builder builder, int idx, BodyFraming framing) {
        while (idx < responseStr.length()) {

            if (responseStr.charAt(idx) == '\r') {
                if (idx + 1 >= responseStr.length()
                        || responseStr.charAt(idx + 1) != '\n') {
                    throw new RuntimeException("Invalid CRLF");
                }

                return idx + 2;
            }

            StringBuilder name = new StringBuilder();

            while (idx < responseStr.length()) {
                char c = responseStr.charAt(idx);

                if (c == ':') {
                    break;
                }

                if (!ParserUtil.isTokenChar(c)) {
                    throw new RuntimeException("Invalid header name");
                }

                name.append(c);
                idx++;
            }

            if (name.isEmpty() || idx >= responseStr.length()) {
                throw new RuntimeException("Invalid header name");
            }

            idx++; // ':'

            StringBuilder value = new StringBuilder();

            while (idx < responseStr.length()) {
                char c = responseStr.charAt(idx);

                if (c == '\r') {
                    if (idx + 1 >= responseStr.length()
                            || responseStr.charAt(idx + 1) != '\n') {
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

            if (idx >= responseStr.length()) {
                throw new RuntimeException("Invalid header");
            }

            String headerName = name.toString();
            String headerValue = ParserUtil.trimOWS(value.toString());

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

    private int parseBlock(String responseStr, StringBuilder result, int idx) {
        while (idx < responseStr.length()) {
            char c = responseStr.charAt(idx);

            if (ParserUtil.isOWS(c)) {
                while (idx < responseStr.length() && ParserUtil.isOWS(responseStr.charAt(idx))) {
                    idx++;
                }

                return idx;
            }

            if (c == '\r' || c == '\n') {
                throw new RuntimeException("Invalid status line");
            }

            result.append(c);
            idx++;
        }

        return idx;
    }

    private int parseBody(String responseStr, Response.Builder builder, int idx,
                          BodyFraming framing, boolean bodyForbidden) {
        if (bodyForbidden) {
            return idx;
        }

        if (framing.chunked && framing.contentLength != -1) {
            throw new RuntimeException("Ambiguous body framing: " +
                    "both Content-Length and chunked Transfer-Encoding present");
        }

        if (framing.chunked) {
            return parseChunkedBody(responseStr, builder, idx);
        }

        if (framing.contentLength != -1) {
            long len = framing.contentLength;

            if (idx + len > responseStr.length()) {
                throw new RuntimeException("Truncated body: expected " + len + " bytes");
            }

            int end = (int) (idx + len);
            builder.body(responseStr.substring(idx, end).getBytes(StandardCharsets.ISO_8859_1));
            return end;
        }

        if (idx >= responseStr.length()) {
            return idx;
        }

        String remaining = responseStr.substring(idx);
        builder.body(remaining.getBytes(StandardCharsets.ISO_8859_1));
        return responseStr.length();
    }

    private int parseChunkedBody(String responseStr, Response.Builder builder, int idx) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        while (true) {
            int lineEnd = responseStr.indexOf("\r\n", idx);
            long chunkSize = getChunkSize(responseStr, idx, lineEnd);

            idx = lineEnd + 2;

            if (chunkSize == 0) {
                idx = parseHeaders(responseStr, builder, idx, new BodyFraming());
                break;
            }

            int size = (int) chunkSize;
            if (idx + size > responseStr.length()) {
                throw new RuntimeException("Truncated chunk data");
            }

            out.writeBytes(responseStr.substring(idx, idx + size).getBytes(StandardCharsets.ISO_8859_1));
            idx += size;

            if (idx + 2 > responseStr.length()
                    || responseStr.charAt(idx) != '\r'
                    || responseStr.charAt(idx + 1) != '\n') {
                throw new RuntimeException("Invalid chunk terminator");
            }
            idx += 2;
        }

        builder.body(out.toByteArray());
        return idx;
    }

    private static long getChunkSize(String responseStr, int idx, int lineEnd) {
        if (lineEnd < 0) {
            throw new RuntimeException("Invalid chunk size line");
        }

        String sizeLine = responseStr.substring(idx, lineEnd);
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

    private static final class BodyFraming {
        long contentLength = -1;
        boolean chunked = false;
    }
}