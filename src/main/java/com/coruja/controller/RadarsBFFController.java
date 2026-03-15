package com.coruja.controller;

import com.coruja.dto.*;
import com.coruja.services.RadarsBFFService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping(value = "/radares")
@RequiredArgsConstructor
@Slf4j
public class RadarsBFFController {

    private final RadarsBFFService radarsBFFService;



    /**
     * ✅ BUSCA POR PLACA (Histórico Completo)
     * Substitui o antigo /placa/{placa} para padronizar query params.
     */
    @GetMapping("/busca-placa")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<RadarPageDTO> buscarPorPlaca(
            @RequestParam String placa,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        try {
            // Sanitização simples
            String termoBusca = (placa != null) ? placa.trim().toUpperCase() : "";

            log.info("📍 [BFF] Buscando histórico completo para placa: {}", termoBusca);
            return ResponseEntity.ok(radarsBFFService.buscarPorPlaca(termoBusca, pageable));
        } catch (Exception e) {
            log.error("🔥 Erro ao processar busca por placa: {}", e.getMessage());
            // Retorna um DTO de página vazio em vez de uma String
            RadarPageDTO emptyPage = new RadarPageDTO();
            emptyPage.setContent(Collections.emptyList());
            // Configure metadados de página zerados se necessário

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(emptyPage);
        }

    }


    /**
     * Endpoint genérico que pode buscar em todas ou em concessionárias específicas.
     * @param concessionaria Lista de concessionárias para filtrar (opcional).
     * @param rodovia Código da rodovia (opcional).
     * @param km Quilômetro da rodovia (opcional).
     * @param sentido Sentido da via (opcional).
     * @param data Data específica (opcional).
     * @param horaInicial Hora inicial do intervalo (opcional).
     * @param horaFinal Hora final do intervalo (opcional).
     * @param pageable Parâmetros de paginação.
     * @return Página com os resultados filtrados.
     * ✅ BUSCA POR LOCAL/Concessionaria (Operacional)
     * Exige Data. Otimizada para usar as partições do banco.
     */
    @GetMapping("/busca-local")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<RadarPageDTO> buscarPorLocal(
            @RequestParam(required = false) List<String> concessionaria, // Parâmetro adicionado
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFinal,
            @RequestParam(required = false) String rodovia,
            @RequestParam(required = false) String km,
            @RequestParam(required = false) String sentido,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        log.info("🔍 [BFF] Buscando Local | Concessionárias: {} | Data: {} | Rodovia: {}", concessionaria, data, rodovia);

        return ResponseEntity.ok(radarsBFFService.buscarPorLocal(
                concessionaria, // Passando a lista para o service
                data, horaInicial, horaFinal, rodovia, km, sentido, pageable
        ));
    }

    /**
     * Retorna os últimos radares processados (do cache em memória).
     * Útil para dashboards e monitoramento em tempo real.
     * @return Lista com os últimos radares de cada concessionária.
     */
    @GetMapping("/ultimos-processados")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<List<RadarDTO>> getUltimosProcessados() {
        try {
            log.info("📡 [API] Recebida requisição em /api/radares/ultimos-processados");
            List<RadarDTO> result = radarsBFFService.getUltimosRadaresProcessados();
            log.info("✅ [API] Sucesso. Retornando {} registros.", result.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("🔥 [API ERROR] Falha crítica ao buscar últimos processados: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Exporta todos os dados de uma busca para Excel.
     * Não utiliza paginação - retorna todos os resultados de uma vez.
     * @param concessionaria Lista de concessionárias para filtrar (opcional).
     * @param placa Placa do veículo (opcional).
     * @param rodovia Código da rodovia (opcional).
     * @param km Quilômetro da rodovia (opcional).
     * @param sentido Sentido da via (opcional).
     * @param praca Praça de pedágio (opcional).
     * @param data Data específica (opcional).
     * @param horaInicial Hora inicial do intervalo (opcional).
     * @param horaFinal Hora final do intervalo (opcional).
     * @return Lista completa de radares que atendem aos filtros.
     */
    @GetMapping("/exportar")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<List<RadarDTO>> exportarComFiltros(
            @RequestParam(required = false) List<String> concessionaria,
            @RequestParam(required = false) String placa,
            @RequestParam(required = false) String rodovia,
            @RequestParam(required = false) String km,
            @RequestParam(required = false) String sentido,
            @RequestParam(required = false) String praca,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicial,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFinal
    ) {
        log.info("💾 Exportando dados com filtros");
        List<RadarDTO> result = radarsBFFService.buscarTodosParaExportacao(
                concessionaria, placa, praca, rodovia, km, sentido, data, horaInicial, horaFinal
        );
        return ResponseEntity.ok(result);
    }

    /**
     * Endpoint para busca Geoespacial (Latitude/Longitude).
     * Orquestra a chamada para todos os microserviços e agrega os resultados próximos.
     */
    @GetMapping("/geo-search")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<RadarPageDTO> buscarPorGeolocalizacao(
            @RequestParam Double latitude,
            @RequestParam Double longitude,
            @RequestParam(required = false, defaultValue = "15000") Double raio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFim,
            Pageable pageable
    ) {
        log.info("🌍 Buscando por geolocalização: Latitude={}, Longitude={}, Raio={}m", latitude, longitude, raio);
        RadarPageDTO result = radarsBFFService.buscarPorGeolocalizacao(
                latitude, longitude, raio, data, horaInicio, horaFim, pageable
        );
        return ResponseEntity.ok(result);
    }

    /**
     * Retorna a lista completa de localizações de radares de todas as concessionárias.
     * Orquestra a chamada para todos os microserviços e agrega os resultados próximos
     */
    @GetMapping("/all-locations")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<List<RadarLocationDTO>> getRadarLocations() {
        log.info("🌍 [BFF] Buscando todas as localizações de radares para o mapa");
        List<RadarLocationDTO> locations = radarsBFFService.getAllRadarLocations();

        // Cacheia no navegador por 1 hora, já que a localização dos radares raramente muda
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.HOURS))
                .body(locations);
    }

    /**
     * NOVO: Exporta todos os dados de uma busca por Geolocalização para Excel.
     * Não utiliza paginação - retorna todos os resultados de uma vez.
     */
    @GetMapping("/geo-exportar")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<List<RadarDTO>> exportarGeoComFiltros(
            @RequestParam Double latitude,
            @RequestParam Double longitude,
            @RequestParam(required = false, defaultValue = "15000") Double raio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaInicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime horaFim
    ) {
        log.info("💾 Exportando dados GEO: Lat={}, Long={}, Raio={}m", latitude, longitude, raio);
        List<RadarDTO> result = radarsBFFService.buscarTodosPorGeolocalizacaoParaExportacao(
                latitude, longitude, raio, data, horaInicio, horaFim
        );
        return ResponseEntity.ok(result);
    }

    /**
     * MANTIDO POR COMPATIBILIDADE (Se necessário)
     * Redireciona para o buscarPorPlaca novo
     */
    @GetMapping("/placa/{placa}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<RadarPageDTO> buscarPorPlacaLegacy(
            @PathVariable String placa,
            Pageable pageable
    ) {
        return buscarPorPlaca(placa, pageable);
    }

    // ==================================================================================
    // 2. GESTÃO DE DOMÍNIO (RODOVIAS E KMs) - NOVO
    // ==================================================================================

    @GetMapping("/rodovias")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<List<RodoviaDTO>> listarRodovias(
            @RequestParam(required = false) String concessionaria
    ) {
        log.info("🛣️ [BFF] Listando rodovias. Filtro concessionária: {}",
                concessionaria != null ? concessionaria : "Todas");

        // Passa o filtro para o service, que decidirá qual microserviço chamar
        List<RodoviaDTO> rodovias = radarsBFFService.listarRodovias(concessionaria);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS)) // Mantém o cache no navegador
                .body(rodovias);
    }

    @PostMapping("/rodovias")
    @PreAuthorize("hasRole('ADMIN')") // Apenas Admin pode criar
    public ResponseEntity<RodoviaDTO> adicionarRodovia(@RequestBody RodoviaDTO rodovia, @RequestParam(required = false) String concessionaria) {
        log.info("🛣️ [BFF] Adicionando rodovia: {}, concessionária: {}", rodovia.getNome(), concessionaria);
        return ResponseEntity.ok(radarsBFFService.salvarRodovia(rodovia, concessionaria));
    }

    @DeleteMapping("/rodovias/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removerRodovia(@PathVariable Long id, @RequestParam(required = false) String concessionaria) {
        log.info("🗑️ [BFF] Removendo rodovia ID: {}, concessionária: {}", id, concessionaria);
        radarsBFFService.deletarRodovia(id, concessionaria);
        return ResponseEntity.noContent().build();
    }

    // --- KMs ---

    @GetMapping("/rodovias/{rodoviaId}/kms")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<List<KmRodoviaDTO>> listarKms(@PathVariable Long rodoviaId, @RequestParam(required = false) String concessionaria) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(30, TimeUnit.MINUTES))
                .body(radarsBFFService.listarKmsPorRodovia(rodoviaId, concessionaria));
    }

    @PostMapping("/kms")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<KmRodoviaDTO> adicionarKm(@RequestBody KmRodoviaDTO km, @RequestParam(required = false) String concessionaria) {
        log.info("➕ [BFF] Adicionando KM na concessionária: {}", concessionaria);
        return ResponseEntity.ok(radarsBFFService.salvarKm(km, concessionaria));
    }

    @DeleteMapping("/kms/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removerKm(@PathVariable Long id, @RequestParam(required = false) String concessionaria) {
        log.info("🗑️ [BFF] Removendo KM ID: {} da concessionária: {}", id, concessionaria);
        radarsBFFService.deletarKm(id, concessionaria);
        return ResponseEntity.noContent().build();
    }
}
