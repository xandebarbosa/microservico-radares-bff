package com.coruja.config;

import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.security.Principal;
import java.util.Map;

/**
 * HandshakeHandler que lê attributes["stompPrincipal"] e retorna como Principal.
 */
public class CustomHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request,
                                      WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {
        Object maybe = attributes.get("stompPrincipal");
        if (maybe instanceof Principal) {
            return (Principal) maybe;
        }
        // fallback para comportamento padrão (gera principal anônimo)
        return super.determineUser(request, wsHandler, attributes);
    }
}