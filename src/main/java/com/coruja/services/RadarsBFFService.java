package com.coruja.services;

import com.coruja.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RadarsBFFService {

    // ─── ObjectMapper compartilhado — criado uma vez, thread-safe ──────────────
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    // ─── Constantes ─────────────────────────────────────────────────────────────
    private static final long   REQUEST_TIMEOUT_SECONDS = 65;  //REQUEST_TIMEOUT_SECONDS
    private static final int    PAGE_SIZE_EXPORTACAO     = 1000;

    // ─── Dependências ───────────────────────────────────────────────────────────
    private final RestTemplate            loadBalancedRestTemplate;
    private final RestTemplate            directRestTemplate;
    private final RealtimeUpdateService   realtimeUpdateService;
    private final CircuitBreakerFactory   circuitBreakerFactory;
    private final SimpMessagingTemplate   messagingTemplate;
    private final DetranService           detranService;

    // Virtual Threads
    private final ExecutorService         executorService = Executors.newVirtualThreadPerTaskExecutor();

    // ─── Estado ─────────────────────────────────────────────────────────────────
    private RestTemplate monitoramentoRestTemplate;
    // Cache de memória para Fallback do Mapa (Resiliência)
    private final ConcurrentHashMap<String, List<RadarLocationDTO>> locationsFallbackCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> serviceUrlMap = new ConcurrentHashMap<>();

    private volatile String ultimoIdRondonEnviado    = "";
    private volatile String ultimoIdMonitoraSPEnviado = "";
    private volatile String ultimoIdEntreviasEnviado = "";
    private volatile String ultimoIdCartEnviado = "";
    private volatile String ultimoIdPantanalEnviado = "";
    private volatile String ultimoIdAppiaEnviado = "";
    private volatile String ultimoIdEixoEnviado = "";

    @Value("${microservico.monitoramento.url:http://MICROSERVICO-MONITORAMENTO}")
    private String monitoramentoUrl;

    @Value("${radares.limite-atraso-minutos:15}")
    private long limiteAtrasoMinutos = 15;

    private final Semaphore detranRateLimiter = new Semaphore(15);
    // ─── Construtor ─────────────────────────────────────────────────────────────
    public RadarsBFFService(
            RestTemplate loadBalancedRestTemplate,
            @Qualifier("directRestTemplate") RestTemplate directRestTemplate,
            RealtimeUpdateService realtimeUpdateService,
            CircuitBreakerFactory circuitBreakerFactory,
            SimpMessagingTemplate messagingTemplate,
            DetranService detranService
    ) {
        this.loadBalancedRestTemplate = loadBalancedRestTemplate;
        this.directRestTemplate       = directRestTemplate;
        this.realtimeUpdateService    = realtimeUpdateService;
        this.circuitBreakerFactory    = circuitBreakerFactory;
        this.messagingTemplate        = messagingTemplate;
        this.detranService            = detranService;
    }

    // ─── Inicialização ──────────────────────────────────────────────────────────
    @PostConstruct
    public void init() {
        log.info("Inicializando mapa de URLs dos serviços de radares...");

        this.monitoramentoRestTemplate =
                (monitoramentoUrl.contains("localhost") ||
                        monitoramentoUrl.contains("host.docker.internal") ||
                        monitoramentoUrl.matches(".*:\\d+.*"))
                        ? directRestTemplate
                        : loadBalancedRestTemplate;

        serviceUrlMap.put("cart",       "MICROSERVICO-RADARES-CART");
        serviceUrlMap.put("eixo",       "MICROSERVICO-RADARES-EIXO");
        serviceUrlMap.put("entrevias",  "MICROSERVICO-RADARES-ENTREVIAS");
        serviceUrlMap.put("rondon",     "MICROSERVICO-RADARES-RONDON");
        serviceUrlMap.put("monitorasp", "MICROSERVICO-RADARES-MONITORASP");
        serviceUrlMap.put("motiva",     "MICROSERVICO-RADARES-PANTANAL");
        serviceUrlMap.put("appia",      "MICROSERVICO-RADARES-APPIA");

        log.info("Mapa de serviços carregado: {}", serviceUrlMap);
    }

    @PreDestroy
    public void shutdown() {
        //log.info("Encerrando ExecutorService do BFF...");
        executorService.shutdown();
    }

    // ==================================================================================
    // 1. BUSCA POR PLACA
    // ==================================================================================
    public RadarPageDTO buscarPorPlaca(String placa, Pageable pageable) {
        List<String> urls = new ArrayList<>(serviceUrlMap.values());
        if (urls.isEmpty()) return paginaVazia(pageable);

        // 🚀 Passo 1: Dispara a busca do Detran em PARALELO com os radares
        CompletableFuture<JsonNode> futuroDetran = CompletableFuture.supplyAsync(() -> {
            try {
                detranRateLimiter.acquire();
                return detranService.consultarVeiculo(placa);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                detranRateLimiter.release();
            }
        }, executorService);

        // Passo 2: Dispara as buscas nos microsserviços de radares
        List<CompletableFuture<RadarPageDTO>> futures = urls.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchPlacaFromMicroservice(baseUrl, placa, pageable), executorService)
                        // ⏱️ O SEGREDO ESTÁ AQUI: Limite máximo de 5 segundos para a concessionária responder
                        .orTimeout(10, TimeUnit.SECONDS)
                        // 🛡️ SE FALHAR (Timeout, 500, Container Caiu): Pega o erro, loga e devolve uma página vazia
                        .exceptionally(ex -> {
                            log.warn("⚠️ [BFF] Fallback ativado para {}. Motivo: {}", baseUrl, ex.getMessage());
                            return new RadarPageDTO();
                        })
                )
                .toList();

        // Aguarda os radares respeitando o timeout
        RadarPageDTO pagina = aggregateBuscaPlaca(collectFutures(futures), pageable);

        // Passo 3: Costura os dados do Detran assim que os radares e o Detran terminarem
        if (pagina.getContent() != null && !pagina.getContent().isEmpty()) {
            JsonNode dadosDetran = null;
            try {
                // Aguarda o Detran por no máximo 4 segundos para não atrasar a resposta da tela
                dadosDetran = futuroDetran.get(4, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("⚠️ [BFF] Detran demorou muito para responder ou falhou, aplicando valores padrão.");
            }

            for (RadarDTO radar : pagina.getContent()) {
                enriquecerRadarComDetran(radar, dadosDetran);
            }
        }

        return pagina;
    }

    private RadarPageDTO fetchPlacaFromMicroservice(String baseUrl, String placa, Pageable pageable) {
        try {
            // SOLUÇÃO: Ignoramos a paginação do utilizador na chamada aos microsserviços.
            // Pedimos sempre a página 0 com 1000 resultados para garantir que temos
            // dados suficientes para fazer a ordenação global e a paginação em memória de forma exata.
            URI uri = UriComponentsBuilder.fromUriString("http://" + baseUrl + "/radares/busca-placa")
                    .queryParam("placa", placa)
                    .queryParam("page", 0)
                    .queryParam("size", 1000)
                    .queryParam("sort", "data,desc")
                    .queryParam("sort", "hora,desc")
                    .build().encode().toUri();

            log.info("📡 [BFF Placa] Chamando: {}", uri);
            return executeCircuitBreakerRequest("buscaPlaca-" + baseUrl, baseUrl, uri.toString());
        } catch (Exception e) {
            log.error("🔥 [BFF] Erro ao preparar chamada placa para {}: {}", baseUrl, e.getMessage());
            return paginaVazia(pageable);
        }
    }

    // ==================================================================================
    // 2. BUSCA POR LOCAL
    // ==================================================================================
    public RadarPageDTO buscarPorLocal(
            List<String> concessionarias, LocalDate data, LocalTime horaInicial, LocalTime horaFinal,
            String rodovia, String praca, String km, String sentido, Pageable pageable) {

        List<String> urls;

        if (CollectionUtils.isEmpty(concessionarias)) {
            // Roteia para todos se a lista vier nula ou vazia
            urls = new ArrayList<>(serviceUrlMap.values());
            log.debug("⚠️ [BFF Local] Nenhuma concessionária especificada. Modo Broadcast ativado.");
        } else {
            // Sanitização pesada: resolve problemas do Spring com @RequestParam List<String>
            urls = concessionarias.stream()
                    .filter(c -> c != null && !c.isBlank())
                    .flatMap(c -> Arrays.stream(c.split(","))) // Divide caso venha "entrevias,rondon" na mesma string
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .map(serviceUrlMap::get)
                    .filter(Objects::nonNull)
                    .distinct() // Evita requisições duplicadas para o mesmo serviço
                    .toList();
        }

        if (urls.isEmpty()) {
            log.warn("⚠️ [BFF Local] Concessionárias informadas não correspondem a nenhum serviço ativo.");
            return paginaVazia(pageable);
        }

        List<CompletableFuture<RadarPageDTO>> futures = urls.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchLocalFromMicroservice(baseUrl, data, horaInicial, horaFinal, rodovia, praca, km, sentido, pageable),
                        executorService)
                        .orTimeout(10, TimeUnit.SECONDS)
                        // 🛡️ SE FALHAR (Timeout, 500, Container Caiu): Pega o erro, loga e devolve uma página vazia
                        .exceptionally(ex -> {
                            log.warn("⚠️ [BFF] Fallback ativado para busca por local {}. Motivo: {}", baseUrl, ex.getMessage());
                            return new RadarPageDTO(); // Retorna vazio para não quebrar a agregação das outras
                        })
                )
                .toList();

        return aggregateGlobalPages(collectFutures(futures), pageable);
    }

    private RadarPageDTO fetchLocalFromMicroservice(
            String baseUrl, LocalDate data,
            LocalTime horaInicial, LocalTime horaFinal,
            String rodovia, String praca, String km, String sentido, Pageable pageable
    ) {
        try {
            String urlBase = "http://" + baseUrl + "/radares/busca-local";

            // UriComponentsBuilder remove automaticamente parâmetros nulos
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(urlBase)
                    .queryParamIfPresent("data", Optional.ofNullable(data != null ? data.toString() : null))
                    .queryParamIfPresent("horaInicial", Optional.ofNullable(horaInicial != null ? horaInicial.toString() : null))
                    .queryParamIfPresent("horaFinal", Optional.ofNullable(horaFinal != null ? horaFinal.toString() : null))
                    .queryParamIfPresent("rodovia", Optional.ofNullable(rodovia))
                    .queryParamIfPresent("km", Optional.ofNullable(km))
                    .queryParamIfPresent("sentido", Optional.ofNullable(sentido))
                    .queryParamIfPresent("praca", Optional.ofNullable(praca))
                    .queryParam("page", pageable.getPageNumber())
                    .queryParam("size", pageable.getPageSize());

            URI urlFinal = builder.build().encode().toUri();
            log.info("📡 [BFF Local] Chamando: {}", urlFinal);

            String cbName = "radaresLocal-" + baseUrl.toLowerCase().replace("microservico-radares-", "");

            return circuitBreakerFactory.create(cbName).run(() -> {
                // Chamada direta com a URL final já codificada e limpa
                ResponseEntity<JsonNode> response = loadBalancedRestTemplate.getForEntity(urlFinal, JsonNode.class);

                RadarPageDTO result = parseJsonNodeToPage(response.getBody(), baseUrl);
                log.info("✅ [BFF Local] Sucesso: {} registros de {}", result.getContent().size(), baseUrl);
                return result;
            }, throwable -> {
                logThrowable("[BFF Local]", cbName, baseUrl, throwable);
                return paginaVazia(Pageable.unpaged());
            });

        } catch (Exception e) {
            log.error("❌ Erro ao preparar chamada local para {}: {}", baseUrl, e.getMessage());
            return paginaVazia(pageable);
        }
    }

    // ==================================================================================
    // 3. RODOVIAS E KMs
    // ==================================================================================

    @Cacheable(
            value  = "lista-rodovias-bff",
            key    = "#concessionaria != null ? #concessionaria.toLowerCase().trim() : 'all'",
            unless = "#result == null || #result.isEmpty()"
    )
    public List<RodoviaDTO> listarRodovias(String concessionaria) {
        //log.info("🔍 BFF: Solicitando lista de rodovias aos microserviços...");

        if (concessionaria != null && !concessionaria.trim().isEmpty()) {
            String concKey = concessionaria.trim().toLowerCase();
            String baseUrl = serviceUrlMap.get(concKey);

            if (baseUrl != null) {
                String url = "http://" + baseUrl + "/radares/rodovias";
                log.debug("📡 [BFF] Buscando rodovias na URL: {}", url);

                return executeCircuitBreakerListRequest(
                        "listRodovias",
                        baseUrl,
                        url,
                        RodoviaDTO.class
                );
            } else {
                log.warn("⚠️ [BFF] Concessionária '{}' não encontrada no serviceUrlMap.", concKey);
                // 🔹 CORREÇÃO: Se o frontend pediu uma específica e não mapeamos, devolvemos vazio!
                // Jamais devemos cair no fetchListFromAll neste cenário.
                return Collections.emptyList();
            }
        }

        // Se o parâmetro concessionaria veio nulo ou vazio, aí sim buscamos de todas.
        log.debug("📡 [BFF] Buscando rodovias de TODAS as concessionárias...");
        return fetchListFromAll("rodovias", RodoviaDTO.class);
    }

    @CacheEvict(value = "lista-rodovias-bff", allEntries = true)
    public RodoviaDTO salvarRodovia(RodoviaDTO dto, String concessionaria) {
        String baseUrl = resolveBaseUrl(concessionaria, "cart");
        return loadBalancedRestTemplate.postForObject(
                "http://" + baseUrl + "/radares/rodovias", dto, RodoviaDTO.class);
    }

    @CacheEvict(value = "lista-rodovias-bff", allEntries = true)
    public void deletarRodovia(Long id, String concessionaria) {
        String baseUrl = resolveBaseUrl(concessionaria, "cart");
        if (baseUrl != null) {
            loadBalancedRestTemplate.delete("http://" + baseUrl + "/radares/rodovias/" + id);
        }
    }

    public List<KmRodoviaDTO> listarKmsPorRodovia(Long rodoviaId, String concessionaria) {
        //log.info("🔍 BFF: Solicitando lista de KMs por rodovia...");
        String baseUrl = resolveBaseUrl(concessionaria, "cart");

        if (baseUrl == null) {
            //log.warn("⚠️ [BFF] Serviço não encontrado para a concessionária: {}", concessionaria);
            return Collections.emptyList();
        }

        //log.info("📍 [BFF] Roteando busca de KMs da rodovia {} para: {}", rodoviaId, baseUrl);
        return executeCircuitBreakerListRequest(
                "listarKms", baseUrl,
                "http://" + baseUrl + "/radares/rodovias/" + rodoviaId + "/kms",
                KmRodoviaDTO.class);
    }

    public KmRodoviaDTO salvarKm(KmRodoviaDTO dto, String concessionaria) {
        String baseUrl = resolveBaseUrl(concessionaria, "cart");
        if (baseUrl == null) throw new IllegalStateException("Serviço não disponível: " + concessionaria);
        return loadBalancedRestTemplate.postForObject(
                "http://" + baseUrl + "/radares/kms", dto, KmRodoviaDTO.class);
    }

    public void deletarKm(Long id, String concessionaria) {
        String baseUrl = resolveBaseUrl(concessionaria, "cart");
        if (baseUrl != null) {
            loadBalancedRestTemplate.delete("http://" + baseUrl + "/radares/kms/" + id);
        }
    }

    // ==================================================================================
    // 4. GEOESPACIAL
    // ==================================================================================
    public RadarPageDTO buscarPorGeolocalizacao(
            Double latitude, Double longitude, Double raio,
            LocalDate data, LocalTime horaInicio, LocalTime horaFim, Pageable pageable) {

        List<String> urls = new ArrayList<>(serviceUrlMap.values());
        if (urls.isEmpty()) return paginaVazia(pageable);

        List<CompletableFuture<RadarPageDTO>> futures = urls.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchGeoPageFromMicroservice(baseUrl, latitude, longitude, raio, data, horaInicio, horaFim, pageable),
                        executorService))
                .toList();

        return aggregateGlobalPages(collectFutures(futures), pageable);
    }

    @Cacheable(value = "locais-radares-bff", unless = "#result == null || #result.isEmpty()")
    public List<RadarLocationDTO> getAllRadarLocations() {
        log.info("📍 [BFF-MAPA] O FrontEnd pediu o mapa! Se este log apareceu, o CACHE ESTÁ DESLIGADO!");
        List<String> urls = new ArrayList<>(serviceUrlMap.values());

        if (urls.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("🚀 [BFF-MAPA] O BFF vai disparar requisições simultâneas para {} concessionárias: {}", urls.size(), urls);

        // 1. Dispara todas as requisições assíncronas e configura o timeout/erro em cada uma individualmente
        List<CompletableFuture<List<RadarLocationDTO>>> futures = urls.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(() -> fetchLocationsFromMicroservices(baseUrl), executorService)
                        // Timeout de 10 segundos (ajuste conforme necessidade)
                        .completeOnTimeout(Collections.emptyList(), 10, TimeUnit.SECONDS)
                        // Tratamento de erro individual para saber qual API falhou
                        .exceptionally(ex -> {
                            log.error("❌ Erro ou Timeout ao buscar localizações na concessionária [{}]: {}", baseUrl, ex.getMessage());
                            return Collections.emptyList(); // Se uma falhar, não quebra as outras
                        })
                )
                .toList(); // toList() inicia a execução imediata de todas

        // 2. Aguarda todas as tarefas terminarem em paralelo
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 3. Coleta os resultados (aqui o .join() é instantâneo pois o allOf já garantiu o término)
        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    public List<RadarDTO> buscarTodosPorGeolocalizacaoParaExportacao(
            Double latitude, Double longitude, Double raio,
            LocalDate data, LocalTime horaInicio, LocalTime horaFim
    ) {
        List<String> urls = new ArrayList<>(serviceUrlMap.values());
        if (urls.isEmpty()) return Collections.emptyList();

        List<CompletableFuture<List<RadarDTO>>> futures = urls.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchAllGeoPagesFromMicroservice(
                                baseUrl, latitude, longitude, raio, data, horaInicio, horaFim),
                        executorService
                ))
                .collect(Collectors.toList());

        List<RadarDTO> all = futures.stream()
                .map(f -> {
                        try { return f.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.MINUTES); }
                    catch (Exception e) {
                        log.error("Erro geo exportação: {}", e.toString());
                        return Collections.<RadarDTO>emptyList();
                    }
                })
                .flatMap(List::stream)
                .sorted(comparatorDataHoraDesc())
                .collect(Collectors.toList());

        // 🚀 APLICA A DEDUPLICAÇÃO NA EXPORTAÇÃO GEOESPACIAL
        List<RadarDTO> dadosDeduplicados = removerDuplicados(all);

        log.info("Exportação GEO finalizada. Total: {}", all.size());
        return dadosDeduplicados;
    }

    // ==================================================================================
    // 5. EXPORTAÇÃO
    // ==================================================================================

    public List<RadarDTO> buscarTodosParaExportacao(
            List<String> concessionarias, String placa, String praca,
            String rodovia, String km, String sentido,
            LocalDate data, LocalTime horaInicial, LocalTime horaFinal
    ) {
        List<String> urls;

        // 1. Blindagem e Sanitização (Semelhante ao buscarPorLocal)
        if (CollectionUtils.isEmpty(concessionarias)) {
            urls = new ArrayList<>(serviceUrlMap.values());
            log.warn("⚠️ [BFF Exportação] Nenhuma concessionária informada. Modo Broadcast ativado.");
        } else {
            urls = concessionarias.stream()
                    .filter(c -> c != null && !c.isBlank())
                    .flatMap(c -> Arrays.stream(c.split(","))) // Divide caso venha agrupado "entrevias,rondon"
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .map(serviceUrlMap::get)
                    .filter(Objects::nonNull)
                    .distinct() // Evita duplicidade de chamadas
                    .collect(Collectors.toList());
        }

        if (urls.isEmpty()) {
            log.warn("⚠️ [BFF Exportação] Nenhuma concessionária válida identificada. Cancelando.");
            return Collections.emptyList();
        }

        // 2. Dispara a busca apenas nos serviços identificados
        List<CompletableFuture<List<RadarDTO>>> futures = urls.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchAllPagesFromMicroservice(
                                baseUrl, placa, praca, rodovia, km, sentido, data, horaInicial, horaFinal),
                        executorService
                ))
                .collect(Collectors.toList());

        List<RadarDTO> all = futures.stream()
                .map(f -> {
                    try { return f.get(5, TimeUnit.MINUTES); }
                    catch (Exception e) { return Collections.<RadarDTO>emptyList(); }
                })
                .flatMap(List::stream)
                .sorted(comparatorDataHoraDesc())
                .collect(Collectors.toList());

        // 🚀 APLICA A DEDUPLICAÇÃO NA EXPORTAÇÃO
        List<RadarDTO> dadosDeduplicados = removerDuplicados(all);

        log.info("Exportação finalizada. Total: {}", all.size());
        return dadosDeduplicados;
    }

    public List<RadarDTO> exportarComDadosDetran(
            List<String> concessionarias, String placa, String praca,
            String rodovia, String km, String sentido,
            LocalDate data, LocalTime horaInicial, LocalTime horaFinal
    ) {
        // 1. Busca todos os dados brutos dos radares (sem paginação)
        List<RadarDTO> listaRadares = buscarTodosParaExportacao(
                concessionarias, placa, praca, rodovia, km, sentido, data, horaInicial, horaFinal
        );

        if (listaRadares.isEmpty()) return listaRadares;

        log.info("🛡️ [BFF] Iniciando enriquecimento SEGURO de {} registros no Detran...", listaRadares.size());

        // 2. Extrai apenas as placas únicas (se o carro passou 10x, só consultamos 1x)
        Set<String> placasUnicas = listaRadares.stream()
                .map(RadarDTO::getPlaca)
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.toSet());

        // 3. Mapa para guardar os dados e depois costurar nos radares
        ConcurrentHashMap<String, JsonNode> dadosDetranMap = new ConcurrentHashMap<>();

        // 4. Dispara a consulta para as placas ÚNICAS, passando pelo Semáforo e pelo Cache
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String placaUnica : placasUnicas) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        // 🚦 A Thread pede permissão para passar. Se já tiverem 15 na frente, ela aguarda.
                        detranRateLimiter.acquire();

                        JsonNode dados = detranService.consultarVeiculo(placaUnica);
                        if (dados != null) {
                            dadosDetranMap.put(placaUnica, dados);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        // Captura exceções do DetranService (como Placa Inválida ou Indisponibilidade)
                        log.warn("⚠️ [Exportação] Falha ao enriquecer a placa {}: {}. Aplicando N/I.", placaUnica, e.getMessage());
                        // Não adiciona nada no dadosDetranMap, o que forçará o loop abaixo a preencher com N/I
                    } finally {
                        // 🚦 A Thread terminou e libera a catraca para a próxima
                        detranRateLimiter.release();
                    }
                }, executor));
            }
            // Aguarda todas as buscas finalizarem
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        // 5. Costura os dados retornados na lista original (Super rápido, tudo em memória)
        for (RadarDTO radar : listaRadares) {
            JsonNode dados = dadosDetranMap.get(radar.getPlaca());
            enriquecerRadarComDetran(radar, dados);
        }

        return listaRadares;
    }

    // ==================================================================================
    // 6. ÚLTIMOS PROCESSADOS (COM DETECÇÃO DE ATRASO)
    // ==================================================================================

    public List<RadarDTO> getUltimosRadaresProcessados() {
        log.info("📡 [BFF] Buscando últimos radares de todas as concessionárias...");

        // 1. Coleta os radares mais recentes em memória (RabbitMQ / Schedulers)
        Map<String, RadarDTO> mapaPorConcessionaria = new ConcurrentHashMap<>();

        realtimeUpdateService.getLatestRadars().forEach((concessionaria, radar) -> {
            if (radar != null && concessionaria != null && !concessionaria.isBlank()) {
                mapaPorConcessionaria.put(concessionaria.trim().toUpperCase(), radar);
            }
        });

        // 2. Busca do banco e dos microsserviços em paralelo com Virtual Threads
        List<RadarDTO> doBanco = fetchUltimosFromDatabaseParalelo();
        for (RadarDTO radar : doBanco) {
            if (radar != null && radar.getConcessionaria() != null && !radar.getConcessionaria().isBlank()) {
                String concKey = radar.getConcessionaria().trim().toUpperCase();
                mapaPorConcessionaria.merge(concKey, radar, (existente, novo) -> {
                    LocalDateTime dtExistente = extrairLocalDateTime(existente);
                    LocalDateTime dtNovo = extrairLocalDateTime(novo);
                    if (dtExistente == null) return novo;
                    if (dtNovo == null) return existente;
                    return dtNovo.isAfter(dtExistente) ? novo : existente;
                });
            }
        }

        // 3. Garante que TODAS as concessionárias configuradas estejam representadas no Dashboard
        Set<String> concessionariasEsperadas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        concessionariasEsperadas.addAll(List.of("CART", "EIXO", "ENTREVIAS", "RONDON", "MONITORASP", "PANTANAL", "APPIA"));
        for (String k : serviceUrlMap.keySet()) {
            if (!k.equalsIgnoreCase("motiva")) {
                concessionariasEsperadas.add(k.toUpperCase());
            }
        }

        for (String concKey : concessionariasEsperadas) {
            mapaPorConcessionaria.computeIfAbsent(concKey, key -> {
                RadarDTO placeholder = new RadarDTO();
                placeholder.setConcessionaria(formatarNomeConcessionaria(key));
                placeholder.setPlaca("---");
                placeholder.setRodovia("N/A");
                placeholder.setKm("N/A");
                placeholder.setPraca("N/A");
                placeholder.setSentido("N/A");
                placeholder.setAtrasado(true);
                placeholder.setStatusOperacional("SEM_COMUNICACAO");
                placeholder.setTempoAtrasoFormatado("Sem comunicação");
                return placeholder;
            });
        }

        // 4. Calcula o atraso e status operacional de cada radar
        List<RadarDTO> resultado = new ArrayList<>(mapaPorConcessionaria.values());
        for (RadarDTO radar : resultado) {
            if (!"SEM_COMUNICACAO".equals(radar.getStatusOperacional())) {
                radar.calcularAtraso(limiteAtrasoMinutos);
                if (radar.getConcessionaria() != null) {
                    realtimeUpdateService.getLatestRadars().put(radar.getConcessionaria().trim().toUpperCase(), radar);
                }
            }
        }

        // 5. Ordena: Concessionárias atrasadas primeiro (para destacar alertas), depois por nome
        resultado.sort(Comparator
                .comparing(RadarDTO::getAtrasado, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(RadarDTO::getConcessionaria, String.CASE_INSENSITIVE_ORDER));

        long totalAtrasados = resultado.stream().filter(r -> Boolean.TRUE.equals(r.getAtrasado())).count();
        long totalAtuais = resultado.size() - totalAtrasados;
        log.info("✅ [BFF] Painel de últimos radares: {} concessionárias ({} atuais, {} atrasadas, limite tolerância: {} min).",
                resultado.size(), totalAtuais, totalAtrasados, limiteAtrasoMinutos);

        return resultado;
    }

    private List<RadarDTO> fetchUltimosFromDatabase() {
        return fetchUltimosFromDatabaseParalelo();
    }

    private List<RadarDTO> fetchUltimosFromDatabaseParalelo() {
        List<CompletableFuture<List<RadarDTO>>> futures = new ArrayList<>();

        // Consulta paralela direta do último registro de cada microsserviço
        Set<String> servicos = new LinkedHashSet<>(List.of("cart", "eixo", "entrevias", "rondon", "monitorasp", "motiva", "appia"));

        for (String servico : servicos) {
            futures.add(CompletableFuture.supplyAsync(() -> fetchUltimosPorServico(servico), executorService)
                    .completeOnTimeout(Collections.emptyList(), 4, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        log.warn("⚠️ [BFF] Falha ao consultar últimos de {}: {}", servico, ex.getMessage());
                        return Collections.emptyList();
                    }));
        }

        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<RadarDTO> fetchUltimosMonitoramentoLegado() {
        String endpoint = getMonitoramentoUrl("/api/monitoramento/ultimos");
        try {
            ResponseEntity<List<RadarDTO>> resp = monitoramentoRestTemplate.exchange(
                    endpoint,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {});

            List<RadarDTO> registros = Optional.ofNullable(resp.getBody()).orElse(Collections.emptyList());
            if (!registros.isEmpty()) {
                log.info("✅ Recuperados {} registros do Monitoramento legado.", registros.size());
            }
            return registros;
        } catch (HttpServerErrorException | HttpClientErrorException e) {
            log.warn("⚠️ Endpoint do Monitoramento indisponível (Status: {}): ignorando.", e.getStatusCode());
        } catch (Exception e) {
            log.error("❌ Falha de rede ao tentar conectar no Monitoramento: {}", e.getMessage());
            log.debug("Verifique a URL configurada no application.properties/yaml. Não use host.docker.internal.");
        }
        return Collections.emptyList();
    }

    private List<RadarDTO> fetchUltimosPorServico(String servico) {
        String baseUrl = serviceUrlMap.get(servico.toLowerCase());
        if (baseUrl == null || baseUrl.isBlank()) {
            return Collections.emptyList();
        }

        String url = "http://" + baseUrl + "/radares/ultimos?limite=1";
        try {
            ResponseEntity<RadarDTO[]> resp = loadBalancedRestTemplate.getForEntity(url, RadarDTO[].class);
            RadarDTO[] body = resp.getBody();

            if (body != null && body.length > 0) {
                log.info("✅ Recuperado último registro da {}.", servico.toUpperCase());
                for (RadarDTO r : body) {
                    if (r != null) {
                        r.setConcessionaria(formatarNomeConcessionaria(servico));
                    }
                }
                return Arrays.asList(body);
            }
        } catch (Exception e) {
            log.warn("❌ Falha de comunicação com {}: {}", servico.toUpperCase(), e.getMessage());
        }
        return Collections.emptyList();
    }

    private LocalDateTime extrairLocalDateTime(RadarDTO radar) {
        if (radar == null || radar.getData() == null || radar.getHora() == null) {
            return null;
        }
        try {
            return LocalDateTime.of(radar.getData(), radar.getHora());
        } catch (Exception e) {
            return null;
        }
    }

    private String formatarNomeConcessionaria(String nome) {
        if (nome == null || nome.isBlank()) return "Desconhecido";
        String lower = nome.trim().toLowerCase();
        return switch (lower) {
            case "cart" -> "Cart";
            case "eixo" -> "Eixo";
            case "entrevias" -> "Entrevias";
            case "rondon" -> "Rondon";
            case "monitorasp" -> "MonitoraSP";
            case "motiva" -> "Motiva";
            case "appia" -> "Appia";
            default -> Character.toUpperCase(lower.charAt(0)) + (lower.length() > 1 ? lower.substring(1) : "");
        };
    }

    // ─── Método Utilitário de Deduplicação ──────────────────────────────────────

    /**
     * Remove registros de radares duplicados baseados na mesma Placa, Data e Hora exata.
     */
    private List<RadarDTO> removerDuplicados(List<RadarDTO> radares) {
        Set<String> vistos = new HashSet<>();
        return radares.stream()
                .filter(r -> {
                    // Se faltar algum dado essencial, mantemos por segurança
                    if (r.getPlaca() == null || r.getData() == null || r.getHora() == null) {
                        return true;
                    }

                    // Chave de unicidade (Ex: "ABC1D23|2026-04-26|08:48:28")
                    String chaveUnica = r.getPlaca().trim().toUpperCase() + "|" +
                            r.getData().toString() + "|" +
                            r.getHora().toString();

                    // O método add() retorna 'true' se for um item inédito,
                    // e 'false' se já existir no Set (ou seja, é duplicado e será removido)
                    return vistos.add(chaveUnica);
                })
                .collect(Collectors.toList());
    }

    // ==================================================================================
    // CIRCUIT BREAKERS — HTTP com JsonNode (sem RestPage, sem ambiguidade)
    // ==================================================================================

    /**
     * CB genérico para busca paginada (busca-placa, geo-search).
     * Usa JsonNode — elimina o WARN do RestPage e suporta formato Spring e Quarkus.
     */
    private RadarPageDTO executeCircuitBreakerRequest(String cbName, String baseUrl, String url) {
        return circuitBreakerFactory.create(cbName).run(() -> {
            ResponseEntity<JsonNode> response = loadBalancedRestTemplate.exchange(url, HttpMethod.GET, null, JsonNode.class);
            return parseJsonNodeToPage(response.getBody(), baseUrl);
        }, throwable -> handlePlacaFallback(cbName, baseUrl, throwable));
    }

    /**
     * CB para busca-local — mesmo padrão, separado para clareza nos logs.
     */
    private RadarPageDTO executeCircuitBreakerRequestJsonNode(String cbName, String baseUrl, URI uri) {
        return circuitBreakerFactory.create(cbName).run(() -> {
            // 🔴 ENVIANDO A 'uri' COMO OBJETO: Impede o Spring de fazer % virar %25
            ResponseEntity<JsonNode> response = loadBalancedRestTemplate.getForEntity(uri, JsonNode.class);
            RadarPageDTO result = parseJsonNodeToPage(response.getBody(), baseUrl);
            log.info("✅ [BFF Local] Sucesso: {} registros de {}", result.getContent().size(), baseUrl);
            return result;
        }, throwable -> {
            logThrowable("[BFF Local]", cbName, baseUrl, throwable);
            return paginaVazia(Pageable.unpaged());
        });
    }

    /**
     * CB para listas (rodovias, KMs).
     * CollectionType resolve o type erasure do Java Generics.
     */
    private <T> List<T> executeCircuitBreakerListRequest(
            String cbName, String baseUrl, String url, Class<T> itemType
    ) {
        CircuitBreaker cb = circuitBreakerFactory.create(cbName);

        return cb.run(() -> {
            log.info("📡 [BFF List] Chamando: {}", url);

            ResponseEntity<JsonNode> response = loadBalancedRestTemplate.exchange(
                    url, HttpMethod.GET, null, JsonNode.class);

            JsonNode body = response.getBody();
            if (body == null || body.isNull() || body.isEmpty()) {
                log.warn("⚠️ [BFF List] Resposta vazia de {}", baseUrl);
                return Collections.<T>emptyList();
            }

            CollectionType listType = MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, itemType);
            List<T> result = MAPPER.convertValue(body, listType);

            log.info("✅ [BFF List] {} itens ({}) recebidos de {}",
                    result.size(), itemType.getSimpleName(), baseUrl);
            return result;

        }, throwable -> {
            log.warn("⚠️ [BFF List] Fallback {} [{}]: {}", baseUrl, cbName, throwable.getMessage());
            return Collections.<T>emptyList();
        });
    }

    /**
     * Agregador exclusivo para a Busca por Placa.
     * Faz a junção, remoção de duplicados, ordenação global e paginação rigorosa em memória.
     */
    private RadarPageDTO aggregateBuscaPlaca(List<RadarPageDTO> pages, Pageable pageable) {
        // 1. Junta tudo o que os microsserviços devolveram (Até 5000 registos em memória)
        List<RadarDTO> combined = pages.stream()
                .filter(p -> p != null && p.getContent() != null)
                .flatMap(p -> p.getContent().stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 2. Remove duplicados ANTES de fatiar a página
        List<RadarDTO> deduplicados = removerDuplicados(combined);

        // 3. Ordenação Global Absoluta de todos os resultados
        deduplicados.sort(comparatorDataHoraDesc());

        // 4. Paginação Real em Memória para o DataGrid
        int totalElements = deduplicados.size();
        int pageSize = pageable.getPageSize();
        int pageNumber = pageable.getPageNumber();
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalElements / pageSize) : 0;

        int fromIndex = pageNumber * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, totalElements);

        List<RadarDTO> paged = (fromIndex >= totalElements)
                ? Collections.emptyList()
                : deduplicados.subList(fromIndex, toIndex);

        log.info("📄 [BFF] Paginação em Memória da Placa: {} registos totais globais, a devolver a página {}/{} com {} itens",
                totalElements, pageNumber, totalPages, paged.size());

        return new RadarPageDTO(paged, new PageMetadata(pageNumber, pageSize, totalElements, totalPages));
    }

    // ==================================================================================
    // HELPERS PRIVADOS
    // ==================================================================================

    /**
     * Converte JsonNode paginado para RadarPageDTO.
     * Suporta formato Quarkus {"page":{...}} e Spring {number, size, ...} no mesmo método.
     */
    private RadarPageDTO parseJsonNodeToPage(JsonNode root, String baseUrl) {
        if (root == null || root.isEmpty()) {
            return paginaVazia(Pageable.unpaged());
        }

        List<RadarDTO> content = new ArrayList<>();
        JsonNode contentNode = root.get("content");
        if (contentNode != null && contentNode.isArray() && !contentNode.isEmpty()) {
            content = MAPPER.convertValue(contentNode, new TypeReference<>() {});

            // 🚀 A BLINDAGEM: Se o JSON veio sem concessionária, o BFF injeta baseado no nome do microsserviço chamado
            String concName = baseUrl.replace("MICROSERVICO-RADARES-", "").toLowerCase();
            for (RadarDTO dto : content) {
                if (dto.getConcessionaria() == null || dto.getConcessionaria().trim().isEmpty()) {
                    dto.setConcessionaria(concName);
                }
            }
        }

        int number = 0, size = 20, totalPages = 0;
        long totalElements = 0L;

        JsonNode p = root.has("page") && root.get("page").isObject() ? root.get("page") : root;
        number = p.path("number").asInt(0);
        size = p.path("size").asInt(20);
        totalPages = p.path("totalPages").asInt(0);
        totalElements = p.path("totalElements").asLong(0);

        return new RadarPageDTO(content, new PageMetadata(number, size, totalElements, totalPages));
    }

    /**
     * Fallback do buscarPorPlaca — distingue 404 normal de falha real.
     */
    private RadarPageDTO handlePlacaFallback(String cbName, String baseUrl, Throwable throwable) {
        logThrowable("[BFF Placa]", cbName, baseUrl, throwable);
        return paginaVazia(Pageable.unpaged());
    }

    private void logThrowable(String prefix, String cbName, String baseUrl, Throwable throwable) {
        if (throwable instanceof HttpClientErrorException.NotFound) {
            log.info("🔍 {} Não encontrado em {} (404)", prefix, baseUrl);
        } else if (throwable instanceof TimeoutException) {
            log.warn("⏱️ {} Timeout [{}] para {}", prefix, cbName, baseUrl);
        } else {
            log.warn("⚠️ {} Fallback [{}] para {}: {}", prefix, cbName, baseUrl, throwable.getMessage());
        }
    }

    private <T> List<T> fetchListFromAll(String endpointSuffix, Class<T> itemType) {
        List<CompletableFuture<List<T>>> futures = serviceUrlMap.values().stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(() ->
                                executeCircuitBreakerListRequest(
                                        "genericList", baseUrl,
                                        "http://" + baseUrl + "/radares/" + endpointSuffix,
                                        itemType),
                        executorService))
                .toList();

        return futures.stream()
                .map(f -> {
                    try { return f.get(5, TimeUnit.MINUTES); }
                    catch (Exception e) { return Collections.<T>emptyList(); }
                })
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    // ─── Coleta de futuros ──────────────────────────────────────────────────────

    private List<RadarPageDTO> collectFutures(List<CompletableFuture<RadarPageDTO>> futures) {
        return futures.stream()
                .map(f -> {
                    try {
                        return f.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        // 🚨 AGORA O ERRO VAI GRITAR NO TERMINAL!
                        log.error("⏱️ [BFF] TIMEOUT ESTOUROU: Um dos microsserviços demorou mais de {} segundos para responder e foi cortado!", REQUEST_TIMEOUT_SECONDS);
                        return null;
                    } catch (Exception e) {
                        log.error("❌ [BFF] Erro ao aguardar resposta do microsserviço: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private List<RadarPageDTO> collectFuturesBuscaPorLocal(List<CompletableFuture<RadarPageDTO>> futures) {
        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ─── Agregação ──────────────────────────────────────────────────────────────

    /**
     * Agrega páginas de múltiplos serviços.
     * Usa nullsLast para proteger contra data/hora nulos.
     */
    private RadarPageDTO aggregatePages(List<RadarPageDTO> pages, Pageable pageable) {
        List<RadarDTO> combined = pages.stream()
                .filter(p -> p != null && p.getContent() != null)
                .flatMap(p -> p.getContent().stream())
                .sorted(comparatorDataHoraDesc())
                .collect(Collectors.toList());

        long total = pages.stream()
                .filter(p -> p != null && p.getPage() != null)
                .mapToLong(p -> p.getPage().getTotalElements())
                .sum();

        int pageSize   = pageable.getPageSize();
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;

        List<RadarDTO> paged = combined.stream().limit(pageSize).collect(Collectors.toList());
        return new RadarPageDTO(paged, new PageMetadata(pageable.getPageNumber(), pageSize, total, totalPages));
    }

    private RadarPageDTO aggregatePagesBuscaPorLocal(List<RadarPageDTO> pages, Pageable pageable) {
        List<RadarDTO> all = pages.stream()
                .filter(p -> p != null && p.getContent() != null)
                .flatMap(p -> p.getContent().stream())
                .sorted(comparatorDataHoraDesc())
                .collect(Collectors.toList());

        long total     = pages.stream()
                .filter(p -> p != null && p.getPage() != null)
                .mapToLong(p -> p.getPage().getTotalElements()).sum();
        int pageSize   = pageable.getPageSize();
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;

        if (all.size() > pageSize) all = all.subList(0, pageSize);

        return new RadarPageDTO(all,
                new PageMetadata(pageable.getPageNumber(), pageSize, total, totalPages));
    }

    /** Comparador nulo-seguro para data DESC, hora DESC. */
    private Comparator<RadarDTO> comparatorDataHoraDesc() {
        return Comparator.comparing(RadarDTO::getData, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(RadarDTO::getHora, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    /**
     * 🔹 CORREÇÃO APLICADA: Agregação distribuída corrigida.
     * Como os microsserviços já pularam os itens (paginação na origem), o BFF não pode usar
     * fromIndex/toIndex novamente. Ele apenas junta as páginas correspondentes, ordena globalmente
     * e garante que o tamanho máximo devolvido é o pageSize da tela.
     */
    private RadarPageDTO aggregateGlobalPages(List<RadarPageDTO> pages, Pageable pageable) {
        List<RadarDTO> combined = pages.stream()
                .filter(p -> p != null && p.getContent() != null)
                .flatMap(p -> p.getContent().stream())
                .filter(Objects::nonNull)
                .sorted(comparatorDataHoraDesc())
                .limit(pageable.getPageSize()) // Corta o excesso de forma segura
                .collect(Collectors.toList());

        // 🚀 APLICA A DEDUPLICAÇÃO ANTES DE MOSTRAR NA TELA
        List<RadarDTO> deduplicados = removerDuplicados(combined);

        long totalElements = pages.stream()
                .filter(p -> p != null && p.getPage() != null)
                .mapToLong(p -> p.getPage().getTotalElements())
                .sum();

        int pageSize = pageable.getPageSize();
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalElements / pageSize) : 0;

        // Limita ao tamanho da página
        List<RadarDTO> paged = deduplicados.stream()
                .limit(pageSize)
                .collect(Collectors.toList());

        log.info("📄 [BFF] Paginação global: {} registros totais reportados, página {}/{}, retornando {} deduplicados",
                totalElements, pageable.getPageNumber(), totalPages, paged.size());

        return new RadarPageDTO(paged, new PageMetadata(pageable.getPageNumber(), pageSize, totalElements, totalPages));
    }

    // ─── Exportação paginada ─────────────────────────────────────────────────────

    private List<RadarDTO> fetchAllPagesFromMicroservice(
            String baseUrl, String placa, String praca, String rodovia, String km,
            String sentido, LocalDate data, LocalTime horaInicial, LocalTime horaFinal
    ) {
        List<RadarDTO> all = new ArrayList<>();
        int page = 0;
        boolean hasMore = true;
        boolean isByPlaca = (placa != null && !placa.isBlank());
        String endpoint   = isByPlaca ? "/radares/busca-placa" : "/radares/busca-local";

        while (hasMore) {
            // build().encode().toUri() — evita double encoding na exportação
            UriComponentsBuilder builder = UriComponentsBuilder
                    .fromUriString("http://" + baseUrl + endpoint)
                    .queryParam("page", page)
                    .queryParam("size", PAGE_SIZE_EXPORTACAO);

            if (isByPlaca) {
                builder.queryParam("placa", placa);
            } else {
                if (data       != null) builder.queryParam("data",       data);
                if (rodovia    != null) builder.queryParam("rodovia",    rodovia);
                if (km         != null) builder.queryParam("km",         km);
                if (sentido    != null) builder.queryParam("sentido",    sentido);
                if (horaInicial != null) builder.queryParam("horaInicial", horaInicial);
                if (horaFinal   != null) builder.queryParam("horaFinal",   horaFinal);
            }

            String url = builder.build().encode().toUri().toString();
            log.info("📡 [Exportação] Chamando: {}", url);

            try {
                ResponseEntity<JsonNode> response = loadBalancedRestTemplate.getForEntity(url, JsonNode.class);
                JsonNode root = response.getBody();

                if (root == null || root.isEmpty()) { hasMore = false; continue; }

                JsonNode contentNode = root.get("content");
                if (contentNode != null && contentNode.isArray() && contentNode.size() > 0) {
                    List<RadarDTO> batch = MAPPER.convertValue(contentNode, new TypeReference<>() {});

                    // 🚀 A BLINDAGEM PARA O EXCEL
                    String concName = baseUrl.replace("MICROSERVICO-RADARES-", "").toLowerCase();
                    for (RadarDTO dto : batch) {
                        if (dto.getConcessionaria() == null || dto.getConcessionaria().trim().isEmpty()) {
                            dto.setConcessionaria(concName);
                        }
                    }

                    all.addAll(batch);
                    log.info("✅ [Exportação] {} registros da página {} de {}", batch.size(), page, baseUrl);
                }

                int totalPages = root.has("page") && root.get("page").has("totalPages")
                        ? root.get("page").get("totalPages").asInt()
                        : root.path("totalPages").asInt(0);

                page++;
                hasMore = page < totalPages;

            } catch (Exception e) {
                log.error("❌ [Exportação] Erro ao chamar {}: {}", baseUrl, e.getMessage());
                hasMore = false;
            }
        }

        log.info("🏁 Exportação: {} páginas de {}, {} registros.", page, baseUrl, all.size());
        return all;
    }

    private List<RadarDTO> fetchAllGeoPagesFromMicroservice(
            String baseUrl, Double lat, Double lon, Double raio,
            LocalDate data, LocalTime inicio, LocalTime fim
    ) {
        List<RadarDTO> all = new ArrayList<>();
        int page = 0;
        boolean hasMore = true;

        while (hasMore) {
            String url = UriComponentsBuilder
                    .fromUriString("http://" + baseUrl + "/radares/geo-search")
                    .queryParam("latitude",  lat)
                    .queryParam("longitude", lon)
                    .queryParam("raio",      raio)
                    .queryParam("data",      data.toString())
                    .queryParam("horaInicio", inicio.toString())
                    .queryParam("horaFim",    fim.toString())
                    .queryParam("page", page)
                    .queryParam("size", PAGE_SIZE_EXPORTACAO)
                    .build().encode().toUri().toString();

            try {
                ResponseEntity<JsonNode> response = loadBalancedRestTemplate.getForEntity(url, JsonNode.class);
                RadarPageDTO p = parseJsonNodeToPage(response.getBody(), baseUrl);

                if (p.getContent() != null && !p.getContent().isEmpty()) {
                    all.addAll(p.getContent());
                    page++;
                    hasMore = p.getPage() != null && page < p.getPage().getTotalPages();
                } else {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Erro GEO página {} de {}: {}", page, baseUrl, e.toString());
                hasMore = false;
            }
        }

        return all;
    }

    private RadarPageDTO fetchGeoPageFromMicroservice(
            String baseUrl, Double lat, Double lon, Double raio,
            LocalDate data, LocalTime inicio, LocalTime fim, Pageable pageable
    ) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("http://" + baseUrl + "/radares/geo-search")
                .queryParam("latitude",  lat)
                .queryParam("longitude", lon)
                .queryParam("raio",      raio)
                .queryParam("data",      data)
                .queryParam("page",      pageable.getPageNumber())
                .queryParam("size",      pageable.getPageSize());

        if (inicio != null) builder.queryParam("horaInicio", inicio);
        if (fim    != null) builder.queryParam("horaFim",    fim);

        String url = builder.build().encode().toUri().toString();
        log.info("📡 [BFF Geo] Chamando: {}", url);
        return executeCircuitBreakerRequest("geoSearch", baseUrl, url);
    }

    private List<RadarLocationDTO> fetchLocationsFromMicroservices(String baseUrl) {
        String url = "http://" + baseUrl + "/radares/all-locations";
        log.info("📡 [BFF] Chamando locations: {}", url);

        return circuitBreakerFactory.create("locationsService").run(
                () -> {
                    // 1. O RestTemplate mapeia a Lista diretamente de forma segura
                    // Requisito: A classe RadarLocationDTO deve ter @JsonIgnoreProperties(ignoreUnknown = true)
                    ResponseEntity<List<RadarLocationDTO>> response = loadBalancedRestTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            null,
                            new ParameterizedTypeReference<List<RadarLocationDTO>>() {}
                    );

                    List<RadarLocationDTO> results = response.getBody();
                    if (results == null) {
                        results = new ArrayList<>();
                    }

                    // 2. Blindagem de segurança: Garante a concessionária correta
                    String concName = baseUrl.replace("MICROSERVICO-RADARES-", "").toLowerCase();
                    for (RadarLocationDTO r : results) {
                        if (r.getConcessionaria() == null || r.getConcessionaria().isBlank()) {
                            r.setConcessionaria(concName);
                        }
                    }

                    // 3. Salva no cache de resiliência se a chamada foi um sucesso
                    if (!results.isEmpty()) {
                        locationsFallbackCache.put(baseUrl, results);
                    }

                    return results;
                },
                throwable -> {
                    // 4. Fallback seguro
                    List<RadarLocationDTO> cachedData = locationsFallbackCache.getOrDefault(baseUrl, Collections.emptyList());

                    // Corrigido os caracteres inválidos '??' para um emoji de alerta '⚠️' para manter os logs limpos no Linux
                    log.warn("⚠️ [Circuit Breaker FALLBACK] Falha ao processar {}: {}. Retornando {} registros salvos em cache.",
                            baseUrl, throwable.getMessage(), cachedData.size());

                    return cachedData;
                }
        );
    }

    // ─── Schedulers ─────────────────────────────────────────────────────────────

    @Scheduled(fixedRate = 60000)
    public void recalcularAtrasosPainel() {
        realtimeUpdateService.getLatestRadars().forEach((conc, radar) -> {
            if (radar != null && !"SEM_COMUNICACAO".equals(radar.getStatusOperacional())) {
                boolean estavaAtrasado = Boolean.TRUE.equals(radar.getAtrasado());
                radar.calcularAtraso(limiteAtrasoMinutos);
                if (Boolean.TRUE.equals(radar.getAtrasado()) != estavaAtrasado) {
                    messagingTemplate.convertAndSend("/topic/last-radar", radar);
                }
            }
        });
    }

    @Scheduled(fixedDelay = 15000)
    public void atualizarRondonNoPainel() {
        String baseUrl = serviceUrlMap.get("rondon");
        if (baseUrl == null) return;

        try {
            ResponseEntity<RadarDTO[]> resp = loadBalancedRestTemplate.getForEntity(
                    "http://" + baseUrl + "/radares/ultimos?limite=1", RadarDTO[].class);

            if (resp.getBody() != null && resp.getBody().length > 0) {
                RadarDTO recente = resp.getBody()[0];
                String id = String.valueOf(recente.getId());
                if (!id.equals(ultimoIdRondonEnviado)) {
                    ultimoIdRondonEnviado = id;
                    recente.setConcessionaria("Rondon");
                    recente.calcularAtraso(limiteAtrasoMinutos);
                    realtimeUpdateService.getLatestRadars().put("RONDON", recente);
                    log.info("⏰ [Scheduler] Nova passagem da Rondon (Placa: {} - Data: {} - Hora: {})", recente.getPlaca(), recente.getData(), recente.getHora());
                    messagingTemplate.convertAndSend("/topic/last-radar", recente);
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [Scheduler] Aguardando disponibilidade da Rondon...");
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void atualizarMonitoraSPNoPainel() {
        String baseUrl = serviceUrlMap.get("monitorasp");
        if (baseUrl == null) return;

        try {
            ResponseEntity<RadarDTO[]> resp = loadBalancedRestTemplate.getForEntity(
                    "http://" + baseUrl + "/radares/ultimos?limite=1", RadarDTO[].class);

            if (resp.getBody() != null && resp.getBody().length > 0) {
                RadarDTO recente = resp.getBody()[0];
                String id = String.valueOf(recente.getId());
                if (!id.equals(ultimoIdMonitoraSPEnviado)) {
                    ultimoIdMonitoraSPEnviado = id;
                    recente.setConcessionaria("MonitoraSP");
                    recente.calcularAtraso(limiteAtrasoMinutos);
                    realtimeUpdateService.getLatestRadars().put("MONITORASP", recente);
                    log.info("⏰ [Scheduler] Nova passagem do MonitoraSP (Placa: {} - Dia: {} - Hora: {})", recente.getPlaca(), recente.getData(), recente.getHora());
                    messagingTemplate.convertAndSend("/topic/last-radar", recente);
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [Scheduler] Aguardando disponibilidade do MonitoraSP...");
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void atualizarEntreviasNoPainel() {
        String baseUrl = serviceUrlMap.get("entrevias");
        if (baseUrl == null) return;

        try {
            ResponseEntity<RadarDTO[]> resp = loadBalancedRestTemplate.getForEntity(
                    "http://" + baseUrl + "/radares/ultimos?limite=1", RadarDTO[].class);

            if (resp.getBody() != null && resp.getBody().length > 0) {
                RadarDTO recente = resp.getBody()[0];
                String id = String.valueOf(recente.getId());

                // Adicione este campo 'ultimoIdEntreviasEnviado' no topo da classe como volatile String
                if (!id.equals(ultimoIdEntreviasEnviado)) {
                    ultimoIdEntreviasEnviado = id;
                    recente.setConcessionaria("Entrevias");
                    recente.calcularAtraso(limiteAtrasoMinutos);
                    realtimeUpdateService.getLatestRadars().put("ENTREVIAS", recente);
                    log.info("⭐ [Scheduler] Nova passagem da Entrevias (Placa: {} - Data: {} - Hora: {})",
                            recente.getPlaca(), recente.getData(), recente.getHora());
                    messagingTemplate.convertAndSend("/topic/last-radar", recente);
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [Scheduler] Aguardando disponibilidade da Entrevias...");
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void atualizarAppiaNoPainel() {
        String baseUrl = serviceUrlMap.get("appia");
        if (baseUrl == null) return;

        try {
            ResponseEntity<RadarDTO[]> resp = loadBalancedRestTemplate.getForEntity(
                    "http://" + baseUrl + "/radares/ultimos?limite=1", RadarDTO[].class);

            if (resp.getBody() != null && resp.getBody().length > 0) {
                RadarDTO recente = resp.getBody()[0];
                String id = String.valueOf(recente.getId());
                if (!id.equals(ultimoIdAppiaEnviado)) {
                    ultimoIdAppiaEnviado = id;
                    recente.setConcessionaria("Appia");
                    recente.calcularAtraso(limiteAtrasoMinutos);
                    realtimeUpdateService.getLatestRadars().put("APPIA", recente);
                    log.info("⏰ [Scheduler] Nova passagem do APPIA (Placa: {} - Dia: {} - Hora: {})", recente.getPlaca(), recente.getData(), recente.getHora());
                    messagingTemplate.convertAndSend("/topic/last-radar", recente);
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [Scheduler] Aguardando disponibilidade do APPIA...");
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void atualizarCartNoPainel() {
        String baseUrl = serviceUrlMap.get("cart");
        if (baseUrl == null) return;

        try {
            ResponseEntity<RadarDTO[]> resp = loadBalancedRestTemplate.getForEntity(
                    "http://" + baseUrl + "/radares/ultimos?limite=1", RadarDTO[].class
            );

            if (resp.getBody() != null && resp.getBody().length > 0) {
                RadarDTO recente = resp.getBody()[0];
                String id = String.valueOf(recente.getId());

                if (!id.equals(ultimoIdCartEnviado)) {
                    ultimoIdCartEnviado = id;
                    recente.setConcessionaria("Cart");
                    recente.calcularAtraso(limiteAtrasoMinutos);
                    realtimeUpdateService.getLatestRadars().put("CART", recente);
                    log.info("🦉 [Scheduler] Nova passagem da Cart (Placa: {} - Data: {} - Hora: {})",
                            recente.getPlaca(), recente.getData(), recente.getHora());
                    messagingTemplate.convertAndSend("/topic/last-radar", recente);
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [Scheduler] Aguardando disponibilidade da Cart...");
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void atualizarEixoNoPainel() {
        String baseUrl = serviceUrlMap.get("eixo");
        if (baseUrl == null) return;

        try {
            ResponseEntity<RadarDTO[]> resp = loadBalancedRestTemplate.getForEntity(
                    "http://" + baseUrl + "/radares/ultimos?limite=1", RadarDTO[].class
            );
            if (resp.getBody() != null && resp.getBody().length > 0) {
                RadarDTO recente = resp.getBody()[0];
                String id = String.valueOf(recente.getId());

                if (!id.equals(ultimoIdEixoEnviado)) {
                    ultimoIdEixoEnviado = id;
                    recente.setConcessionaria("Eixo");
                    recente.calcularAtraso(limiteAtrasoMinutos);
                    realtimeUpdateService.getLatestRadars().put("EIXO", recente);
                    log.info("🦉 [Scheduler] Nova passagem da Eixo (Placa: {} - Data: {} - Hora: {})",
                            recente.getPlaca(), recente.getData(), recente.getHora());
                    messagingTemplate.convertAndSend("/topic/last-radar", recente);
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [Scheduler] Aguardando disponibilidade da Eixo...");
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void atualizarPantanalNoPainel() {
        String baseUrl = serviceUrlMap.get("motiva");
        if (baseUrl == null) return;

        try {
            ResponseEntity<RadarDTO[]> resp = loadBalancedRestTemplate.getForEntity(
                    "http://" + baseUrl + "/radares/ultimos?limite=1", RadarDTO[].class
            );
            if (resp.getBody() != null && resp.getBody().length > 0) {
                RadarDTO recente = resp.getBody()[0];
                String id = String.valueOf(recente.getId());

                if (!id.equals(ultimoIdPantanalEnviado)) {
                    ultimoIdPantanalEnviado = id;
                    recente.setConcessionaria("Pantanal");
                    recente.calcularAtraso(limiteAtrasoMinutos);
                    realtimeUpdateService.getLatestRadars().put("PANTANAL", recente);
                    log.info("🦉 [Scheduler] Nova passagem da Pantanal (Placa: {} - Data: {} - Hora: {})",
                            recente.getPlaca(), recente.getData(), recente.getHora());
                    messagingTemplate.convertAndSend("/topic/last-radar", recente);
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [Scheduler] Aguardando disponibilidade da Motiva...");
        }
    }

    // ─── Utilitários ─────────────────────────────────────────────────────────────

    private RadarPageDTO paginaVazia(Pageable pageable) {
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        int size = pageable.isPaged() ? pageable.getPageSize() : 20;
        return new RadarPageDTO(Collections.emptyList(), new PageMetadata(page, size, 0, 0));
    }

    private String resolveBaseUrl(String concessionaria, String fallback) {
        String key = (concessionaria != null && !concessionaria.isBlank())
                ? concessionaria.toLowerCase()
                : fallback;
        return serviceUrlMap.get(key);
    }

    private String getMonitoramentoUrl(String path) {
        String base = monitoramentoUrl.endsWith("/")
                ? monitoramentoUrl.substring(0, monitoramentoUrl.length() - 1)
                : monitoramentoUrl;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * Agrega resultados de múltiplos serviços com ordenação global correta.
     * Todos os registros são combinados, ordenados por data+hora desc,
     * e então a página solicitada é extraída.
     */
    private RadarPageDTO aggregatePagesComOrdenacaoGlobal(
            List<RadarPageDTO> pages, Pageable pageable) {

        // 1. Combina TODOS os registros de todos os serviços
        List<RadarDTO> todosRegistros = pages.stream()
                .filter(p -> p != null && p.getContent() != null)
                .flatMap(p -> p.getContent().stream())
                .filter(Objects::nonNull)
                // 2. Ordena globalmente por data DESC, hora DESC
                .sorted(comparatorDataHoraDesc())
                .collect(Collectors.toList());

        // 3. Total real = soma dos totais reportados por cada serviço
        long totalElements = pages.stream()
                .filter(p -> p != null && p.getPage() != null)
                .mapToLong(p -> p.getPage().getTotalElements())
                .sum();

        // 4. Aplica a paginação solicitada sobre a lista global ordenada
        int pageNumber = pageable.getPageNumber();
        int pageSize   = pageable.getPageSize();

        int fromIndex = pageNumber * pageSize;
        int toIndex   = Math.min(fromIndex + pageSize, todosRegistros.size());

        // Protege contra page além do fim da lista
        List<RadarDTO> paginaAtual = (fromIndex >= todosRegistros.size())
                ? Collections.emptyList()
                : todosRegistros.subList(fromIndex, toIndex);

        int totalPages = pageSize > 0
                ? (int) Math.ceil((double) totalElements / pageSize)
                : 0;

        log.info("📄 [BFF] Paginação global: {} registros totais, página {}/{}, retornando {}",
                totalElements, pageNumber, totalPages, paginaAtual.size());

        return new RadarPageDTO(paginaAtual,
                new PageMetadata(pageNumber, pageSize, totalElements, totalPages));
    }

    public RadarPageDTO buscarPorLocalComDetran(
            List<String> concessionarias, LocalDate data, LocalTime horaInicial, LocalTime horaFinal,
            String rodovia, String praca, String km, String sentido, Pageable pageable) {

        // 1. Faz a busca super rápida nos microserviços de radares
        RadarPageDTO pagina = buscarPorLocal(concessionarias, data, horaInicial, horaFinal, rodovia, praca, km, sentido, pageable);

        // 2. Enriquece a página de forma SEGURA e CONTROLADA
        if (pagina.getContent() != null && !pagina.getContent().isEmpty()) {

            // Isola placas únicas para não consultar o mesmo carro duas vezes na mesma página
            Set<String> placasUnicas = pagina.getContent().stream()
                    .map(RadarDTO::getPlaca)
                    .filter(p -> p != null && !p.isBlank())
                    .collect(Collectors.toSet());

            ConcurrentHashMap<String, JsonNode> dadosDetranMap = new ConcurrentHashMap<>();

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (String placaUnica : placasUnicas) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            // 🚦 SEMÁFORO APLICADO AQUI TAMBÉM!
                            detranRateLimiter.acquire();

                            JsonNode dadosDetran = detranService.consultarVeiculo(placaUnica);
                            if (dadosDetran != null) {
                                dadosDetranMap.put(placaUnica, dadosDetran);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.error("Thread interrompida ao buscar Detran para placa: {}", placaUnica);
                        } finally {
                            detranRateLimiter.release();
                        }
                    }, executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }

            // 3. Costura os dados híbridos de volta nos DTOs da página
            for (RadarDTO radar : pagina.getContent()) {
                JsonNode dados = dadosDetranMap.get(radar.getPlaca());
                enriquecerRadarComDetran(radar, dados);
            }
        }

        return pagina;
    }

    /**
     * Enriquece um RadarDTO com os dados retornados pela API do Detran.
     * Suporta de forma transparente ambos os formatos de payloads:
     * - Payload 1: proprietario como objeto { "nome": "...", "numeroDocumento": "...", "tipoDocumento": "..." }
     * - Payload 2: proprietario como string "NOME...", "proprietarioNumeroDocumento": "...", "proprietarioTipoDocumento": "..."
     */
    public void enriquecerRadarComDetran(RadarDTO radar, JsonNode dados) {
        if (radar == null) return;

        if (dados != null && !dados.isEmpty()) {
            radar.setMarcaModelo(extrairCampoHibrido(dados, "marca", "descricao", "nome"));
            radar.setCor(extrairCampoHibrido(dados, "cor", "descricao", "nome"));
            radar.setMunicipio(extrairCampoHibrido(dados, "municipio", "nome", "descricao"));
            radar.setUf(dados.hasNonNull("uf") ? dados.get("uf").asText().trim() : "N/I");
            radar.setAnoModelo(dados.hasNonNull("anoModelo") ? dados.get("anoModelo").asText().trim() : "N/I");

            String nome = "N/I";
            String documento = "N/I";

            if (dados.hasNonNull("proprietario")) {
                JsonNode propNode = dados.get("proprietario");
                if (propNode.isObject()) {
                    // Payload 1: "proprietario": { "nome": "MANUEL ", "numeroDocumento": "0000000", ... }
                    if (propNode.hasNonNull("nome") && !propNode.get("nome").asText().isBlank()) {
                        nome = propNode.get("nome").asText().trim();
                    }
                    if (propNode.hasNonNull("numeroDocumento") && !propNode.get("numeroDocumento").asText().isBlank()) {
                        documento = propNode.get("numeroDocumento").asText().trim();
                    }
                } else if (propNode.isTextual() && !propNode.asText().isBlank()) {
                    // Payload 2: "proprietario": "MANUEL THEO "
                    nome = propNode.asText().trim();
                }
            }

            // Payload 2: documento na raiz "proprietarioNumeroDocumento"
            if ("N/I".equals(documento) && dados.hasNonNull("proprietarioNumeroDocumento") && !dados.get("proprietarioNumeroDocumento").asText().isBlank()) {
                documento = dados.get("proprietarioNumeroDocumento").asText().trim();
            }

            radar.setNomeProprietario(nome);
            radar.setCpfProprietario(documento);
        } else {
            radar.setMarcaModelo("N/I");
            radar.setCor("N/I");
            radar.setMunicipio("N/I");
            radar.setUf("N/I");
            radar.setAnoModelo("N/I");
            radar.setNomeProprietario("Não Encontrado");
            radar.setCpfProprietario("Não Encontrado");
        }
    }

    private String extrairCampoHibrido(JsonNode root, String campo, String... subCampos) {
        if (!root.hasNonNull(campo)) return "N/I";
        JsonNode node = root.get(campo);
        if (node.isObject()) {
            for (String subCampo : subCampos) {
                if (node.hasNonNull(subCampo) && !node.get(subCampo).asText().isBlank()) {
                    return node.get(subCampo).asText().trim();
                }
            }
            return "N/I";
        }
        return node.asText().trim();
    }
}