package com.unsubble.smokin.parser;

import java.io.IOException;

public interface HttpResponseFramer {

    byte[] read() throws IOException;

    byte[] read(String requestMethod) throws IOException;
}
