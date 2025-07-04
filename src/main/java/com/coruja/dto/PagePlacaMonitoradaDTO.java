package com.coruja.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Classe auxiliar para ajudar o WebClient a desserializar respostas paginadas (Page) do Spring.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PagePlacaMonitoradaDTO extends PageImpl<PlacaMonitoradaDTO> {

    /**
     * Construtor especial que o Jackson (conversor JSON) usará.
     * As anotações @JsonProperty dizem a ele como mapear os campos do JSON
     * para os parâmetros deste construtor.
     */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public PagePlacaMonitoradaDTO(@JsonProperty("content") List<PlacaMonitoradaDTO> content,
                                  @JsonProperty("number") int number,
                                  @JsonProperty("size") int size,
                                  @JsonProperty("totalElements") Long totalElements,
                                  @JsonProperty("pageable") JsonNode pageable,
                                  @JsonProperty("last") boolean last,
                                  @JsonProperty("totalPages") int totalPages,
                                  @JsonProperty("sort") JsonNode sort,
                                  @JsonProperty("first") boolean first,
                                  @JsonProperty("numberOfElements") int numberOfElements) {

        // Chama o construtor da classe pai (PageImpl) com os dados recebidos
        super(content != null ? content : Collections.emptyList(),
                PageRequest.of(number, Math.max(size, 1)),
                totalElements != null ? totalElements : 0L);
    }

    // Construtores padrão
    public PagePlacaMonitoradaDTO(List<PlacaMonitoradaDTO> content, Pageable pageable, long total) {
        super(content, pageable, total);
    }

    public PagePlacaMonitoradaDTO(List<PlacaMonitoradaDTO> content) {
        super(content);
    }
}
