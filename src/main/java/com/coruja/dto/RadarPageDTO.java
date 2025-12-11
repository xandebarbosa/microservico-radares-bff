package com.coruja.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // <-- Ignora campos como "pageable" e "sort"
public class RadarPageDTO {
    private List<RadarDTO> content;
    private PageMetadata page;

    public RadarPageDTO(List<RadarDTO> content, PageMetadata metadata) {
        this.content = content;
        this.page = metadata;
    }

    /**
     * ✅ CORREÇÃO: Este construtor ensina o Jackson a ler o JSON "plano"
     * que vem dos microserviços (ex: Cart) e mapear os campos soltos
     * (totalElements, number, etc.) para dentro do objeto 'page'.
     */
    @JsonCreator
    public RadarPageDTO(
            @JsonProperty("content") List<RadarDTO> content,
            @JsonProperty("number") int number,
            @JsonProperty("size") int size,
            @JsonProperty("totalElements") Long totalElements,
            @JsonProperty("totalPages") int totalPages
    ) {
        this.content = content;
        this.page = new PageMetadata(
                number,
                size,
                totalElements != null ? totalElements : 0L,
                totalPages
        );
    }

}
