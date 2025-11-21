package com.coruja.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker // Habilita o broker de mensagens WebSocket
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtDecoder jwtDecoder;

    @Autowired
    public WebSocketConfig(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
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
        // Registra o endpoint "/ws" para os clientes se conectarem.
        // É o "handshake" inicial do WebSocket.
        // setAllowedOriginPatterns("*") permite a conexão de qualquer origem (importante para CORS)
        //registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
        registry.addEndpoint("/ws")
                // 🔥 PERMITE conexão do frontend Next.js
                .setAllowedOrigins("http://192.168.0.6:3000", "http://localhost:3000")
                .setHandshakeHandler(new CustomHandshakeHandler())
                .addInterceptors(new JwtHandshakeInterceptor(jwtDecoder))
                // 🔥 Habilita SockJS (fallback para clients sem WS)
                .withSockJS();
    }
}
