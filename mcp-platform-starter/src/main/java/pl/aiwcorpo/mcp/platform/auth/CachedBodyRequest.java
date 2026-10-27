package pl.aiwcorpo.mcp.platform.auth;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * The request with its body read once and replayed from memory, so the filter can inspect the JSON-RPC
 * message and the MCP transport still reads the same bytes.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    /** Thrown when the body is larger than allowed; the request is refused before the body is kept. */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("request body too large");
        }
    }

    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    static CachedBodyRequest read(HttpServletRequest request, long maxBytes) throws IOException {
        if (request.getContentLengthLong() > maxBytes) {
            throw new BodyTooLargeException();
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, maxBytes + 1));
            if (bytes.length > maxBytes) {
                throw new BodyTooLargeException();
            }
            return new CachedBodyRequest(request, bytes);
        }
    }

    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("the body is already buffered");
            }

            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                return in.read(buffer, offset, length);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }
}
