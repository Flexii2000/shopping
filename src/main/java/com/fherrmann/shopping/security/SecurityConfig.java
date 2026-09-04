package com.fherrmann.shopping.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Die API haengt hinter den eigenen Token ({@link TokenRegistry}); die
 * Seite selbst und {@code /setup} sind frei - sonst koennte man den Link, der
 * das Cookie setzt, gar nicht erst oeffnen. Ohne Token antwortet die API mit
 * 401 und einem Satz Klartext; die Seite zeigt daraufhin "Kein Zugang".
 *
 * <p>Kein Privat-Gate in nginx davor: der Dienst ist der einzige, den eine
 * zweite Person erreichen soll.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public TokenAuthFilter tokenAuthFilter(TokenRegistry registry) {
        return new TokenAuthFilter(registry);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthFilter tokenAuthFilter)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(tokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("text/plain;charset=UTF-8");
                    response.getWriter().write("Kein Zugang - bitte den Link mit Token öffnen.");
                }))
                // Token im Header oder in einem SameSite=Lax-Cookie, keine
                // Formulare, keine Sitzungen - kein CSRF obendrauf noetig.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
