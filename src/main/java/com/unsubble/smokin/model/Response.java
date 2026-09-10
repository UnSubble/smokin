package com.unsubble.smokin.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class Response {

    private final String version;
    private final int statusCode;
    private final String reasonPhrase;
    private final List<Header> headers;
    private final byte[] body;

    private Response(String version, int statusCode, String reasonPhrase, List<Header> headers, byte[] body) {
        this.version = Objects.requireNonNull(version);
        this.statusCode = Objects.requireNonNull(statusCode);
        this.reasonPhrase = Objects.requireNonNull(reasonPhrase);
        this.headers = List.copyOf(headers);
        this.body = body;
    }

    public String version() {
        return version;
    }

    public int statusCode() {
        return statusCode;
    }

    public String reasonPhrase() {
        return reasonPhrase;
    }

    public List<Header> headers() {
        return List.copyOf(headers);
    }

    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }

    public static class Builder {

        private String version;
        private int statusCode;
        private String reasonPhrase;
        private final List<Header> headers;
        private byte[] body;

        public Builder() {
            this.version = "1.1";
            this.statusCode = 200;
            this.reasonPhrase = "OK";
            this.headers = new ArrayList<>();
            this.body = new byte[0];
        }

        public Response.Builder statusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        public Response.Builder reasonPhrase(String reasonPhrase) {
            this.reasonPhrase = reasonPhrase;
            return this;
        }

        public Response.Builder version(String version) {
            this.version = version;
            return this;
        }

        public Response.Builder addHeader(Header header) {
            headers.add(Objects.requireNonNull(header));
            return this;
        }

        public Response.Builder setHeader(Header header) {
            Objects.requireNonNull(header);

            int last = -1;

            for (int i = 0; i < headers.size(); ++i) {
                if (headers.get(i).name().equalsIgnoreCase(header.name())) {
                    last = i;
                }
            }

            if (last == -1) {
                headers.add(header);
            } else {
                headers.set(last, header);
            }

            return this;
        }

        public Response.Builder body(byte[] body) {
            this.body = Objects.requireNonNull(body);
            return this;
        }

        public Response build() {
            return new Response(version, statusCode, reasonPhrase, headers, body);
        }
    }

    public static Response.Builder newBuilder() {
        return new Response.Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Response response)) return false;
        return Objects.equals(version, response.version) &&
                Objects.equals(statusCode, response.statusCode) &&
                Objects.equals(headers, response.headers) &&
                Objects.deepEquals(body, response.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, statusCode, headers, Arrays.hashCode(body));
    }
}
