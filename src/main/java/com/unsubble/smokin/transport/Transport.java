package com.unsubble.smokin.transport;

import java.io.IOException;

public interface Transport extends AutoCloseable {
    void connect() throws IOException;

    void write(byte[] data) throws IOException;

    byte[] read() throws IOException;

    int read(byte[] buffer, int offset, int length) throws IOException;

    void close() throws IOException;
}
