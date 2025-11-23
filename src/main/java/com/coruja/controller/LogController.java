package com.coruja.controller;

import com.coruja.services.LogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Controller para busca de logs no Elasticsearch.
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {
    private final LogService logService;

    @Autowired
    public LogController(LogService logService) {
        this.logService = logService;
    }

    /**
     * Busca logs no Elasticsearch com paginação.
     * @param query Query de busca (padrão: "*" para todos).
     * @param page Número da página (padrão: 0).
     * @param size Tamanho da página (padrão: 50).
     * @return Lista de logs encontrados.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('admin')")
    public ResponseEntity<Object> searchLogs(
            @RequestParam(defaultValue = "*") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        List<Map<String, Object>> result = (List<Map<String, Object>>) logService.searchLogs(query, page, size);
        return ResponseEntity.ok(result);
    }
}
