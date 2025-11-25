package com.coruja.controller;

import com.coruja.dto.NotificationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
@Slf4j
public class NotificationController {

    private final SimpMessagingTemplate messagingTemplate;

    public NotificationController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // 🔥 Envia para todos inscritos em /topic/notificacoes
    @MessageMapping("/notify")
    @SendTo("/topic/notificacoes")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public NotificationMessage notifyAll(NotificationMessage msg) {
        log.info("📢 Mensagem recebida (broadcast): {}", msg.getMensagem());
        return msg;
    }

    // 🔥 Envia mensagem APENAS para um usuário específico
    // Frontend envia para /app/notify-user/{userId}
    @MessageMapping("/notify-user/{userId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public void notifyUser(
            @DestinationVariable String userId,
            NotificationMessage msg
    ) {
        log.info("📬 Enviando mensagem para o usuário {}", userId);

        messagingTemplate.convertAndSend(
                "/topic/notificacoes/" + userId,
                msg
        );
    }

    // 🔥 Apenas echo (pra testes)
    @MessageMapping("/echo")
    @SendTo("/topic/echo")
    public NotificationMessage echo(NotificationMessage msg) {
        return new NotificationMessage("ECHO: " + msg.getMensagem());
    }
}