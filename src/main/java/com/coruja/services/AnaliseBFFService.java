package com.coruja.services;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;

@Service
@Slf4j
public class AnaliseBFFService {

    @Autowired
    private RestTemplate loadBalancedRestTemplate;

    private static final String ANALISE_SERVICE_NAME = "MICROSERVICO-ANALISE-INTELIGENTE";

    public JsonNode buscarComboio(String placaAlvo, String data, int tempoMinutos) {
        log.info("🔍 [BFF] Repassando análise de comboio para o serviço de inteligência...");

        String url = UriComponentsBuilder
                .fromHttpUrl("http://" + ANALISE_SERVICE_NAME + "/analise/comboio")
                .queryParam("placaAlvo", placaAlvo)
                .queryParam("data", data)
                .queryParam("tempoMinutos", tempoMinutos)
                .toUriString();

        try {
            // Captura a requisição atual do FrontEnd/Postman para roubar o Token JWT
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            String authHeader = request.getHeader("Authorization");

            // Insere o Token JWT no cabeçalho da chamada para o microsserviço
            HttpHeaders headers = new HttpHeaders();
            if (authHeader != null) {
                headers.set("Authorization", authHeader);
            }
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Usa o método exchange em vez de getForObject para poder enviar os headers
            ResponseEntity<JsonNode> response = loadBalancedRestTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            return response.getBody();

        } catch (Exception e) {
            log.error("❌ [BFF] Erro ao comunicar com o serviço de Análise: {}", e.getMessage());
            throw new RuntimeException("Falha ao analisar comboio: " + e.getMessage());
        }
    }

    // Importe MediaType: import org.springframework.http.MediaType;

    public JsonNode buscarComboioPorPassagens(JsonNode requestBody) {
        log.info("🔍 [BFF] Repassando análise de comboio avançada (por passagens selecionadas)...");

        String url = "http://" + ANALISE_SERVICE_NAME + "/analise/comboio/passagens";

        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            String authHeader = request.getHeader("Authorization");

            HttpHeaders headers = new HttpHeaders();
            if (authHeader != null) headers.set("Authorization", authHeader);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Envia o corpo (a lista de passagens) e o Header JWT
            HttpEntity<JsonNode> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<JsonNode> response = loadBalancedRestTemplate.exchange(url, HttpMethod.POST, entity, JsonNode.class);
            return response.getBody();

        } catch (Exception e) {
            log.error("❌ [BFF] Erro ao comunicar com o serviço de Análise: {}", e.getMessage());
            throw new RuntimeException("Falha ao analisar comboio avançado: " + e.getMessage());
        }
    }
}
