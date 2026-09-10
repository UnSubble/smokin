package com.unsubble.smokin.encoder;

import com.unsubble.smokin.model.Request;

public interface HttpRequestEncoder {

    byte[] encode(Request request);
}
