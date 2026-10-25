package com.coruja.services;

import com.coruja.config.ElasticsearchConfig;
import com.coruja.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * Serviço de alta performance para consulta de logs e métricas no Elasticsearch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LogService {

    private final RestClient restClient;
    private final ElasticsearchConfig elasticsearchConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String INDEX_PATTERN = "spring_boot_app-*";

    /**
     * Busca paginada estruturada de logs com suporte a múltiplos filtros.
     */
    public LogPageResponseDTO searchLogs(
            String query,
            String level,
            String service,
            String startDate,
            String endDate,
            String timeRange,
            int page,
            int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(200, Math.max(1, size));
        int from = safePage * safeSize;

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("from", from);
            root.put("size", safeSize);

            // Sort por timestamp decrescente
            ArrayNode sortArray = root.putArray("sort");
            sortArray.addObject().putObject("@timestamp").put("order", "desc");

            // Query booleana com filtros
            ObjectNode boolQuery = buildBoolQuery(query, level, service, startDate, endDate, timeRange);
            root.set("query", boolQuery);

            String requestBody = objectMapper.writeValueAsString(root);

            Request request = new Request("POST", "/" + INDEX_PATTERN + "/_search");
            request.setJsonEntity(requestBody);

            Response response = restClient.performRequest(request);
            String responseBody = EntityUtils.toString(response.getEntity());
            JsonNode json = objectMapper.readTree(responseBody);

            JsonNode hitsNode = json.path("hits");
            long totalElements = hitsNode.path("total").path("value").asLong(0L);
            int totalPages = safeSize > 0 ? (int) Math.ceil((double) totalElements / safeSize) : 0;

            List<LogEntryDTO> content = new ArrayList<>();
            JsonNode hitsArray = hitsNode.path("hits");
            if (hitsArray.isArray()) {
                for (JsonNode hit : hitsArray) {
                    content.add(mapHitToLogEntry(hit));
                }
            }

            return LogPageResponseDTO.builder()
                    .content(content)
                    .pageNumber(safePage)
                    .pageSize(safeSize)
                    .totalElements(totalElements)
                    .totalPages(totalPages)
                    .hasNext(safePage + 1 < totalPages)
                    .hasPrevious(safePage > 0)
                    .build();

        } catch (Exception e) {
            log.error("❌ [LogService] Falha ao consultar logs no Elasticsearch: {}", e.getMessage());
            return LogPageResponseDTO.builder()
                    .content(Collections.emptyList())
                    .pageNumber(safePage)
                    .pageSize(safeSize)
                    .totalElements(0)
                    .totalPages(0)
                    .build();
        }
    }

    /**
     * Retorna estatísticas e agregações consolidadas para os gráficos do Dashboard.
     */
    public LogStatsDTO getLogStats(
            String timeRange,
            String service,
            String query,
            String startDate,
            String endDate
    ) {
        String effectiveRange = (timeRange != null && !timeRange.isBlank()) ? timeRange : "24h";

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("size", 0);

            // Filtro aplicado nas métricas
            ObjectNode boolQuery = buildBoolQuery(query, null, service, startDate, endDate, effectiveRange);
            root.set("query", boolQuery);

            // Agregações
            ObjectNode aggs = root.putObject("aggs");

            // 1. Distribuição por Nível (INFO, WARN, ERROR, DEBUG)
            aggs.putObject("by_level")
                    .putObject("terms")
                    .put("field", "level.keyword")
                    .put("size", 10);

            // 2. Distribuição por Microsserviço
            aggs.putObject("by_service")
                    .putObject("terms")
                    .put("field", "application_name.keyword")
                    .put("size", 20);

            // 3. Linha do Tempo (Histograma Temporal)
            ObjectNode timelineAgg = aggs.putObject("timeline");
            ObjectNode dateHist = timelineAgg.putObject("date_histogram");
            dateHist.put("field", "@timestamp");
            dateHist.put("min_doc_count", 0);

            switch (effectiveRange) {
                case "15m", "1h" -> dateHist.put("fixed_interval", "2m");
                case "24h" -> dateHist.put("fixed_interval", "1h");
                case "7d", "30d" -> dateHist.put("calendar_interval", "1d");
                default -> dateHist.put("fixed_interval", "1h");
            }

            // Sub-agregação para contar os níveis dentro de cada bucket de tempo
            timelineAgg.putObject("aggs")
                    .putObject("levels")
                    .putObject("terms")
                    .put("field", "level.keyword")
                    .put("size", 5);

            String requestBody = objectMapper.writeValueAsString(root);

            Request request = new Request("POST", "/" + INDEX_PATTERN + "/_search");
            request.setJsonEntity(requestBody);

            Response response = restClient.performRequest(request);
            String responseBody = EntityUtils.toString(response.getEntity());
            JsonNode json = objectMapper.readTree(responseBody);

            long total = json.path("hits").path("total").path("value").asLong(0L);

            // Parse by_level
            Map<String, Long> byLevel = new LinkedHashMap<>();
            long errorCount = 0;
            long warnCount = 0;
            long infoCount = 0;
            long debugCount = 0;

            JsonNode levelBuckets = json.path("aggregations").path("by_level").path("buckets");
            if (levelBuckets.isArray()) {
                for (JsonNode b : levelBuckets) {
                    String key = b.path("key").asText().toUpperCase();
                    long count = b.path("doc_count").asLong(0L);
                    byLevel.put(key, count);

                    switch (key) {
                        case "ERROR" -> errorCount += count;
                        case "WARN" -> warnCount += count;
                        case "INFO" -> infoCount += count;
                        case "DEBUG" -> debugCount += count;
                    }
                }
            }

            // Parse by_service
            List<ServiceCountDTO> byService = new ArrayList<>();
            JsonNode serviceBuckets = json.path("aggregations").path("by_service").path("buckets");
            if (serviceBuckets.isArray()) {
                for (JsonNode b : serviceBuckets) {
                    byService.add(new ServiceCountDTO(b.path("key").asText(), b.path("doc_count").asLong(0L)));
                }
            }

            // Parse timeline
            List<TimelinePointDTO> timeline = new ArrayList<>();
            JsonNode timeBuckets = json.path("aggregations").path("timeline").path("buckets");
            if (timeBuckets.isArray()) {
                for (JsonNode b : timeBuckets) {
                    String keyStr = b.path("key_as_string").asText();
                    long docCount = b.path("doc_count").asLong(0L);

                    long bError = 0, bWarn = 0, bInfo = 0, bDebug = 0;
                    JsonNode subLevels = b.path("levels").path("buckets");
                    if (subLevels.isArray()) {
                        for (JsonNode sl : subLevels) {
                            String lKey = sl.path("key").asText().toUpperCase();
                            long lCount = sl.path("doc_count").asLong(0L);
                            switch (lKey) {
                                case "ERROR" -> bError += lCount;
                                case "WARN" -> bWarn += lCount;
                                case "INFO" -> bInfo += lCount;
                                case "DEBUG" -> bDebug += lCount;
                            }
                        }
                    }

                    timeline.add(TimelinePointDTO.builder()
                            .timestamp(keyStr)
                            .label(formatTimelineLabel(keyStr, effectiveRange))
                            .total(docCount)
                            .error(bError)
                            .warn(bWarn)
                            .info(bInfo)
                            .debug(bDebug)
                            .build());
                }
            }

            // Busca os 5 erros mais recentes
            List<LogEntryDTO> recentErrors = fetchRecentErrors(service, effectiveRange);

            double errorRate = total > 0 ? ((double) errorCount / total) * 100.0 : 0.0;
            errorRate = Math.round(errorRate * 100.0) / 100.0; // 2 casas decimais

            return LogStatsDTO.builder()
                    .total(total)
                    .errorCount(errorCount)
                    .warnCount(warnCount)
                    .infoCount(infoCount)
                    .debugCount(debugCount)
                    .errorRate(errorRate)
                    .byLevel(byLevel)
                    .byService(byService)
                    .timeline(timeline)
                    .recentErrors(recentErrors)
                    .build();

        } catch (Exception e) {
            log.error("❌ [LogService] Falha ao coletar estatísticas de logs: {}", e.getMessage());
            return LogStatsDTO.builder().build();
        }
    }

    /**
     * Retorna a lista de nomes de microsserviços disponíveis para preencher o select de filtros.
     */
    public List<String> getAvailableServices() {
        Set<String> services = new TreeSet<>();
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("size", 0);
            ObjectNode aggs = root.putObject("aggs");
            aggs.putObject("services")
                    .putObject("terms")
                    .put("field", "application_name.keyword")
                    .put("size", 50);

            Request request = new Request("POST", "/" + INDEX_PATTERN + "/_search");
            request.setJsonEntity(objectMapper.writeValueAsString(root));

            Response response = restClient.performRequest(request);
            JsonNode json = objectMapper.readTree(EntityUtils.toString(response.getEntity()));

            JsonNode buckets = json.path("aggregations").path("services").path("buckets");
            if (buckets.isArray()) {
                for (JsonNode b : buckets) {
                    String name = b.path("key").asText().trim();
                    if (!name.isBlank()) {
                        services.add(name);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [LogService] Não foi possível obter lista dinâmica de serviços do ES: {}", e.getMessage());
        }

        if (services.isEmpty()) {
            services.addAll(List.of(
                    "microservico-radares-cart",
                    "microservico-radares-bff",
                    "microservico-api-gateway",
                    "microservico-radares-eixo",
                    "microservico-radares-entrevias",
                    "microservico-radares-rondon",
                    "microservico-radares-monitorasp",
                    "microservico-radares-pantanal",
                    "microservico-radares-appia"
            ));
        }

        return new ArrayList<>(services);
    }

    /**
     * Retorna a URL configurada do Kibana.
     */
    public String getKibanaUrl() {
        return elasticsearchConfig.getKibanaExternalUrl();
    }

    // ─── Helpers Privados ────────────────────────────────────────────────────────────

    private ObjectNode buildBoolQuery(
            String query, String level, String service,
            String startDate, String endDate, String timeRange
    ) {
        ObjectNode boolNode = objectMapper.createObjectNode();
        ObjectNode boolInner = boolNode.putObject("bool");

        ArrayNode mustArray = boolInner.putArray("must");
        ArrayNode filterArray = boolInner.putArray("filter");

        // 1. Busca textual
        if (query != null && !query.trim().isEmpty() && !"*".equals(query.trim())) {
            ObjectNode qs = mustArray.addObject().putObject("query_string");
            qs.put("query", query.trim());
            qs.put("default_operator", "AND");
            ArrayNode fields = qs.putArray("fields");
            fields.add("message^3");
            fields.add("logger_name^2");
            fields.add("stack_trace");
            fields.add("application_name");
        } else {
            mustArray.addObject().putObject("match_all");
        }

        // 2. Filtro de Nível
        if (level != null && !level.trim().isEmpty() && !"ALL".equalsIgnoreCase(level.trim())) {
            filterArray.addObject()
                    .putObject("term")
                    .put("level.keyword", level.trim().toUpperCase());
        }

        // 3. Filtro de Microsserviço
        if (service != null && !service.trim().isEmpty() && !"ALL".equalsIgnoreCase(service.trim())) {
            filterArray.addObject()
                    .putObject("term")
                    .put("application_name.keyword", service.trim());
        }

        // 4. Filtro de Data/Período
        ObjectNode rangeNode = objectMapper.createObjectNode();
        boolean hasDateFilter = false;

        if (startDate != null && !startDate.isBlank()) {
            rangeNode.put("gte", startDate.trim());
            hasDateFilter = true;
        } else if (timeRange != null && !timeRange.isBlank() && !"all".equalsIgnoreCase(timeRange.trim())) {
            String gteValue = switch (timeRange.trim().toLowerCase()) {
                case "15m" -> "now-15m";
                case "1h" -> "now-1h";
                case "24h" -> "now-24h";
                case "7d" -> "now-7d/d";
                case "30d" -> "now-30d/d";
                default -> "now-24h";
            };
            rangeNode.put("gte", gteValue);
            hasDateFilter = true;
        }

        if (endDate != null && !endDate.isBlank()) {
            rangeNode.put("lte", endDate.trim());
            hasDateFilter = true;
        }

        if (hasDateFilter) {
            filterArray.addObject()
                    .putObject("range")
                    .set("@timestamp", rangeNode);
        }

        return boolNode;
    }

    private List<LogEntryDTO> fetchRecentErrors(String service, String timeRange) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("size", 5);

            ArrayNode sort = root.putArray("sort");
            sort.addObject().putObject("@timestamp").put("order", "desc");

            ObjectNode boolQuery = buildBoolQuery(null, "ERROR", service, null, null, timeRange);
            root.set("query", boolQuery);

            Request request = new Request("POST", "/" + INDEX_PATTERN + "/_search");
            request.setJsonEntity(objectMapper.writeValueAsString(root));

            Response response = restClient.performRequest(request);
            JsonNode json = objectMapper.readTree(EntityUtils.toString(response.getEntity()));

            List<LogEntryDTO> errors = new ArrayList<>();
            JsonNode hits = json.path("hits").path("hits");
            if (hits.isArray()) {
                for (JsonNode h : hits) {
                    errors.add(mapHitToLogEntry(h));
                }
            }
            return errors;
        } catch (Exception e) {
            log.debug("Erro ao consultar top erros recentes: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private LogEntryDTO mapHitToLogEntry(JsonNode hit) {
        String id = hit.path("_id").asText();
        JsonNode source = hit.path("_source");

        String appName = source.has("application_name")
                ? source.path("application_name").asText()
                : source.path("springAppName").asText("desconhecido");

        String message = source.has("message")
                ? source.path("message").asText()
                : source.path("log_message").asText("");

        return LogEntryDTO.builder()
                .id(id)
                .timestamp(source.path("@timestamp").asText())
                .level(source.path("level").asText("INFO").toUpperCase())
                .levelValue(source.path("level_value").asLong(0L))
                .applicationName(appName)
                .message(message)
                .loggerName(source.path("logger_name").asText(""))
                .threadName(source.path("thread_name").asText(""))
                .stackTrace(source.path("stack_trace").asText(null))
                .tag(source.path("tag").asText(""))
                .extraFields(objectMapper.convertValue(source, Map.class))
                .build();
    }

    private String formatTimelineLabel(String isoTimestamp, String range) {
        if (isoTimestamp == null || isoTimestamp.length() < 16) return isoTimestamp;
        try {
            // Ex: "2026-10-17T18:00:00.000Z"
            if ("7d".equalsIgnoreCase(range) || "30d".equalsIgnoreCase(range)) {
                return isoTimestamp.substring(5, 10); // MM-DD
            }
            return isoTimestamp.substring(11, 16); // HH:mm
        } catch (Exception e) {
            return isoTimestamp;
        }
    }
}
