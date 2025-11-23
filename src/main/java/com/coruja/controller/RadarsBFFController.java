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
import org.springframework.security.access.prepost.PreAuthorize;
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

    /**
     * Busca todos os registros de radares para uma placa específica.
     * @param placa A placa do veículo a ser pesquisada.     *
     * @return Uma página de resultados de radares para a placa informada.
     */
    @GetMapping("/placa/{placa}")
    @PreAuthorize("hasAnyRole('user', 'admin')")
    public ResponseEntity<RadarPageDTO> buscarPorPlaca(
            @PathVariable String placa,
            Pageable pageable
    ) {
        RadarPageDTO result = radarsBFFService.buscarComFiltros(
                null, placa, null, null, null, null, null, null, null, pageable
        );
        return ResponseEntity.ok(result);
    }


    /**
     * Endpoint genérico que pode buscar em todas ou em concessionárias específicas.
     * @param concessionaria Lista de concessionárias para filtrar (opcional).
     * @param placa Placa do veículo (opcional).
     * @param praca Praça de pedágio (opcional).
     * @param rodovia Código da rodovia (opcional).
     * @param km Quilômetro da rodovia (opcional).
     * @param sentido Sentido da via (opcional).
     * @param data Data específica (opcional).
     * @param horaInicial Hora inicial do intervalo (opcional).
     * @param horaFinal Hora final do intervalo (opcional).
     * @param pageable Parâmetros de paginação.
     * @return Página com os resultados filtrados.
     */
    @GetMapping("/filtros")
    @PreAuthorize("hasAnyRole('user', 'admin')")
    public ResponseEntity<RadarPageDTO> buscarComTodosOsFiltros(
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
        RadarPageDTO result = radarsBFFService.buscarComFiltros(
                concessionaria, placa, praca, rodovia, km, sentido, data, horaInicial, horaFinal, pageable
        );
        return ResponseEntity.ok(result);
    }

    /**
     * Busca registros de radares em uma concessionária específica com filtros.
     * @param nomeConcessionaria O nome da concessionária (ex: rondon, cart, eixo, entrevias).
     * @param placa Placa do veículo (opcional).
     * @param praca Praça de pedágio (opcional).
     * @param rodovia Código da rodovia (opcional).
     * @param km Quilômetro da rodovia (opcional).
     * @param sentido Sentido da via (opcional).
     * @param data Data específica (opcional).
     * @param horaInicial Hora inicial do intervalo (opcional).
     * @param horaFinal Hora final do intervalo (opcional).
     * @param pageable Parâmetros de paginação.
     * @return Página com os resultados filtrados da concessionária.
     */
    @GetMapping("/concessionaria/{nomeConcessionaria}/filtros")
    @PreAuthorize("hasAnyRole('user', 'admin')")
    public ResponseEntity<RadarPageDTO> buscarPorConcessionaria(
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
        RadarPageDTO result = radarsBFFService.buscarComFiltros(
                List.of(nomeConcessionaria), placa, praca, rodovia, km, sentido, data, horaInicial, horaFinal, pageable
        );
        return ResponseEntity.ok(result);
    }

    @GetMapping("/concessionaria/{nomeConcessionaria}/opcoes-filtro")
    @PreAuthorize("hasAnyRole('user', 'admin')")
    public ResponseEntity<FilterOptionsDTO> getFiltersOptions(@PathVariable String nomeConcessionaria) {
        FilterOptionsDTO result = radarsBFFService.getFilterOptionsForConcessionaria(nomeConcessionaria);
        return ResponseEntity.ok(result);
    }

    /**
     * Busca os KMs disponíveis para uma rodovia específica em uma concessionária.
     * @param nomeConcessionaria Nome da concessionária.
     * @param rodovia Código da rodovia.
     * @return Lista de KMs disponíveis.
     */
    @GetMapping("/concessionaria/{nomeConcessionaria}/kms-por-rodovia")
    @PreAuthorize("hasAnyRole('user', 'admin')")
    public ResponseEntity<List<String>> getKmsByRodovia(
            @PathVariable String nomeConcessionaria,
            @RequestParam String rodovia
    ) {
        List<String> result = radarsBFFService.getKmsForRodoviaByConcessionaria(nomeConcessionaria, rodovia);
        return ResponseEntity.ok(result);
    }

    /**
     * Retorna os últimos radares processados (do cache em memória).
     * Útil para dashboards e monitoramento em tempo real.
     * @return Lista com os últimos radares de cada concessionária.
     */
    @GetMapping("/ultimos-processados")
    @PreAuthorize("hasAnyRole('user', 'admin')")
    public ResponseEntity<List<RadarDTO>> getUltimosProcessados() {
        List<RadarDTO> result = radarsBFFService.getUltimosRadaresProcessados();
        return ResponseEntity.ok(result);
    }

    /**
     * Exporta todos os dados de uma busca para Excel.
     * Não utiliza paginação - retorna todos os resultados de uma vez.
     * @param concessionaria Lista de concessionárias para filtrar (opcional).
     * @param placa Placa do veículo (opcional).
     * @param praca Praça de pedágio (opcional).
     * @param rodovia Código da rodovia (opcional).
     * @param km Quilômetro da rodovia (opcional).
     * @param sentido Sentido da via (opcional).
     * @param data Data específica (opcional).
     * @param horaInicial Hora inicial do intervalo (opcional).
     * @param horaFinal Hora final do intervalo (opcional).
     * @return Lista completa de radares que atendem aos filtros.
     */
    @GetMapping("/exportar")
    public ResponseEntity<List<RadarDTO>> exportarComFiltros(
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
        List<RadarDTO> result = radarsBFFService.buscarTodosParaExportacao(
                concessionaria, placa, praca, rodovia, km, sentido, data, horaInicial, horaFinal
        );
        return ResponseEntity.ok(result);
    }
}
