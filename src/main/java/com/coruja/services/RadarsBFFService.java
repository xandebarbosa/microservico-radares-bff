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
import java.time.LocalDate;
import java.time.LocalTime;
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
    private static final long   REQUEST_TIMEOUT_SECONDS = 120;
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
    private final ConcurrentHashMap<String, String> serviceUrlMap = new ConcurrentHashMap<>();

    private volatile String ultimoIdRondonEnviado    = "";
    private volatile String ultimoIdMonitoraSPEnviado = "";

    @Value("${microservico.monitoramento.url:http://MICROSERVICO-MONITORAMENTO}")
    private String monitoramentoUrl;

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

        List<CompletableFuture<RadarPageDTO>> futures = urls.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchPlacaFromMicroservice(baseUrl, placa, pageable), executorService))
                .toList();

        return aggregateGlobalPages(collectFutures(futures), pageable);
    }

    private RadarPageDTO fetchPlacaFromMicroservice(String baseUrl, String placa, Pageable pageable) {
        try {
            URI uri = UriComponentsBuilder.fromUriString("http://" + baseUrl + "/radares/busca-placa")
                    .queryParam("placa", placa)
                    .queryParam("page", pageable.getPageNumber()) // 🔹 Corrigido para repassar a página solicitada
                    .queryParam("size", pageable.getPageSize())
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
                        executorService))
                .toList();

        return aggregateGlobalPages(collectFutures(futures), pageable);
    }

    private RadarPageDTO fetchLocalFromMicroservice(
            String baseUrl, LocalDate data,
            LocalTime horaInicial, LocalTime horaFinal,
            String rodovia, String praca, String km, String sentido, Pageable pageable
    ) {
        try {
            // Montamos a URL com placeholders do Spring (ex: {km}, {rodovia})
            StringBuilder urlBuilder = new StringBuilder("http://" + baseUrl + "/radares/busca-local");
            urlBuilder.append("?data={data}");
            urlBuilder.append("&horaInicial={horaInicial}");
            urlBuilder.append("&horaFinal={horaFinal}");
            urlBuilder.append("&rodovia={rodovia}");
            urlBuilder.append("&km={km}");
            urlBuilder.append("&sentido={sentido}");
            urlBuilder.append("&page={page}");
            urlBuilder.append("&size={size}");

            // Preenchemos os valores reais em um Map
            Map<String, Object> params = new HashMap<>();
            params.put("data", data.toString());
            params.put("horaInicial", horaInicial.toString());
            params.put("horaFinal", horaFinal.toString());
            params.put("rodovia", rodovia);
            params.put("km", km); // O Spring garantirá que o "498+600" chegue ileso
            params.put("sentido", sentido);
            params.put("page", pageable.getPageNumber());
            params.put("size", pageable.getPageSize());

            // Se a praça não for nula, enviamos EXATAMENTE como veio (mesmo se for "" ou " ")
            if (praca != null) {
                urlBuilder.append("&praca={praca}");
                params.put("praca", praca);
            }

            String urlStr = urlBuilder.toString();
            log.info("📡 [BFF Local] Chamando: {} com params: {}", urlStr, params);

            String cbName = "radaresLocal-" + baseUrl.toLowerCase().replace("microservico-radares-", "");

            return circuitBreakerFactory.create(cbName).run(() -> {
                // Ao passar o Map 'params', o RestTemplate cuida de todo o encoding automaticamente!
                ResponseEntity<JsonNode> response = loadBalancedRestTemplate.getForEntity(urlStr, JsonNode.class, params);
                RadarPageDTO result = parseJsonNodeToPage(response.getBody(), baseUrl);
                log.info("✅ [BFF Local] Sucesso: {} registros de {}", result.getContent().size(), baseUrl);
                return result;
            }, throwable -> {
                logThrowable("[BFF Local]", cbName, baseUrl, throwable);
                return paginaVazia(Pageable.unpaged());
            });

        } catch (Exception e) {
            log.error("🔥 Erro ao preparar chamada local para {}: {}", baseUrl, e.getMessage());
            return paginaVazia(pageable);
        }
    }

    // ==================================================================================
    // 3. RODOVIAS E KMs
    // ==================================================================================

    @Cacheable(
            value  = "lista-rodovias-bff",
            key    = "#concessionaria != null ? #concessionaria : 'all'",
            unless = "#result == null || #result.isEmpty()"
    )
    public List<RodoviaDTO> listarRodovias(String concessionaria) {
        log.info("🔍 BFF: Solicitando lista de rodovias aos microserviços...");

        if (concessionaria != null && !concessionaria.isEmpty()) {
            String baseUrl = serviceUrlMap.get(concessionaria.toLowerCase());
            if (baseUrl != null) {
                return executeCircuitBreakerListRequest(
                        "listRodovias", baseUrl,
                        "http://" + baseUrl + "/radares/rodovias",
                        RodoviaDTO.class);
            }
        }
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
        log.info("🔍 BFF: Solicitando lista de KMs por rodovia...");
        String baseUrl = resolveBaseUrl(concessionaria, "cart");

        if (baseUrl == null) {
            log.warn("⚠️ [BFF] Serviço não encontrado para a concessionária: {}", concessionaria);
            return Collections.emptyList();
        }

        log.info("📍 [BFF] Roteando busca de KMs da rodovia {} para: {}", rodoviaId, baseUrl);
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
        List<String> urls = new ArrayList<>(serviceUrlMap.values());
        if (urls.isEmpty()) return Collections.emptyList();

        log.info("Buscando localizações de radares em {} serviços...", urls.size());

        List<CompletableFuture<List<RadarLocationDTO>>> futures = urls.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchLocationsFromMicroservices(baseUrl), executorService))
                .toList();

        return futures.stream()
                .map(f -> {
                    try { return f.get(5, TimeUnit.MINUTES); }
                    catch (Exception e) {
                        log.error("Erro ao buscar localizações: {}", e.getMessage());
                        return Collections.<RadarLocationDTO>emptyList();
                    }
                })
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
                    try { return f.get(5, TimeUnit.MINUTES); }
                    catch (Exception e) {
                        log.error("Erro geo exportação: {}", e.toString());
                        return Collections.<RadarDTO>emptyList();
                    }
                })
                .flatMap(List::stream)
                .sorted(comparatorDataHoraDesc())
                .collect(Collectors.toList());

        log.info("Exportação GEO finalizada. Total: {}", all.size());
        return all;
    }

    // ==================================================================================
    // 5. EXPORTAÇÃO
    // ==================================================================================

    public List<RadarDTO> buscarTodosParaExportacao(
            List<String> concessionarias, String placa, String praca,
            String rodovia, String km, String sentido,
            LocalDate data, LocalTime horaInicial, LocalTime horaFinal
    ) {
        List<String> urls = CollectionUtils.isEmpty(concessionarias)
                ? new ArrayList<>(serviceUrlMap.values())
                : concessionarias.stream()
                .map(nome -> serviceUrlMap.get(nome.toLowerCase()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (urls.isEmpty()) return Collections.emptyList();

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

        log.info("Exportação finalizada. Total: {}", all.size());
        return all;
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

        log.info("📊 [BFF] Iniciando enriquecimento de {} registros para exportação...", listaRadares.size());

        // 2. Enriquece em paralelo usando Virtual Threads
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (RadarDTO radar : listaRadares) {
                if (radar.getPlaca() != null && !radar.getPlaca().isEmpty()) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        JsonNode dados = detranService.consultarVeiculo(radar.getPlaca());
                        if (dados != null) {
                            radar.setMarcaModelo(dados.hasNonNull("marca") && dados.get("marca").hasNonNull("descricao")
                                    ? dados.get("marca").get("descricao").asText() : "N/I");
                            radar.setCor(dados.hasNonNull("cor") && dados.get("cor").hasNonNull("descricao")
                                    ? dados.get("cor").get("descricao").asText() : "N/I");
                            radar.setMunicipio(dados.hasNonNull("municipio") && dados.get("municipio").hasNonNull("nome")
                                    ? dados.get("municipio").get("nome").asText() : "N/I");
                        }
                    }, executor));
                }
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        return listaRadares;
    }

    // ==================================================================================
    // 6. ÚLTIMOS PROCESSADOS
    // ==================================================================================

    public List<RadarDTO> getUltimosRadaresProcessados() {
        List<RadarDTO> ultimos = new ArrayList<>(realtimeUpdateService.getLatestRadars().values());
        List<RadarDTO> doBanco = fetchUltimosFromDatabase();
        ultimos.addAll(doBanco);

        if (ultimos.isEmpty()) {
            log.info("Cache de memória vazio. Nenhum registro encontrado.");
        }

        return ultimos.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(RadarDTO::getData,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RadarDTO::getHora,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .collect(Collectors.toList());
    }

    private List<RadarDTO> fetchUltimosFromDatabase() {
        List<RadarDTO> todos = new ArrayList<>();

        // 1. Monitoramento legado (Cart, Eixo, Entrevias)
        try {
            ResponseEntity<List<RadarDTO>> resp = monitoramentoRestTemplate.exchange(
                    getMonitoramentoUrl("/api/monitoramento/ultimos"),
                    HttpMethod.GET, null,
                    new ParameterizedTypeReference<>() {});
            if (resp.getBody() != null) {
                todos.addAll(resp.getBody());
                log.info("Recuperados {} registros do Monitoramento.", resp.getBody().size());
            }
        } catch (HttpServerErrorException e) {
            log.debug("Endpoint /api/monitoramento/ultimos indisponível ({}): ignorando.",
                    e.getStatusCode());
        } catch (Exception e) {
            log.debug("Falha ao buscar histórico no Monitoramento: {}", e.getMessage());
        }

        // 2. Rondon (Quarkus)
        String baseUrlRondon = serviceUrlMap.get("rondon");
        if (baseUrlRondon != null) {
            try {
                ResponseEntity<RadarDTO[]> resp = loadBalancedRestTemplate.getForEntity(
                        "http://" + baseUrlRondon + "/radares/ultimos?limite=10",
                        RadarDTO[].class);
                if (resp.getBody() != null) {
                    todos.addAll(Arrays.asList(resp.getBody()));
                    log.info("Recuperados {} registros da Rondon.", resp.getBody().length);
                }
            } catch (Exception e) {
                log.warn("⚠️ Falha ao buscar últimos da Rondon: {}", e.getMessage());
            }
        }

        // 3. MonitoraSP (Quarkus)
        String baseUrlMonitoraSP = serviceUrlMap.get("monitorasp");
        if (baseUrlMonitoraSP != null) {
            try {
                ResponseEntity<RadarDTO[]> resp = loadBalancedRestTemplate.getForEntity(
                        "http://" + baseUrlMonitoraSP + "/radares/ultimos?limite=10",
                        RadarDTO[].class);
                if (resp.getBody() != null) {
                    todos.addAll(Arrays.asList(resp.getBody()));
                    log.info("Recuperados {} registros do MonitoraSP.", resp.getBody().length);
                }
            } catch (Exception e) {
                log.warn("⚠️ Falha ao buscar últimos do MonitoraSP: {}", e.getMessage());
            }
        }

        return todos.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(RadarDTO::getData,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RadarDTO::getHora,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
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
                    } catch (Exception e) {
                        return null; // O aggregateGlobalPages filtra nulos
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

        long totalElements = pages.stream()
                .filter(p -> p != null && p.getPage() != null)
                .mapToLong(p -> p.getPage().getTotalElements())
                .sum();

        int pageSize = pageable.getPageSize();
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalElements / pageSize) : 0;

        log.info("📄 [BFF] Paginação global: {} registros totais, página {}/{}, retornando {}",
                totalElements, pageable.getPageNumber(), totalPages, combined.size());

        return new RadarPageDTO(combined, new PageMetadata(pageable.getPageNumber(), pageSize, totalElements, totalPages));
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
        log.info("BFF chamando locations: {}", url);

        return circuitBreakerFactory.create("locationsService").run(
                () -> {
                    ResponseEntity<List<RadarLocationDTO>> response = loadBalancedRestTemplate.exchange(
                            url, HttpMethod.GET, null,
                            new ParameterizedTypeReference<>() {});
                    return response.getBody() != null ? response.getBody() : Collections.emptyList();
                },
                throwable -> {
                    log.warn("Fallback locations {}: {}", baseUrl, throwable.getMessage());
                    return Collections.emptyList();
                }
        );
    }

    // ─── Schedulers ─────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60000)
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
                    log.info("⏰ [Scheduler] Nova passagem da Rondon (Placa: {} - Data: {})", recente.getPlaca(), recente.getData());
                    messagingTemplate.convertAndSend("/topic/last-radar", recente);
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [Scheduler] Aguardando disponibilidade da Rondon...");
        }
    }

    @Scheduled(fixedDelay = 60000)
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
                    log.info("⏰ [Scheduler] Nova passagem do MonitoraSP (Placa: {})", recente.getPlaca());
                    messagingTemplate.convertAndSend("/topic/last-radar", recente);
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [Scheduler] Aguardando disponibilidade do MonitoraSP...");
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

        // 1. Faz a busca normal nos microserviços de radares
        RadarPageDTO pagina = buscarPorLocal(concessionarias, data, horaInicial, horaFinal, rodovia, praca, km, sentido, pageable);

        // 2. Enriquece a página de resultados com dados do Detran paralelamente
        if (pagina.getContent() != null && !pagina.getContent().isEmpty()) {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (RadarDTO radar : pagina.getContent()) {
                    if (radar.getPlaca() != null && !radar.getPlaca().isEmpty()) {
                        futures.add(CompletableFuture.runAsync(() -> {
                            JsonNode dadosDetran = detranService.consultarVeiculo(radar.getPlaca());

                            if (dadosDetran != null) {
                                String marcaModelo = dadosDetran.hasNonNull("marca") && dadosDetran.get("marca").hasNonNull("descricao")
                                        ? dadosDetran.get("marca").get("descricao").asText() : "N/I";

                                String cor = dadosDetran.hasNonNull("cor") && dadosDetran.get("cor").hasNonNull("descricao")
                                        ? dadosDetran.get("cor").get("descricao").asText() : "N/I";

                                String municipio = dadosDetran.hasNonNull("municipio") && dadosDetran.get("municipio").hasNonNull("nome")
                                        ? dadosDetran.get("municipio").get("nome").asText() : "N/I";

                                radar.setMarcaModelo(marcaModelo);
                                radar.setCor(cor);
                                radar.setMunicipio(municipio);
                            } else {
                                radar.setMarcaModelo("Não Encontrado");
                                radar.setCor("Não Encontrado");
                                radar.setMunicipio("Não Encontrado");
                            }
                        }, executor));
                    }
                }
                // Aguarda todas as chamadas do Detran terminarem antes de devolver a página
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
        }

        return pagina;
    }
}