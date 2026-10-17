package com.coruja.services;

import com.coruja.exceptions.DetranIndisponivelException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class DetranService {

    // ─── 4 Rotas possíveis da API externa do Detran ─────────────────────────────
    private static final List<String> ENDPOINTS_DETRAN = List.of(
            "/v1/dados",
            "/v2/dados",
            "/v3/dados",
            "/v4/dados"
    );

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

    private final HttpClient httpClient;

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

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
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

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(authUrl))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

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
     * Consulta os dados de um veículo percorrendo todas as rotas possíveis (/v1/dados, /v2/dados, /v3/dados, /v4/dados)
     * até localizar a placa pesquisada ou esgotar as rotas.
     */
    @Cacheable(value = "cache-detran-placas", key = "#placa", unless = "#result == null")
    public JsonNode consultarVeiculo(String placa) {
        if (placa == null || placa.isBlank()) {
            return null;
        }

        String placaFormatada = placa.trim().toUpperCase();

        LocalDateTime bloqueioAtual = this.mainframeBloqueadoAte;
        if (bloqueioAtual != null) {
            if (LocalDateTime.now().isBefore(bloqueioAtual)) {
                log.warn("🛑 Consulta ignorada para {}: Mainframe do Detran em período de bloqueio por instabilidade.", placaFormatada);
                throw new DetranIndisponivelException("O serviço do Detran encontra-se instável e as consultas estão pausadas. Tente novamente em instantes.");
            } else {
                this.mainframeBloqueadoAte = null;
            }
        }

        String token = obterToken();
        if (token == null) {
            log.warn("❌ Falha na autenticação: Não foi possível obter o token do Detran para consultar a placa {}", placaFormatada);
            throw new DetranIndisponivelException("Não foi possível autenticar com o provedor de dados estadual no momento.");
        }

        int falhasServidorOuTimeout = 0;
        Exception ultimaExcecao = null;

        // Itera por todas as 4 rotas da API externa até localizar os dados do veículo
        for (String endpoint : ENDPOINTS_DETRAN) {
            try {
                log.info("📡 [Detran] Consultando rota [{}] para placa: {}", endpoint, placaFormatada);
                JsonNode dados = realizarConsultaGet(placaFormatada, endpoint, token);

                if (dados != null && !dados.isEmpty()) {
                    log.info("✅ [Detran] Placa {} localizada com sucesso na rota [{}]!", placaFormatada, endpoint);
                    return dados;
                } else {
                    log.debug("ℹ️ [Detran] Placa {} não encontrada na rota [{}]. Tentando próxima...", placaFormatada, endpoint);
                }
            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                    log.warn("🔄 [Detran] Token expirado ou rejeitado (401) na rota [{}]. Renovando token...", endpoint);
                    synchronized (TOKEN_LOCK) {
                        this.currentToken = null;
                        this.tokenExpirationTime = null;
                    }
                    token = obterToken();
                    if (token != null) {
                        try {
                            JsonNode dados = realizarConsultaGet(placaFormatada, endpoint, token);
                            if (dados != null && !dados.isEmpty()) {
                                log.info("✅ [Detran] Placa {} localizada com sucesso após renovação do token na rota [{}]!", placaFormatada, endpoint);
                                return dados;
                            }
                        } catch (Exception ex) {
                            log.warn("⚠️ [Detran] Falha na segunda tentativa com novo token em [{}]: {}", endpoint, ex.getMessage());
                        }
                    }
                } else if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                    log.debug("ℹ️ [Detran] 404 Not Found na rota [{}] para placa: {}", endpoint, placaFormatada);
                } else if (e.getStatusCode().is5xxServerError()) {
                    falhasServidorOuTimeout++;
                    ultimaExcecao = e;
                    log.warn("⚠️ [Detran] Erro HTTP {} na rota [{}] para placa {}. Continuando busca nas outras rotas...", e.getStatusCode(), endpoint, placaFormatada);
                } else {
                    log.warn("⚠️ [Detran] Erro HTTP {} na rota [{}] para placa {}: {}", e.getStatusCode(), endpoint, placaFormatada, e.getMessage());
                }
            } catch (ResourceAccessException e) {
                falhasServidorOuTimeout++;
                ultimaExcecao = e;
                log.warn("⏱️ [Detran] Timeout ou falha física de rede na rota [{}] para placa {}. Continuando busca nas outras rotas...", endpoint, placaFormatada);
            } catch (Exception e) {
                log.warn("⚠️ [Detran] Erro inesperado na rota [{}] para placa {}: {}", endpoint, placaFormatada, e.getMessage());
            }
        }

        // Se todas as 4 rotas falharam por indisponibilidade / timeout do servidor
        if (falhasServidorOuTimeout >= ENDPOINTS_DETRAN.size() && ultimaExcecao != null) {
            log.warn("🚨 [Detran] Todas as 4 rotas do Detran falharam por indisponibilidade/timeout. Ativando circuito de proteção por 1 minuto.");
            this.mainframeBloqueadoAte = LocalDateTime.now().plusMinutes(1);
            throw new DetranIndisponivelException("O serviço do Detran encontra-se indisponível no momento. Tente novamente mais tarde.");
        }

        log.info("ℹ️ [Detran] Placa {} não localizada em nenhuma das rotas consultadas {}.", placaFormatada, ENDPOINTS_DETRAN);
        return null;
    }

    /**
     * Realiza o envio da requisição HTTP GET de forma segura, suportando ambos os formatos de resposta:
     * - Payload como Array JSON: [ { ... } ]
     * - Payload como Objeto JSON direto: { ... }
     */
    private JsonNode realizarConsultaGet(String placa, String endpoint, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Accept", "application/json");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String urlBaseLimpa = baseUrl != null && baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;

        String urlConsultarPlaca = UriComponentsBuilder
                .fromHttpUrl(urlBaseLimpa + endpoint)
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
            // Formato 1: Array [...] (Ex: Payloads 1 e 2 fornecidos pelo Detran)
            if (responseBody.isArray()) {
                if (!responseBody.isEmpty()) {
                    JsonNode primeiroItem = responseBody.get(0);
                    if (primeiroItem != null && primeiroItem.isObject() && !primeiroItem.isEmpty()) {
                        return primeiroItem;
                    }
                }
                return null; // Lista vazia [] significa não encontrado
            }
            // Formato 2: Objeto direto { ... }
            else if (responseBody.isObject() && !responseBody.isEmpty()) {
                return responseBody;
            }
        }

        return null;
    }
}
