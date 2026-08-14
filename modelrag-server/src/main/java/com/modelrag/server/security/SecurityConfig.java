package com.modelrag.server.security;

import com.modelrag.common.security.LocalAuthTokenService;
import com.modelrag.common.security.RequestUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The only production authentication boundary. Controllers still perform dataset/role checks. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    /** Prevents Spring Boot from creating a generated in-memory user in production or tests. */
    @Bean
    UserDetailsService noDefaultUserDetailsService() {
        return username -> { throw new UsernameNotFoundException("Database authentication is required: " + username); };
    }

    @Bean
    @Profile("!test")
    SecurityFilterChain productionSecurity(HttpSecurity http, LocalAuthTokenService tokens, JdbcTemplate jdbc,
            ObjectMapper json) throws Exception {
        BearerAuthenticationFilter bearer = new BearerAuthenticationFilter(tokens);
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**", "/api/v2/auth/**", "/actuator/health/**",
                                "/actuator/info", "/actuator/prometheus", "/api/v2/openapi.json",
                                "/v3/api-docs", "/error")
                        .permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new ApiIdempotencyFilter(jdbc, json), BearerAuthenticationFilter.class);
        return http.build();
    }

    /** Test fakes are intentionally isolated from the production authentication chain. */
    @Bean
    @Profile("test")
    SecurityFilterChain testSecurity(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    static final class BearerAuthenticationFilter extends OncePerRequestFilter {
        private final LocalAuthTokenService tokens;

        BearerAuthenticationFilter(LocalAuthTokenService tokens) {
            this.tokens = tokens;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header == null || header.isBlank()) {
                chain.doFilter(request, response);
                return;
            }
            if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Bearer token required");
                return;
            }
            try {
                RequestUser user = tokens.parse(header.substring(7).trim());
                var authorities = user.roles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .collect(Collectors.toUnmodifiableSet());
                var authentication = UsernamePasswordAuthenticationToken.authenticated(user.id(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                chain.doFilter(request, response);
            } catch (RuntimeException error) {
                SecurityContextHolder.clearContext();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid bearer token");
            }
        }
    }
}
