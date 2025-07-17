package com.coruja.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@NoArgsConstructor
public class AlertaPassagemDTO {
    private Long id;
    private String concessionaria;
    private LocalDate data;
    private LocalTime hora;
    private String placa;
    private String marcaModelo;
    private String cor;
    private String motivo;
    private String observacao;
    private String interessado;
    private String praca;
    private String rodovia;
    private String km;
    private String sentido;
    private LocalDateTime timestampAlerta;

    // Opcional: Se quiser mostrar os detalhes da placa que gerou o alerta
    private PlacaMonitoradaDTO placaMonitorada;
}
