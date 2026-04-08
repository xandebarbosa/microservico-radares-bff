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

@Service
@Slf4j
public class AnaliseBFFService {

    @Autowired
    private RestTemplate loadBalancedRestTemplate;

    @Autowired
    private DetranService detranService;

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
            JsonNode resultados = response.getBody();
            return enriquecerComDadosDoDetran(resultados);
            //return resultados;

        } catch (Exception e) {
            log.error("❌ [BFF] Erro ao comunicar com o serviço de Análise: {}", e.getMessage());
            throw new RuntimeException("Falha ao analisar comboio avançado: " + e.getMessage());
        }
    }

    /**
     * Método auxiliar que recebe a lista de suspeitos da IA e injeta os dados do Detran
     */
    private JsonNode enriquecerComDadosDoDetran(JsonNode resultados) {
        if (resultados != null && resultados.isArray()) {
            for (JsonNode node : resultados) {
                if (node instanceof ObjectNode objNode) {
                    String placaSuspeita = objNode.has("placa") ? objNode.get("placa").asText() : null;

                    if (placaSuspeita != null) {
                        JsonNode dadosDetran = detranService.consultarVeiculo(placaSuspeita);

                        if (dadosDetran != null) {
                            // Navega nos nós internos garantindo que não vai dar NullPointerException
                            String marcaModelo = dadosDetran.hasNonNull("marca") && dadosDetran.get("marca").hasNonNull("descricao")
                                    ? dadosDetran.get("marca").get("descricao").asText()
                                    : "N/I";

                            String cor = dadosDetran.hasNonNull("cor") && dadosDetran.get("cor").hasNonNull("descricao")
                                    ? dadosDetran.get("cor").get("descricao").asText()
                                    : "N/I";

                            String municipio = dadosDetran.hasNonNull("municipio") && dadosDetran.get("municipio").hasNonNull("nome")
                                    ? dadosDetran.get("municipio").get("nome").asText()
                                    : "N/I";

                            objNode.put("marcaModelo", marcaModelo);
                            objNode.put("cor", cor);
                            objNode.put("municipio", municipio);
                        } else {
                            objNode.put("marcaModelo", "Não Encontrado");
                            objNode.put("cor", "Não Encontrado");
                            objNode.put("municipio", "Não Encontrado");
                        }
                    }
                }
            }
        }
        return resultados;
    }
}
