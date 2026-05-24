package com.coruja.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
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

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Variáveis de controle de Cache do Token
    private String currentToken = null;
    private LocalDateTime tokenExpirationTime = null;

    private volatile LocalDateTime mainframeBloqueadoAte = null;

    private static final Object TOKEN_LOCK = new Object();

    /**
     * Construtor: Inicializa o RestTemplate com proteção contra Timeout infinito.
     */
    public DetranService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); // 3 segundos máximo para handshake
        factory.setReadTimeout(5000);    // 5 segundos máximo esperando payload do veículo

        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Obtém o token. Reutiliza o token salvo em memória se ainda estiver dentro da validade.
     */
    private String obterToken() {
        // Primeiro Check (Sem Lock): Se o token está válido, devolve imediatamente em alta performance
        if (currentToken != null && tokenExpirationTime != null && LocalDateTime.now().isBefore(tokenExpirationTime.minusMinutes(1))) {
            return currentToken;
        }

        // Segundo Check (Com Lock): Se precisa gerar, as threads entram em fila organizada aqui
        synchronized (TOKEN_LOCK) {
            // Double-Checked Locking Pattern: A primeira thread da fila gera o token.
            // As próximas que entrarem aqui vão ler o token já gerado pela primeira e pular o bloco POST.
            if (currentToken != null && tokenExpirationTime != null && LocalDateTime.now().isBefore(tokenExpirationTime.minusMinutes(1))) {
                return currentToken;
            }

            log.info("🔑 [Detran - Concorrência] Thread autorizada a gerar novo token na API do Detran (IDP.SP.GOV.BR)...");
            try {
                String safeClientId = clientId != null ? clientId.trim() : "";
                String safeClientSecret = clientSecret != null ? clientSecret.trim() : "";

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

                        log.info("✅ [Detran] Novo token gerado com sucesso global pelas Virtual Threads. Válido por {} segundos.", expiresIn);
                        return currentToken;
                    }
                } else {
                    log.error("❌ Erro HTTP ao gerar token na API do Detran: Status {} - Resposta: {}", response.statusCode(), response.body());
                }
            } catch (Exception e) {
                log.error("❌ Erro crítico na requisição concorrente do token do Detran: {}", e.getMessage());
            }
            return null;
        }
    }

    /**
     * Consulta os dados de um veículo de forma híbrida e resiliente.
     */
    @Cacheable(value = "cache-detran-placas", key = "#placa", unless = "#result == null")
    public JsonNode consultarVeiculo(String placa) {
        LocalDateTime bloqueioAtual = this.mainframeBloqueadoAte;

        if (bloqueioAtual != null) {
            if (LocalDateTime.now().isBefore(bloqueioAtual)) {
                log.warn("🛑 Consulta ignorada para {}: Mainframe do Detran em período de bloqueio por instabilidade.", placa);
                return null;
            } else {
                this.mainframeBloqueadoAte = null;
            }
        }

        String token = obterToken();
        if (token == null) {
            log.warn("❌ Falha na autenticação: Não foi possível obter o token do Detran para consultar a placa {}", placa);
            return null;
        }

        // 1ª Tentativa: Base do Estado de São Paulo (v3)
        try {
            log.info("📡 [Detran] Tentando Base SP (v3) para placa: {}", placa);
            return realizarConsultaGet(placa, "/v3/dados", token);
        } catch (Exception e) {
            log.warn("⚠️ Falha na base SP (v3) para a placa {}: {}. Roteando para a Base Nacional (v1)...", placa, e.getMessage());

            // Se o token foi rejeitado na v3, limpa para a próxima execução por segurança
            verificarSeTokenExpirou(e);
        }

        // 2ª Tentativa: Base Nacional (v1) - Acionada se a v3 der 404, Timeout ou 500 do governo
        try {
            log.info("📡 [Detran] Tentando Base Nacional (v1) para placa: {}", placa);
            return realizarConsultaGet(placa, "/v1/dados", token);
        } catch (Exception e) {
            // Se ambas as bases falharem, centraliza e ativa o circuito de proteção se necessário
            return tratarErroFinalConsulta(placa, e);
        }
    }

    private void verificarSeTokenExpirou(Exception e) {
        if (e instanceof HttpStatusCodeException && ((HttpStatusCodeException) e).getStatusCode() == HttpStatus.UNAUTHORIZED) {
            log.warn("🔄 Token do Detran rejeitado (401). Forçando renovação no próximo ciclo.");
            this.currentToken = null;
        }
    }

    /**
     * Realiza o envio da requisição HTTP GET de forma segura.
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

        if (responseBody != null) {
            // Se for Lista (Padrão v3 SP)
            if (responseBody.isArray() && !responseBody.isEmpty()) {
                return responseBody.get(0);
            }
            // Se for Objeto direto (Padrão v1 Nacional)
            else if (responseBody.isObject()) {
                return responseBody;
            }
        }

        log.warn("⚠️ Formato de resposta inesperado do Detran para a placa {}: {}", placa, responseBody);
        return null;
    }

    /**
     * Centraliza o tratamento final das falhas após esgotar as duas bases de dados.
     */
    private JsonNode tratarErroFinalConsulta(String placa, Exception e) {
        if (e instanceof HttpStatusCodeException) {
            HttpStatusCodeException httpError = (HttpStatusCodeException) e;

            if (httpError.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.info("ℹ️ Placa {} não encontrada em nenhuma das bases do Detran (SP ou Nacional).", placa);
                return null;
            }

            if (httpError.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                this.currentToken = null;
            }

            log.error("❌ Detran retornou erro HTTP {} para a placa {}: {}", httpError.getStatusCode(), placa, httpError.getResponseBodyAsString());
        } else if (e instanceof ResourceAccessException) {
            log.error("🔌 Timeout ou falha física de rede ao conectar na API do Detran para a placa {}: {}", placa, e.getMessage());
        } else {
            log.error("❌ Erro inesperado na API do Detran para a placa {}: {}", placa, e.getMessage());
        }

        // Ativa o circuito de proteção se o governo de SP e o Nacional caírem juntos ou derem Timeout
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("500") || msg.contains("timeout") || msg.contains("connection") || e instanceof ResourceAccessException) {
            log.warn("🚨 API do Detran instável ou indisponível. Suspendendo novas chamadas externas por 1 minuto.");
            this.mainframeBloqueadoAte = LocalDateTime.now().plusMinutes(1);
        }

        return null;
    }
}