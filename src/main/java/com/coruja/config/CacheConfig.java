package com.coruja.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Configuração padrão para todos os caches:
     * - TTL padrão de 60 minutos
     * - Não armazena valores nulos
     * - Serializa os objetos para JSON (legível) em vez de binário Java
     */
    @Bean
    public RedisCacheConfiguration cacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(60))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));
    }

    /**
     * Customizador para definir tempos de expiração (TTL) específicos por cache.
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return (builder) -> {
            Map<String, RedisCacheConfiguration> configurationMap = new HashMap<>();

            // Cache de filtros e opções (TTL curto/médio)
            configurationMap.put("radares-bff-filtros", RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(30)));
            configurationMap.put("opcoes-filtro-cart-v2", RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(1)));
            configurationMap.put("kms-rodovia-cart-v2", RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(1)));

            // --- O IMPORTANTE PARA O MAPA ---
            // Cache dos pontos do mapa (TTL longo, pois muda raramente)
            configurationMap.put("locais-radares-bff", RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofHours(24)));

            builder.withInitialCacheConfigurations(configurationMap);
        };
    }
}
