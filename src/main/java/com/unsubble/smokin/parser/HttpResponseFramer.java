package com.unsubble.smokin.parser;

import com.unsubble.smokin.transport.Transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class HttpResponseFramer {

    private static final int READ_CHUNK_SIZE = 4096;

    private final Transport transport;

    public HttpResponseFramer(Transport transport) {
        this.transport = transport;
    }

    public byte[] read() throws IOException {
        return read(null);
    }

    public byte[] read(String requestMethod) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        String statusLine = readLineCRLF(out);
        List<String> headerLines = readHeaderLines(out);

        FramingInfo info = extractFramingInfo(statusLine, headerLines);
        boolean bodyForbidden = isBodyForbidden(info.statusCode, requestMethod);

        if (bodyForbidden) {
            return out.toByteArray();
        }

        if (info.chunked && info.contentLength != -1) {
            throw new IOException("Ambiguous body framing: both Content-Length and chunked Transfer-Encoding present");
        }

        if (info.chunked) {
            readChunkedBody(out);
        } else if (info.contentLength != -1) {
            readExact(out, info.contentLength);
        } else {
            readUntilClosed(out);
        }

        return out.toByteArray();
    }

    private void readChunkedBody(ByteArrayOutputStream out) throws IOException {
        while (true) {
            String sizeLine = readLineCRLF(out);
            long chunkSize = parseChunkSize(sizeLine);

            if (chunkSize == 0) {
                readHeaderLines(out); // optional trailer headers, up to the blank line
                return;
            }

            readExact(out, chunkSize);
            readChunkTerminator(out);
        }
    }

    private static long parseChunkSize(String sizeLine) throws IOException {
        int semicolon = sizeLine.indexOf(';'); // ignore chunk extensions
        String sizeHex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();

        long chunkSize;
        try {
            chunkSize = Long.parseLong(sizeHex, 16);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid chunk size");
        }

        if (chunkSize < 0 || chunkSize > Integer.MAX_VALUE) {
            throw new IOException("Invalid chunk size");
        }

        return chunkSize;
    }

    private void readChunkTerminator(ByteArrayOutputStream out) throws IOException {
        int cr = transport.readSingle();
        int lf = transport.readSingle();

        if (cr == -1 || lf == -1) {
            throw new IOException("Connection closed mid-chunk");
        }

        out.write(cr);
        out.write(lf);

        if (cr != '\r' || lf != '\n') {
            throw new IOException("Invalid chunk terminator");
        }
    }

    private List<String> readHeaderLines(ByteArrayOutputStream out) throws IOException {
        List<String> lines = new ArrayList<>();

        while (true) {
            String line = readLineCRLF(out);
            if (line.isEmpty()) {
                return lines;
            }
            lines.add(line);
        }
    }

    private String readLineCRLF(ByteArrayOutputStream out) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int prev = -1;

        while (true) {
            int b = transport.readSingle();
            if (b == -1) {
                throw new IOException("Connection closed before CRLF");
            }

            out.write(b);

            if (prev == '\r' && b == '\n') {
                byte[] raw = line.toByteArray();
                return new String(raw, 0, raw.length - 1, StandardCharsets.ISO_8859_1);
            }

            line.write(b);
            prev = b;
        }
    }

    private void readExact(ByteArrayOutputStream out, long length) throws IOException {
        byte[] buf = new byte[(int) Math.min(length, READ_CHUNK_SIZE)];
        long remaining = length;

        while (remaining > 0) {
            int toRead = (int) Math.min(remaining, buf.length);
            int n = transport.read(buf, 0, toRead);

            if (n == -1) {
                throw new IOException("Connection closed before expected body bytes were received");
            }

            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    private void readUntilClosed(ByteArrayOutputStream out) throws IOException {
        byte[] buf = new byte[READ_CHUNK_SIZE];

        int n;
        while ((n = transport.read(buf, 0, buf.length)) != -1) {
            out.write(buf, 0, n);
        }
    }

    private FramingInfo extractFramingInfo(String statusLine, List<String> headerLines) throws IOException {
        FramingInfo info = new FramingInfo();
        info.statusCode = parseStatusCode(statusLine);

        for (String line : headerLines) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IOException("Invalid header line: " + line);
            }

            String name = line.substring(0, colon);
            String value = ParserUtil.trimOWS(line.substring(colon + 1));

            if (name.equalsIgnoreCase("Content-Length")) {
                info.contentLength = getLength(value, info);
            } else if (name.equalsIgnoreCase("Transfer-Encoding")) {
                if (containsToken(value, "chunked")) {
                    info.chunked = true;
                }
            }
        }

        return info;
    }

    private static long getLength(String value, FramingInfo info) throws IOException {
        long len;
        try {
            len = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Content-Length");
        }

        if (len < 0) {
            throw new IOException("Invalid Content-Length");
        }

        if (info.contentLength != -1 && info.contentLength != len) {
            throw new IOException("Conflicting Content-Length headers");
        }
        return len;
    }

    private static int parseStatusCode(String statusLine) throws IOException {
        String[] parts = statusLine.split("[ \t]+", 3);
        if (parts.length < 2 || parts[1].length() != 3) {
            throw new IOException("Invalid status line");
        }

        for (int i = 0; i < 3; i++) {
            if (!Character.isDigit(parts[1].charAt(i))) {
                throw new IOException("Invalid status line");
            }
        }

        return Integer.parseInt(parts[1]);
    }

    private static boolean isBodyForbidden(int statusCode, String requestMethod) {
        if (requestMethod != null && requestMethod.equalsIgnoreCase("HEAD")) {
            return true;
        }

        if (statusCode >= 100 && statusCode < 200) {
            return true;
        }

        return statusCode == 204 || statusCode == 304;
    }

    private static boolean containsToken(String headerValue, String token) {
        for (String part : headerValue.split(",")) {
            if (part.trim().equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    private static final class FramingInfo {
        int statusCode;
        long contentLength = -1;
        boolean chunked = false;
    }
}