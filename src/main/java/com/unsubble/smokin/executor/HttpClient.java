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

public class HttpClient implements AutoCloseable {

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

    public Response send(Request request) throws IOException {
        byte[] data = encoder.encode(request);

        transport.connect();
        transport.write(data);

        byte[] responseData = framer.read(request.method());

        Response response = parser.parse(responseData);

        if (shouldClose(response))
            close();

        return response;
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