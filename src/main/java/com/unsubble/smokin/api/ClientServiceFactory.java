package com.unsubble.smokin.api;

import com.unsubble.smokin.encoder.Http1RequestEncoder;
import com.unsubble.smokin.encoder.HttpRequestEncoder;
import com.unsubble.smokin.executor.Client;
import com.unsubble.smokin.executor.ClientService;
import com.unsubble.smokin.executor.HttpClient;
import com.unsubble.smokin.model.Protocol;
import com.unsubble.smokin.model.Version;
import com.unsubble.smokin.parser.*;
import com.unsubble.smokin.transport.TcpTransport;
import com.unsubble.smokin.transport.TlsTransport;
import com.unsubble.smokin.transport.Transport;

import java.util.Objects;

public class ClientServiceFactory {

    ClientServiceFactory() {
    }

    ClientService create(Protocol protocol, String host, int port) {
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(host);

        return new ClientService(() -> createClient(Version.UNKNOWN, protocol, host, port));
    }

    ClientService create(Version version, Protocol protocol, String host, int port) {
        Objects.requireNonNull(version);
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(host);

        return new ClientService(() -> createClient(version, protocol, host, port));
    }

    private Client createClient(Version version, Protocol protocol, String host, int port) {
        HttpRequestEncoder requestEncoder = createRequestEncoder(version);
        HttpResponseParser responseParser = createResponseParser(version);
        Transport transport = createTransport(protocol, host, port);
        HttpResponseFramer responseFramer = createResponseFramer(version, transport);

        return new HttpClient(requestEncoder, responseParser, responseFramer, transport);
    }

    private Transport createTransport(Protocol protocol, String host, int port) {
        return switch (protocol) {
            case HTTP -> new TcpTransport(host, port);
            case HTTPS -> new TlsTransport(host, port);
        };
    }

    private HttpResponseFramer createResponseFramer(Version version, Transport transport) {
        return switch (version) {
            case UNKNOWN, HTTP_1_1 -> new Http1ResponseFramer(transport);
        };
    }

    private HttpRequestEncoder createRequestEncoder(Version version) {
        return switch (version) {
            case UNKNOWN, HTTP_1_1 -> new Http1RequestEncoder();
        };
    }

    private HttpResponseParser createResponseParser(Version version) {
        return switch (version) {
            case UNKNOWN, HTTP_1_1 -> new Http1ResponseParser();
        };
    }
}