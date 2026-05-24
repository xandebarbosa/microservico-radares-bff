package com.coruja.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RadarLocationDTO {
    private Long id;
    private String concessionaria;
    private String rodovia;
    private String km;
    private String praca;
    private Double latitude;
    private Double longitude;
}
