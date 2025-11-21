package com.coruja.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

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
                .csrf(csrf -> csrf.disable()) // CSRF interfere em SockJS
                .cors(Customizer.withDefaults())
                // (4) Configura o gerenciamento de sessão para stateless
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // Libera pré-flight
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Libera handshake /ws/** (o Jwt é tratado no BFF handshake)
                        // NOTA: O Gateway protege /api/ws, o BFF protege /ws
                        .requestMatchers("/ws/**").permitAll()

                        // Libera rotas de health/public
                        .requestMatchers("/actuator/**", "/health").permitAll()

                        // Protege o resto da API
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/radares/**").authenticated()
                        .requestMatchers("/monitoramento/**").authenticated()

                        .anyRequest().denyAll() // (5) Mudou de .anyExchange()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)) // (6) Usa o conversor direto
                );

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return JwtDecoders.fromIssuerLocation(this.issuerUri);
    }
}