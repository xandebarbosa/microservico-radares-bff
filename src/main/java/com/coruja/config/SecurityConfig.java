package com.coruja.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Desabilita o CSRF, pois não estamos usando sessões/cookies para autenticação
                .csrf(AbstractHttpConfigurer::disable)

                // Configura o CORS para usar as definições do nosso bean 'corsConfigurationSource'
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Define as regras de autorização para as requisições
                .authorizeHttpRequests(auth -> auth
                        // PERMITE TODAS as requisições para qualquer endpoint ("/**") sem autenticação
                        .requestMatchers("/**").permitAll()
                        // Se no futuro você precisar proteger algo, adicionaria aqui:
                        // .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Permite requisições da origem do seu frontend Next.js
        configuration.setAllowedOrigins(List.of("http://localhost:3009"));

        // Define os métodos HTTP permitidos
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));

        // Permite todos os cabeçalhos
        configuration.setAllowedHeaders(List.of("*"));

        // Permite o envio de credenciais (como cookies), se necessário
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Aplica esta configuração de CORS para todos os endpoints ("/**")
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
