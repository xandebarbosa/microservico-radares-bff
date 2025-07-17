package com.coruja.services;

import com.coruja.dto.AlertaPassagemDTO;
import com.coruja.dto.PageAlertaPassagemDTO;
import com.coruja.dto.PagePlacaMonitoradaDTO;
import com.coruja.dto.PlacaMonitoradaDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class MonitoramentoBFFService {

    private final WebClient webClient;

    @Value("${microservico.monitoramento.url}")
    private String monitoramentoUrl;

    public Mono<Page<PlacaMonitoradaDTO>> listarMonitorados(Pageable pageable) {
        String url = String.format("%s/api/monitoramento?page=%d&size=%d",
                monitoramentoUrl, pageable.getPageNumber(), pageable.getPageSize());
        log.info("BFF chamando serviço de monitoramento em: {}", url);

        // AGORA FUNCIONA: Usamos a nossa nova classe concreta para a conversão
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(PagePlacaMonitoradaDTO.class)
                // Fazemos um "cast" para a interface Page, que é o que o método retorna
                .map(pageDto -> (Page<PlacaMonitoradaDTO>) pageDto);
    }

    public Mono<PlacaMonitoradaDTO> buscarPorId(Long id) {
        String url = String.format("%s/api/monitoramento/%d", monitoramentoUrl, id);
        return webClient.get().uri(url).retrieve().bodyToMono(PlacaMonitoradaDTO.class);
    }

    public Mono<PlacaMonitoradaDTO> criarMonitorado(PlacaMonitoradaDTO dto) {
        String url = String.format("%s/api/monitoramento", monitoramentoUrl);
        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(PlacaMonitoradaDTO.class);
    }

    public Mono<PlacaMonitoradaDTO> atualizarMonitorado(Long id, PlacaMonitoradaDTO dto) {
        String url = String.format("%s/api/monitoramento/%d", monitoramentoUrl, id);
        return webClient.put()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .retrieve()
                .bodyToMono(PlacaMonitoradaDTO.class);
    }

    public Mono<Void> deletarMonitorado(Long id) {
        String url = String.format("%s/api/monitoramento/%d", monitoramentoUrl, id);
        return webClient.delete().uri(url).retrieve().bodyToMono(Void.class);
    }

    /**
     * NOVO: Busca o histórico de alertas de forma paginada no serviço de monitoramento.
     */
    public Mono<Page<AlertaPassagemDTO>> listarAlertas(Pageable pageable) {

        // Constrói a URL de forma segura e programática
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(monitoramentoUrl + "/api/monitoramento/alertas")
                .queryParam("page", pageable.getPageNumber())
                .queryParam("size", pageable.getPageSize());

        // Adiciona os parâmetros de sort se eles existirem
        pageable.getSort().forEach(order -> {
            builder.queryParam("sort", order.getProperty() + "," + order.getDirection().name());
        });

        String url = builder.toUriString();
        log.info("BFF chamando serviço de monitoramento para buscar histórico de alertas: {}", url);

        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(PageAlertaPassagemDTO.class) // Usa o DTO de página
                .map(pageDto -> (Page<AlertaPassagemDTO>) pageDto); // Converte para a interface Page
    }

}
