package com.unsubble.smokin.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;

public class TcpTransport implements Transport {

    private final String host;
    private final int port;
    private boolean closed;

    private Socket socket;

    public TcpTransport(String host, int port) {
        this.host = host;
        this.port = port;
        closed = false;
    }

    @Override
    public void connect() throws IOException {
        close();
        closed = false;
        socket = new Socket(host, port);
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