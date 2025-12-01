package com.coruja.services;

import com.coruja.dto.AlertaPassagemDTO;
import com.coruja.dto.PageAlertaPassagemDTO;
import com.coruja.dto.PagePlacaMonitoradaDTO;
import com.coruja.dto.PlacaMonitoradaDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    // ✅ Lendo do application.properties para flexibilidade
    @Value("${microservico.monitoramento.url:http://host.docker.internal:8089}")
    private String monitoramentoUrl;

    public MonitoramentoBFFService(RestTemplate restTemplate, CircuitBreakerFactory cbFactory) {
        this.restTemplate = restTemplate;
        this.circuitBreakerFactory = cbFactory;
        System.out.println(">>> MONITORAMENTO URL CONFIGURADA: " + this.monitoramentoUrl);
    }

    // Método auxiliar para garantir formatação da URL
    private String getUrl(String path) {
        // Garante que não duplique ou falte a barra
        String base = monitoramentoUrl.endsWith("/") ? monitoramentoUrl.substring(0, monitoramentoUrl.length() - 1) : monitoramentoUrl;
        String endpoint = path.startsWith("/") ? path : "/" + path;
        return base + endpoint;
    }

    public PageImpl<? extends Object> listarMonitorados(Pageable pageable) {
        // Usa a URL dinâmica
        String url = UriComponentsBuilder.fromUriString(getUrl("/api/monitoramento"))
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize())
                .toUriString();

        log.info("BFF chamando serviço de monitoramento em: {}", url);

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
        String url = getUrl("/api/monitoramento/" + id);
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");
        return circuitBreaker.run(
                () -> restTemplate.getForObject(url, PlacaMonitoradaDTO.class),
                t -> { throw new RuntimeException("Serviço indisponível"); }
        );
    }

    public PlacaMonitoradaDTO criarMonitorado(PlacaMonitoradaDTO dto) {
        String url = getUrl("/api/monitoramento");
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");
        return circuitBreaker.run(
                () -> restTemplate.postForObject(url, dto, PlacaMonitoradaDTO.class),
                t -> { throw new RuntimeException("Falha ao criar."); }
        );
    }

    public PlacaMonitoradaDTO atualizarMonitorado(Long id, PlacaMonitoradaDTO dto) {
        String url = getUrl("/api/monitoramento/" + id);
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");
        return circuitBreaker.run(
                () -> { restTemplate.put(url, dto); return dto; },
                t -> { throw new RuntimeException("Falha ao atualizar."); }
        );
    }

    public void deletarMonitorado(Long id) {
        String url = getUrl("/api/monitoramento/" + id);
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");
        circuitBreaker.run(
                () -> { restTemplate.delete(url); return null; },
                t -> { throw new RuntimeException("Falha ao deletar."); }
        );
    }

    public Page<AlertaPassagemDTO> listarAlertas(Pageable pageable) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(getUrl("/api/monitoramento/alertas"))
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize());

        // ... lógica de sort mantida ...

        String url = builder.toUriString();
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");
        return circuitBreaker.run(
                () -> {
                    ResponseEntity<PageAlertaPassagemDTO> response = restTemplate.getForEntity(url, PageAlertaPassagemDTO.class);
                    return response.getBody() != null ? response.getBody() : new PageImpl<>(Collections.emptyList(), pageable, 0);
                },
                t -> new PageImpl<>(Collections.emptyList(), pageable, 0)
        );
    }
}
