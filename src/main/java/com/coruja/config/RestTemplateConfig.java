package com.coruja.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Configuração centralizada e blindada dos clientes HTTP para comunicação entre microsserviços.
 */
@Configuration
public class RestTemplateConfig {

    /**
     * RestTemplate PRINCIPAL (Com Load Balancing do Eureka).
     * Injeta automaticamente o ObjectMapper e protege as threads do BFF com timeouts adequados.
     */
    @Bean
    @Primary
    @LoadBalanced
    public RestTemplate loadBalancedRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(4))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * RestTemplate RÁPIDO (Com Load Balancing do Eureka).
     * Exclusivo para Schedulers e verificações de últimos registros (3s connect, 5s read).
     * Evita que um microsserviço fora do ar trave as threads do sistema.
     */
    @Bean("fastLoadBalancedRestTemplate")
    @LoadBalanced
    public RestTemplate fastLoadBalancedRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * RestTemplate DIRETO (Sem Load Balancing).
     * Utilizado para chamadas externas (APIs de terceiros, Detran, webhooks).
     */
    @Bean("directRestTemplate")
    public RestTemplate directRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Configuração global do Jackson para serialização correta de Datas ISO 8601.
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    /**
     * RestTemplate exclusivo para Análises Pesadas e Inteligência Artificial.
     */
    @Bean("analysisRestTemplate")
    @LoadBalanced
    public RestTemplate analysisRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(90))
                .build();
    }

    @Bean("radarsRestTemplate")
    public RestTemplate radarsRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }
}
