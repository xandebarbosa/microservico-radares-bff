package com.coruja.services;

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
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpServletRequest request = attributes != null ? attributes.getRequest() : null;
            String authHeader = request != null ? request.getHeader("Authorization") : null;

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
        log.info("🎯 [BFF] Repassando JSON estruturado do React para o serviço de Análise Inteligente...");

        String url = "http://" + ANALISE_SERVICE_NAME + "/analise/comboio/passagens";

        try {
            // Sanitiza o requestBody para assegurar que locais, concessionária (Motiva/Pantanal) e dados de passagem estejam consistentes
            JsonNode payloadSanitizado = sanitizarRequestBodyPassagens(requestBody);

            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpServletRequest request = attributes != null ? attributes.getRequest() : null;
            String authHeader = request != null ? request.getHeader("Authorization") : null;

            HttpHeaders headers = new HttpHeaders();
            if (authHeader != null) headers.set("Authorization", authHeader);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<JsonNode> entity = new HttpEntity<>(payloadSanitizado, headers);

            ResponseEntity<JsonNode> response = analysisRestTemplate.exchange(url, HttpMethod.POST, entity, JsonNode.class);
            JsonNode resultados = response.getBody();

            return enriquecerComDadosDoDetran(resultados);

        } catch (DetranIndisponivelException e) {
            log.warn("⚠️ [BFF] Análise de passagens abortada: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("❌ [BFF] Erro genérico ao comunicar com o serviço de Análise Inteligente: {}", e.getMessage());
            throw new RuntimeException("Falha ao analisar comboio avançado: " + e.getMessage());
        }
    }

    /**
     * Sanitiza os nós de passagens para assegurar compatibilidade e precisão:
     * 1. Concessionária: normaliza "pantanal" para "Motiva".
     * 2. Praça e Rodovia: na Motiva, a praça/local é o identificador físico (ex: BURI, PEDRO GOMES).
     *    Se rodovia vier "N/I" e praça válida, sincroniza. Se praça vier "N/I" e rodovia válida, sincroniza.
     * 3. KM: remove placeholders inválidos ("N/I", "N/A", "null") para não poluir consultas de cruzamento.
     */
    private JsonNode sanitizarRequestBodyPassagens(JsonNode requestBody) {
        if (requestBody == null || !requestBody.isObject()) {
            return requestBody;
        }

        JsonNode passagensNode = requestBody.get("passagens");
        if (passagensNode != null && passagensNode.isArray()) {
            for (JsonNode pNode : passagensNode) {
                if (pNode instanceof ObjectNode objPassagem) {
                    // 1. Normaliza Concessionária
                    if (objPassagem.hasNonNull("concessionaria")) {
                        String conc = objPassagem.get("concessionaria").asText().trim();
                        if ("pantanal".equalsIgnoreCase(conc) || "motiva".equalsIgnoreCase(conc)) {
                            objPassagem.put("concessionaria", "Motiva");
                        }
                    }

                    // 2. Harmonização entre rodovia e praça
                    String rodovia = objPassagem.hasNonNull("rodovia") ? objPassagem.get("rodovia").asText().trim() : null;
                    String praca = objPassagem.hasNonNull("praca") ? objPassagem.get("praca").asText().trim() : null;

                    boolean rodoviaValida = isTextoValido(rodovia);
                    boolean pracaValida = isTextoValido(praca);

                    if (!rodoviaValida && pracaValida) {
                        objPassagem.put("rodovia", praca);
                    } else if (rodoviaValida && !pracaValida) {
                        objPassagem.put("praca", rodovia);
                    }

                    // 3. Sanitização de KM
                    if (objPassagem.hasNonNull("km")) {
                        String km = objPassagem.get("km").asText().trim();
                        if (!isTextoValido(km)) {
                            objPassagem.putNull("km");
                        }
                    }
                }
            }
        }
        return requestBody;
    }

    private boolean isTextoValido(String val) {
        if (val == null || val.isBlank()) return false;
        String v = val.trim();
        return !v.equalsIgnoreCase("N/I") &&
               !v.equalsIgnoreCase("N/A") &&
               !v.equalsIgnoreCase("null") &&
               !v.equalsIgnoreCase("undefined") &&
               !v.equalsIgnoreCase("DESCONHECIDO");
    }

    /**
     * Recebe a lista de suspeitos da IA e injeta os dados do Detran de forma reativa e híbrida,
     * garantindo também a normalização das concessionárias (ex: Motiva) e preenchimento de praças nos encontros.
     */
    private JsonNode enriquecerComDadosDoDetran(JsonNode resultados) {
        if (resultados != null && resultados.isArray()) {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (JsonNode node : resultados) {
                    if (node instanceof ObjectNode objNode) {
                        // Normaliza encontros dos suspeitos (concessionária Motiva e praça/rodovia)
                        normalizarEncontros(objNode);

                        String placaSuspeita = objNode.hasNonNull("placa") ? objNode.get("placa").asText() : null;

                        if (placaSuspeita != null && !placaSuspeita.isBlank()) {
                            futures.add(CompletableFuture.runAsync(() -> {
                                try {
                                    JsonNode dadosDetran = detranService.consultarVeiculo(placaSuspeita);
                                    if (dadosDetran != null) {
                                        objNode.put("marcaModelo", extrairCampoHibrido(dadosDetran, "marca"));
                                        objNode.put("cor", extrairCampoHibrido(dadosDetran, "cor"));
                                        objNode.put("municipio", extrairMunicipio(dadosDetran));
                                        objNode.put("uf", extrairCampoHibrido(dadosDetran, "uf"));
                                        objNode.put("anoModelo", extrairCampoHibrido(dadosDetran, "anoModelo"));
                                        objNode.put("nomeProprietario", extrairNomeProprietario(dadosDetran));
                                        objNode.put("cpfProprietario", extrairDocumentoProprietario(dadosDetran));
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
                    throw e;
                }
            }
        }
        return resultados;
    }

    /**
     * Normaliza os encontros dentro de cada suspeito para exibição perfeita no frontend:
     * - Garante "Motiva" caso venha "pantanal"
     * - Garante preenchimento de praça caso venha apenas como rodovia (padrão Motiva/Pantanal)
     */
    private void normalizarEncontros(ObjectNode objNode) {
        if (!objNode.hasNonNull("encontros")) return;
        JsonNode encontrosNode = objNode.get("encontros");
        if (encontrosNode.isArray()) {
            for (JsonNode eNode : encontrosNode) {
                if (eNode instanceof ObjectNode enc) {
                    if (enc.hasNonNull("concessionaria")) {
                        String conc = enc.get("concessionaria").asText().trim();
                        if ("pantanal".equalsIgnoreCase(conc) || "motiva".equalsIgnoreCase(conc)) {
                            enc.put("concessionaria", "Motiva");
                        }
                    }
                    String praca = enc.hasNonNull("praca") ? enc.get("praca").asText().trim() : "";
                    String rodovia = enc.hasNonNull("rodovia") ? enc.get("rodovia").asText().trim() : "";
                    if ((praca.isEmpty() || praca.equalsIgnoreCase("N/I")) && isTextoValido(rodovia)) {
                        enc.put("praca", rodovia);
                    }
                }
            }
        }
    }

    private String extrairNomeProprietario(JsonNode node) {
        if (node == null) return "N/I";
        if (node.hasNonNull("proprietario")) {
            JsonNode prop = node.get("proprietario");
            if (prop.isObject() && prop.hasNonNull("nome")) {
                return prop.get("nome").asText();
            } else if (prop.isTextual()) {
                return prop.asText();
            }
        }
        return "N/I";
    }

    private String extrairDocumentoProprietario(JsonNode node) {
        if (node == null) return "N/I";
        if (node.hasNonNull("proprietario")) {
            JsonNode prop = node.get("proprietario");
            if (prop.isObject() && prop.hasNonNull("numeroDocumento")) {
                return prop.get("numeroDocumento").asText();
            }
        }
        if (node.hasNonNull("proprietarioNumeroDocumento")) {
            return node.get("proprietarioNumeroDocumento").asText();
        }
        return "N/I";
    }

    private String extrairMunicipio(JsonNode node) {
        if (node == null || !node.hasNonNull("municipio")) return "N/I";
        JsonNode mun = node.get("municipio");
        if (mun.isObject()) {
            if (mun.hasNonNull("nome")) return mun.get("nome").asText();
            if (mun.hasNonNull("descricao")) return mun.get("descricao").asText();
        } else if (mun.isTextual()) {
            return mun.asText();
        }
        return "N/I";
    }

    private void preencherComNaoInformado(ObjectNode objNode) {
        objNode.put("marcaModelo", "N/I");
        objNode.put("cor", "N/I");
        objNode.put("municipio", "N/I");
        objNode.put("uf", "N/I");
        objNode.put("anoModelo", "N/I");
        objNode.put("nomeProprietario", "N/I");
        objNode.put("cpfProprietario", "N/I");
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
            return pai.asText();
        }
        return "N/I";
    }
}
