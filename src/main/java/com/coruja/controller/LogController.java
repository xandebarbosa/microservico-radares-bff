package com.coruja.controller;

import com.coruja.dto.LogPageResponseDTO;
import com.coruja.dto.LogStatsDTO;
import com.coruja.services.LogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller REST profissional para gerenciamento, consulta e métricas de logs do Elasticsearch/EFK.
 */
@RestController
@RequestMapping("/logs")
@RequiredArgsConstructor
@Slf4j
public class LogController {

    private final LogService logService;

    /**
     * Busca estruturada de logs com filtros flexíveis e paginação.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<LogPageResponseDTO> searchLogs(
            @RequestParam(required = false, defaultValue = "*") String query,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false, defaultValue = "24h") String timeRange,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size
    ) {
        log.debug("📋 [LogController] Busca de logs: query='{}', level='{}', service='{}', range='{}', page={}",
                query, level, service, timeRange, page);

        LogPageResponseDTO result = logService.searchLogs(query, level, service, startDate, endDate, timeRange, page, size);
        return ResponseEntity.ok(result);
    }

    /**
     * Retorna métricas consolidadas, contadores por severidade e dados para gráficos temporais.
     */
    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<LogStatsDTO> getLogStats(
            @RequestParam(required = false, defaultValue = "24h") String timeRange,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate
    ) {
        log.debug("📊 [LogController] Estatísticas de logs para range='{}', service='{}'", timeRange, service);
        LogStatsDTO stats = logService.getLogStats(timeRange, service, query, startDate, endDate);
        return ResponseEntity.ok(stats);
    }

    /**
     * Retorna a lista de microsserviços ativos identificados nos logs do Elasticsearch.
     */
    @GetMapping("/services")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<List<String>> getAvailableServices() {
        return ResponseEntity.ok(logService.getAvailableServices());
    }

    /**
     * Retorna a URL externa do Kibana para navegação avançada no dashboard oficial.
     */
    @GetMapping("/kibana-url")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<Map<String, String>> getKibanaUrl() {
        return ResponseEntity.ok(Map.of("url", logService.getKibanaUrl()));
    }
}
