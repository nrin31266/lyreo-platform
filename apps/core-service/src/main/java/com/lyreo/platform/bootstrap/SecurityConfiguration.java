package com.lyreo.platform.bootstrap;

import com.lyreo.contracts.identity.AccountAccessGuard;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import com.lyreo.platform.web.ProblemAccessDeniedHandler;
import com.lyreo.platform.web.ProblemAuthenticationEntryPoint;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(
        HttpSecurity http,
        @Qualifier("apiRateLimitFilter") OncePerRequestFilter apiRateLimitFilter,
        CorsConfigurationSource corsConfigurationSource,
        ProblemAuthenticationEntryPoint authenticationEntryPoint,
        ProblemAccessDeniedHandler accessDeniedHandler,
        ObjectProvider<AccountAccessGuard> accountAccess
    ) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // Controller exists only in the dev Spring profile and additionally requires a shared token.
                .requestMatchers("/internal/dev/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/lexicon/**").hasAnyRole("LEARNER", "ADMIN")
                .requestMatchers(
                    "/api/v1/learner/**",
                    "/api/v1/curriculum/**",
                    "/api/v1/lessons/**",
                    "/api/v1/shadowing-attempts/**",
                    "/api/v1/vocabulary/**",
                    "/api/v1/grammar/**",
                    "/api/v1/toeic/catalog/**",
                    "/api/v1/assessment-sessions/**",
                    "/api/v1/gamification/**",
                    "/api/v1/analytics/**",
                    "/api/v1/notifications/**",
                    "/api/v1/chat/**"
                ).hasRole("LEARNER")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter()))
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .addFilterAfter(apiRateLimitFilter, BearerTokenAuthenticationFilter.class);
        AccountAccessGuard guard = accountAccess.getIfAvailable();
        if (guard != null) {
            http.addFilterAfter(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                    if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken auth
                        && !guard.canUseToken(auth.getToken().getSubject(), auth.getToken().getIssuedAt())) {
                        SecurityContextHolder.clearContext();
                        authenticationEntryPoint.commence(request, response,
                            new InvalidBearerTokenException("This session is no longer valid"));
                        return;
                    }
                    chain.doFilter(request, response);
                }
            }, BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
        @Value("${lyreo.security.cors-allowed-origins:http://localhost:5173}") String allowedOrigins
    ) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
            .map(String::strip)
            .filter(value -> !value.isBlank())
            .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id", "Idempotency-Key"));
        config.setExposedHeaders(List.of(
            "X-Correlation-Id", "Location", "Retry-After", "ETag", "Content-Range", "Accept-Ranges"
        ));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
