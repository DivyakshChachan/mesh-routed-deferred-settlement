package com.demo.upimesh.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Simple API key filter for bridge ingestion endpoints.
 *
 * Only applied to /api/bridge/** paths. All other paths pass through.
 * In production, bridge nodes must include a valid API key in the
 * X-Bridge-API-Key header; otherwise they receive a 401.
 */
public class BridgeApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BridgeApiKeyFilter.class);

    private final String headerName;
    private final Set<String> allowedKeys;

    public BridgeApiKeyFilter(String headerName, Set<String> allowedKeys) {
        this.headerName = headerName;
        this.allowedKeys = allowedKeys != null ? allowedKeys : Set.of();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // Only enforce on bridge ingestion endpoint
        if (request.getRequestURI().startsWith("/api/bridge/")) {
            String providedKey = request.getHeader(headerName);
            if (providedKey == null || !allowedKeys.contains(providedKey.trim())) {
                log.warn("Unauthorized bridge access from {} — missing or invalid API key",
                        request.getRemoteAddr());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"unauthorized\",\"message\":\"Invalid or missing API key\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
