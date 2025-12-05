package com.coruja.controller;

import com.coruja.dto.AlertaPassagemDTO;
import com.coruja.dto.PlacaMonitoradaDTO;
import com.coruja.services.MonitoramentoBFFService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Controller BFF para operações relacionadas ao monitoramento de placas.
 */

@RestController
@RequestMapping("/monitoramento")
@RequiredArgsConstructor
public class MonitoramentoBFFController {

    private final MonitoramentoBFFService service;

    /**
     * Lista todas as placas monitoradas com paginação.
     * @param pageable Parâmetros de paginação (page, size, sort).
     * @return Página com as placas monitoradas.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<Page<PlacaMonitoradaDTO>> listar(Pageable pageable) {
        Page<PlacaMonitoradaDTO> result = (Page<PlacaMonitoradaDTO>) service.listarMonitorados(pageable);
        return ResponseEntity.ok(result);
    }

    /**
     * Busca uma placa monitorada específica por ID.
     * @param id ID da placa monitorada.
     * @return Dados da placa monitorada.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<PlacaMonitoradaDTO> buscarPorId(@PathVariable Long id) {
        PlacaMonitoradaDTO result = service.buscarPorId(id);
        return ResponseEntity.ok(result);
    }

    /**
     * Cria um novo registro de placa monitorada.
     * @param dto Dados da placa a ser monitorada.
     * @return A placa monitorada criada com status 201 (CREATED).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<PlacaMonitoradaDTO> criar(@RequestBody PlacaMonitoradaDTO dto) {
        PlacaMonitoradaDTO result = service.criarMonitorado(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Atualiza uma placa monitorada existente.
     * @param id ID da placa a ser atualizada.
     * @param dto Novos dados da placa monitorada.
     * @return A placa monitorada atualizada.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<PlacaMonitoradaDTO> atualizar(
            @PathVariable Long id,
            @RequestBody PlacaMonitoradaDTO dto
    ) {
        PlacaMonitoradaDTO result = service.atualizarMonitorado(id, dto);
        return ResponseEntity.ok(result);
    }

    /**
     * Remove uma placa monitorada.
     * @param id ID da placa a ser removida.
     * @return Status 204 (NO_CONTENT) indicando sucesso na exclusão.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<Void> deletar(@PathVariable Long id) {
        service.deletarMonitorado(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Lista o histórico de passagens que geraram alertas (com paginação).
     * @param pageable Parâmetros de paginação (page, size, sort).
     * @return Página com os alertas de passagem.
     */
    @GetMapping("/alertas")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public ResponseEntity<Page<AlertaPassagemDTO>> listarAlertas(Pageable pageable) {
        Page<AlertaPassagemDTO> result = service.listarAlertas(pageable);
        return ResponseEntity.ok(result);
    }
}
