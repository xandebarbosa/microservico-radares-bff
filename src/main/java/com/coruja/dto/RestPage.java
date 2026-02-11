package com.coruja.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper auxiliar para desserializar Page<T> do Spring via RestTemplate.
 * O Spring Data PageImpl não tem construtor padrão, então o Jackson falha sem isso.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RestPage<T> extends PageImpl<T> {

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public RestPage(@JsonProperty("content") List<T> content,
                    @JsonProperty("number") int number,
                    @JsonProperty("size") int size,
                    @JsonProperty("totalElements") Long totalElements,
                    @JsonProperty("pageable") JsonNode pageable,
                    @JsonProperty("last") boolean last,
                    @JsonProperty("totalPages") int totalPages,
                    @JsonProperty("sort") JsonNode sort,
                    @JsonProperty("first") boolean first,
                    @JsonProperty("numberOfElements") int numberOfElements,
                    @JsonProperty("empty") Boolean empty
    ) {

        super(content != null ? content : new ArrayList<>(), PageRequest.of(number, size), totalElements != null ? totalElements : 0L);
    }

    /**
     * ✅ CONSTRUTOR ALTERNATIVO - Para respostas customizadas (RadarPageDTO)
     *
     * Exemplo de JSON recebido:
     * {
     *   "content": [...],
     *   "page": {
     *     "number": 0,
     *     "size": 10,
     *     "totalElements": 100,
     *     "totalPages": 10
     *   }
     * }
     */
    public RestPage(
            @JsonProperty("content") List<T> content,
            @JsonProperty("page") PageMetadata pageMetadata
    ) {
        super(
                content != null ? content : new ArrayList<>(),
                PageRequest.of(
                        pageMetadata != null ? pageMetadata.getNumber() : 0,
                        pageMetadata != null && pageMetadata.getSize() > 0 ? pageMetadata.getSize() : 20
                ),
                pageMetadata != null ? pageMetadata.getTotalElements() : 0L
        );
    }

    /**
     * Construtor padrão com Pageable
     */
    public RestPage(List<T> content, Pageable pageable, long total) {
        super(content, pageable, total);
    }

    /**
     * Construtor simplificado
     */
    public RestPage(List<T> content) {
        super(content);
    }

    /**
     * Construtor vazio
     */
    public RestPage() {
        super(new ArrayList<>());
    }
}
