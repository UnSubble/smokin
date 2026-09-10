package com.unsubble.smokin.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Objects;

public class TcpTransport implements Transport {

    private final String host;
    private final int port;

    private Socket socket;

    public TcpTransport(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void connect() throws IOException {
        close();
        socket = new Socket(host, port);
    }

    @Override
    public void write(byte[] data) throws IOException {
        Objects.requireNonNull(data);
        OutputStream output = socket.getOutputStream();
        output.write(data);
        output.flush();
    }

    @Override
    public byte[] read() throws IOException {
        InputStream input = socket.getInputStream();

        return input.readAllBytes();
    }

    @Override
    public void close() throws IOException {
        if (socket != null) {
            socket.close();
        }
    }
}