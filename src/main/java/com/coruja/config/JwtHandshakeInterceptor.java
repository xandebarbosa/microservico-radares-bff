package com.coruja.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
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

    private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);
    private final JwtDecoder jwtDecoder;

    public JwtHandshakeInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) throws Exception {

        // Verifica se é uma requisição Servlet (MVC)
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpServletRequest = servletRequest.getServletRequest();
            String token = httpServletRequest.getParameter("access_token");

            if (token != null && !token.isBlank()) {
                try {
                    Jwt jwt = jwtDecoder.decode(token);
                    Principal principal = new StompPrincipal(jwt.getSubject());
                    attributes.put("stompPrincipal", principal);
                    return true;
                } catch (Exception e) {
                    log.error("Erro ao validar token no handshake: {}", e.getMessage());
                    // Retorna true para permitir conexão anônima se falhar, ou false para bloquear
                    return true;
                }
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                               org.springframework.http.server.ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        if (exception != null) {
            log.error("❌ [Handshake] Erro após handshake: ", exception);
            System.err.println("❌ Erro no handshake: " + exception.getMessage());
        } else {
            log.debug("✅ [Handshake] Finalizado com sucesso.");
        }
    }

    private String extractToken(ServerHttpRequest request) {
        // 1) Query param ?access_token=...
        if (request instanceof ServletServerHttpRequest servletReq) {
            HttpServletRequest httpReq = servletReq.getServletRequest();
            String q = httpReq.getParameter("access_token");
            if (q != null && !q.isBlank()) {
                log.debug("🔑 [Handshake] Token encontrado na URL");
                System.out.println("🔑 Token encontrado na query string");
                return q;
            }
        }

        // 2) Authorization header
        List<String> authHeaders = request.getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION);
        if (!authHeaders.isEmpty()) {
            String header = authHeaders.get(0);
            if (header.toLowerCase().startsWith("bearer ")) {
                log.debug("🔑 [Handshake] Token encontrado no Header");
                System.out.println("🔑 Token encontrado no header Authorization");
                return header.substring(7).trim();
            }
        }

        System.out.println("⚠️ Nenhum token encontrado no handshake");
        return null;
    }

    // Principal custom simples
    public static class StompPrincipal implements Principal {
        private final String name;
        public StompPrincipal(String name) { this.name = name; }
        @Override public String getName() { return this.name; }
        //@Override public String toString() { return "StompPrincipal[" + name + "]"; }
    }
}
