package io.ledger.gateway;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.ledger.gateway.ratelimit.RateLimitProperties;
import io.ledger.gateway.ratelimit.TenantRateLimitFilter;
import io.ledger.gateway.tenant.TenantClaimFilter;
import io.ledger.gateway.web.Problems;
import io.ledger.gateway.web.RequestIdWebFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties(RateLimitProperties.class)
public class GatewayConfiguration {

    @Bean
    Problems problems(JsonMapper jsonMapper) {
        return new Problems(jsonMapper);
    }

    @Bean
    RequestIdWebFilter requestIdWebFilter() {
        return new RequestIdWebFilter();
    }

    @Bean
    TenantClaimFilter tenantClaimFilter(Problems problems) {
        return new TenantClaimFilter(problems);
    }

    @Bean
    TenantRateLimitFilter tenantRateLimitFilter(RateLimitProperties properties, Problems problems) {
        return new TenantRateLimitFilter(properties, problems);
    }

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, Problems problems) {
        ServerAuthenticationEntryPoint unauthenticated = (exchange, e) ->
                problems.write(exchange, Problems.Code.UNAUTHENTICATED, null);
        ServerAccessDeniedHandler denied = (exchange, e) ->
                problems.write(exchange, Problems.Code.PERMISSION_DENIED, null);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // stateless bearer-token API, no cookies
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(cors -> {
                })
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers("/api/v1/admin/**").hasRole("PLATFORM_ADMIN")
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${gateway.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Last-Event-ID",
                TenantClaimFilter.HEADER, "Idempotency-Key", RequestIdWebFilter.HEADER));
        cors.setExposedHeaders(List.of(RequestIdWebFilter.HEADER, "Location", "Idempotent-Replayed", "Retry-After",
                TenantRateLimitFilter.LIMIT, TenantRateLimitFilter.REMAINING));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    /** Keycloak realm roles ({@code realm_access.roles}) become {@code ROLE_*} authorities. */
    static Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        Converter<Jwt, Collection<GrantedAuthority>> realmRoles = jwt -> {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            Object roles = realmAccess == null ? null : realmAccess.get("roles");
            if (!(roles instanceof Collection<?> list)) {
                return List.of();
            }
            return list.stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList();
        };
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(realmRoles);
        converter.setPrincipalClaimName("preferred_username");
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
