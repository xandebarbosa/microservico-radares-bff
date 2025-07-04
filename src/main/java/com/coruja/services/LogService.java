package com.coruja.services;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LogService {

    private final ElasticsearchClient esClient;

    @Autowired
    public LogService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @SuppressWarnings("unchecked") // Suprime o aviso de cast inseguro, pois sabemos que o JSON do ES é Map<String, Object>
    public Mono<List<Map<String, Object>>> searchLogs(String query, int page, int size) {
        int from = page * size;

        return Mono.fromCallable(() -> {
            try {
                SearchRequest searchRequest = SearchRequest.of(s -> s
                        .index("spring_boot_app-*")
                        .from(from)
                        .size(size)
                        .query(q -> q
                                .queryString(qs -> qs
                                        .query(query)
                                )
                        )
                        .sort(sort -> sort
                                .field(f -> f.field("@timestamp").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))
                        )
                );

                // O cliente retorna um SearchResponse com Map genérico
                SearchResponse<Map> response = esClient.search(searchRequest, Map.class);

                // =======================================================
                // ##               CORREÇÃO APLICADA AQUI              ##
                // =======================================================
                // Trocamos .map(Hit::source) por uma expressão lambda que faz o cast explícito.
                return response.hits().hits().stream()
                        .map(hit -> (Map<String, Object>) hit.source())
                        .collect(Collectors.toList());

            } catch (IOException e) {
                throw new RuntimeException("Falha ao buscar logs no Elasticsearch", e);
            }
        });
    }
}
