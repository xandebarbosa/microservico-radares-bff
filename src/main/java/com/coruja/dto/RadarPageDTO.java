package com.coruja.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

}
