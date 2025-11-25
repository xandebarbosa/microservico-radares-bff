package com.coruja.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        // Tenta pegar as roles da claim "realm_access" (Padrão do Keycloak)
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");

        List<String> roles = null;

        if (realmAccess != null && realmAccess.containsKey("roles")) {
            roles = (List<String>) realmAccess.get("roles");
        }

        // Fallback: Tenta pegar de "roles" direto (caso tenha mapper customizado)
        if (roles == null || roles.isEmpty()) {
            roles = jwt.getClaimAsStringList("roles");
        }

        if (roles == null) {
            return List.of();
        }

        // Transforma em GrantedAuthority com prefixo ROLE_ e MAIÚSCULO
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                .collect(Collectors.toList());
    }
}