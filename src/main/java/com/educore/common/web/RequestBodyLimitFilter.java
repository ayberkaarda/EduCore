package com.educore.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Caps every non-multipart request body at {@code educore.http.max-body-size} (default 64 KB) before any
 * controller, authentication or JSON parsing sees it, so anonymous endpoints cannot be made to buffer large
 * bodies.
 * <ul>
 *   <li>A declared {@code Content-Length} above the limit is answered 413 {@code request/payload-too-large}
 *       immediately; the body is not read.</li>
 *   <li>Bodies without a length (chunked) are read through a counting stream that fails with
 *       {@link RequestBodyTooLargeException} at the first byte past the limit; {@link ProblemDetailsAdvice}
 *       turns that into the same 413.</li>
 * </ul>
 * {@code multipart/*} bodies are left to the multipart limits ({@code spring.servlet.multipart.*}), and
 * {@code application/x-www-form-urlencoded} bodies that Tomcat parses itself to its {@code maxPostSize}.
 * Runs right after the request-id filter and before the security filter chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long limit;
    private final ProblemResponseWriter writer;

    public RequestBodyLimitFilter(@Value("${educore.http.max-body-size:64KB}") DataSize limit,
                                  ProblemResponseWriter writer) {
        this.limit = limit.toBytes();
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limit) {
            writer.write(request, response, HttpStatus.PAYLOAD_TOO_LARGE);
            return;
        }
        chain.doFilter(new LimitedRequest(request, limit), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        LimitedInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int bytes) throws RequestBodyTooLargeException {
            count += bytes;
            if (count > limit) {
                throw new RequestBodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
