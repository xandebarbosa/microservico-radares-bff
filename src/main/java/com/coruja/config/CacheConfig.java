package com.coruja.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        // Define os nomes dos caches que sua aplicação usará
        cacheManager.setCacheNames(List.of(
                "radares-bff-filtros",
                "radares",
                "radares-filtros"
                // você pode adicionar outros nomes de cache aqui no futuro
        ));

        // Habilita o suporte a cache assíncrono, necessário para tipos reativos como Mono e Flux.
        cacheManager.setAsyncCacheMode(true);

        // Configuração padrão para os caches (pode ser customizada por cache)
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES) // Itens expiram 10 minutos após serem gravados
                .maximumSize(500) // Limita o cache a um máximo de 500 entradas
                .initialCapacity(100));

        return cacheManager;
    }
}
