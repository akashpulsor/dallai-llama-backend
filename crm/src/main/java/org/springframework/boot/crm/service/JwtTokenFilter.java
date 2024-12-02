package org.springframework.boot.crm.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

@Slf4j
@Component
public class JwtTokenFilter extends OncePerRequestFilter {
    private final JwtService jwtService;

    public JwtTokenFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // Log the request URL and method
            log.debug("Processing request: {} {}", request.getMethod(), request.getRequestURL());

            String authorizationHeader = request.getHeader("Authorization");
            log.debug("Authorization header: {}", authorizationHeader);

            if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
                log.debug("No valid Authorization header found");
                filterChain.doFilter(request, response);
                return;
            }

            // Extract token (trim both the token and the header to avoid whitespace issues)
            String token = authorizationHeader.substring(7).trim();
            log.debug("Extracted token: {}", token);

            if (token.isEmpty()) {
                log.warn("Empty token found");
                filterChain.doFilter(request, response);
                return;
            }

            // Validate token
            if (!jwtService.validate(token)) {
                log.warn("Invalid token");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }

            // Get username from token
            String username = jwtService.getUsername(token);
            if (username == null || username.isEmpty()) {
                log.warn("No username found in token");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }

            log.debug("Token validated for user: {}", username);

            // Create authentication token
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            username,
                            null,
                            new ArrayList<>()
                    );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // Set authentication in context
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Authentication set in SecurityContext for user: {}", username);

            filterChain.doFilter(request, response);

        } catch (Exception e) {
            log.error("Cannot set user authentication: {}", e.getMessage(), e);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getServletPath();
        // Add paths that should not be filtered (like public endpoints)
        return path.startsWith("/api/auth/login") ||
                path.startsWith("/api/auth/register") ||
                path.startsWith("/api/auth/verification-code") ||
                path.startsWith("/api/auth/verify-code")||
                path.startsWith("/swagger-ui");
    }
}