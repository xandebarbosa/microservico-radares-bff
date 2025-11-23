package com.coruja.services;

import com.coruja.dto.AlertaPassagemDTO;
import com.coruja.dto.PageAlertaPassagemDTO;
import com.coruja.dto.PagePlacaMonitoradaDTO;
import com.coruja.dto.PlacaMonitoradaDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.util.Collections;

@Service
@Slf4j
public class MonitoramentoBFFService {

    private final RestTemplate restTemplate;
    private final CircuitBreakerFactory circuitBreakerFactory;

//    @Value("${microservico.monitoramento.url}")
//    private String monitoramentoUrl;

    private static final String MONITORAMENTO_SERVICE_ID = "MICROSERVICO-MONITORAMENTO";

    public MonitoramentoBFFService(RestTemplate restTemplate, CircuitBreakerFactory cbFactory) {
        this.restTemplate = restTemplate;
        this.circuitBreakerFactory = cbFactory;
    }

    public PageImpl<? extends Object> listarMonitorados(Pageable pageable) {
        // 4. ALTERADO: A URL agora é construída com o NOME DO SERVIÇO. O "http://" é importante.
        String url = UriComponentsBuilder.fromUriString("http://" + MONITORAMENTO_SERVICE_ID + "/api/monitoramento")
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize())
                .toUriString();
        log.info("BFF chamando serviço de monitoramento via Discovery: {}", url);

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");

        return circuitBreaker.run(
                () -> {
                    try {
                        ResponseEntity<PagePlacaMonitoradaDTO> response = restTemplate.getForEntity(
                                url,
                                PagePlacaMonitoradaDTO.class
                        );
                        return response.getBody() != null
                                ? response.getBody()
                                : new PageImpl<>(Collections.emptyList(), pageable, 0);
                    } catch (Exception e) {
                        log.error("Erro ao listar monitorados: {}", e.getMessage());
                        throw e;
                    }
                },
                throwable -> {
                    log.warn("Circuit Breaker ativo para listarMonitorados: {}", throwable.getMessage());
                    return new PageImpl<>(Collections.emptyList(), pageable, 0);
                }
        );
    }

    public PlacaMonitoradaDTO buscarPorId(Long id) {
        String url = "http://" + MONITORAMENTO_SERVICE_ID + "/api/monitoramento/" + id;

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");

        return circuitBreaker.run(
                () -> {
                    ResponseEntity<PlacaMonitoradaDTO> response = restTemplate.getForEntity(
                            url,
                            PlacaMonitoradaDTO.class
                    );
                    return response.getBody();
                },
                throwable -> {
                    log.warn("Circuit Breaker ativo para buscarPorId({})", id);
                    throw new RuntimeException("Serviço de monitoramento indisponível");
                }
        );
    }

    public PlacaMonitoradaDTO criarMonitorado(PlacaMonitoradaDTO dto) {
        String url = "http://" + MONITORAMENTO_SERVICE_ID + "/api/monitoramento";

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");

        return circuitBreaker.run(
                () -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    HttpEntity<PlacaMonitoradaDTO> request = new HttpEntity<>(dto, headers);

                    ResponseEntity<PlacaMonitoradaDTO> response = restTemplate.postForEntity(
                            url,
                            request,
                            PlacaMonitoradaDTO.class
                    );
                    return response.getBody();
                },
                throwable -> {
                    log.warn("Circuit Breaker ativo para criarMonitorado");
                    throw new RuntimeException("Falha ao criar monitoramento. Serviço indisponível.");
                }
        );
    }

    public PlacaMonitoradaDTO atualizarMonitorado(Long id, PlacaMonitoradaDTO dto) {
        String url = "http://" + MONITORAMENTO_SERVICE_ID + "/api/monitoramento/" + id;

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");

        return circuitBreaker.run(
                () -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    HttpEntity<PlacaMonitoradaDTO> request = new HttpEntity<>(dto, headers);

                    ResponseEntity<PlacaMonitoradaDTO> response = restTemplate.exchange(
                            url,
                            HttpMethod.PUT,
                            request,
                            PlacaMonitoradaDTO.class
                    );
                    return response.getBody();
                },
                throwable -> {
                    log.warn("Circuit Breaker ativo para atualizarMonitorado({})", id);
                    throw new RuntimeException("Falha ao atualizar monitoramento. Serviço indisponível.");
                }
        );
    }

    public void deletarMonitorado(Long id) {
        String url = "http://" + MONITORAMENTO_SERVICE_ID + "/api/monitoramento/" + id;

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");

        circuitBreaker.run(
                () -> {
                    restTemplate.delete(url);
                    return null;
                },
                throwable -> {
                    log.warn("Circuit Breaker ativo para deletarMonitorado({})", id);
                    throw new RuntimeException("Falha ao deletar monitoramento. Serviço indisponível.");
                }
        );
    }

    public Page<AlertaPassagemDTO> listarAlertas(Pageable pageable) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("http://" + MONITORAMENTO_SERVICE_ID + "/api/monitoramento/alertas")
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize());

        pageable.getSort().forEach(order ->
                builder.queryParam("sort", order.getProperty() + "," + order.getDirection().name()));

        String url = builder.toUriString();
        log.info("BFF chamando serviço de monitoramento para alertas: {}", url);

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");

        return circuitBreaker.run(
                () -> {
                    ResponseEntity<PageAlertaPassagemDTO> response = restTemplate.getForEntity(
                            url,
                            PageAlertaPassagemDTO.class
                    );
                    return response.getBody() != null
                            ? response.getBody()
                            : new PageImpl<>(Collections.emptyList(), pageable, 0);
                },
                throwable -> {
                    log.warn("Circuit Breaker ativo para listarAlertas");
                    return new PageImpl<>(Collections.emptyList(), pageable, 0);
                }
        );
    }

}
