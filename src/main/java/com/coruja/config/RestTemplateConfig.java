package com.coruja.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Configuração do RestTemplate para chamadas aos microserviços.
 * Substitui o WebClient para remover a dependência do WebFlux.
 */
@Configuration
public class RestTemplateConfig {

    /**
     * RestTemplate com Load Balancing via Eureka.
     * A anotação @LoadBalanced permite usar nomes de serviço em vez de URLs.
     */
    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate(clientHttpRequestFactory());

        // Adiciona conversor JSON com suporte a LocalDate/LocalTime
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper());

        restTemplate.setMessageConverters(List.of(converter));

        return restTemplate;
    }

    /**
     * Factory para configurar timeouts e outras propriedades da conexão HTTP.
     */
    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        // Timeout de conexão: 5 segundos
        factory.setConnectTimeout(5000);

        // Timeout de leitura: 10 segundos
        factory.setReadTimeout(10000);

        return factory;
    }

    /**
     * ObjectMapper com suporte a tipos Java 8+ (LocalDate, LocalTime, etc).
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}