package com.coruja.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class AnaliseBFFService {

    @Autowired
    private RestTemplate loadBalancedRestTemplate;

    @Autowired
    private DetranService detranService;

    private static final String ANALISE_SERVICE_NAME = "MICROSERVICO-ANALISE-INTELIGENTE";

    public JsonNode buscarComboio(String placaAlvo, String data, int tempoMinutos) {
        log.info("📡 [BFF] Repassando análise de comboio automático para o serviço de inteligência...");

        String url = UriComponentsBuilder
                .fromHttpUrl("http://" + ANALISE_SERVICE_NAME + "/analise/comboio")
                .queryParam("placaAlvo", placaAlvo)
                .queryParam("data", data)
                .queryParam("tempo", tempoMinutos) // Ajustado para o nome do parâmetro esperado no Quarkus
                .toUriString();

        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            String authHeader = request.getHeader("Authorization");

            HttpHeaders headers = new HttpHeaders();
            if (authHeader != null) {
                headers.set("Authorization", authHeader);
            }
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<JsonNode> response = loadBalancedRestTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            JsonNode resultados = response.getBody();

            // Aplica o enriquecimento inteligente também na busca automática
            return enriquecerComDadosDoDetran(resultados);

        } catch (Exception e) {
            log.error("❌ [BFF] Erro ao comunicar com o serviço de Análise: {}", e.getMessage());
            throw new RuntimeException("Falha ao analisar comboio: " + e.getMessage());
        }
    }

    public JsonNode buscarComboioPorPassagens(JsonNode requestBody) {
        log.info("📡 [BFF] Repassando análise de comboio avançada (por passagens selecionadas)...");

        String url = "http://" + ANALISE_SERVICE_NAME + "/analise/comboio/passagens";

        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            String authHeader = request.getHeader("Authorization");

            HttpHeaders headers = new HttpHeaders();
            if (authHeader != null) headers.set("Authorization", authHeader);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<JsonNode> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<JsonNode> response = loadBalancedRestTemplate.exchange(url, HttpMethod.POST, entity, JsonNode.class);
            JsonNode resultados = response.getBody();

            return enriquecerComDadosDoDetran(resultados);

        } catch (Exception e) {
            log.error("❌ [BFF] Erro ao comunicar com o serviço de Análise: {}", e.getMessage());
            throw new RuntimeException("Falha ao analisar comboio avançado: " + e.getMessage());
        }
    }

    /**
     * Recebe a lista de suspeitos da IA e injeta os dados do Detran de forma reativa e híbrida
     */
    private JsonNode enriquecerComDadosDoDetran(JsonNode resultados) {
        if (resultados != null && resultados.isArray()) {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (JsonNode node : resultados) {
                    if (node instanceof ObjectNode objNode) {
                        String placaSuspeita = objNode.has("placa") ? objNode.get("placa").asText() : null;

                        if (placaSuspeita != null) {
                            futures.add(CompletableFuture.runAsync(() -> {
                                JsonNode dadosDetran = detranService.consultarVeiculo(placaSuspeita);

                                if (dadosDetran != null) {
                                    // Extração blindada que suporta tanto o JSON v3 (SP) quanto o v1 (Nacional)
                                    objNode.put("marcaModelo", extrairCampoHibrido(dadosDetran, "marca"));
                                    objNode.put("cor", extrairCampoHibrido(dadosDetran, "cor"));
                                    objNode.put("municipio", extrairCampoHibrido(dadosDetran, "municipio"));
                                    objNode.put("uf", extrairCampoHibrido(dadosDetran, "uf"));
                                    objNode.put("anoModelo", extrairCampoHibrido(dadosDetran, "anoModelo"));

                                    // Adicionando proprietário para análises mais profundas de frota
                                    objNode.put("nomeProprietario", extrairCampoHibrido(dadosDetran, "proprietario", "nome"));
                                } else {
                                    objNode.put("marcaModelo", "N/I");
                                    objNode.put("cor", "N/I");
                                    objNode.put("municipio", "N/I");
                                    objNode.put("uf", "N/I");
                                    objNode.put("anoModelo", "N/I");
                                    objNode.put("nomeProprietario", "N/I");
                                }
                            }, executor));
                        }
                    }
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
        }
        return resultados;
    }

    /**
     * Utilitário para lidar com o polimorfismo das respostas do Governo (v1 vs v3)
     */
    private String extrairCampoHibrido(JsonNode node, String campoPrincipal) {
        if (node == null || !node.hasNonNull(campoPrincipal)) return "N/I";

        JsonNode campo = node.get(campoPrincipal);
        if (campo.isObject() && campo.hasNonNull("descricao")) {
            return campo.get("descricao").asText();
        } else if (campo.isTextual() || campo.isNumber()) {
            return campo.asText();
        }
        return "N/I";
    }

    /**
     * Utilitário sobrecarregado para nós aninhados específicos (ex: proprietario.nome)
     */
    private String extrairCampoHibrido(JsonNode node, String nodoPai, String subCampo) {
        if (node == null || !node.hasNonNull(nodoPai)) return "N/I";

        JsonNode pai = node.get(nodoPai);
        if (pai.isObject() && pai.hasNonNull(subCampo)) {
            return pai.get(subCampo).asText();
        } else if (pai.isTextual()) {
            // Caso a v1 retorne o nome diretamente na string
            return pai.asText();
        }
        return "N/I";
    }
}
