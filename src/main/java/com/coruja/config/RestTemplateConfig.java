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
                // 5 segundos é tempo de sobra para estabelecer conexão na rede interna do Docker
                .setConnectTimeout(Duration.ofSeconds(5))
                // 🚀 A MÁGICA AQUI: Aumentamos de 14s para 60s.
                // Isso dá fôlego para o MongoDB (Rondon) processar a Análise de Comboio sem quebrar a conexão!
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * RestTemplate DIRETO (Sem Load Balancing).
     * Utilizado para chamadas externas (APIs de terceiros, Detran, webhooks).
     */
    @Bean("directRestTemplate")
    public RestTemplate directRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Configuração global do Jackson para serialização correta de Datas ISO 8601.
     * O @Primary garante que tanto o Spring MVC quanto os RestTemplates usem esta mesma instância.
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
     * Possui um timeout longo (2 minutos) para permitir o cruzamento de milhares de placas.
     */
    @Bean("analysisRestTemplate")
    @LoadBalanced
    public RestTemplate analysisRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                // Damos 120 segundos (2 minutos) de paciência para o Quarkus cruzar os dados
                .setReadTimeout(Duration.ofSeconds(120))
                .build();
    }

    @Bean("radarsRestTemplate")
    public RestTemplate radarsRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        // Tempo máximo para estabelecer a conexão (2 segundos)
        factory.setConnectTimeout(2000);

        // Tempo máximo esperando a resposta da concessionária (5 segundos)
        // Se passar disso, o RestTemplate lança uma exceção e aborta.
        factory.setReadTimeout(5000);

        return new RestTemplate(factory);
    }
}