package com.unsubble.smokin.transport;

import java.io.IOException;

public interface Transport {
    void connect() throws IOException;

    void write(byte[] data) throws IOException;

    byte[] read() throws IOException;

    void close() throws IOException;
}
