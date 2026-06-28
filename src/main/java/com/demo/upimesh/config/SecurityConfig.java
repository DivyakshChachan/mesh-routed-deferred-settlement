package com.demo.upimesh.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring Security configuration.
 *
 * In production: the bridge ingestion endpoint requires an API key header.
 * In dev: all endpoints are open (enforceApiKey = false).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${upi.mesh.security.enforce-api-key:false}")
    private boolean enforceApiKey;

    @Value("${upi.mesh.security.api-key-header:X-Bridge-API-Key}")
    private String apiKeyHeader;

    @Value("${upi.mesh.security.api-keys:dev-bridge-secret-key-12345}")
    private String apiKeysString;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            );

        if (enforceApiKey) {
            Set<String> allowedKeys = Arrays.stream(apiKeysString.split(","))
                    .map(String::trim)
                    .filter(k -> !k.isEmpty())
                    .collect(Collectors.toSet());

            http.addFilterBefore(
                new BridgeApiKeyFilter(apiKeyHeader, allowedKeys),
                UsernamePasswordAuthenticationFilter.class
            );
        }

        return http.build();
    }
}
