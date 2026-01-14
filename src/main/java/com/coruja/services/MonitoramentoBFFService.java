package com.coruja.services;

import com.coruja.dto.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class MonitoramentoBFFService {

    private final RestTemplate loadBalancedRestTemplate; // Para Eureka
    private final RestTemplate directRestTemplate;       // Para Host/IP
    private final CircuitBreakerFactory circuitBreakerFactory;

    // Variável que guardará o RestTemplate correto para usar
    private RestTemplate currentRestTemplate;

    // ✅ Lendo do application.properties para flexibilidade
    //@Value("${microservico.monitoramento.url:http://host.docker.internal:8089}")
    @Value("${microservico.monitoramento.url:http://MICROSERVICO-MONITORAMENTO}")
    private String monitoramentoUrl;

    public MonitoramentoBFFService(
            RestTemplate restTemplate,
            @Qualifier("directRestTemplate") RestTemplate directRestTemplate,
            CircuitBreakerFactory cbFactory)
    {
        this.loadBalancedRestTemplate = restTemplate;
        this.directRestTemplate = directRestTemplate;
        this.circuitBreakerFactory = cbFactory;
        System.out.println(">>> MONITORAMENTO URL CONFIGURADA: " + this.monitoramentoUrl);
    }

    /**
     * Decide qual RestTemplate usar baseado na URL configurada.
     */
    @PostConstruct
    public void init() {
        // Se a URL tiver "localhost", ":" (porta) ou "host.docker", usamos conexão direta
        if (monitoramentoUrl.contains("localhost") ||
                monitoramentoUrl.contains("host.docker.internal") ||
                monitoramentoUrl.matches(".*:\\d+.*")) { // regex para detectar porta

            this.currentRestTemplate = directRestTemplate;
            log.info("🔧 MODO DEV DETECTADO: Usando conexão DIRETA para Monitoramento em: {}", monitoramentoUrl);
        } else {
            this.currentRestTemplate = loadBalancedRestTemplate;
            log.info("☁️ MODO CLOUD DETECTADO: Usando conexão EUREKA para Monitoramento em: {}", monitoramentoUrl);
        }
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
                        ResponseEntity<PagePlacaMonitoradaDTO> response = currentRestTemplate.getForEntity(
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
                () -> currentRestTemplate.getForObject(url, PlacaMonitoradaDTO.class),
                throwable -> handleException("buscar por id", throwable)
        );
    }

    public PlacaMonitoradaDTO criarMonitorado(PlacaMonitoradaDTO dto) {
        String url = getUrl("/api/monitoramento");
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");
        return circuitBreaker.run(
                () -> currentRestTemplate.postForObject(url, dto, PlacaMonitoradaDTO.class),
                throwable -> handleException("criar", throwable) // Chama o método auxiliar
        );
    }

    public PlacaMonitoradaDTO atualizarMonitorado(Long id, PlacaMonitoradaDTO dto) {
        String url = getUrl("/api/monitoramento/" + id);
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");
        return circuitBreaker.run(
                () -> { currentRestTemplate.put(url, dto); return dto; },
                throwable -> handleException("atualizar", throwable)
        );
    }

    public void deletarMonitorado(Long id) {
        String url = getUrl("/api/monitoramento/" + id);
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoService");
        circuitBreaker.run(
                () -> { currentRestTemplate.delete(url); return null; },
                throwable -> {
                    handleException("deletar", throwable);
                    return null;
                }
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
                    ResponseEntity<PageAlertaPassagemDTO> response = currentRestTemplate.getForEntity(url, PageAlertaPassagemDTO.class);
                    return response.getBody() != null ? response.getBody() : new PageImpl<>(Collections.emptyList(), pageable, 0);
                },
                t -> new PageImpl<>(Collections.emptyList(), pageable, 0)
        );
    }

    // --- NOVOS MÉTODOS: TELEGRAM ---

    /**
     * Busca a lista de usuários do Telegram cadastrados no microsserviço.
     */
    public List<UsuarioTelegramDTO> listarUsuariosTelegram() {
        String url = getUrl("/api/usuarios-telegram");
        log.info("BFF buscando usuários do Telegram: {}", url);

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoTelegramService");

        return circuitBreaker.run(
                () -> {
                    // Usamos exchange com ParameterizedTypeReference para mapear a Lista corretamente
                    ResponseEntity<List<UsuarioTelegramDTO>> response = currentRestTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            null,
                            new ParameterizedTypeReference<List<UsuarioTelegramDTO>>() {}
                    );
                    return response.getBody() != null ? response.getBody() : Collections.emptyList();
                },
                throwable -> {
                    log.error("Falha ao buscar usuários telegram: {}", throwable.getMessage());
                    return Collections.emptyList();
                }
        );
    }

    /**
     * Aciona o endpoint de sincronização no microsserviço para buscar novas mensagens do Bot.
     */
    public List<UsuarioTelegramDTO> sincronizarUsuariosTelegram() {
        String url = getUrl("/api/usuarios-telegram/sincronizar");
        log.info("BFF solicitando sincronização do Telegram: {}", url);

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("monitoramentoTelegramService");

        return circuitBreaker.run(
                () -> {
                    ResponseEntity<List<UsuarioTelegramDTO>> response = currentRestTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            null,
                            new ParameterizedTypeReference<List<UsuarioTelegramDTO>>() {}
                    );
                    return response.getBody() != null ? response.getBody() : Collections.emptyList();
                },
                throwable -> {
                    log.error("Falha ao sincronizar telegram: {}", throwable.getMessage());
                    return Collections.emptyList();
                }
        );
    }

    private PlacaMonitoradaDTO handleException(String action, Throwable throwable) {
        log.error("❌ Erro ao {} monitorado: {}", action, throwable.getMessage());

        // Se for um erro HTTP (ex: 400 Bad Request, 404 Not Found) vindo do microserviço
        if (throwable instanceof HttpStatusCodeException) {
            HttpStatusCodeException httpError = (HttpStatusCodeException) throwable;
            log.error("Detalhes do erro API: Status={}, Body={}", httpError.getStatusCode(), httpError.getResponseBodyAsString());

            // Relança a exceção para que o Controller do BFF possa retornar o status code correto (ex: 400)
            throw httpError;
        }

        // Se for outro erro (timeout, conexão recusada), lança erro genérico
        throw new RuntimeException("Falha ao " + action + ": " + throwable.getMessage(), throwable);
    }
}
