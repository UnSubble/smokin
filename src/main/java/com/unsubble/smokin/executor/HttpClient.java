package com.unsubble.smokin.executor;

import com.unsubble.smokin.parser.HttpResponseFramer;
import com.unsubble.smokin.parser.HttpResponseParser;
import com.unsubble.smokin.encoder.HttpRequestEncoder;
import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.Response;
import com.unsubble.smokin.transport.Transport;

import java.io.IOException;

public class HttpClient {

    private final HttpRequestEncoder encoder;
    private final HttpResponseParser parser;
    private final Transport transport;
    private final HttpResponseFramer framer;

    public HttpClient(HttpRequestEncoder encoder, HttpResponseParser parser, Transport transport) {
        this.encoder = encoder;
        this.parser = parser;
        this.transport = transport;
        this.framer = new HttpResponseFramer(transport);
    }

    public Response send(Request request) throws IOException {
        byte[] data = encoder.encode(request);

        transport.connect();
        transport.write(data);

        byte[] responseData = framer.read();

        return parser.parse(responseData);
    }
}