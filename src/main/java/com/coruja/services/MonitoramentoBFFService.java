package com.coruja.services;

import com.coruja.dto.AlertaPassagemDTO;
import com.coruja.dto.PageAlertaPassagemDTO;
import com.coruja.dto.PagePlacaMonitoradaDTO;
import com.coruja.dto.PlacaMonitoradaDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.util.Collections;

@Service
@Slf4j
public class MonitoramentoBFFService {

    private final WebClient webClient;
    private final ReactiveCircuitBreaker circuitBreaker;

//    @Value("${microservico.monitoramento.url}")
//    private String monitoramentoUrl;

    private static final String MONITORAMENTO_SERVICE_ID = "MICROSERVICO-MONITORAMENTO";

    public MonitoramentoBFFService(WebClient.Builder webClientBuilder, ReactiveCircuitBreakerFactory cbFactory) {
        this.webClient = webClientBuilder.build();
        // A criação do circuit breaker no construtor está correta.
        this.circuitBreaker = cbFactory.create("monitoramentoService");
    }

    public Mono<Page<PlacaMonitoradaDTO>> listarMonitorados(Pageable pageable) {
        // 4. ALTERADO: A URL agora é construída com o NOME DO SERVIÇO. O "http://" é importante.
        String url = UriComponentsBuilder.fromUriString("http://" + MONITORAMENTO_SERVICE_ID + "/api/monitoramento")
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize())
                .toUriString();
        log.info("BFF chamando serviço de monitoramento via Discovery: {}", url);

        Mono<Page<PlacaMonitoradaDTO>> remoteCall = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(PagePlacaMonitoradaDTO.class)
                .map(pageDto -> (Page<PlacaMonitoradaDTO>) pageDto);

        return circuitBreaker.run(remoteCall, throwable -> {
            log.warn("Circuit Breaker ATIVADO para listarMonitorados. Causa: {}", throwable.getMessage());
            return Mono.just(new PageImpl<>(Collections.emptyList(), pageable, 0));
        });
    }

    public Mono<PlacaMonitoradaDTO> buscarPorId(Long id) {
        String url = "http://" + MONITORAMENTO_SERVICE_ID + "/api/monitoramento/" + id;
        Mono<PlacaMonitoradaDTO> remoteCall = webClient.get().uri(url).retrieve().bodyToMono(PlacaMonitoradaDTO.class);

        return circuitBreaker.run(remoteCall, throwable -> {
            log.warn("Circuit Breaker ATIVADO para buscarPorId({}). Causa: {}", id, throwable.getMessage());
            // Retorna um erro claro para o cliente, indicando que o recurso não foi encontrado
            return Mono.error(new WebClientResponseException(HttpStatus.SERVICE_UNAVAILABLE.value(), "Serviço de monitoramento indisponível", null, null, null));
        });
    }

    public Mono<PlacaMonitoradaDTO> criarMonitorado(PlacaMonitoradaDTO dto) {
        String url = "http://" + MONITORAMENTO_SERVICE_ID + "/api/monitoramento";
        Mono<PlacaMonitoradaDTO> remoteCall = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(PlacaMonitoradaDTO.class);

        return circuitBreaker.run(remoteCall, throwable -> {
            log.warn("Circuit Breaker ATIVADO para criarMonitorado. Causa: {}", throwable.getMessage());
            // Lança um erro, pois a operação de escrita falhou e o cliente precisa saber.
            return Mono.error(new RuntimeException("Falha ao criar monitoramento. Serviço indisponível."));
        });
    }

    public Mono<PlacaMonitoradaDTO> atualizarMonitorado(Long id, PlacaMonitoradaDTO dto) {
        String url = "http://" + MONITORAMENTO_SERVICE_ID + "/api/monitoramento/" + id;
        Mono<PlacaMonitoradaDTO> remoteCall = webClient.put()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(PlacaMonitoradaDTO.class);

        return circuitBreaker.run(remoteCall, throwable -> {
            log.warn("Circuit Breaker ATIVADO para atualizarMonitorado({}). Causa: {}", id, throwable.getMessage());
            return Mono.error(new RuntimeException("Falha ao atualizar monitoramento. Serviço indisponível."));
        });
    }

    public Mono<Void> deletarMonitorado(Long id) {
        String url = "http://" + MONITORAMENTO_SERVICE_ID + "/api/monitoramento/" + id;
        Mono<Void> remoteCall = webClient.delete().uri(url).retrieve().bodyToMono(Void.class);

        return circuitBreaker.run(remoteCall, throwable -> {
            log.warn("Circuit Breaker ATIVADO para deletarMonitorado({}). Causa: {}", id, throwable.getMessage());
            // Para um delete, podemos retornar um Mono.error() para sinalizar a falha.
            return Mono.error(new RuntimeException("Falha ao deletar monitoramento. Serviço indisponível."));
        });
    }

    /**
     * NOVO: Busca o histórico de alertas de forma paginada no serviço de monitoramento.
     */
    public Mono<Page<AlertaPassagemDTO>> listarAlertas(Pageable pageable) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("http://" + MONITORAMENTO_SERVICE_ID + "/api/monitoramento/alertas")
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize());

        pageable.getSort().forEach(order -> builder.queryParam("sort", order.getProperty() + "," + order.getDirection().name()));
        String url = builder.toUriString();

        log.info("BFF chamando serviço de monitoramento para buscar alertas via Discovery: {}", url);

        Mono<Page<AlertaPassagemDTO>> remoteCall = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(PageAlertaPassagemDTO.class)
                .map(pageDto -> (Page<AlertaPassagemDTO>) pageDto);

        return circuitBreaker.run(remoteCall, throwable -> {
            log.warn("Circuit Breaker ATIVADO para listarAlertas. Causa: {}", throwable.getMessage());
            return Mono.just(new PageImpl<>(Collections.emptyList(), pageable, 0));
        });
    }

}
