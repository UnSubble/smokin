package com.unsubble.smokin.transport;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

public class TlsTransport implements Transport {

    private final String host;
    private final int port;
    private boolean closed;

    private SSLSocket socket;

    public TlsTransport(String host, int port) {
        this.host = host;
        this.port = port;
        closed = false;
    }

    @Override
    public void connect() throws IOException {
        if (socket != null && !socket.isClosed()) {
            return;
        }

        closed = false;

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        socket = (SSLSocket) factory.createSocket(host, port);

        SSLParameters params = socket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(params);

        socket.startHandshake();
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (closed)
            throw new IOException("Transport is closed");

        Objects.requireNonNull(data);

        OutputStream output = socket.getOutputStream();
        output.write(data);
        output.flush();
    }

    @Override
    public byte[] read() throws IOException {
        if (closed)
            throw new IOException("Transport is closed");

        InputStream input = socket.getInputStream();

        return input.readAllBytes();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (closed)
            throw new IOException("Transport is closed");

        Objects.requireNonNull(buffer);

        return socket.getInputStream().read(buffer, offset, length);
    }

    @Override
    public int readSingle() throws IOException {
        if (closed)
            throw new IOException("Transport is closed");

        InputStream input = socket.getInputStream();

        return input.read();
    }

    @Override
    public void close() throws IOException {
        if (socket != null) {
            socket.close();
            closed = true;
        }
    }
}