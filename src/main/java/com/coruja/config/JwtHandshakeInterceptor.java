package com.coruja.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.security.Principal;

/**
 * Interceptor para validar o token JWT no momento do handshake.
 * Procura por:
 *  - query param "access_token"
 *  - header Authorization: Bearer <token>
 *
 * Se o token for válido, coloca um Principal (StompPrincipal) em attributes com chave "stompPrincipal".
 */
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtDecoder jwtDecoder;

    public JwtHandshakeInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   org.springframework.http.server.ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) throws Exception {

        // extrai token: query param ou header
        String token = extractToken(request);

        if (token == null || token.isBlank()) {
            // Sem token: retornamos true para permitir handshake (caso queira forçar autenticação aqui retorne false).
            // Como o Gateway pode bloquear, aqui preferimos permitir e deixar autenticação ser exigida em mensagens ou business logic,
            // mas se quiser fechar handshake com UNAUTHORIZED, retorne false e defina response status.
            return true;
        }

        try {
            Jwt jwt = jwtDecoder.decode(token);

            // você pode extrair roles/authorities se quiser
            String subject = jwt.getSubject();
            // cria um Principal simples com o subject
            StompPrincipal principal = new StompPrincipal(subject);

            // armazena para o HandshakeHandler extrair
            attributes.put("stompPrincipal", principal);

            // opcional: guarde claims importantes também
            attributes.put("jwtClaims", jwt.getClaims());

            return true;
        } catch (JwtException ex) {
            // Token inválido: rejeitar handshake
            return false;
        }
    }

    @Override
    public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                               org.springframework.http.server.ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // nada a fazer
    }

    private String extractToken(ServerHttpRequest request) {
        // 1) query param ?access_token=...
        if (request instanceof ServletServerHttpRequest servletReq) {
            HttpServletRequest httpReq = servletReq.getServletRequest();
            String q = httpReq.getParameter("access_token");
            if (q != null && !q.isBlank()) return q;
        }

        // 2) Authorization header
        List<String> authHeaders = request.getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION);
        if (!authHeaders.isEmpty()) {
            // pega o primeiro header "Bearer <token>"
            String header = authHeaders.get(0);
            if (header.toLowerCase().startsWith("bearer ")) {
                return header.substring(7).trim();
            }
        }

        return null;
    }

    // Principal custom simples
    public static class StompPrincipal implements Principal {
        private final String name;
        public StompPrincipal(String name) { this.name = name; }
        @Override public String getName() { return this.name; }
        @Override public String toString() { return "StompPrincipal[" + name + "]"; }
    }
}
