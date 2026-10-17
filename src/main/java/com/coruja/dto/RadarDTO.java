package com.coruja.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

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

    // Campos API do Detran
    private String marcaModelo;
    private String cor;
    private String municipio;
    private String uf;
    private String anoModelo;
    private String nomeProprietario;
    private String cpfProprietario;
    private String proprietario;
    private String nome;
    private String proprietarioNumeroDocumento;
    private String numeroDocumento;

    // ─── Informações de Status e Atraso Operacional (Dashboard e Tempo Real) ───
    private Boolean atrasado;
    private Long minutosAtraso;
    private String tempoAtrasoFormatado;
    private String statusOperacional; // "ATUALIZADO", "ATRASADO", "SEM_COMUNICACAO", "SEM_DADOS"

    /**
     * Calcula o status de atraso com base na data/hora da passagem e no horário atual.
     *
     * @param limiteMinutos Tolerância máxima em minutos antes de considerar o radar atrasado.
     */
    public void calcularAtraso(long limiteMinutos) {
        if (this.data == null || this.hora == null) {
            this.atrasado = true;
            this.statusOperacional = "SEM_DADOS";
            this.tempoAtrasoFormatado = "Sem dados";
            this.minutosAtraso = null;
            return;
        }

        try {
            LocalDateTime dataHoraPassagem = LocalDateTime.of(this.data, this.hora);
            LocalDateTime agora = LocalDateTime.now(ZoneId.of("America/Sao_Paulo"));

            long diffMinutos = Duration.between(dataHoraPassagem, agora).toMinutes();
            if (diffMinutos < 0) {
                diffMinutos = 0;
            }

            this.minutosAtraso = diffMinutos;
            this.atrasado = diffMinutos > limiteMinutos;
            this.statusOperacional = this.atrasado ? "ATRASADO" : "ATUALIZADO";

            if (diffMinutos == 0) {
                this.tempoAtrasoFormatado = "Agora mesmo";
            } else if (diffMinutos < 60) {
                this.tempoAtrasoFormatado = diffMinutos + " min atrás";
            } else if (diffMinutos < 1440) {
                long horas = diffMinutos / 60;
                long restoMin = diffMinutos % 60;
                this.tempoAtrasoFormatado = horas + "h " + (restoMin > 0 ? restoMin + "min " : "") + "atrás";
            } else {
                long dias = diffMinutos / 1440;
                long restoHoras = (diffMinutos % 1440) / 60;
                if (restoHoras > 0) {
                    this.tempoAtrasoFormatado = dias + (dias == 1 ? " dia e " : " dias e ") + restoHoras + "h atrás";
                } else {
                    this.tempoAtrasoFormatado = dias + (dias == 1 ? " dia atrás" : " dias atrás");
                }
            }
        } catch (Exception e) {
            this.atrasado = true;
            this.statusOperacional = "ERRO_DATA";
            this.tempoAtrasoFormatado = "Data inválida";
        }
    }
}
