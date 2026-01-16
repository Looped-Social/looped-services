package com.looped.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import org.springframework.http.HttpMethod;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${auth.issuer}")
    private String issuer;

    @Value("${auth.audience}")
    private String audience;

    @Value("${auth.jwksUri}")
    private String jwksUri;

    @Value("${cors.allowed-origins:http://localhost:5173}")
    private String corsAllowedOrigins;

    @Value("${cors.allowed-methods:GET,POST,PATCH,PUT,DELETE,OPTIONS}")
    private String corsAllowedMethods;

    @Value("${cors.allowed-headers:Authorization,Content-Type,Idempotency-Key,X-Actor,X-Media-Signature}")
    private String corsAllowedHeaders;

    @Value("${cors.allow-credentials:false}")
    private boolean corsAllowCredentials;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authz -> authz
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/health", "/actuator/health", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/media/presign").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/media/callback").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/media/resolve").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/posts").permitAll()
                .requestMatchers(HttpMethod.PUT, "/v1/posts/*").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/v1/posts/*").permitAll()
                .requestMatchers(HttpMethod.PUT, "/v1/posts/*/repost").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/v1/posts/*/repost").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/posts/*/like").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/v1/posts/*/like").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/posts/*/save").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/v1/posts/*/save").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/posts/*/comments").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/posts/*/comments").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/comments/*/replies").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/comments/*/like").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/v1/comments/*/like").permitAll()
                .requestMatchers(HttpMethod.PUT, "/v1/comments/*").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/v1/comments/*").permitAll()
                .requestMatchers(HttpMethod.PUT, "/v1/anon/*/display-community").permitAll()
                .requestMatchers(HttpMethod.PUT, "/v1/anon/*/display-specialization").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/anon/*/posts/liked").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/anon/*/posts/saved").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/anon/*/replies").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/users/*/follow").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/v1/users/*/follow").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/users/*/block").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/v1/users/*/block").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/principals/*/block").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/v1/principals/*/block").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/users/*/replies").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/feedback").permitAll()
                .requestMatchers("/anon/register", "/anon/revoke").permitAll()
                .requestMatchers(HttpMethod.PUT, "/users/me").authenticated()
                .requestMatchers(HttpMethod.POST, "/users/verify-employment").authenticated()
                .requestMatchers("/v1/**", "/anon/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> withAudience = new AudienceValidator(audience);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(splitCsv(corsAllowedOrigins));
        config.setAllowedMethods(splitCsv(corsAllowedMethods));
        config.setAllowedHeaders(splitCsv(corsAllowedHeaders));
        config.setAllowCredentials(corsAllowCredentials);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
