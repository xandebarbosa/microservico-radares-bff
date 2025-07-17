package com.coruja.controller;

import com.coruja.dto.AlertaPassagemDTO;
import com.coruja.dto.PlacaMonitoradaDTO;
import com.coruja.services.MonitoramentoBFFService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/monitoramento")
@RequiredArgsConstructor
public class MonitoramentoBFFController {

    private final MonitoramentoBFFService service;

    @GetMapping
    public Mono<ResponseEntity<Page<PlacaMonitoradaDTO>>> listar(Pageable pageable) {
        return service.listarMonitorados(pageable)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<PlacaMonitoradaDTO>> buscarPorId(@PathVariable Long id) {
        return service.buscarPorId(id)
                .map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<PlacaMonitoradaDTO>> criar(@RequestBody PlacaMonitoradaDTO dto) {
        return service.criarMonitorado(dto)
                .map(savedDto -> ResponseEntity.status(HttpStatus.CREATED).body(savedDto));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<PlacaMonitoradaDTO>> atualizar(@PathVariable Long id, @RequestBody PlacaMonitoradaDTO dto) {
        return service.atualizarMonitorado(id, dto)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deletar(@PathVariable Long id) {
        return service.deletarMonitorado(id)
                .thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * NOVO: Endpoint para listar o histórico de passagens que geraram alerta.
     */
    @GetMapping("/alertas")
    public Mono<ResponseEntity<Page<AlertaPassagemDTO>>> listarAlertas(Pageable pageable) {
        return service.listarAlertas(pageable)
                .map(ResponseEntity::ok);
    }
}
