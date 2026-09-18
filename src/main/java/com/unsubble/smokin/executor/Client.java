package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.Response;

import java.io.IOException;

public interface Client extends AutoCloseable {

    void connect() throws IOException;

    Response send(Request request) throws IOException;

    default Response send(Request request, LastByteCoordinator coordinator) throws IOException {
        if (coordinator != null) {
            coordinator.await();
        }
        return send(request);
    }

    @Override
    void close() throws IOException;
}
