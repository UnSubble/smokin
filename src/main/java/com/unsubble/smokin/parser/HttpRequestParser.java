package com.unsubble.smokin.parser;

import com.unsubble.smokin.model.Header;
import com.unsubble.smokin.model.Request;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class HttpRequestParser {

    public HttpRequestParser() {

    }

    public Request parse(String requestStr) {
        Request.Builder builder = Request.newBuilder();
        parse(requestStr, builder, 0);
        return builder.build();
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

        idx = parseHeaders(requestStr, builder, idx);

        return parseBody(requestStr, builder, idx);
    }

    private int parseHeaders(String requestStr, Request.Builder builder, int idx) {
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

                if (c == '\r' || c == '\n') {
                    throw new RuntimeException("Invalid header");
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

            builder.addHeader(new Header(name.toString().trim(), value.toString().trim())
            );

            idx += 2; // CRLF
        }

        return idx;
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

            result.append(c);
            idx++;
        }

        return idx;
    }

    private int parseBlock(String requestStr, StringBuilder result, int idx) {
        while (idx < requestStr.length() && Character.isWhitespace(requestStr.charAt(idx))) {
            idx++;
        }

        while (idx < requestStr.length()) {
            char c = requestStr.charAt(idx);

            if (Character.isWhitespace(c)) {
                while (idx < requestStr.length() && Character.isWhitespace(requestStr.charAt(idx))) {
                    idx++;
                }

                return idx;
            }

            result.append(c);
            idx++;
        }

        return idx;
    }

    private int parseBody(String requestStr, Request.Builder builder, int idx) {
        if (idx >= requestStr.length()) {
            return idx;
        }

        requestStr = requestStr.substring(idx);

        byte[] body = requestStr.getBytes(StandardCharsets.ISO_8859_1);
        builder.body(body);

        return requestStr.length();
    }

    private StringBuilder resetStringBuilder(StringBuilder builder) {
        Objects.requireNonNull(builder);
        if (!builder.isEmpty())
            builder.setLength(0);
        return builder;
    }
}
