package com.coruja.services;

import com.coruja.dto.RadarDTO;
import com.coruja.exceptions.DetranIndisponivelException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class AnaliseBFFService {

    @Autowired
    @Qualifier("analysisRestTemplate")
    private RestTemplate analysisRestTemplate;

    @Autowired
    private DetranService detranService;

    @Autowired
    private ObjectMapper objectMapper;

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

            ResponseEntity<JsonNode> response = analysisRestTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            JsonNode resultados = response.getBody();

            // Aplica o enriquecimento inteligente também na busca automática
            return enriquecerComDadosDoDetran(resultados);

        } catch (DetranIndisponivelException e) {
            log.warn("⚠️ [BFF] Análise abortada: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("❌ [BFF] Erro ao comunicar com o serviço de Análise: {}", e.getMessage());
            throw new RuntimeException("Falha ao analisar comboio: " + e.getMessage());
        }
    }

    public JsonNode buscarComboioPorPassagens(JsonNode requestBody) {
        log.info("🎯 [BFF] Repassando JSON estruturado do React diretamente para o Quarkus...");

        String url = "http://" + ANALISE_SERVICE_NAME + "/analise/comboio/passagens";

        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            String authHeader = request.getHeader("Authorization");

            HttpHeaders headers = new HttpHeaders();
            if (authHeader != null) headers.set("Authorization", authHeader);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 💡 A SOLUÇÃO FINAL: Como o React já manda os campos "data" e "hora" corretos,
            // passamos o JsonNode direto. O RestTemplate faz a serialização JSON nativa perfeita!
            HttpEntity<JsonNode> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<JsonNode> response = analysisRestTemplate.exchange(url, HttpMethod.POST, entity, JsonNode.class);
            JsonNode resultados = response.getBody();

            return enriquecerComDadosDoDetran(resultados);

        } catch (DetranIndisponivelException e) {
            // O interceptador do Spring vai pegar essa exceção e retornar o HTTP 503 pro frontend
            log.warn("⚠️ [BFF] Análise de passagens abortada: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("❌ [BFF] Erro genérico ao comunicar com o serviço de Análise Inteligente: {}", e.getMessage());
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
                                try {
                                    JsonNode dadosDetran = detranService.consultarVeiculo(placaSuspeita);
                                    if (dadosDetran != null) {
                                        objNode.put("marcaModelo", extrairCampoHibrido(dadosDetran, "marca"));
                                        objNode.put("cor", extrairCampoHibrido(dadosDetran, "cor"));
                                        objNode.put("municipio", extrairCampoHibrido(dadosDetran, "municipio"));
                                        objNode.put("uf", extrairCampoHibrido(dadosDetran, "uf"));
                                        objNode.put("anoModelo", extrairCampoHibrido(dadosDetran, "anoModelo"));
                                        objNode.put("nomeProprietario", extrairCampoHibrido(dadosDetran, "proprietario", "nome"));
                                    } else {
                                        preencherComNaoInformado(objNode);
                                    }
                                } catch (DetranIndisponivelException e) {
                                    // Se o DetranService lançar a exceção, capturamos aqui dentro da thread
                                    // Preenchemos com "N/I" para não perder os dados vitais do radar (Quarkus)
                                    preencherComNaoInformado(objNode);
                                }
                            }, executor));
                        }
                    }
                }

                // Agora o .join() não vai mais estourar por causa do Detran,
                // pois tratamos a exceção dentro da tarefa assíncrona.
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                } catch (CompletionException e) {
                    log.error("Erro inesperado durante processamento paralelo das placas: {}", e.getMessage());
                    // Se for um erro crítico de infraestrutura (falta de memória, etc), lançamos.
                    throw e;
                }
            }
        }
        return resultados;
    }

    private void preencherComNaoInformado(ObjectNode objNode) {
        objNode.put("marcaModelo", "N/I");
        objNode.put("cor", "N/I");
        objNode.put("municipio", "N/I");
        objNode.put("uf", "N/I");
        objNode.put("anoModelo", "N/I");
        objNode.put("nomeProprietario", "N/I");
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
