package com.coruja.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuração do Circuit Breaker resiliente para RestTemplate.
 * Protege contra timeouts infinitos e cascata de falhas quando um serviço cai.
 */
@Configuration
public class CircuitBreakerConfiguration {

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> defaultCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        // Timeout máximo de 8s para chamadas síncronas entre microserviços
                        .timeoutDuration(Duration.ofSeconds(8))
                        .build())
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        // Abre o circuito se 50% das chamadas falharem
                        .failureRateThreshold(50)
                        // Avalia após 5 chamadas mínimas
                        .minimumNumberOfCalls(5)
                        // Mantém aberto por 15s (retorna fallback instantâneo sem onerar rede)
                        .waitDurationInOpenState(Duration.ofSeconds(15))
                        // Testa 3 chamadas para verificar recuperação do serviço
                        .permittedNumberOfCallsInHalfOpenState(3)
                        // Chamadas acima de 4s são consideradas lentas
                        .slowCallRateThreshold(50)
                        .slowCallDurationThreshold(Duration.ofSeconds(4))
                        .slidingWindowSize(20)
                        .build())
                .build());
    }
}
