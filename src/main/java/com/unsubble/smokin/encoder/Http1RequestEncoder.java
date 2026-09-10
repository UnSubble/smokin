package com.unsubble.smokin.encoder;

import com.unsubble.smokin.model.Header;
import com.unsubble.smokin.model.Request;

import java.nio.charset.StandardCharsets;

public class Http1RequestEncoder implements HttpRequestEncoder {

    @Override
    public byte[] encode(Request request) {
        StringBuilder builder = new StringBuilder();

        builder.append(request.method())
                .append(' ')
                .append(request.path())
                .append(' ')
                .append(request.version())
                .append("\r\n");

        for (Header header : request.headers()) {
            builder.append(header.name())
                    .append(": ")
                    .append(header.value())
                    .append("\r\n");
        }

        builder.append("\r\n");

        byte[] headers = builder.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = request.body();

        byte[] result = new byte[headers.length + body.length];

        System.arraycopy(headers, 0, result, 0, headers.length);
        System.arraycopy(body, 0, result, headers.length, body.length);

        return result;
    }
}
