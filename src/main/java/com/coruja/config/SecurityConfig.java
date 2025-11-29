package com.coruja.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity // (1) Mudou de @EnableWebFluxSecurity
@EnableMethodSecurity // (2) Necessário para @PreAuthorize nos controllers
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    // Injetamos a URL das chaves (JWK) em vez do Issuer
    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    private final KeycloakRoleConverter keycloakRoleConverter;

    public SecurityConfig(KeycloakRoleConverter keycloakRoleConverter) {
        this.keycloakRoleConverter = keycloakRoleConverter;
    }

    /**
     * ✅ CORREÇÃO FINAL WEBSOCKET:
     * Diz ao Spring Security para IGNORAR totalmente estas rotas.
     * O filtro de CORS (configurado abaixo) ainda rodará porque tem prioridade máxima,
     * mas nenhum filtro de segurança (autenticação) será executado.
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring()
                .requestMatchers("/api/ws/**");
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
                //.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Configura sessão stateless
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CRÍTICO: Configuração de autorização
                .authorizeHttpRequests(authorize -> authorize
                        // Libera preflight requests (OPTIONS) para evitar erros de CORS 403
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 🔥 PERMITIR completamente endpoints WebSocket (SEM AUTENTICAÇÃO)
                        //.requestMatchers("/api/ws/**").permitAll()
                        // Libera endpoints de health check
                        .requestMatchers("/actuator/**", "/health").permitAll()
                        // Protege todas as outras rotas da API
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                )
                // Configura OAuth2 Resource Server com JWT
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)) // (6) Usa o conversor direto
                )
                // 5. ISSO CORRIGE O ERRO DE CORS FALSO NO NAVEGADOR EM CASO DE 401/403
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                );

        return http.build();
    }

    /**
     * CORREÇÃO DEFINITIVA DE CORS:
     * Registra o filtro com prioridade máxima (Ordered.HIGHEST_PRECEDENCE).
     * Isso garante que ele rode antes do Spring Security e do Spring MVC,
     * aplicando os headers apenas uma vez.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowCredentials(true);

        // Configure suas origens permitidas aqui
        config.setAllowedOriginPatterns(Arrays.asList(
                "http://localhost:3000",
                "http://localhost:3009",
                "http://192.168.*.*:[*]", // Suporte para IPs de rede
                "*"
        ));

        config.setAllowedHeaders(Arrays.asList(
                "Origin", "Content-Type", "Accept", "Authorization",
                "X-Requested-With", "Access-Control-Request-Method",
                "Access-Control-Request-Headers", "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials"
        ));

        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

        // Expõe o header Authorization para o frontend ler (se necessário)
        config.setExposedHeaders(List.of("Authorization"));

        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        //return JwtDecoders.fromIssuerLocation(this.issuerUri);
        // REFATORAÇÃO: Usa NimbusJwtDecoder com a URL JWK Set diretamente.
        // Isso evita a chamada de "Discovery" no issuer-uri que estava dando timeout,
        // e usa a rota interna (host.docker.internal) para baixar as chaves.
        return NimbusJwtDecoder.withJwkSetUri(this.jwkSetUri).build();
    }
}