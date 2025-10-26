package com.coruja.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração centralizada para a topologia do RabbitMQ.
 * Declara as filas, o exchange e as ligações (bindings) necessárias para a comunicação
 * entre os microserviços de radares.
 */
@Configuration
public class RabbitMQConfig {

    // Exchange principal para todos os eventos de radares
    public static final String EXCHANGE_NAME = "radares_exchange";

    // --- Configuração para a Fila de Dados Gerais de Radares ---
    public static final String RADARES_DATA_QUEUE = "radares_data_queue";
    public static final String RADARES_ROUTING_KEY_PATTERN = "radares.*";

    // --- Configuração para a Fila de Alertas Confirmados ---
    public static final String ALERTAS_QUEUE = "alertas_confirmados_queue";
    public static final String ALERTAS_ROUTING_KEY = "alerta.confirmado";


    /**
     * Cria o Exchange (a "sala de triagem") do tipo Topic.
     */
    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange(EXCHANGE_NAME);
    }

    /**
     * Cria a fila para receber todos os dados de monitoramento de radares.
     * É durável para sobreviver a reinicializações do broker.
     */
    @Bean
    public Queue radaresDataQueue() {
        return new Queue(RADARES_DATA_QUEUE, true);
    }

    /**
     * Cria a fila específica para receber alertas confirmados.
     * É durável para sobreviver a reinicializações do broker.
     */
    @Bean
    public Queue alertasConfirmadosQueue() {
        return new Queue(ALERTAS_QUEUE, true);
    }

    /**
     * Cria a ligação (Binding) que conecta o exchange à fila de dados gerais.
     * Usa @Qualifier para resolver a ambiguidade e garantir que a fila correta seja injetada.
     */
    @Bean
    public Binding radaresBinding(@Qualifier("radaresDataQueue") Queue queue, TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(RADARES_ROUTING_KEY_PATTERN);
    }

    /**
     * Cria a ligação (Binding) que conecta o exchange à fila de alertas.
     * Usa @Qualifier para garantir que a fila correta seja injetada.
     */
    @Bean
    public Binding alertasBinding(@Qualifier("alertasConfirmadosQueue") Queue queue, TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(ALERTAS_ROUTING_KEY);
    }
}
