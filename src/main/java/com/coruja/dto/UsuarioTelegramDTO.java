package com.coruja.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UsuarioTelegramDTO {
    private Long id;
    private String telegramId;
    private String username;
    private String primeiroNome;
    private String sobrenome;
    private LocalDateTime dataCadastro;
    private LocalDateTime ultimoAcesso;
}
