package com.coruja.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;


@Configuration
@EnableWebSecurity // GARANTA QUE SEJA ESTA ANOTAÇÃO, NÃO @EnableWebFluxSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    private final KeycloakRoleConverter keycloakRoleConverter;

    public SecurityConfig(KeycloakRoleConverter keycloakRoleConverter) {
        this.keycloakRoleConverter = keycloakRoleConverter;
    }

    // 1. Usar o WebSecurityCustomizer é a forma mais forte de ignorar segurança
    // Isso remove o Spring Security completamente dessas rotas.
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring()
                // Tenta casar o caminho normal
                .requestMatchers("/ws/**")
                // PRECAUÇÃO: Tenta casar o caminho COM o prefixo /api,
                // caso o Gateway não tenha feito o strip corretamente.
                .requestMatchers("/api/ws/**")
                // Libera OPTIONS para não ter erro de CORS no preflight
                .requestMatchers(HttpMethod.OPTIONS, "/**");
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(keycloakRoleConverter);

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Reativamos o CORS no BFF para evitar problemas de "Missing Allow Origin"
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                .authorizeHttpRequests(authorize -> authorize
                        // Se o web.ignoring() acima funcionar, estas linhas são redundantes,
                        // mas deixamos aqui como "seguro de vida".
                        //.requestMatchers("/ws/**").permitAll()
                        //.requestMatchers("/api/ws/**").permitAll()

                        .requestMatchers("/actuator/**", "/health").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                );

        return http.build();
    }

    /**
     * Configuração CORS permissiva para o BFF (Interno).
     * O Gateway filtra para o mundo externo, mas o BFF aceita do Gateway.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*")); // Aceita tudo (vem do Gateway)
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withJwkSetUri(this.jwkSetUri).build();
    }
}