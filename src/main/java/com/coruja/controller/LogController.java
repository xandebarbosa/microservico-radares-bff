package com.coruja.controller;

import com.coruja.services.LogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/logs")
public class LogController {
    private final LogService logService;

    @Autowired
    public LogController(LogService logService) {
        this.logService = logService;
    }

    @GetMapping("/search")
    public Mono<ResponseEntity<Object>> searchLogs(
            @RequestParam(defaultValue = "*") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        // Retorna um Object para simplificar, mas o ideal seria um DTO de página de logs
        return logService.searchLogs(query, page, size)
                .map(ResponseEntity::ok);
    }
}
