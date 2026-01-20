package com.coruja.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * Configuração do RestTemplate para chamadas aos microserviços.
 * Substitui o WebClient para remover a dependência do WebFlux.
 */
@Configuration
public class RestTemplateConfig {

    /**
     * RestTemplate PRINCIPAL (com Load Balancing).
     * Usado quando chamamos serviços pelo nome (ex: http://MICROSERVICO-RADARES).
     */
    @Bean
    @LoadBalanced
    @Primary
    public RestTemplate restTemplate() {
        return createRestTemplate();
    }

    /**
     * RestTemplate DIRETO (sem Load Balancing).
     * Usado quando chamamos URLs fixas (ex: http://host.docker.internal:8089 ou http://google.com).
     */
    @Bean("directRestTemplate")
    public RestTemplate directRestTemplate() {
        return createRestTemplate();
    }

    // Método auxiliar para não duplicar configuração
    private RestTemplate createRestTemplate() {
        RestTemplate restTemplate = new RestTemplate(clientHttpRequestFactory());
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper());
        restTemplate.setMessageConverters(List.of(converter));
        return restTemplate;
    }

    @Bean
    public ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(60000);
        factory.setReadTimeout(90000);
        return factory;
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }


    /**
     * RestTemplate com Load Balancing via Eureka.
     * A anotação @LoadBalanced permite usar nomes de serviço em vez de URLs.
     */
//    @Bean
//    @LoadBalanced
//    public RestTemplate restTemplate() {
//        RestTemplate restTemplate = new RestTemplate(clientHttpRequestFactory());
//
//        // Adiciona conversor JSON com suporte a LocalDate/LocalTime
//        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
//        converter.setObjectMapper(objectMapper());
//
//        restTemplate.setMessageConverters(List.of(converter));
//
//        return restTemplate;
//    }

    /**
     * Factory para configurar timeouts e outras propriedades da conexão HTTP.
     */
//    @Bean
//    public ClientHttpRequestFactory clientHttpRequestFactory() {
//        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
//
//        // Timeout de conexão: 5 segundos
//        factory.setConnectTimeout(5000);
//
//        // Timeout de leitura: 45 segundos
//        factory.setReadTimeout(45000);
//
//        return factory;
//    }

    /**
     * ObjectMapper com suporte a tipos Java 8+ (LocalDate, LocalTime, etc).
     */
//    @Bean
//    public ObjectMapper objectMapper() {
//        ObjectMapper mapper = new ObjectMapper();
//        mapper.registerModule(new JavaTimeModule());
//        return mapper;
//    }
}