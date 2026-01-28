package com.coruja.services;

import com.coruja.dto.*;
import jakarta.annotation.PostConstruct;
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
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    private final Map<String, String> serviceUrlMap = new HashMap<>();
    private final CircuitBreakerFactory circuitBreakerFactory;
    private final ExecutorService executorService;

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
        this.executorService = Executors.newFixedThreadPool(10);
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
        //serviceUrlMap.put("eixo", "MICROSERVICO-RADARES-EIXO");
        //serviceUrlMap.put("entrevias", "MICROSERVICO-RADARES-ENTREVIAS");
        //serviceUrlMap.put("rondon", "MICROSERVICO-RADARES-RONDON");
        log.info("Mapa de serviços carregado: {}", serviceUrlMap);
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
        // Chama o novo endpoint /busca-placa
        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromUriString("http://" + baseUrl + "/radares/busca-placa")
                .queryParam("placa", placa)
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize())
                .queryParam("sort", "data,desc") // Força ordenação cronológica
                .queryParam("sort", "hora,desc");

        String urlFinal = uriBuilder.toUriString();
        log.info("📡 BFF Request [{}]: {}", baseUrl, urlFinal);

        // USANDO O RESTPAGE (Wrapper Genérico)
        // Precisamos usar ParameterizedTypeReference para listas/genéricos
        ParameterizedTypeReference<RestPage<RadarDTO>> responseType =
                new ParameterizedTypeReference<RestPage<RadarDTO>>() {};

        return executeCircuitBreakerRequest("buscaPlaca", baseUrl, urlFinal, responseType);
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
            String praca,
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
                                baseUrl, data, horaInicial, horaFinal, rodovia, praca, km, sentido, pageable
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
            String praca,
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
                    .queryParam("praca", praca)
                    .queryParam("km", km)
                    .queryParam("sentido", sentido)
                    .queryParam("page", pageable.getPageNumber())
                    .queryParam("size", pageable.getPageSize())
                    .toUriString();

            // 🔥 O PULO DO GATO: Chama o Circuit Breaker passando a URL montada
            return executeCircuitBreakerRequestBuscaPorLocal("radares-cb", baseUrl, urlCompleta);

        } catch (Exception e) {
            log.error("🔥 Erro ao preparar chamada para {}: {}", baseUrl, e.getMessage());
            return new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0));
        }
    }

    // ==================================================================================
    // 3. GESTÃO DE RODOVIAS E KMs (NOVO)
    // ==================================================================================

    @Cacheable(value = "lista-rodovias-bff")
    public List<RodoviaDTO> listarRodovias() {
        // Vamos buscar da 'cart' como fonte principal, ou agregar de todas
        // Por simplificação, pegamos do primeiro serviço disponível ou iteramos
        return fetchListFromAll("rodovias", RodoviaDTO.class);
    }

    @CacheEvict(value = "lista-rodovias-bff", allEntries = true)
    public RodoviaDTO salvarRodovia(RodoviaDTO dto) {
        // Envia para o serviço CART (assumindo que ele gerencia o domínio)
        String baseUrl = serviceUrlMap.get("cart");
        if (baseUrl == null) throw new IllegalStateException("Serviço CART não configurado para gestão de rodovias");

        String url = "http://" + baseUrl + "/radares/rodovias";
        return loadBalancedRestTemplate.postForObject(url, dto, RodoviaDTO.class);
    }

    @CacheEvict(value = "lista-rodovias-bff", allEntries = true)
    public void deletarRodovia(Long id) {
        String baseUrl = serviceUrlMap.get("cart");
        if (baseUrl != null) {
            loadBalancedRestTemplate.delete("http://" + baseUrl + "/radares/rodovias/" + id);
        }
    }

    // --- KMs ---

    public List<KmRodoviaDTO> listarKmsPorRodovia(Long rodoviaId) {
        String baseUrl = serviceUrlMap.get("cart");
        if (baseUrl == null) return Collections.emptyList();

        String url = "http://" + baseUrl + "/radares/rodovias/" + rodoviaId + "/kms";
        return executeCircuitBreakerListRequest("listarKms", baseUrl, url, KmRodoviaDTO.class);
    }

    public KmRodoviaDTO salvarKm(KmRodoviaDTO dto) {
        String baseUrl = serviceUrlMap.get("cart");
        if (baseUrl == null) throw new IllegalStateException("Serviço CART não disponível");

        return loadBalancedRestTemplate.postForObject("http://" + baseUrl + "/radares/kms", dto, KmRodoviaDTO.class);
    }

    public void deletarKm(Long id) {
        String baseUrl = serviceUrlMap.get("cart");
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
                // Faz a chamada esperando o RestPage
                ResponseEntity<RestPage<RadarDTO>> response = loadBalancedRestTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        null,
                        responseType
                );

                RestPage<RadarDTO> page = response.getBody();

                if (page == null || page.getContent().isEmpty()) {
                    return new RadarPageDTO(new ArrayList<>(), new PageMetadata(0, 0, 0, 0));
                }

                // Converte o RestPage (Spring) para o seu RadarPageDTO (BFF/Front)
                PageMetadata metadata = new PageMetadata(
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalElements(),
                        page.getTotalPages()
                );

                return new RadarPageDTO(page.getContent(), metadata);

            } catch (Exception e) {
                log.error("🔥 Erro ao chamar {}: {}", url, e.getMessage());
                throw e;
            }
        }, throwable -> {
            log.warn("⚠️ Fallback para {}: {}", baseUrl, throwable.getMessage());
            return new RadarPageDTO(new ArrayList<>(), new PageMetadata(0, 0, 0, 0));
        });
    }

    private RadarPageDTO executeCircuitBreakerRequestBuscaPorLocal(
            String cbName,
            String baseUrl,
            String url
    ) {
        CircuitBreaker cb = circuitBreakerFactory.create(cbName);

        return cb.run(() -> {
            try {
                // ✅ MUDANÇA: Agora esperamos RadarPageDTO direto
                ResponseEntity<RadarPageDTO> response = loadBalancedRestTemplate.getForEntity(
                        url,
                        RadarPageDTO.class
                );

                RadarPageDTO body = response.getBody();

                // Se vier nulo ou vazio, retornamos um objeto vazio seguro
                if (body == null) {
                    return new RadarPageDTO(new ArrayList<>(), new PageMetadata(0, 0, 0, 0));
                }

                return body;

            } catch (Exception e) {
                log.error("🔥 Erro ao chamar {}: {}", url, e.getMessage());
                throw e; // Lança para ativar o fallback do Circuit Breaker
            }
        }, throwable -> {
            log.warn("⚠️ Fallback para {}: {}", baseUrl, throwable.getMessage());
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
        // 1. Tenta pegar do cache em tempo real (dados chegando agora)
        List<RadarDTO> fromMemory = new ArrayList<>(realtimeUpdateService.getLatestRadars().values());

        if (!fromMemory.isEmpty()) {
            return fromMemory;
        }

        // 2. Se a memória estiver vazia, busca os últimos do histórico no Banco de Dados
        log.info("Cache de memória vazio. Buscando últimos registros no Banco de Dados...");
        return fetchUltimosFromDatabase();
    }

    private List<RadarDTO> fetchUltimosFromDatabase() {
        // Endpoint que criamos/sugerimos no MonitoramentoController
        String url = getMonitoramentoUrl("/api/monitoramento/ultimos");

        try {
            // Usa o 'currentRestTemplate' para garantir a conexão correta (IP ou Eureka)
            ResponseEntity<List<RadarDTO>> response = monitoramentoRestTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<RadarDTO>>() {}
            );

            List<RadarDTO> result = response.getBody();
            if (result != null) {
                log.info("Recuperados {} registros do histórico.", result.size());
                return result;
            }
        } catch (Exception e) {
            log.error("Falha ao buscar histórico de radares no Monitoramento: {}", e.getMessage());
        }

        return Collections.emptyList();
    }



    // =========================================================================
    // MÉTODOS PRIVADOS AUXILIARES
    // =========================================================================



    /**
     * Busca todas as páginas de um microserviço (para exportação).
     */
    private List<RadarDTO> fetchAllPagesFromMicroservice(
            String baseUrl,
            String placa,
            String praca,
            String rodovia,
            String km,
            String sentido,
            LocalDate data,
            LocalTime horaInicial,
            LocalTime horaFinal
    ) {
        List<RadarDTO> allRadars = new ArrayList<>();
        int pageNumber = 0;
        final int pageSize = 1000;
        boolean hasMorePages = true;

        while (hasMorePages) {
            // ✅ CORREÇÃO: Endpoint correto é /busca-local se tiver data, ou /busca-placa se tiver placa
            // Lógica simplificada: Se tem placa, usa busca-placa. Se tem data, busca-local.
            String endpoint = (placa != null && !placa.isBlank()) ? "/radares/busca-placa" : "/radares/busca-local";

            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromUriString("http://" + baseUrl + endpoint)
                    .queryParam("page", pageNumber)
                    .queryParam("size", pageSize);

            if (placa != null && !placa.isBlank()) uriBuilder.queryParam("placa", placa);
            else {
                // Filtros de Local
                if (data != null) uriBuilder.queryParam("data", data);
                if (rodovia != null) uriBuilder.queryParam("rodovia", rodovia);
                if (km != null) uriBuilder.queryParam("km", km);
                if (sentido != null) uriBuilder.queryParam("sentido", sentido);
                if (horaInicial != null) uriBuilder.queryParam("horaInicial", horaInicial);
                if (horaFinal != null) uriBuilder.queryParam("horaFinal", horaFinal);
            }

            try {
                // ✅ CORREÇÃO: Usando RestPage para exportação também
                ResponseEntity<RestPage<RadarDTO>> response = loadBalancedRestTemplate.exchange(
                        uriBuilder.toUriString(),
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<RestPage<RadarDTO>>() {}
                );

                RestPage<RadarDTO> page = response.getBody();
                if (page != null && !page.getContent().isEmpty()) {
                    allRadars.addAll(page.getContent());
                    pageNumber++;
                    hasMorePages = pageNumber < page.getTotalPages();
                } else {
                    hasMorePages = false;
                }
            } catch (Exception e) {
                log.error("Erro na exportação de {}: {}", baseUrl, e.getMessage());
                hasMorePages = false;
            }
        }

        log.info("Buscadas {} páginas de {} com {} registros", pageNumber, baseUrl, allRadars.size());
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

}
