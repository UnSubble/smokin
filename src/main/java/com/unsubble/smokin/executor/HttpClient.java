package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Header;
import com.unsubble.smokin.parser.HttpResponseFramer;
import com.unsubble.smokin.parser.HttpResponseParser;
import com.unsubble.smokin.encoder.HttpRequestEncoder;
import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.Response;
import com.unsubble.smokin.transport.Transport;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public class HttpClient implements Client {

    private final HttpRequestEncoder encoder;
    private final HttpResponseParser parser;
    private final Transport transport;
    private final HttpResponseFramer framer;

    public HttpClient(HttpRequestEncoder encoder, HttpResponseParser parser,
                      HttpResponseFramer framer, Transport transport) {
        this.encoder = encoder;
        this.parser = parser;
        this.framer = framer;
        this.transport = transport;
    }

    @Override
    public void connect() throws IOException {
        transport.connect();
    }

    public void write(Request request) throws IOException {
        Objects.requireNonNull(request, "request must not be null");
        write(encoder.encode(request));
    }

    public void write(byte[] data) throws IOException {
        Objects.requireNonNull(data, "data must not be null");
        transport.connect();
        transport.write(data);
    }

    public Response read(String method) throws IOException {
        Objects.requireNonNull(method, "method must not be null");
        byte[] responseData = framer.read(method);
        Response response = parser.parse(responseData);

        if (shouldClose(response))
            close();

        return response;
    }

    @Override
    public Response send(Request request) throws IOException {
        write(request);
        return read(request.method());
    }

    @Override
    public Response send(Request request, LastByteCoordinator coordinator) throws IOException {
        Objects.requireNonNull(request, "request must not be null");
        if (coordinator == null) {
            return send(request);
        }

        int splitIndex = Math.max(0, request.body().length - 1);
        Request[] parts = request.split(splitIndex);
        Request firstPart = parts[0];
        Request secondPart = parts[1];

        try {
            write(firstPart);
        } catch (IOException e) {
            coordinator.abort(e);
            throw e;
        }

        coordinator.await();

        if (secondPart.body().length > 0) {
            write(secondPart.body());
        }

        return read(firstPart.method());
    }

    @Override
    public void close() throws IOException {
        transport.close();
    }

    private static boolean shouldClose(Response response) {
        return response.headers()
                .stream()
                .filter(header -> header.name().equalsIgnoreCase("Connection"))
                .map(Header::value)
                .filter(Objects::nonNull)
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .anyMatch(value -> value.equalsIgnoreCase("close"));
    }
}