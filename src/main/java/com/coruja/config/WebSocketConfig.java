package com.coruja.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker // Habilita o broker de mensagens WebSocket
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Autowired
    public WebSocketConfig(WebSocketAuthInterceptor authInterceptor) {
        this.webSocketAuthInterceptor = authInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Habilita um broker de mensagens simples em memória.
        // Os clientes irão se inscrever em destinos que começam com "/topic".
        config.enableSimpleBroker("/topic", "/queue");
        // Define o prefixo para os destinos dos endpoints que os controllers irão usar.
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
         //✅ SIMPLIFICADO: Sem HandshakeHandler nem Interceptor
        // A autenticação acontece APENAS no STOMP CONNECT via WebSocketAuthInterceptor
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*") // Permite todas as origens (ajuste em produção)
                .withSockJS()
                .setHeartbeatTime(25000)      // Heartbeat a cada 25s
                .setDisconnectDelay(5000);    // Tempo antes de considerar desconectado
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // ✅ Autenticação acontece AQUI no STOMP CONNECT
        registration.interceptors(webSocketAuthInterceptor);
    }
}
