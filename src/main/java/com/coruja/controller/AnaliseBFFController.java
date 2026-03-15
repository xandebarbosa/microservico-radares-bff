package com.coruja.controller;

import com.coruja.services.AnaliseBFFService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/analise")
public class AnaliseBFFController {

    @Autowired
    private AnaliseBFFService analiseBFFService;

    @GetMapping("/comboio")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')") // Mantém a sua segurança JWT ativa!
    public ResponseEntity<JsonNode> analisarComboio(
            @RequestParam String placaAlvo,
            @RequestParam String data,
            @RequestParam(defaultValue = "30") int tempoMinutos) {

        JsonNode resultado = analiseBFFService.buscarComboio(placaAlvo, data, tempoMinutos);
        return ResponseEntity.ok(resultado);
    }

    @PostMapping("/comboio/passagens")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<JsonNode> analisarComboioAvancado(@RequestBody JsonNode requestBody) {
        JsonNode resultado = analiseBFFService.buscarComboioPorPassagens(requestBody);
        return ResponseEntity.ok(resultado);
    }
}
