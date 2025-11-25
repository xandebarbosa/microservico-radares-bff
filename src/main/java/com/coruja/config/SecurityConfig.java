package com.coruja.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity // (1) Mudou de @EnableWebFluxSecurity
@EnableMethodSecurity // (2) Necessário para @PreAuthorize nos controllers
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    private final KeycloakRoleConverter keycloakRoleConverter;

    public SecurityConfig(KeycloakRoleConverter keycloakRoleConverter) {
        this.keycloakRoleConverter = keycloakRoleConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception { // (3) Usa HttpSecurity

        // Converte Jwt -> GrantedAuthority
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(keycloakRoleConverter);

        http
                // CRÍTICO: Desabilita CSRF (necessário para APIs REST e WebSocket)
                .csrf(csrf -> csrf.disable()) // WebSocket + REST → CSRF deve ser desabilitado
                // CRÍTICO: Habilita CORS com configuração personalizada
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Configura sessão stateless
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CRÍTICO: Configuração de autorização
                .authorizeHttpRequests(authorize -> authorize
                        // 🔥 PERMITIR completamente endpoints WebSocket (SEM AUTENTICAÇÃO)
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/api/ws/**").permitAll()

                        // Libera preflight requests (OPTIONS) para evitar erros de CORS 403
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Libera endpoints de health check
                        .requestMatchers("/actuator/**", "/health").permitAll()

                        // 4. Protege o restante
                        .anyRequest().authenticated()
                )
                // Configura OAuth2 Resource Server com JWT
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)) // (6) Usa o conversor direto
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // ✅ CORREÇÃO: Use setAllowedOrigins em vez de setAllowedOriginPatterns
        // para evitar múltiplos headers Access-Control-Allow-Origin
        configuration.setAllowedOriginPatterns(Arrays.asList(
                "http://localhost:3000",
                "http://localhost:3009",
                "http://192.168.0.*:[*]", // Funciona apenas com AllowedOriginPatterns
                "*"
        ));

        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

        // Headers essenciais
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization",
                "Cache-Control",
                "Content-Type",
                "X-Requested-With",
                "Access-Control-Allow-Origin",
                "Access-Control-Allow-Credentials"
        ));

        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("Authorization"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return JwtDecoders.fromIssuerLocation(this.issuerUri);
    }
}