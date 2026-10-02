package com.educore.publicapi;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds {@code X-Robots-Tag: noindex, nofollow} to every response under {@code /api/} (the public API
 * included: crawlers index the prerendered pages, never the JSON behind them). {@code /sitemap.xml} and
 * every other path outside {@code /api/} are left alone. The header is set before the rest of the chain
 * runs, so it is also present on 401/403/404 problems written by Spring Security or the error handling.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RobotsTagFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Robots-Tag";
    public static final String VALUE = "noindex, nofollow";

    private static final String API_PREFIX = "/api/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(API_PREFIX)) {
            response.setHeader(HEADER, VALUE);
        }
        filterChain.doFilter(request, response);
    }
}
