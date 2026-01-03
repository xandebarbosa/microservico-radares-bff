package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RadarLocationDTO {
    private Long id;
    private String concessionaria;
    private String rodovia;
    private String km;
    private String praca;
    private Double latitude;
    private Double longitude;
}
