package com.coruja.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuração do Circuit Breaker para RestTemplate (não-reativo).
 */
@Configuration
public class CircuitBreakerConfiguration {

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> defaultCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(10))
                        .build())
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        // Threshold de falhas para abrir o circuito (50%)
                        .failureRateThreshold(50)
                        // Número mínimo de chamadas antes de calcular taxa de falha
                        .minimumNumberOfCalls(5)
                        // Tempo que o circuito fica aberto (30s)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        // Número de chamadas permitidas no estado half-open
                        .permittedNumberOfCallsInHalfOpenState(3)
                        // Threshold de chamadas lentas (50%)
                        .slowCallRateThreshold(50)
                        // Duração para considerar uma chamada lenta (5s)
                        .slowCallDurationThreshold(Duration.ofSeconds(5))
                        .build())
                .build());
    }
}
