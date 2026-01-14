package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlacaMonitoradaDTO {
    private Long id;
    private String placa;
    private String marcaModelo;
    private String cor;
    private String motivo;
    private boolean statusAtivo;
    private String observacao;
    private String interessado;
    private String telegramChatId;
    private String telefone;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
