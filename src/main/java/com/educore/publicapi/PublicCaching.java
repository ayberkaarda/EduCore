package com.educore.publicapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Caching headers of the public endpoints: {@code Cache-Control: public, max-age=300} and a strong
 * {@code ETag} (SHA-256 of the exact representation, first 128 bits, hex). Spring MVC compares the ETag with
 * {@code If-None-Match} when the {@link ResponseEntity} is written and answers {@code 304 Not Modified}
 * without a body (headers kept) when they match (weak comparison, lists of validators). {@code If-None-Match: *}
 * matches any existing representation (RFC 9110 13.1.2), so it is answered with 304 here as well.
 */
@Component
public class PublicCaching {

    static final Duration MAX_AGE = Duration.ofSeconds(300);

    private static final CacheControl CACHE_CONTROL = CacheControl.maxAge(MAX_AGE).cachePublic();

    private final ObjectMapper objectMapper;

    public PublicCaching(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** A 200 JSON response for {@code body} with the public caching headers. */
    public <T> ResponseEntity<T> json(T body) {
        try {
            String etag = etag(objectMapper.writeValueAsBytes(body));
            if (wildcardMatch()) {
                return notModified(etag);
            }
            return ResponseEntity.ok().cacheControl(CACHE_CONTROL).eTag(etag).body(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Public response could not be serialised", e);
        }
    }

    /** A 200 response for an already rendered document with the public caching headers. */
    public ResponseEntity<String> document(String body, MediaType contentType) {
        String etag = etag(body.getBytes(StandardCharsets.UTF_8));
        if (wildcardMatch()) {
            return notModified(etag);
        }
        return ResponseEntity.ok().cacheControl(CACHE_CONTROL).eTag(etag).contentType(contentType).body(body);
    }

    private static <T> ResponseEntity<T> notModified(String etag) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(CACHE_CONTROL).eTag(etag).build();
    }

    /** Whether the current GET/HEAD request carries {@code If-None-Match: *}. */
    private static boolean wildcardMatch() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return false;
        }
        HttpServletRequest request = attributes.getRequest();
        String method = request.getMethod();
        String header = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        return ("GET".equals(method) || "HEAD".equals(method)) && header != null && "*".equals(header.trim());
    }

    static String etag(byte[] representation) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(representation);
            return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
