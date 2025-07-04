package com.coruja.controller;

import com.coruja.dto.FilterOptionsDTO;
import com.coruja.dto.RadarDTO;
import com.coruja.dto.RadarPageDTO;
import com.coruja.dto.RadarPageResponse;
import com.coruja.services.RadarsBFFService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/radares")
@RequiredArgsConstructor
public class RadarsBFFController {

    private final RadarsBFFService radarsBFFService;

    // =================================================================
    // NOVO ENDPOINT 1: Busca simples e direta por placa
    // =================================================================
    /**
     * Busca todos os registros de radares para uma placa específica.
     * @param placa A placa do veículo a ser pesquisada.     *
     * @return Uma página de resultados de radares para a placa informada.
     */
    @GetMapping("/placa/{placa}")
    public Mono<ResponseEntity<RadarPageDTO>> buscarPorPlaca(
            @PathVariable String placa,
            Pageable pageable
    ) {
        // Chama o método unificado, passando a lista de concessionárias como nula para buscar em todas.
        return radarsBFFService.buscarComFiltros(
                null, placa, null, null, null, null, null, null, null, pageable
        ).map(ResponseEntity::ok);
    }


    /**
     * Endpoint genérico que pode buscar em todas ou em concessionárias específicas.
     */
    @GetMapping("/filtros")
    public Mono<ResponseEntity<RadarPageDTO>> buscarComTodosOsFiltros(
            @RequestParam(required = false) List<String> concessionaria,
            @RequestParam(required = false) String placa,
            @RequestParam(required = false) String praca,
            @RequestParam(required = false) String rodovia,
            @RequestParam(required = false) String km,
            @RequestParam(required = false) String sentido,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFinal,
            Pageable pageable
    ) {
        return radarsBFFService.buscarComFiltros(
                concessionaria, placa, praca, rodovia, km, sentido, data, horaInicial, horaFinal, pageable
        ).map(ResponseEntity::ok);
    }

    // =================================================================
    // NOVO ENDPOINT: Rota específica por concessionária
    // =================================================================
    /**
     * Busca registros de radares em uma concessionária específica com filtros.
     * @param nomeConcessionaria O nome da concessionária (ex: rondon, cart, eixo, entrevias).
     */
    @GetMapping("/concessionaria/{nomeConcessionaria}/filtros")
    public Mono<ResponseEntity<RadarPageDTO>> buscarPorConcessionaria(
            @PathVariable String nomeConcessionaria,
            @RequestParam(required = false) String placa,
            @RequestParam(required = false) String praca,
            @RequestParam(required = false) String rodovia,
            @RequestParam(required = false) String km,
            @RequestParam(required = false) String sentido,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFinal,
            Pageable pageable
    ) {
        return radarsBFFService.buscarComFiltros(
                List.of(nomeConcessionaria), placa, praca, rodovia, km, sentido, data, horaInicial, horaFinal, pageable
        ).map(ResponseEntity::ok);
    }

    @GetMapping("/concessionaria/{nomeConcessionaria}/opcoes-filtro")
    public Mono<ResponseEntity<FilterOptionsDTO>> getFiltersOptions(@PathVariable String nomeConcessionaria) {
        return radarsBFFService.getFilterOptionsForConcessionaria(nomeConcessionaria)
                .map(ResponseEntity::ok);
    }

    // =================================================================
    // NOVO ENDPOINT: Rota para buscar KMs de uma rodovia específica
    // =================================================================
    @GetMapping("/concessionaria/{nomeConcessionaria}/kms-por-rodovia")
    public Mono<ResponseEntity<List<String>>> getKmsByRodovia(
            @PathVariable String nomeConcessionaria,
            @RequestParam String rodovia
    ) {
        return radarsBFFService.getKmsForRodoviaByConcessionaria(nomeConcessionaria, rodovia)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/ultimos-processados")
    public Mono<ResponseEntity<List<RadarDTO>>> getUltimosProcessados() {
        // Usamos Mono.fromCallable para não bloquear a thread principal
        return Mono.fromCallable(() -> radarsBFFService.getUltimosRadaresProcessados())
                .map(ResponseEntity::ok);
    }

    /**
     * Endpoint para exportar todos os dados de uma busca para Excel.
     * Não utiliza paginação, pois busca todos os resultados de uma vez.
     */
    @GetMapping("/exportar")
    public Mono<ResponseEntity<List<RadarDTO>>> exportarComFiltros(
            @RequestParam(required = false) List<String> concessionaria,
            @RequestParam(required = false) String placa,
            @RequestParam(required = false) String praca,
            @RequestParam(required = false) String rodovia,
            @RequestParam(required = false) String km,
            @RequestParam(required = false) String sentido,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFinal
    ) {
        return radarsBFFService.buscarTodosParaExportacao(
                concessionaria, placa, praca, rodovia, km, sentido, data, horaInicial, horaFinal
        ).map(ResponseEntity::ok);
    }
}
