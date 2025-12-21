package com.coruja.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration
public class EtagConfig {

    /**
     * Este filtro gera automaticamente um hash MD5 do corpo da resposta.
     * Se o cliente (Frontend) enviar um hash igual no header 'If-None-Match',
     * o filtro retorna 304 (Not Modified) e economiza banda de rede.
     */
    @Bean
    public ShallowEtagHeaderFilter shallowEtagHeaderFilter() {
        return new ShallowEtagHeaderFilter();
    }
}
