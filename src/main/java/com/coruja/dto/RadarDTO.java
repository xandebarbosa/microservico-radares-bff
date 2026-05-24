package com.coruja.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Date;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RadarDTO {
    private String id;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate data;

    @JsonFormat(pattern = "HH:mm:ss")
    private LocalTime hora;
    private String placa;
    private String praca;
    private String rodovia;
    private String km;
    private String sentido;
    private String concessionaria;

    //Campos API do Detran
    private String marcaModelo;
    private String cor;
    private String municipio;
    private String uf;
    private String anoModelo;
    private String nomeProprietario;
    private String cpfProprietario;
}
