package com.dalaillama.content.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

@Component
public class JwtTokenFilter   extends OncePerRequestFilter {

    public final JwtService jwtService;

    public JwtTokenFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // trying to find Authorization header
        final String authorizationHeader = request.getHeader("Authorization");
        if (authorizationHeader == null || authorizationHeader.isEmpty() || !authorizationHeader.startsWith("Bearer")){
            // if Authorization header does not exist, then skip this filter
            // and continue to execute next filter class
            filterChain.doFilter(request, response);
            return;
        }

        final String token = authorizationHeader.split(" ")[1].trim();
        if (!this.jwtService.validate(token)) {
            // if token is not valid, then skip this filter
            // and continue to execute next filter class.
            // This means authentication is not successful since token is invalid.
            filterChain.doFilter(request, response);
            return;
        }

        // Authorization header exists, token is valid. So, we can authenticate.
        String username = this.jwtService.getUsername(token);
        // initializing UsernamePasswordAuthenticationToken with its 3 parameter constructor
        // because it sets super.setAuthenticated(true); in that constructor.
        UsernamePasswordAuthenticationToken upassToken = new UsernamePasswordAuthenticationToken(username, null, new ArrayList<>());
        upassToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        // finally, give the authentication token to Spring Security Context
        SecurityContextHolder.getContext().setAuthentication(upassToken);

        // end of the method, so go for next filter class
        filterChain.doFilter(request, response);

    }

}
