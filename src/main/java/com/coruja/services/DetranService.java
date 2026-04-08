package com.coruja.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Service
@Slf4j
public class DetranService {

    @Value("${detran.api.base-url}")
    private String baseUrl;

    @Value("${detran.api.auth-url}")
    private String authUrl;

    @Value("${detran.api.client-id}")
    private String clientId;

    @Value("${detran.api.client-secret}")
    private String clientSecret;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Variáveis de controle de Cache do Token
    private String currentToken = null;
    private LocalDateTime tokenExpirationTime = null;

    /**
     * Obtém o token. Reutiliza o token salvo em memória se ainda estiver dentro do tempo de validade.
     */
    private synchronized String obterToken() {
        if (currentToken != null && tokenExpirationTime != null && LocalDateTime.now().isBefore(tokenExpirationTime.minusMinutes(1))) {
            return currentToken;
        }

        log.info("🔑 Gerando novo token na API do Detran (IDP.SP.GOV.BR)...");
        try {
            String safeClientId = clientId != null ? clientId.trim() : "";
            String safeClientSecret = clientSecret != null ? clientSecret.trim() : "";

            // O novo Keycloak (idp.sp.gov.br) precisa do grant_type e das credenciais no formato form-urlencoded
            String formBody = "grant_type=client_credentials" +
                    "&client_id=" + URLEncoder.encode(safeClientId, StandardCharsets.UTF_8) +
                    "&client_secret=" + URLEncoder.encode(safeClientSecret, StandardCharsets.UTF_8) +
                    "&scope=" + URLEncoder.encode("api:detran.veiculos.search", StandardCharsets.UTF_8);

            HttpClient client = HttpClient.newHttpClient();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(authUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode jsonNode = objectMapper.readTree(response.body());
                if (jsonNode.has("access_token")) {
                    currentToken = jsonNode.get("access_token").asText();
                    int expiresIn = jsonNode.has("expires_in") ? jsonNode.get("expires_in").asInt() : 3600;
                    tokenExpirationTime = LocalDateTime.now().plusSeconds(expiresIn);

                    log.info("✅ Novo token do Detran gerado com sucesso. Válido por {} segundos.", expiresIn);
                    return currentToken;
                }
            } else {
                log.error("❌ Erro HTTP ao gerar token na API do Detran: Status {} - Resposta: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("❌ Erro na requisição do token do Detran: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Consulta os dados de um veículo específico na API do Detran.
     */
    public JsonNode consultarVeiculo(String placa) {
        String token = obterToken();
        if (token == null) {
            log.warn("Falha na autenticação: Não foi possível obter o token do Detran para consultar a placa {}", placa);
            return null;
        }

        try {
            // 1ª Tentativa: Base do Estado de São Paulo (v3)
            return realizarConsultaGet(placa, "/v3/dados", token);

        } catch (HttpClientErrorException.NotFound e) {
            log.info("Placa {} não encontrada na base SP (v3). Buscando na base Nacional (v1)...", placa);

            try {
                // 2ª Tentativa: Base Nacional - Outros Estados (v1)
                return realizarConsultaGet(placa, "/v1/dados", token);

            } catch (Exception ex) {
                return tratarErroConsulta(placa, ex);
            }

        } catch (Exception e) {
            return tratarErroConsulta(placa, e);
        }
    }

    /**
     * Método auxiliar para evitar repetição de código na montagem e envio da requisição GET
     */
    private JsonNode realizarConsultaGet(String placa, String endpoint, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Accept", "application/json");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String urlConsultarPlaca = UriComponentsBuilder
                .fromHttpUrl(baseUrl + endpoint)
                .queryParam("placa", placa)
                .toUriString();

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                urlConsultarPlaca,
                HttpMethod.GET,
                entity,
                JsonNode.class
        );

        JsonNode responseBody = response.getBody();

        // Retorna o primeiro objeto do Array
        if (responseBody != null && responseBody.isArray() && !responseBody.isEmpty()) {
            return responseBody.get(0);
        }

        return null;
    }

    /**
     * Centraliza o tratamento de erros HTTP e renovação do Token
     */
    private JsonNode tratarErroConsulta(String placa, Exception e) {
        // Se for um Erro 404 da Base Nacional, apenas logamos como Info em vez de Error
        if (e instanceof HttpClientErrorException.NotFound) {
            log.info("Placa {} não encontrada em nenhuma das bases do Detran (SP ou Nacional).", placa);
            return null;
        }

        log.error("Erro ao buscar dados da placa {} no Detran: {}", placa, e.getMessage());

        // Se a API do Detran rejeitar o token (401), invalidamos ele para forçar a geração de um novo
        if (e.getMessage() != null && e.getMessage().contains("401")) {
            log.warn("⚠️ Token do Detran invalidado. Forçando renovação.");
            this.currentToken = null;
        }

        return null;
    }
}