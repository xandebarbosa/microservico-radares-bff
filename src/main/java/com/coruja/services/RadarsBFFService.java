package com.coruja.services;

import com.coruja.dto.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
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
import reactor.core.publisher.Mono;

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

    private final RestTemplate restTemplate;
    private final RealtimeUpdateService realtimeUpdateService;
    private final Map<String, String> serviceUrlMap = new HashMap<>();
    private final CircuitBreakerFactory circuitBreakerFactory;
    private final ExecutorService executorService;

    // Constante para Timeout (unificado)
    private static final long REQUEST_TIMEOUT_SECONDS = 45;

    // ALTERE o construtor para receber o Builder
    public RadarsBFFService(
            RestTemplate restTemplate,
            RealtimeUpdateService realtimeUpdateService,
            CircuitBreakerFactory circuitBreakerFactory
    ) {
        this.restTemplate = restTemplate;
        this.realtimeUpdateService = realtimeUpdateService;
        this.circuitBreakerFactory = circuitBreakerFactory;
        // Thread pool para chamadas paralelas aos microserviços
        this.executorService = Executors.newFixedThreadPool(10);
    }

    /**
     * NOVO: Este método é executado uma vez após a construção do serviço
     * para inicializar nosso mapa de serviços.
     */
    @PostConstruct
    public void init() {
        log.info("Inicializando mapa de URLs dos serviços de radares...");
        // Mapeie para os NOMES DE SERVIÇO (spring.application.name)
        // Por padrão, o Eureka registra os nomes em MAIÚSCULAS.
        serviceUrlMap.put("cart", "MICROSERVICO-RADARES-CART");
        //serviceUrlMap.put("eixo", "MICROSERVICO-RADARES-EIXO");
        //serviceUrlMap.put("entrevias", "MICROSERVICO-RADARES-ENTREVIAS");
        //serviceUrlMap.put("rondon", "MICROSERVICO-RADARES-RONDON");
        log.info("Mapa de serviços carregado: {}", serviceUrlMap);
    }

    /**
     * Método unificado para buscar dados paginados. Filtra por concessionárias se a lista for fornecida,
     * ou busca em todas se a lista for nula ou vazia.
     */
    public RadarPageDTO buscarComFiltros(
            List<String> concessionarias,
            String placa,
            String praca,
            String rodovia,
            String km,
            String sentido,
            LocalDate data,
            LocalTime horaInicial,
            LocalTime horaFinal,
            Pageable pageable
    ) {
        final List<String> urlsParaChamar;

        if (CollectionUtils.isEmpty(concessionarias)) {
            urlsParaChamar = new ArrayList<>(serviceUrlMap.values());
            log.info("Busca agregada em todos os {} serviços.", urlsParaChamar.size());
        } else {
            urlsParaChamar = concessionarias.stream()
                    .map(nome -> serviceUrlMap.get(nome.toLowerCase()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            log.info("Busca direcionada para as concessionárias: {}", concessionarias);
        }

        if (urlsParaChamar.isEmpty()) {
            log.warn("Nenhuma URL de serviço válida encontrada. Concessionárias: {}", concessionarias);
            return new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0));
        }

        // Executa chamadas paralelas
        List<CompletableFuture<RadarPageDTO>> futures = urlsParaChamar.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchPageFromMicroservice(
                                baseUrl, placa, praca, rodovia, km, sentido,
                                data, horaInicial, horaFinal, pageable
                        ),
                        executorService
                ))
                .toList();

        // Aguarda todas as respostas
        List<RadarPageDTO> pages = futures.stream()
                .map(future -> {
                    try {
                        return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.error("❌ Erro ao buscar dados de radar (Filtros): {}", e.toString());
                        return new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0));
                    }
                })
                .collect(Collectors.toList());

        return aggregatePages(pages, pageable);
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

        if (urlsParaChamar.isEmpty()) {
            return Collections.emptyList();
        }

        // Busca todas as páginas de todos os serviços em paralelo
        List<CompletableFuture<List<RadarDTO>>> futures = urlsParaChamar.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> fetchAllPagesFromMicroservice(
                                baseUrl, placa, praca, rodovia, km, sentido,
                                data, horaInicial, horaFinal
                        ),
                        executorService
                ))
                .collect(Collectors.toList()).reversed();

        // Combina todos os resultados
        List<RadarDTO> allRadars = futures.stream()
                .map(future -> {
                    try {
                        return future.get(60, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.error("Erro ao buscar todos os dados para exportação: {}",e.toString());
                        return Collections.<RadarDTO>emptyList();
                    }
                })
                .flatMap(List::stream)
                .collect(Collectors.toList());

        // Ordena por data e hora (mais recentes primeiro)
        allRadars.sort(Comparator
                .comparing(RadarDTO::getData, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(RadarDTO::getHora, Comparator.nullsLast(Comparator.reverseOrder())));

        log.info("Exportação finalizada. Total de registros: {}", allRadars.size());
        return allRadars;
    }

    /**
     * Retorna os últimos radares processados (do cache em memória).
     */
    public List<RadarDTO> getUltimosRadaresProcessados() {
        return new ArrayList<>(realtimeUpdateService.getLatestRadars().values());
    }

    /**
     * Busca as opções de filtro disponíveis para uma concessionária.
     */
    @Cacheable(value = "radares-bff-filtros", key = "#nomeConcessionaria", unless = "#result == null || #result.rodovias.isEmpty()")
    public FilterOptionsDTO getFilterOptionsForConcessionaria(String nomeConcessionaria) {
        String baseUrl = serviceUrlMap.get(nomeConcessionaria.toLowerCase());
        if (baseUrl == null) {
            log.warn("Concessionária '{}' não encontrada no mapa de serviços", nomeConcessionaria);
            return new FilterOptionsDTO(List.of(), List.of(), List.of(), List.of());
        }

        String url = "http://" + baseUrl + "/radares/opcoes-filtro";
        log.info("BFF buscando opções de filtro em: {}", url);

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("filterOptions");

        return circuitBreaker.run(
                () -> {
                    // Tenta fazer a requisição
                    try {
                        FilterOptionsDTO response = restTemplate.getForObject(url, FilterOptionsDTO.class);
                        if (response == null) {
                            log.warn("⚠️ [BFF] Resposta NULA recebida de {}", url);
                            return new FilterOptionsDTO(List.of(), List.of(), List.of(), List.of());
                        }
                        log.info("✅ [BFF] Filtros recebidos com sucesso! Rodovias: {}", response.getRodovias().size());
                        return response;
                    } catch (Exception e) {
                        log.error("🔥 [BFF] Erro ao chamar REST TEMPLATE para {}: {}", url, e.getMessage());
                        throw e; // Relança para ativar o fallback
                    }
                },
                throwable -> {
                    // FALLBACK - Aqui descobrimos a causa
                    log.error("❌ [CIRCUIT BREAKER] Falha ao buscar filtros de '{}'. Causa: {}", nomeConcessionaria, throwable.toString());
                    // Retorna vazio para não quebrar o frontend, mas agora SABEMOS o erro no log
                    return new FilterOptionsDTO(List.of(), List.of(), List.of(), List.of());
                }
        );
    }

    /**
     * Busca os KMs disponíveis para uma rodovia específica.
     */
    public List<String> getKmsForRodoviaByConcessionaria(String nomeConcessionaria, String rodovia) {
        String baseUrl = serviceUrlMap.get(nomeConcessionaria.toLowerCase());
        if (baseUrl == null) {
            return Collections.emptyList();
        }

        String url = UriComponentsBuilder.fromHttpUrl("http://" + baseUrl + "/radares/kms-por-rodovia")
                .queryParam("rodovia", rodovia)
                .toUriString();

        log.info("BFF buscando KMs por rodovia em: {}", url);

        try {
            ResponseEntity<List<String>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<String>>() {}
            );
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Erro ao buscar KMs para rodovia '{}': {}", rodovia, e.getMessage());
            return Collections.emptyList();
        }
    }

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

    // =========================================================================
    // MÉTODOS PRIVADOS AUXILIARES
    // =========================================================================

    private RadarPageDTO fetchPageFromMicroservice(
            String baseUrl,
            String placa,
            String praca,
            String rodovia,
            String km,
            String sentido,
            LocalDate data,
            LocalTime horaInicial,
            LocalTime horaFinal,
            Pageable pageable
    ) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromUriString("http://" + baseUrl + "/radares/filtros")
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize());

        // 1. LÓGICA DE ORDENAÇÃO CORRIGIDA
        if (pageable.getSort().isSorted()) {
            // Se o frontend mandou sort, repassa exatamente como veio
            pageable.getSort().forEach(order -> {
                uriBuilder.queryParam("sort", order.getProperty() + "," + order.getDirection());
            });
        } else {
            // Se não mandou, força o padrão DESC para pegar os mais recentes
            uriBuilder.queryParam("sort", "data,desc");
            uriBuilder.queryParam("sort", "hora,desc");
        }

        if (placa != null && !placa.isBlank()) uriBuilder.queryParam("placa", placa);
        if (praca != null && !praca.isBlank()) uriBuilder.queryParam("praca", praca);
        if (rodovia != null && !rodovia.isBlank()) uriBuilder.queryParam("rodovia", rodovia);
        if (km != null && !km.isBlank()) uriBuilder.queryParam("km", km);
        if (sentido != null && !sentido.isBlank()) uriBuilder.queryParam("sentido", sentido);
        if (data != null) uriBuilder.queryParam("data", data.toString());
        if (horaInicial != null) uriBuilder.queryParam("horaInicial", horaInicial.toString());
        if (horaFinal != null) uriBuilder.queryParam("horaFinal", horaFinal.toString());

        String urlFinal = uriBuilder.toUriString();
        log.info("BFF chamando serviço via Service Discovery [{}]: {}", baseUrl, urlFinal);

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("radaresService");

        return circuitBreaker.run(
                () -> {
                    try {
                        ResponseEntity<RadarPageDTO> response = restTemplate.getForEntity(urlFinal, RadarPageDTO.class);
                        return response.getBody() != null ? response.getBody() : new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0));
                    } catch (Exception e) {
                        log.error("Erro chamando {}: {}", baseUrl, e.toString());
                        throw e;
                    }
                },
                throwable -> {
                    // Logamos aqui para saber se foi o Circuit Breaker que abriu ou Timeout
                    log.warn("Fallback acionado para {}: {}", baseUrl, throwable.toString());
                    return new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0));
                }
        );
    }

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
            UriComponentsBuilder uriBuilder = UriComponentsBuilder
                    .fromUriString("http://" + baseUrl + "/radares/filtros")
                    .queryParam("page", pageNumber)
                    .queryParam("size", pageSize)
                    .queryParam("sort", "data,desc")
                    .queryParam("sort", "hora,desc");

            if (placa != null && !placa.isBlank()) uriBuilder.queryParam("placa", placa);
            if (praca != null && !praca.isBlank()) uriBuilder.queryParam("praca", praca);
            if (rodovia != null && !rodovia.isBlank()) uriBuilder.queryParam("rodovia", rodovia);
            if (km != null && !km.isBlank()) uriBuilder.queryParam("km", km);
            if (sentido != null && !sentido.isBlank()) uriBuilder.queryParam("sentido", sentido);
            if (data != null) uriBuilder.queryParam("data", data.toString());
            if (horaInicial != null) uriBuilder.queryParam("horaInicial", horaInicial.toString());
            if (horaFinal != null) uriBuilder.queryParam("horaFinal", horaFinal.toString());

            try {
                ResponseEntity<RadarPageDTO> response = restTemplate.getForEntity(
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
                log.error("Erro ao buscar página {} de {}: {}", pageNumber, baseUrl, e.toString());
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
                ResponseEntity<RadarPageDTO> response = restTemplate.getForEntity(
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
     * Agrega múltiplas páginas de diferentes serviços em uma única página.
     */
    private RadarPageDTO aggregatePages(List<RadarPageDTO> pages, Pageable pageable) {
        // 1. Combina o conteúdo de todas as páginas recebidas (caso haja múltiplos serviços)
        List<RadarDTO> combinedContent = pages.stream()
                .filter(p -> p != null && p.getContent() != null)
                .flatMap(p -> p.getContent().stream())
                .collect(Collectors.toList());

        // 2. Ordena em memória (importante se vier de múltiplos serviços)
        // Se o sort for complexo no futuro, essa lógica precisará ser mais dinâmica
        if (pageable.getSort().isSorted()) {
            // Lógica básica para respeitar o sort do pageable se possível,
            // mas aqui forçamos o padrão esperado pelo usuário para simplificar.
            combinedContent.sort(Comparator
                    .comparing(RadarDTO::getData, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(RadarDTO::getHora, Comparator.nullsLast(Comparator.reverseOrder())));
        } else {
            combinedContent.sort(Comparator
                    .comparing(RadarDTO::getData, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(RadarDTO::getHora, Comparator.nullsLast(Comparator.reverseOrder())));
        }

        long totalElements = pages.stream()
                .filter(p -> p != null && p.getPage() != null)
                .mapToLong(p -> p.getPage().getTotalElements())
                .sum();

        List<RadarDTO> paginatedContent = combinedContent.stream()
                //.skip((long) pageable.getPageNumber() * pageable.getPageSize())
                .limit(pageable.getPageSize())
                .collect(Collectors.toList());

        int totalPages = pageable.getPageSize() > 0 ? (int) Math.ceil((double) totalElements / pageable.getPageSize()) : 0;

        return new RadarPageDTO(paginatedContent, new PageMetadata(pageable.getPageNumber(), pageable.getPageSize(), totalElements, totalPages));
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
                .queryParam("data", data.toString())
                .queryParam("horaInicio", horaInicio.toString())
                .queryParam("horaFim", horaFim.toString())
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize());

        // Ordenação padrão por data/hora se não vier especificado
        uriBuilder.queryParam("sort", "data,desc");
        uriBuilder.queryParam("sort", "hora,desc");

        String urlFinal = uriBuilder.toUriString();
        log.info("BFF Geo-Search [{}]: {}", baseUrl, urlFinal);

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("radaresGeoService");

        return circuitBreaker.run(
                () -> {
                    try {
                        ResponseEntity<RadarPageDTO> response = restTemplate.getForEntity(urlFinal, RadarPageDTO.class);
                        return response.getBody() != null ? response.getBody() : new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0));
                    } catch (Exception e) {
                        log.error("Erro chamando GeoSearch em {}: {}", baseUrl, e.toString());
                        throw e;
                    }
                },
                throwable -> {
                    log.warn("Fallback GeoSearch para {}: {}", baseUrl, throwable.toString());
                    return new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0));
                }
        );
    }

    private List<RadarLocationDTO> fetchLocationsFromMicroservices(String baseUrl) {
        String url = "http://" + baseUrl + "/radares/all-locations";
        log.info("BFF chamando locations: {}", url);

        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("locationsService");

        return circuitBreaker.run(
                () -> {
                    try {
                        ResponseEntity<List<RadarLocationDTO>> response = restTemplate.exchange(
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
