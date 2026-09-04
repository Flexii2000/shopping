package com.fherrmann.shopping.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Meldet an, wer einen gueltigen Token mitbringt - als {@code Authorization:
 * Bearer …} (die App) oder als Cookie {@code shopping_token} (der Browser,
 * gesetzt ueber {@code /setup?token=…}). Der Principal ist der Name aus der
 * Token-Liste; er steht spaeter an den Eintraegen.
 */
public class TokenAuthFilter extends OncePerRequestFilter {

    public static final String COOKIE = "shopping_token";
    private static final String BEARER = "Bearer ";

    private final TokenRegistry registry;

    public TokenAuthFilter(TokenRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> name = fromHeader(request).or(() -> fromCookie(request));
        name.ifPresent(n -> SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(n, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        chain.doFilter(request, response);
    }

    /**
     * Auch beim ERROR-Dispatch pruefen - sonst ersetzt Spring Security jeden
     * echten Fehlerstatus durch ein 401, weil der Kontext dann leer ist.
     * Dieselbe Falle wie bei To-Do und beim Kalorienzaehler.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private Optional<String> fromHeader(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        return registry.nameFor(header.substring(BEARER.length()).trim());
    }

    private Optional<String> fromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName())) {
                Optional<String> name = registry.nameFor(cookie.getValue());
                if (name.isPresent()) {
                    return name;
                }
            }
        }
        return Optional.empty();
    }
}
