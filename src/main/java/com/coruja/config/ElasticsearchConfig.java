package com.coruja.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    @Value("${elasticsearch.host}")
    private String host;

    @Value("${elasticsearch.port}")
    private int port;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // Cria o cliente de baixo nível que se conecta ao Elasticsearch via HTTP
        RestClient restClient = RestClient.builder(
                new HttpHost(host, port)
        ).build();

        // Cria o transporte usando o cliente REST e um mapeador JSON (Jackson)
        RestClientTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());

        // Retorna o cliente de API do Elasticsearch
        return new ElasticsearchClient(transport);
    }
}
