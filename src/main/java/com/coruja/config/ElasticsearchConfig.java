package com.coruja.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.Getter;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class ElasticsearchConfig {

    @Value("${elasticsearch.host:elasticsearch}")
    private String host;

    @Value("${elasticsearch.port:9200}")
    private int port;

    @Value("${kibana.external-url:http://192.168.0.251:5601}")
    private String kibanaExternalUrl;

    @Bean
    public RestClient restClient() {
        return RestClient.builder(new HttpHost(host, port))
                .setRequestConfigCallback(builder -> builder
                        .setConnectTimeout(5000)
                        .setSocketTimeout(30000))
                .build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        RestClientTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
