package com.unsubble.smokin.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Request {

    private final String method;
    private final String path;
    private final String version;
    private final List<Header> headers;
    private final byte[] body;

    private Request(String method, String path, String version, List<Header> headers, byte[] body) {
        this.method = Objects.requireNonNull(method);
        this.path = Objects.requireNonNull(path);
        this.version = Objects.requireNonNull(version);
        this.headers = List.copyOf(headers);
        this.body = body;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public String version() {
        return version;
    }

    public List<Header> headers() {
        return headers;
    }

    public Request[] split(int idx) {
        throw new RuntimeException("not implemented yet.");
    }

    public static class Builder {

        private String method;
        private String path;
        private String version;
        private final List<Header> headers;
        private byte[] body;

        public Builder() {
            this.method = "GET";
            this.path = "/";
            this.version = "1.1";
            this.headers = new ArrayList<>();
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder addHeader(Header header) {
            headers.add(Objects.requireNonNull(header));
            return this;
        }

        public Builder setHeader(Header header) {
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

        public Builder body(byte[] body) {
            this.body = Objects.requireNonNull(body);
            return this;
        }

        public Request build() {
            return new Request(method, path, version, headers, body);
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }
}