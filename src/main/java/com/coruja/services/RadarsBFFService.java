package com.coruja.services;

import com.coruja.dto.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RadarsBFFService {

    private final WebClient webClient;
    private final RealtimeUpdateService realtimeUpdateService;
    private final Map<String, String> serviceUrlMap = new HashMap<>();

    // --- Injeção de Configurações ---
    //@Value("${microservico.rondon.url}")
    //private String rondonUrl;
    @Value("${microservico.cart.url}")
    private String cartUrl;
    //@Value("${microservico.eixo.url}")
    //private String eixoUrl;
    //@Value("${microservico.entrevias.url}")
    //private String entreviasUrl;

    /**
     * NOVO: Este método é executado uma vez após a construção do serviço
     * para inicializar nosso mapa de serviços.
     */
    @PostConstruct
    public void init() {
        log.info("Inicializando mapa de URLs dos serviços de radares...");
        // CORREÇÃO: Descomentado para popular o mapa.
        serviceUrlMap.put("cart", cartUrl);
        //serviceUrlMap.put("eixo", eixoUrl);
        //serviceUrlMap.put("entrevias", entreviasUrl);
        //serviceUrlMap.put("rondon", rondonUrl);
        log.info("Mapa de serviços carregado: {}", serviceUrlMap);
    }

    /**
     * Método unificado para buscar dados paginados. Filtra por concessionárias se a lista for fornecida,
     * ou busca em todas se a lista for nula ou vazia.
     */
    public Mono<RadarPageDTO> buscarComFiltros(
            List<String> concessionarias, String placa, String praca, String rodovia, String km, String sentido,
            LocalDate data, LocalTime horaInicial, LocalTime horaFinal, Pageable pageable
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
            log.warn("Nenhuma URL de serviço válida encontrada para a busca. Concessionárias pedidas: {}.", concessionarias);
            return Mono.just(new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0)));
        }

        Flux<RadarPageDTO> responsesFlux = Flux.fromIterable(urlsParaChamar)
                .flatMap(baseUrl -> fetchPageFromMicroservice(
                        baseUrl, placa, praca, rodovia, km, sentido, data, horaInicial, horaFinal, pageable
                ));

        return responsesFlux.collectList().map(pages -> aggregatePages(pages, pageable));
    }

    /**
     * Busca TODOS os registros que correspondem a um filtro, para exportação.
     */
    public Mono<List<RadarDTO>> buscarTodosParaExportacao(
            List<String> concessionarias, String placa, String praca, String rodovia, String km, String sentido,
            LocalDate data, LocalTime horaInicial, LocalTime horaFinal
    ) {
        final List<String> urlsParaChamar;
        if (CollectionUtils.isEmpty(concessionarias)) {
            urlsParaChamar = new ArrayList<>(serviceUrlMap.values());
        } else {
            urlsParaChamar = concessionarias.stream().map(nome -> serviceUrlMap.get(nome.toLowerCase())).filter(Objects::nonNull).collect(Collectors.toList());
        }

        if (urlsParaChamar.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }

        Flux<RadarDTO> todosOsRadaresFlux = Flux.fromIterable(urlsParaChamar)
                .flatMap(baseUrl -> fetchAllPagesFromMicroservice(baseUrl, placa, praca, rodovia, km, sentido, data, horaInicial, horaFinal));

        return todosOsRadaresFlux.collectList()
                .map(lista -> {
                    lista.sort(Comparator.comparing(RadarDTO::getData, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(RadarDTO::getHora, Comparator.nullsLast(Comparator.reverseOrder())));
                    return lista;
                });
    }

    // --- Métodos para popular filtros do Frontend ---

    public List<RadarDTO> getUltimosRadaresProcessados() {
        return new ArrayList<>(realtimeUpdateService.getLatestRadars().values());
    }

    public Mono<FilterOptionsDTO> getFilterOptionsForConcessionaria(String nomeConcessionaria) {
        String baseUrl = serviceUrlMap.get(nomeConcessionaria.toLowerCase());
        if (baseUrl == null) {
            return Mono.just(new FilterOptionsDTO(List.of(), List.of(), List.of(), List.of()));
        }
        String url = baseUrl + "/radares/opcoes-filtro";
        log.info("BFF buscando opções de filtro em: {}", url);
        return webClient.get().uri(url).retrieve().bodyToMono(FilterOptionsDTO.class)
                .onErrorResume(e -> {
                    log.error("BFF: Falha ao buscar opções de filtro para {}: {}", nomeConcessionaria, e.getMessage());
                    return Mono.just(new FilterOptionsDTO(List.of(), List.of(), List.of(), List.of()));
                });
    }

    public Mono<List<String>> getKmsForRodoviaByConcessionaria(String nomeConcessionaria, String rodovia) {
        String baseUrl = serviceUrlMap.get(nomeConcessionaria.toLowerCase());
        if (baseUrl == null) {
            return Mono.just(Collections.emptyList());
        }
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/radares/kms-por-rodovia")
                .queryParam("rodovia", rodovia)
                .toUriString();
        log.info("BFF buscando KMs por rodovia em: {}", url);
        return webClient.get().uri(url).retrieve().bodyToMono(new ParameterizedTypeReference<List<String>>() {})
                .onErrorResume(e -> {
                    log.error("BFF: Falha ao buscar KMs para a rodovia '{}': {}", rodovia, e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }


    // =========================================================================
    // MÉTODOS PRIVADOS AUXILIARES
    // =========================================================================

    private Mono<RadarPageDTO> fetchPageFromMicroservice(String baseUrl, String placa, String praca, String rodovia, String km, String sentido, LocalDate data, LocalTime horaInicial, LocalTime horaFinal, Pageable pageable) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/radares/filtros")
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize())
                .queryParam("sort", "data,desc");

        if (placa != null && !placa.isBlank()) uriBuilder.queryParam("placa", placa);
        if (praca != null && !praca.isBlank()) uriBuilder.queryParam("praca", praca);
        if (rodovia != null && !rodovia.isBlank()) uriBuilder.queryParam("rodovia", rodovia);
        if (km != null && !km.isBlank()) uriBuilder.queryParam("km", km);
        if (sentido != null && !sentido.isBlank()) uriBuilder.queryParam("sentido", sentido);
        if (data != null) uriBuilder.queryParam("data", data.toString());
        if (horaInicial != null) uriBuilder.queryParam("horaInicial", horaInicial.toString());
        if (horaFinal != null) uriBuilder.queryParam("horaFinal", horaFinal.toString());

        String urlFinal = uriBuilder.toUriString();
        log.info("BFF chamando: {}", urlFinal);

        return webClient.get().uri(urlFinal).retrieve()
                .bodyToMono(RadarPageDTO.class)
                .doOnError(e -> log.error("BFF: ERRO DETALHADO ao chamar {}: ", urlFinal, e))
                .onErrorResume(e -> {
                    log.warn("BFF: Falha na chamada para {}. Retornando página vazia.", urlFinal);
                    return Mono.just(new RadarPageDTO(Collections.emptyList(), new PageMetadata(0, 0, 0, 0)));
                });
    }

    private Flux<RadarDTO> fetchAllPagesFromMicroservice(String baseUrl, String placa, String praca, String rodovia, String km, String sentido, LocalDate data, LocalTime horaInicial, LocalTime horaFinal) {
        final int pageSize = 1000;
        return Mono.just(0)
                .expand(pageNumber -> {
                    UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/radares/filtros")
                            .queryParam("page", pageNumber).queryParam("size", pageSize);
                    if (placa != null && !placa.isBlank()) uriBuilder.queryParam("placa", placa);
                    if (praca != null && !praca.isBlank()) uriBuilder.queryParam("praca", praca);
                    if (rodovia != null && !rodovia.isBlank()) uriBuilder.queryParam("rodovia", rodovia);
                    if (km != null && !km.isBlank()) uriBuilder.queryParam("km", km);
                    if (sentido != null && !sentido.isBlank()) uriBuilder.queryParam("sentido", sentido);
                    if (data != null) uriBuilder.queryParam("data", data.toString());
                    if (horaInicial != null) uriBuilder.queryParam("horaInicial", horaInicial.toString());
                    if (horaFinal != null) uriBuilder.queryParam("horaFinal", horaFinal.toString());

                    return webClient.get().uri(uriBuilder.toUriString()).retrieve()
                            .bodyToMono(RadarPageDTO.class)
                            .onErrorResume(e -> Mono.empty())
                            .flatMap(pageResponse -> {
                                boolean isLastPage = pageResponse.getContent() == null || pageResponse.getContent().isEmpty();
                                return isLastPage ? Mono.<Integer>empty() : Mono.just(pageNumber + 1);
                            });
                })
                .flatMap(pageNumber -> {
                    UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/radares/filtros")
                            .queryParam("page", pageNumber).queryParam("size", pageSize);
                    // (O ideal seria não repetir esta lógica, mas para garantir correção, a mantemos aqui)
                    if (placa != null && !placa.isBlank()) uriBuilder.queryParam("placa", placa);
                    if (praca != null && !praca.isBlank()) uriBuilder.queryParam("praca", praca);
                    if (rodovia != null && !rodovia.isBlank()) uriBuilder.queryParam("rodovia", rodovia);
                    if (km != null && !km.isBlank()) uriBuilder.queryParam("km", km);
                    if (sentido != null && !sentido.isBlank()) uriBuilder.queryParam("sentido", sentido);
                    if (data != null) uriBuilder.queryParam("data", data.toString());
                    if (horaInicial != null) uriBuilder.queryParam("horaInicial", horaInicial.toString());
                    if (horaFinal != null) uriBuilder.queryParam("horaFinal", horaFinal.toString());

                    return webClient.get().uri(uriBuilder.toUriString()).retrieve()
                            .bodyToMono(RadarPageDTO.class)
                            .flatMapMany(pageResponse -> Flux.fromIterable(pageResponse.getContent()))
                            .onErrorResume(e -> Flux.empty());
                });
    }

    private RadarPageDTO aggregatePages(List<RadarPageDTO> pages, Pageable pageable) {
        List<RadarDTO> combinedContent = pages.stream()
                .filter(p -> p != null && p.getContent() != null)
                .flatMap(p -> p.getContent().stream())
                .collect(Collectors.toList());

        combinedContent.sort(Comparator.comparing(RadarDTO::getData, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(RadarDTO::getHora, Comparator.nullsLast(Comparator.reverseOrder())));

        long totalElements = pages.stream()
                .filter(p -> p != null && p.getPage() != null)
                .mapToLong(p -> p.getPage().getTotalElements())
                .sum();

        int totalPages = pageable.getPageSize() > 0 ? (int) Math.ceil((double) totalElements / pageable.getPageSize()) : 0;
        PageMetadata metadata = new PageMetadata(pageable.getPageNumber(), pageable.getPageSize(), totalElements, totalPages);

        log.info("BFF: Agregação finalizada. Total de elementos: {}. Itens combinados: {}", totalElements, combinedContent.size());
        return new RadarPageDTO(combinedContent, metadata);
    }
}
