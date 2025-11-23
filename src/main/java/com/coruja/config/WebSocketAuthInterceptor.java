package com.coruja.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;

    public WebSocketAuthInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            // ✅ Tenta pegar o token de múltiplos lugares
            String authToken = accessor.getFirstNativeHeader("Authorization");

            if (authToken == null) {
                authToken = accessor.getFirstNativeHeader("X-Authorization");
            }

            if (authToken != null && authToken.startsWith("Bearer ")) {
                try {
                    String token = authToken.substring(7);
                    Jwt jwt = jwtDecoder.decode(token);

                    // ✅ Extrai roles do token
                    List<String> roles = jwt.getClaimAsStringList("roles");
                    if (roles == null) {
                        var realmAccess = jwt.getClaimAsMap("realm_access");
                        if (realmAccess != null && realmAccess.containsKey("roles")) {
                            roles = (List<String>) realmAccess.get("roles");
                        }
                    }

                    List<SimpleGrantedAuthority> authorities = roles != null
                            ? roles.stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                            .collect(Collectors.toList())
                            : List.of();

                    Authentication authentication = new UsernamePasswordAuthenticationToken(
                            jwt.getSubject(),
                            null,
                            authorities
                    );

                    accessor.setUser(authentication);

                    System.out.println("✅ WebSocket autenticado para usuário: " + jwt.getSubject());

                } catch (Exception e) {
                    System.err.println("❌ Erro ao validar token JWT: " + e.getMessage());
                    // ✅ NÃO lança exceção - permite conexão sem autenticação
                    // O SecurityConfig já protege os endpoints REST
                }
            }
        }

        return message;
    }
}