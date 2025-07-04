package com.coruja.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker // Habilita o broker de mensagens WebSocket
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Habilita um broker de mensagens simples em memória.
        // Os clientes irão se inscrever em destinos que começam com "/topic".
        config.enableSimpleBroker("/topic");
        // Define o prefixo para os destinos dos endpoints que os controllers irão usar.
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Registra o endpoint "/ws" para os clientes se conectarem.
        // É o "handshake" inicial do WebSocket.
        // setAllowedOriginPatterns("*") permite a conexão de qualquer origem (importante para CORS)
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }
}
