package com.genhao.hal1000.auth;

import com.auth0.jwt.exceptions.JWTVerificationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // Required for SseEmitter/async dispatch: otherwise the async phase loses SecurityContext and may be denied.
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            var token = header.substring("Bearer ".length()).trim();
            if (!token.isBlank() && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    var jwt = jwtService.verify(token);
                    var userId = jwt.getSubject();
                    if (userId != null && !userId.isBlank()) {
                        var auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } catch (JWTVerificationException ignored) {
                    // leave unauthenticated
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}

