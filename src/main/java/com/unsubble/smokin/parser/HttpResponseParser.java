package com.unsubble.smokin.parser;

import com.unsubble.smokin.model.Response;

public interface HttpResponseParser {
    Response parse(byte[] data);
}