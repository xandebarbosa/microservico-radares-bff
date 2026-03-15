package com.coruja.services;

import com.coruja.dto.*;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RadarsBFFService {

    // RestTemplates injetados separadamente
    private final RestTemplate loadBalancedRestTemplate;
    private final RestTemplate directRestTemplate;

    // Variável que decidirá qual usar em tempo de execução
    private RestTemplate monitoramentoRestTemplate;

    private final RealtimeUpdateService realtimeUpdateService;
    //private final Map<String, String> serviceUrlMap = new HashMap<>();
    private final CircuitBreakerFactory circuitBreakerFactory;
    // ✅ Uso de Virtual Threads para escalabilidade massiva (Java 21+)
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    // ✅ Thread-safe para acesso simultâneo
    private final ConcurrentHashMap<String, String> serviceUrlMap = new ConcurrentHashMap<>();

    // URL do Monitoramento (pode ser via Eureka ou IP Direto)
    @Value("${microservico.monitoramento.url:http://MICROSERVICO-MONITORAMENTO}")
    private String monitoramentoUrl;

    // Constante para Timeout (unificado)
    private static final long REQUEST_TIMEOUT_SECONDS = 45;

    // ALTERE o construtor para receber o Builder
    public RadarsBFFService(
            RestTemplate loadBalancedRestTemplate, // Injetado pelo @Primary
            @Qualifier("directRestTemplate") RestTemplate directRestTemplate,
            RealtimeUpdateService realtimeUpdateService,
            CircuitBreakerFactory circuitBreakerFactory
    ) {
        this.loadBalancedRestTemplate = loadBalancedRestTemplate;
        this.directRestTemplate = directRestTemplate;
        this.realtimeUpdateService = realtimeUpdateService;
        this.circuitBreakerFactory = circuitBreakerFactory;
        // Thread pool para chamadas paralelas aos microserviços
        //this.executorService = Executors.newFixedThreadPool(10);
    }

    /**
     * Este método é executado uma vez após a construção do serviço
     * para inicializar nosso mapa de serviços.
     */
    @PostConstruct
    public void init() {
        log.info("Inicializando mapa de URLs dos serviços de radares...");

        // 1. Configura qual RestTemplate usar para o Monitoramento (Inteligente)
        if (monitoramentoUrl.contains("localhost") ||
                monitoramentoUrl.contains("host.docker.internal") ||
                monitoramentoUrl.matches(".*:\\d+.*")) {

            // Se tem cara de URL física (IP/Porta), usa o Direct
            this.monitoramentoRestTemplate = directRestTemplate;
            log.info("🔧 RadarsBFF: Usando conexão DIRETA para Monitoramento: {}", monitoramentoUrl);
        } else {
            // Se não, assume que é nome do Eureka
            this.monitoramentoRestTemplate = loadBalancedRestTemplate;
            log.info("☁️ RadarsBFF: Usando conexão EUREKA para Monitoramento: {}", monitoramentoUrl);
        }
        // Mapeie para os NOMES DE SERVIÇO (spring.application.name)
        // Por padrão, o Eureka registra os nomes em MAIÚSCULAS.
        serviceUrlMap.put("cart", "MICROSERVICO-RADARES-CART");
        serviceUrlMap.put("eixo", "MICROSERVICO-RADARES-EIXO");
        serviceUrlMap.put("entrevias", "MICROSERVICO-RADARES-ENTREVIAS");
        serviceUrlMap.put("rondon", "MICROSERVICO-RADARES-RONDON");
        serviceUrlMap.put("monitorasp", "MICROSERVICO-RADARES-MONITORASP");
        log.info("Mapa de serviços carregado: {}", serviceUrlMap);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Encerrando ExecutorService do BFF...");
        executorService.shutdown();
    }

    // ==================================================================================
    // 1. BUSCA POR PLACA (HISTÓRICO COMPLETO)
    // ==================================================================================
    public RadarPageDTO buscarPorPlaca(String placa, Pageable pageable) {
        // Busca em todos os serviços registrados, pois o histórico pode estar em qualquer um
        List<String> urlsParaChamar = new ArrayList<>(serviceUrlMap.values());

        if (urlsParaChamar.isEmpty()) {
            return new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0));
        }

        List<CompletableFuture<RadarPageDTO>> futures = urlsParaChamar.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> (RadarPageDTO) fetchPlacaFromMicroservice(baseUrl, placa, pageable),
                        executorService
                ))
                .toList();

        List<RadarPageDTO> pages = collectFutures(futures);
        return aggregatePages(pages, pageable);
    }

    private Object fetchPlacaFromMicroservice(String baseUrl, String placa, Pageable pageable) {
        try {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromUriString("http://" + baseUrl + "/radares/busca-placa")
                    .queryParam("placa", placa)
                    .queryParam("page", pageable.getPageNumber())
                    .queryParam("size", pageable.getPageSize())
                    .queryParam("sort", "data,desc")
                    .queryParam("sort", "hora,desc");

            String urlFinal = uriBuilder.toUriString();

            // Usa o método corrigido
            ParameterizedTypeReference<RestPage<RadarDTO>> responseType =
                    new ParameterizedTypeReference<RestPage<RadarDTO>>() {};

            return executeCircuitBreakerRequest("buscaPlaca", baseUrl, urlFinal, responseType);

        } catch (Exception e) {
            log.error("🔥 [BFF] Erro ao preparar chamada para {}: {}", baseUrl, e.getMessage());
            return new RadarPageDTO(new ArrayList<>(), new PageMetadata(0, 0, 0, 0));
        }
    }

    // ==================================================================================
    // 2. BUSCA POR LOCAL (OPERACIONAL / FILTROS)
    // ==================================================================================
    /**
     * ✅ 1. MÉTODO PRINCIPAL (Orquestrador)
     * Decide quais serviços chamar e executa em paralelo.
     */
    public RadarPageDTO buscarPorLocal(
            List<String> concessionarias,
            LocalDate data,
            LocalTime horaInicial,
            LocalTime horaFinal,
            String rodovia,
            String km,
            String sentido,
            Pageable pageable
    ) {
        // A. Define quais serviços chamar
        final List<String> urlsParaChamar;
        if (CollectionUtils.isEmpty(concessionarias)) {
            urlsParaChamar = new ArrayList<>(serviceUrlMap.values());
            log.info("🔍 Busca por local em TODOS os {} serviços.", urlsParaChamar.size());
        } else {
            urlsParaChamar = concessionarias.stream()
                    .map(nome -> serviceUrlMap.get(nome.toLowerCase()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            log.info("🔍 Busca por local direcionada para: {}", concessionarias);
        }

        if (urlsParaChamar.isEmpty()) {
            return new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0));
        }

        // B. Execução Paralela (Async)
        List<CompletableFuture<RadarPageDTO>> futures = urlsParaChamar.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchLocalFromMicroservice(
                                baseUrl, data, horaInicial, horaFinal, rodovia, km, sentido, pageable
                        ),
                        executorService
                ))
                .collect(Collectors.toList());

        // C. Coleta e Agrega os Resultados
        List<RadarPageDTO> pages = collectFuturesBuscaPorLocal(futures);
        return aggregatePagesBuscaPorLocal(pages, pageable);
    }

    private RadarPageDTO fetchLocalFromMicroservice(
            String baseUrl,
            LocalDate data,
            LocalTime horaInicial,
            LocalTime horaFinal,
            String rodovia,
            String km,
            String sentido,
            Pageable pageable
    ) {
        try {
            // ✅ CORREÇÃO: Garante que o serviceId tenha o prefixo http://
            //String baseUrl = serviceId.startsWith("http") ? serviceId : "http://" + serviceId;
            // Monta a URL com todos os filtros
            String urlCompleta = UriComponentsBuilder
                    .fromUriString("http://" + baseUrl + "/radares/busca-local")
                    .queryParam("data", data) // Data é obrigatória
                    .queryParam("horaInicial", horaInicial)
                    .queryParam("horaFinal", horaFinal)
                    .queryParam("rodovia", rodovia)
                    .queryParam("km", km)
                    .queryParam("sentido", sentido)
                    .queryParam("page", pageable.getPageNumber())
                    .queryParam("size", pageable.getPageSize())
                    .toUriString();

            // 🔥 O PULO DO GATO: Chama o Circuit Breaker passando a URL montada
            return executeCircuitBreakerRequestBuscaPorLocal("radaresLocal", baseUrl, urlCompleta);

        } catch (Exception e) {
            log.error("🔥 Erro ao preparar chamada para {}: {}", baseUrl, e.getMessage());
            return new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0));
        }
    }

    // ==================================================================================
    // 3. GESTÃO DE RODOVIAS E KMs (NOVO)
    // ==================================================================================

    @Cacheable(value = "lista-rodovias-bff", key = "#concessionaria", unless = "#result == null")
    public List<RodoviaDTO> listarRodovias(String concessionaria) {
        log.info("🔍 BFF: Solicitando lista de rodovias aos microserviços...");

        if (concessionaria != null && !concessionaria.isEmpty()) {
            // Busca apenas no microserviço solicitado (ex: 'eixo' ou 'cart')
            String baseUrl = serviceUrlMap.get(concessionaria.toLowerCase());
            if (baseUrl != null) {
                String url = "http://" + baseUrl + "/radares/rodovias";
                return executeCircuitBreakerListRequest("listRodovias", baseUrl, url, RodoviaDTO.class);
            }
        }
        // Se não houver filtro, agrega todas (comportamento atual)
        return fetchListFromAll("rodovias", RodoviaDTO.class);
    }

    @CacheEvict(value = "lista-rodovias-bff", allEntries = true)
    public RodoviaDTO salvarRodovia(RodoviaDTO dto, String concessionaria) {
        String key = (concessionaria != null && !concessionaria.isBlank()) ? concessionaria.toLowerCase() : "cart";
        // Envia para o serviço CART (assumindo que ele gerencia o domínio)
        String baseUrl = serviceUrlMap.get(key);
        if (baseUrl == null) throw new IllegalStateException("Serviço " + key + " não configurado para gestão de rodovias");

        String url = "http://" + baseUrl + "/radares/rodovias";
        return loadBalancedRestTemplate.postForObject(url, dto, RodoviaDTO.class);
    }

    @CacheEvict(value = "lista-rodovias-bff", allEntries = true)
    public void deletarRodovia(Long id, String concessionaria) {
        String key = (concessionaria != null && !concessionaria.isBlank()) ? concessionaria.toLowerCase() : "cart";
        String baseUrl = serviceUrlMap.get(key);
        if (baseUrl != null) {
            loadBalancedRestTemplate.delete("http://" + baseUrl + "/radares/rodovias/" + id);
        }
    }

    // --- KMs ---

    public List<KmRodoviaDTO> listarKmsPorRodovia(Long rodoviaId, String concessionaria) {
        // Se a concessionária vier preenchida, usa ela (em minúsculo). Se não, tenta a 'cart' como fallback legado.
        String key = (concessionaria != null && !concessionaria.isBlank()) ? concessionaria.toLowerCase() : "cart";

        String baseUrl = serviceUrlMap.get(key);

        if (baseUrl == null) {
            log.warn("⚠️ [BFF] Serviço não encontrado para a concessionária: {}", concessionaria);
            return Collections.emptyList();
        }

        log.info("📍 [BFF] Roteando busca de KMs da rodovia {} para o serviço: {}", rodoviaId, baseUrl);
        String url = "http://" + baseUrl + "/radares/rodovias/" + rodoviaId + "/kms";
        return executeCircuitBreakerListRequest("listarKms", baseUrl, url, KmRodoviaDTO.class);
    }

    public KmRodoviaDTO salvarKm(KmRodoviaDTO dto, String concessionaria) {
        // Se a concessionária vier preenchida, usa ela (em minúsculo). Se não, tenta a 'cart' como fallback legado.
        String key = (concessionaria != null && !concessionaria.isBlank()) ? concessionaria.toLowerCase() : "cart";
        String baseUrl = serviceUrlMap.get(key);
        if (baseUrl == null) throw new IllegalStateException("Serviço " + key + " não disponível");

        return loadBalancedRestTemplate.postForObject("http://" + baseUrl + "/radares/kms", dto, KmRodoviaDTO.class);
    }

    public void deletarKm(Long id, String concessionaria) {
        String key = (concessionaria != null && !concessionaria.isBlank()) ? concessionaria.toLowerCase() : "cart";
        String baseUrl = serviceUrlMap.get(key);
        if (baseUrl != null) {
            loadBalancedRestTemplate.delete("http://" + baseUrl + "/radares/kms/" + id);
        }
    }

    // ==================================================================================
    // 4. BUSCA GEOESPACIAL E MAPA
    // ==================================================================================
    /**
     * Orquestra a busca geoespacial em todos os microserviços.
     */
    public RadarPageDTO buscarPorGeolocalizacao(
            Double latitude,
            Double longitude,
            Double raio,
            LocalDate data,
            LocalTime horaInicio,
            LocalTime horaFim,
            Pageable pageable
    ) {
        // Por padrão, a busca geoespacial varre todas as concessionárias cadastradas
        List<String> urlsParaChamar = new ArrayList<>(serviceUrlMap.values());
        if (urlsParaChamar.isEmpty()) {
            log.warn("Nenhum serviço registrado para busca geoespacial.");
            return new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0));
        }

        // Executa chamadas paralelas para o endpoint /geo-search dos microserviços
        List<CompletableFuture<RadarPageDTO>> futures = urlsParaChamar.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchGeoPageFromMicroservice(
                                baseUrl, latitude, longitude, raio, data, horaInicio, horaFim, pageable
                        ),
                        executorService
                ))
                .toList();

        // Aguarda e coleta os resultados
        List<RadarPageDTO> pages = futures.stream()
                .map(future -> {
                    try {
                        return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.error("❌ Erro na busca geoespacial: {}", e.toString());
                        return new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0));
                    }
                })
                .collect(Collectors.toList());

        // Usa o mesmo método de agregação que já existe para combinar os resultados
        return aggregatePages(pages, pageable);
    }

    /**
     * --- NOVO MÉTODO ---
     * Busca a lista completa de localizações (lat/long) de TODOS os microserviços.
     * Usado para plotar os pins no mapa do Frontend.
     */
    @Cacheable(value = "locais-radares-bff", unless = "#result == null || #result.isEmpty()")
    public List<RadarLocationDTO> getAllRadarLocations() {
        List<String> urlsParaChamar = new ArrayList<>(serviceUrlMap.values());

        if (urlsParaChamar.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("Buscando localizações de radares em {} serviços...", urlsParaChamar.size());

        // Chamada paralela aos microserviços
        List<CompletableFuture<List<RadarLocationDTO>>> futures = urlsParaChamar.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchLocationsFromMicroservices(baseUrl),
                        executorService
                ))
                .toList();

        // Agrega os resultados
        return futures.stream()
                .map(future -> {
                    try {
                        return future.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.error("Erro ao buscar localizações: {}", e.getMessage());
                        return Collections.<RadarLocationDTO>emptyList();
                    }
                })
                .flatMap(List::stream)
                .collect(Collectors.toList());

    }

    /**
     * --- NOVO MÉTODO ---
     * Busca TODOS os registros que correspondem a um filtro GEO, para exportação.
     */
    public List<RadarDTO> buscarTodosPorGeolocalizacaoParaExportacao(
            Double latitude,
            Double longitude,
            Double raio,
            LocalDate data,
            LocalTime horaInicio,
            LocalTime horaFim
    ) {
        // Para exportação geo, assume-se busca em todas as concessionárias
        List<String> urlsParaChamar = new ArrayList<>(serviceUrlMap.values());

        if (urlsParaChamar.isEmpty()) {
            return Collections.emptyList();
        }

        // Busca todas as páginas de todos os serviços em paralelo
        List<CompletableFuture<List<RadarDTO>>> futures = urlsParaChamar.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchAllGeoPagesFromMicroservice(
                                baseUrl, latitude, longitude, raio, data, horaInicio, horaFim
                        ),
                        executorService
                ))
                .collect(Collectors.toList());

        // Combina todos os resultados
        List<RadarDTO> allRadars = futures.stream()
                .map(future -> {
                    try {
                        return future.get(60, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.error("Erro ao buscar dados geo para exportação: {}", e.toString());
                        return Collections.<RadarDTO>emptyList();
                    }
                })
                .flatMap(List::stream)
                .collect(Collectors.toList());

        // Ordena por data e hora (mais recentes primeiro)
        allRadars.sort(Comparator
                .comparing(RadarDTO::getData, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(RadarDTO::getHora, Comparator.nullsLast(Comparator.reverseOrder())));

        log.info("Exportação GEO finalizada. Total de registros: {}", allRadars.size());
        return allRadars;
    }

    // ==================================================================================
    // UTILITÁRIOS E HELPERS
    // ==================================================================================

    /**
     * Executa uma requisição com Circuit Breaker genérico
     */
    private RadarPageDTO executeCircuitBreakerRequest(
            String cbName,
            String baseUrl,
            String url,
            ParameterizedTypeReference<RestPage<RadarDTO>> responseType
    ) {
        CircuitBreaker cb = circuitBreakerFactory.create(cbName);

        return cb.run(() -> {
            try {
                log.info("📡 [BFF] Chamando: {}", url);
                // Primeira tentativa: RestPage (formato Spring Data padrão)
                ResponseEntity<RestPage<RadarDTO>> response = loadBalancedRestTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        null,
                        responseType
                );

                RestPage<RadarDTO> page = response.getBody();

                if (page == null || page.getContent().isEmpty()) {
                    log.warn("⚠️ [BFF] Resposta vazia de {}", baseUrl);
                    return new RadarPageDTO(new ArrayList<>(), new PageMetadata(0, 0, 0, 0));
                }

                // Converte o RestPage (Spring) para o seu RadarPageDTO (BFF/Front)
                PageMetadata metadata = new PageMetadata(
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalElements(),
                        page.getTotalPages()
                );

                log.info("✅ [BFF] Sucesso: {} registros de {}", page.getContent().size(), baseUrl);
                return new RadarPageDTO(page.getContent(), metadata);

            } catch (Exception firstError) {
                log.warn("⚠️ [BFF] Erro no formato RestPage, tentando RadarPageDTO: {}", firstError.getMessage());

                try {
                    // Segunda tentativa: RadarPageDTO (formato customizado)
                    ResponseEntity<RadarPageDTO> response = loadBalancedRestTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            null,
                            RadarPageDTO.class
                    );

                    RadarPageDTO result = response.getBody();

                    if (result == null) {
                        log.warn("⚠️ [BFF] Resposta nula de {}", baseUrl);
                        return new RadarPageDTO(new ArrayList<>(), new PageMetadata(0, 0, 0, 0));
                    }

                    log.info("✅ [BFF] Sucesso (RadarPageDTO): {} registros de {}",
                            result.getContent() != null ? result.getContent().size() : 0, baseUrl);

                    return result;

                } catch (Exception secondError) {
                    log.error("❌ [BFF] Falha em ambos os formatos de {}: {}", baseUrl, secondError.getMessage());
                    throw firstError; // Lança o primeiro erro para o Circuit Breaker
                }
            }
        }, throwable -> {
            log.warn("⚠️ [BFF] Circuit Breaker ativo para {}: {}", baseUrl, throwable.getMessage());
            return new RadarPageDTO(new ArrayList<>(), new PageMetadata(0, 0, 0, 0));
        });
    }

    private RadarPageDTO executeCircuitBreakerRequestBuscaPorLocal(String cbName, String baseUrl, String url) {
        CircuitBreaker cb = circuitBreakerFactory.create(cbName);

        return cb.run(() -> {
            try {
                log.info("📡 [BFF Local] Chamando: {}", url);

                // Pede diretamente um JsonNode (Árvore JSON flexível do Jackson)
                ResponseEntity<com.fasterxml.jackson.databind.JsonNode> response =
                        loadBalancedRestTemplate.getForEntity(url, com.fasterxml.jackson.databind.JsonNode.class);

                com.fasterxml.jackson.databind.JsonNode root = response.getBody();

                if (root == null || root.isEmpty()) {
                    return new RadarPageDTO(new ArrayList<>(), new PageMetadata(0, 0, 0, 0));
                }

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
                mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

                // Pega o array "content" do JSON
                com.fasterxml.jackson.databind.JsonNode contentNode = root.get("content");
                List<RadarDTO> content = new ArrayList<>();
                if (contentNode != null && contentNode.isArray()) {
                    content = mapper.convertValue(contentNode, new com.fasterxml.jackson.core.type.TypeReference<List<RadarDTO>>() {});
                }

                // Captura os metadados tolerando ambos os formatos (Spring e Quarkus)
                int number = 0, size = 20, totalPages = 0;
                long totalElements = 0;

                if (root.has("page") && root.get("page").isObject()) {
                    com.fasterxml.jackson.databind.JsonNode pageNode = root.get("page");
                    if(pageNode.has("number")) number = pageNode.get("number").asInt();
                    if(pageNode.has("size")) size = pageNode.get("size").asInt();
                    if(pageNode.has("totalPages")) totalPages = pageNode.get("totalPages").asInt();
                    if(pageNode.has("totalElements")) totalElements = pageNode.get("totalElements").asLong();
                } else {
                    if(root.has("number")) number = root.get("number").asInt();
                    if(root.has("size")) size = root.get("size").asInt();
                    if(root.has("totalPages")) totalPages = root.get("totalPages").asInt();
                    if(root.has("totalElements")) totalElements = root.get("totalElements").asLong();
                }

                log.info("✅ [BFF Local] Sucesso: {} registros de {}", content.size(), baseUrl);
                return new RadarPageDTO(content, new PageMetadata(number, size, totalElements, totalPages));

            } catch (Exception e) {
                log.error("❌ [BFF Local] Erro ao chamar {}: {}", url, e.getMessage());
                throw new RuntimeException(e);
            }
        }, throwable -> {
            log.warn("⚠️ [BFF Local] Fallback para {}: {}", baseUrl, throwable.getMessage());
            return new RadarPageDTO(new ArrayList<>(), new PageMetadata(0, 0, 0, 0));
        });
    }

    /**
     * Helper para buscar listas de todos os microsserviços e agregar
     */
    private <T> List<T> fetchListFromAll(String endpointSuffix, Class<T> itemType) {
        List<String> urls = new ArrayList<>(serviceUrlMap.values());
        if (urls.isEmpty()) return Collections.emptyList();

        List<CompletableFuture<List<T>>> futures = urls.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(() -> {
                    String url = "http://" + baseUrl + "/radares/" + endpointSuffix;
                    return executeCircuitBreakerListRequest("genericList", baseUrl, url, itemType);
                }, executorService))
                .toList();

        return futures.stream()
                .map(f -> {
                    try { return f.get(5, TimeUnit.SECONDS); }
                    catch (Exception e) { return Collections.<T>emptyList(); }
                })
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * Circuit breaker específico para listas (devido ao Type Erasure do Java Generics)
     */
    private <T> List<T> executeCircuitBreakerListRequest(String cbName, String baseUrl, String url, Class<T> itemType) {
        CircuitBreaker cb = circuitBreakerFactory.create(cbName);
        return cb.run(() -> {
            ResponseEntity<List<T>> response = loadBalancedRestTemplate.exchange(
                    url, HttpMethod.GET, null, new ParameterizedTypeReference<List<T>>() {}
            );
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        }, t -> Collections.emptyList());
    }

    private List<RadarPageDTO> collectFutures(List<CompletableFuture<RadarPageDTO>> futures) {
        return futures.stream()
                .map(future -> {
                    try { return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS); }
                    catch (Exception e) { return new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0)); }
                })
                .collect(Collectors.toList());
    }

    private List<RadarPageDTO> collectFuturesBuscaPorLocal(List<CompletableFuture<RadarPageDTO>> futures) {
        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Agrega múltiplas páginas de diferentes serviços em uma única página.
     */
    private RadarPageDTO aggregatePages(List<RadarPageDTO> pages, Pageable pageable) {
        List<RadarDTO> combined = pages.stream()
                .filter(p -> p != null && p.getContent() != null)
                .flatMap(p -> p.getContent().stream())
                .sorted(Comparator.comparing(RadarDTO::getData, Comparator.reverseOrder())
                        .thenComparing(RadarDTO::getHora, Comparator.reverseOrder()))
                .collect(Collectors.toList());

        long totalElements = pages.stream()
                .filter(p -> p != null && p.getPage() != null)
                .mapToLong(p -> p.getPage().getTotalElements())
                .sum();

        List<RadarDTO> paged = combined.stream()
                .limit(pageable.getPageSize())
                .collect(Collectors.toList());

        int totalPages = pageable.getPageSize() > 0 ? (int) Math.ceil((double) totalElements / pageable.getPageSize()) : 0;
        return new RadarPageDTO(paged, new PageMetadata(pageable.getPageNumber(), pageable.getPageSize(), totalElements, totalPages));
    }

    /**
     * ✅ 4. AGREGADOR (Junta os resultados)
     * Soma os totais e junta as listas de múltiplos serviços.
     */
    private RadarPageDTO aggregatePagesBuscaPorLocal(List<RadarPageDTO> pages, Pageable pageable) {
        // Coleta conteúdo
        List<RadarDTO> allContent = pages.stream()
                .filter(p -> p != null && p.getContent() != null)
                .flatMap(p -> p.getContent().stream())
                // Ordenação em memória (ex: Data DESC, Hora DESC)
                .sorted(Comparator.comparing(RadarDTO::getData).reversed()
                        .thenComparing(RadarDTO::getHora).reversed())
                .collect(Collectors.toList());

        // Calcula totais
        long totalElements = pages.stream()
                .filter(p -> p != null && p.getPage() != null)
                .mapToLong(p -> p.getPage().getTotalElements())
                .sum();

        // Opcional: Aplicar limite de tamanho da página no agregado
        int pageSize = pageable.getPageSize();
        if (allContent.size() > pageSize) {
            allContent = allContent.subList(0, pageSize);
        }

        // Calcula total de páginas aproximado
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalElements / pageSize) : 0;

        // Cria metadados corrigidos (number, size, total, totalPages)
        PageMetadata meta = new PageMetadata(
                pageable.getPageNumber(),
                pageSize,
                totalElements,
                totalPages
        );

        return new RadarPageDTO(allContent, meta);
    }

    private String getMonitoramentoUrl(String path) {
        String base = monitoramentoUrl.endsWith("/") ? monitoramentoUrl.substring(0, monitoramentoUrl.length() - 1) : monitoramentoUrl;
        String endpoint = path.startsWith("/") ? path : "/" + path;
        return base + endpoint;
    }


    /**
     * Busca TODOS os registros que correspondem a um filtro, para exportação.
     */
    public List<RadarDTO> buscarTodosParaExportacao(
            List<String> concessionarias,
            String placa,
            String praca,
            String rodovia,
            String km,
            String sentido,
            LocalDate data,
            LocalTime horaInicial,
            LocalTime horaFinal
    ) {
        final List<String> urlsParaChamar;
        if (CollectionUtils.isEmpty(concessionarias)) {
            urlsParaChamar = new ArrayList<>(serviceUrlMap.values());
        } else {
            urlsParaChamar = concessionarias.stream()
                    .map(nome -> serviceUrlMap.get(nome.toLowerCase()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        if (urlsParaChamar.isEmpty()) return Collections.emptyList();

        List<CompletableFuture<List<RadarDTO>>> futures = urlsParaChamar.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchAllPagesFromMicroservice(baseUrl, placa, praca, rodovia, km, sentido, data, horaInicial, horaFinal),
                        executorService
                ))
                .collect(Collectors.toList());

        List<RadarDTO> allRadars = futures.stream()
                .map(f -> {
                    try { return f.get(60, TimeUnit.SECONDS); }
                    catch (Exception e) { return Collections.<RadarDTO>emptyList(); }
                })
                .flatMap(List::stream)
                .sorted(Comparator.comparing(RadarDTO::getData, Comparator.reverseOrder()).thenComparing(RadarDTO::getHora, Comparator.reverseOrder()))
                .collect(Collectors.toList());

        log.info("Exportação finalizada. Total de registros: {}", allRadars.size());
        return allRadars;
    }

    /**
     * Retorna os últimos radares processados.
     * Tenta buscar do Cache em Memória (RabbitMQ) primeiro.
     * Se estiver vazio (ex: após restart), busca do Banco de Dados via API Monitoramento.
     */
    public List<RadarDTO> getUltimosRadaresProcessados() {
        // 1. Pega os dados que chegaram agora em tempo real (RabbitMQ)
        List<RadarDTO> ultimos = new ArrayList<>(realtimeUpdateService.getLatestRadars().values());

        // 2. SEMPRE busca do banco/microsserviços para trazer dados consolidados (Rondon)
        List<RadarDTO> doBanco = fetchUltimosFromDatabase();

        // 3. Junta as duas listas
        ultimos.addAll(doBanco);

        // 2. Se a memória estiver vazia, busca os últimos do histórico no Banco de Dados
        log.info("Cache de memória vazio. Buscando últimos registros no Banco de Dados...");
        // 4. Remove nulos, ordena pelos mais recentes absolutos e corta os 10 primeiros
        return ultimos.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RadarDTO::getData, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RadarDTO::getHora, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .collect(Collectors.toList());
    }

    private List<RadarDTO> fetchUltimosFromDatabase() {
        List<RadarDTO> todosUltimos = new ArrayList<>();

        // 1. Busca do Monitoramento (Legado: Cart, Eixo, Entrevias)
        String urlMonitoramento = getMonitoramentoUrl("/api/monitoramento/ultimos");
        try {
            ResponseEntity<List<RadarDTO>> responseMon = monitoramentoRestTemplate.exchange(
                    urlMonitoramento,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<RadarDTO>>() {}
            );
            if (responseMon.getBody() != null) {
                todosUltimos.addAll(responseMon.getBody());
                log.info("Recuperados {} registros do histórico (Monitoramento).", responseMon.getBody().size());
            }
        } catch (Exception e) {
            log.error("Falha ao buscar histórico no Monitoramento: {}", e.getMessage());
        }

        // 2. Busca direto do Rondon (Novo microsserviço)
        String baseUrlRondon = serviceUrlMap.get("rondon");
        if (baseUrlRondon != null) {
            try {
                String urlRondon = "http://" + baseUrlRondon + "/radares/ultimos?limite=10";
                // Dica: O uso de Array (RadarDTO[]) evita erros de casting com o Jackson
                ResponseEntity<RadarDTO[]> responseRondon = loadBalancedRestTemplate.getForEntity(urlRondon, RadarDTO[].class);
                if (responseRondon.getBody() != null) {
                    todosUltimos.addAll(Arrays.asList(responseRondon.getBody()));
                    log.info("Recuperados {} registros direto da Rondon.", responseRondon.getBody().length);
                }
            } catch (Exception e) {
                log.warn("⚠️ Falha ao buscar últimos registros direto da Rondon: {}", e.getMessage());
            }
        }

        // 3. Mescla tudo, ordena do mais recente para o mais antigo e corta os 10 primeiros
        return todosUltimos.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RadarDTO::getData, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(RadarDTO::getHora, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .collect(Collectors.toList());
    }



    // =========================================================================
    // MÉTODOS PRIVADOS AUXILIARES
    // =========================================================================

    /**
     * Busca todas as páginas de um microserviço (para exportação).
     * Inteligente: Suporta tanto o formato RestPage quanto RadarPageDTO.
     */
    private List<RadarDTO> fetchAllPagesFromMicroservice(
            String baseUrl, String placa, String praca, String rodovia, String km,
            String sentido, LocalDate data, LocalTime horaInicial, LocalTime horaFinal
    ) {
        List<RadarDTO> allRadars = new ArrayList<>();
        int pageNumber = 0;
        final int pageSize = 1000;
        boolean hasMorePages = true;

        // Leitor universal de JSON para tolerar Quarkus e Spring
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        while (hasMorePages) {
            boolean isBuscaPorPlaca = (placa != null && !placa.isBlank());
            String endpoint = isBuscaPorPlaca ? "/radares/busca-placa" : "/radares/busca-local";

            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromUriString("http://" + baseUrl + endpoint)
                    .queryParam("page", pageNumber)
                    .queryParam("size", pageSize);

            if (isBuscaPorPlaca) {
                uriBuilder.queryParam("placa", placa);
            } else {
                if (data != null) uriBuilder.queryParam("data", data);
                if (rodovia != null) uriBuilder.queryParam("rodovia", rodovia);
                if (km != null) uriBuilder.queryParam("km", km);
                if (sentido != null) uriBuilder.queryParam("sentido", sentido);
                if (horaInicial != null) uriBuilder.queryParam("horaInicial", horaInicial);
                if (horaFinal != null) uriBuilder.queryParam("horaFinal", horaFinal);
            }

            // O pulo do gato: Voltar a utilizar String em vez de URI para o LoadBalancer funcionar
            String url = uriBuilder.toUriString();
            log.info("📡 [Exportação] Chamando: {}", url);

            try {
                // Utilizando getForEntity com String garante o mesmo comportamento de sucesso da Busca por Local
                ResponseEntity<com.fasterxml.jackson.databind.JsonNode> response =
                        loadBalancedRestTemplate.getForEntity(url, com.fasterxml.jackson.databind.JsonNode.class);

                com.fasterxml.jackson.databind.JsonNode root = response.getBody();

                if (root == null || root.isEmpty()) {
                    log.warn("⚠️ [Exportação] Retorno vazio ou nulo de {}", baseUrl);
                    hasMorePages = false;
                    continue;
                }

                com.fasterxml.jackson.databind.JsonNode contentNode = root.get("content");

                if (contentNode != null && contentNode.isArray()) {
                    if (contentNode.size() > 0) {
                        List<RadarDTO> currentContent = mapper.convertValue(
                                contentNode,
                                new com.fasterxml.jackson.core.type.TypeReference<List<RadarDTO>>() {}
                        );
                        allRadars.addAll(currentContent);
                        log.info("✅ [Exportação] Recebidos {} registros da página {} de {}", currentContent.size(), pageNumber, baseUrl);
                    } else {
                        log.info("⚠️ [Exportação] Página {} de {} sem registros.", pageNumber, baseUrl);
                    }

                    int totalPages = 0;
                    if (root.has("page") && root.get("page").has("totalPages")) {
                        totalPages = root.get("page").get("totalPages").asInt(); // Formato Quarkus
                    } else if (root.has("totalPages")) {
                        totalPages = root.get("totalPages").asInt(); // Formato Spring
                    }

                    pageNumber++;
                    hasMorePages = pageNumber < totalPages;
                } else {
                    log.warn("⚠️ [Exportação] Chave 'content' não encontrada no JSON de {}", baseUrl);
                    hasMorePages = false;
                }
            } catch (Exception e) {
                log.error("❌ [Exportação] Erro ao chamar {}: {}", baseUrl, e.getMessage());
                hasMorePages = false;
            }
        }

        log.info("🏁 Exportação concluída: Buscadas {} páginas de {} com um total de {} registros", pageNumber, baseUrl, allRadars.size());
        return allRadars;
    }

    /**
     * --- NOVO MÉTODO AUXILIAR ---
     * Itera sobre todas as páginas do endpoint /geo-search de um microserviço.
     */
    private List<RadarDTO> fetchAllGeoPagesFromMicroservice(
            String baseUrl,
            Double latitude,
            Double longitude,
            Double raio,
            LocalDate data,
            LocalTime horaInicio,
            LocalTime horaFim
    ) {
        List<RadarDTO> allRadars = new ArrayList<>();
        int pageNumber = 0;
        final int pageSize = 1000;
        boolean hasMorePages = true;

        while (hasMorePages) {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromUriString("http://" + baseUrl + "/radares/geo-search")
                    .queryParam("latitude", latitude)
                    .queryParam("longitude", longitude)
                    .queryParam("raio", raio)
                    .queryParam("data", data.toString())
                    .queryParam("horaInicio", horaInicio.toString())
                    .queryParam("horaFim", horaFim.toString())
                    .queryParam("page", pageNumber)
                    .queryParam("size", pageSize)
                    .queryParam("sort", "data,desc")
                    .queryParam("sort", "hora,desc");

            try {
                // Tenta buscar a página
                ResponseEntity<RadarPageDTO> response = loadBalancedRestTemplate.getForEntity(
                        uriBuilder.toUriString(),
                        RadarPageDTO.class
                );

                RadarPageDTO page = response.getBody();
                if (page != null && page.getContent() != null && !page.getContent().isEmpty()) {
                    allRadars.addAll(page.getContent());
                    pageNumber++;

                    // Verifica se há mais páginas
                    if (page.getPage() != null) {
                        hasMorePages = pageNumber < page.getPage().getTotalPages();
                    } else {
                        hasMorePages = false;
                    }
                } else {
                    hasMorePages = false;
                }
            } catch (Exception e) {
                log.error("Erro ao buscar página GEO {} de {}: {}", pageNumber, baseUrl, e.toString());
                hasMorePages = false;
            }
        }

        return allRadars;
    }


    /**
     * Faz a chamada REST para o endpoint /geo-search do microserviço específico.
     */
    private RadarPageDTO fetchGeoPageFromMicroservice(
            String baseUrl,
            Double latitude,
            Double longitude,
            Double raio,
            LocalDate data,
            LocalTime horaInicio,
            LocalTime horaFim,
            Pageable pageable
    ) {
        // Constrói a URL para o endpoint que criamos no microservico-radares-cart
        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromUriString("http://" + baseUrl + "/radares/geo-search")
                .queryParam("latitude", latitude)
                .queryParam("longitude", longitude)
                .queryParam("raio", raio)
                .queryParam("data", data)
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize());

        if (horaInicio != null) uriBuilder.queryParam("horaInicio", horaInicio);
        if (horaFim != null) uriBuilder.queryParam("horaFim", horaFim);

        // ✅ CORREÇÃO: Usando RestPage
        ParameterizedTypeReference<RestPage<RadarDTO>> responseType =
                new ParameterizedTypeReference<RestPage<RadarDTO>>() {};

        return executeCircuitBreakerRequest("geoSearch", baseUrl, uriBuilder.toUriString(), responseType);
    }

    private List<RadarLocationDTO> fetchLocationsFromMicroservices(String baseUrl) {
        String url = "http://" + baseUrl + "/radares/all-locations";
        log.info("BFF chamando locations: {}", url);

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("locationsService");

        return circuitBreaker.run(
                () -> {
                    try {
                        ResponseEntity<List<RadarLocationDTO>> response = loadBalancedRestTemplate.exchange(
                                url,
                                HttpMethod.GET,
                                null,
                                new ParameterizedTypeReference<List<RadarLocationDTO>>() {}
                        );
                        return response.getBody() != null ? response.getBody() : Collections.emptyList();
                    } catch (Exception e) {
                        log.error("Falha ao buscar locations de {}: {}", baseUrl, e.getMessage());
                        throw e;
                    }
                },
                throwable -> Collections.emptyList() //Fallback retorna lista vazia
        );
    }

    // ═══════════════════════════════════════════════════════════════
    //  ATUALIZAÇÃO AUTOMÁTICA DA RONDON (POLLING)
    // ═══════════════════════════════════════════════════════════════

    // Injeta o disparador de mensagens WebSocket nativo do Spring
    private SimpMessagingTemplate messagingTemplate;

    // Guarda o ID do último radar que vimos para não enviar coisas repetidas para a tela
    private String ultimoIdRondonEnviado = "";

    /**
     * Executa automaticamente a cada 1 minutos (60000 milissegundos).
     * Espia o MongoDB da Rondon. Se tiver novidade, empurra para o FrontEnd.
     */
    @Scheduled(fixedDelay = 60000)
    public void atualizarRondonNoPainel() {
        String baseUrlRondon = serviceUrlMap.get("rondon");
        if (baseUrlRondon == null) return; // Só executa se a Rondon estiver no mapa

        try {
            // Busca apenas o radar mais recente de todos (limite=1) para poupar memória e rede
            String url = "http://" + baseUrlRondon + "/radares/ultimos?limite=1";
            ResponseEntity<RadarDTO[]> response = loadBalancedRestTemplate.getForEntity(url, RadarDTO[].class);

            if (response.getBody() != null && response.getBody().length > 0) {
                RadarDTO maisRecente = response.getBody()[0];
                String idAtual = String.valueOf(maisRecente.getId());

                // Se o ID for diferente do último que enviamos, significa que há uma passagem nova!
                if (!idAtual.equals(ultimoIdRondonEnviado)) {
                    ultimoIdRondonEnviado = idAtual;

                    log.info("⏰ [Scheduler] Nova passagem da Rondon detectada (Placa: {}). Atualizando FrontEnd...", maisRecente.getPlaca());

                    // Dispara a mensagem para o tópico WebSocket que o seu React já está a escutar
                    messagingTemplate.convertAndSend("/topic/last-radar", maisRecente);
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [Scheduler] Aguardando disponibilidade da Rondon para verificação...");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  ATUALIZAÇÃO AUTOMÁTICA DO MONITORASP (POLLING)
    // ═══════════════════════════════════════════════════════════════
    private String ultimoIdMonitoraSPEnviado = "";
    /**
     * Executa automaticamente a cada 1 minutos (60000 milissegundos).
     * Espia o MongoDB da Rondon. Se tiver novidade, empurra para o FrontEnd.
     */
    @Scheduled(fixedDelay = 60000)
    public void atualizarMonitoraSPNoPainel() {
        String baseUrlMonitoraSP = serviceUrlMap.get("monitorasp");
        if (baseUrlMonitoraSP == null) return; // Só executa se a MonitoraSP estiver no mapa

        try {
            // Busca apenas o radar mais recente de todos (limite=1) para poupar memória e rede
            String url = "http://" + baseUrlMonitoraSP + "/radares/ultimos?limite=1";
            ResponseEntity<RadarDTO[]> response = loadBalancedRestTemplate.getForEntity(url, RadarDTO[].class);

            if (response.getBody() != null && response.getBody().length > 0) {
                RadarDTO maisRecente = response.getBody()[0];
                String idAtual = String.valueOf(maisRecente.getId());

                // Se o ID for diferente do último que enviamos, significa que há uma passagem nova!
                if (!idAtual.equals(ultimoIdMonitoraSPEnviado)) {
                    ultimoIdMonitoraSPEnviado = idAtual;

                    log.info("⏰ [Scheduler] Nova passagem da MonitoraSP detectada (Placa: {}). Atualizando FrontEnd...", maisRecente.getPlaca());

                    // Dispara a mensagem para o tópico WebSocket que o seu React já está a escutar
                    messagingTemplate.convertAndSend("/topic/last-radar", maisRecente);
                }
            }
        } catch (Exception e) {
            log.debug("⚠️ [Scheduler] Aguardando disponibilidade do MonitoraSP para verificação...");
        }
    }
}
