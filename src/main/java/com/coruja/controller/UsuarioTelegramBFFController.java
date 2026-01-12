package com.coruja.controller;

import com.coruja.dto.UsuarioTelegramDTO;
import com.coruja.services.MonitoramentoBFFService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/usuarios-telegram")
@RequiredArgsConstructor
@Slf4j
public class UsuarioTelegramBFFController {
    private final MonitoramentoBFFService monitoramentoBFFService;

    /**
     * Endpoint para o Frontend listar usuários para preencher combos/selects.
     * Rota final: GET /usuarios-telegram
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<List<UsuarioTelegramDTO>> listar() {
        return ResponseEntity.ok(monitoramentoBFFService.listarUsuariosTelegram());
    }

    /**
     * Endpoint para o Frontend forçar a atualização (botão "Sincronizar").
     * Rota final: GET /usuarios-telegram/sincronizar
     */
    @GetMapping("/sincronizar")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<List<UsuarioTelegramDTO>> sincronizar() {
        log.info("Requisição de sincronização de usuários do Telegram recebida pelo BFF.");
        List<UsuarioTelegramDTO> usuariosAtualizados = monitoramentoBFFService.sincronizarUsuariosTelegram();
        return ResponseEntity.ok(usuariosAtualizados);
    }
}
