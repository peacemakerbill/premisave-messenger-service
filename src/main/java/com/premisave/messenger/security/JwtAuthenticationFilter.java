package com.premisave.messenger.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        log.debug("Authorization header: {}", authHeader != null ? authHeader.substring(0, Math.min(authHeader.length(), 50)) + "..." : null);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("No Bearer token found - continuing as unauthenticated");
            filterChain.doFilter(request, response);
            return;
        }

        String jwt = authHeader.substring(7);

        try {
            boolean isValid = jwtService.isTokenValid(jwt);
            log.debug("Token validation result: {}", isValid);

            if (isValid) {
                String username = jwtService.extractUsername(jwt);
                log.debug("Extracted username from token: {}", username);

                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    // Create authentication token (we don't load full UserDetails since we use Feign for user info)
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList());

                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    log.info("Successfully authenticated user: {}", username);
                }
            } else {
                log.warn("JWT token validation failed for token starting with: {}", 
                        jwt.substring(0, Math.min(jwt.length(), 30)) + "...");
            }

        } catch (Exception e) {
            log.error("Error processing JWT token", e);
            // Do not throw - continue as unauthenticated
        }

        filterChain.doFilter(request, response);
    }
}