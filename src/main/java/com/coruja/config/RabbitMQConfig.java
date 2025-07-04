package com.coruja.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    // Nomes padronizados para nossa infraestrutura de mensageria
    public static final String EXCHANGE_NAME = "radares_exchange";
    public static final String QUEUE_NAME = "radares_data_queue";
    public static final String ROUTING_KEY_PATTERN = "radares.*"; // O padrão para capturar todas as mensagens de radares

    /**
     * Cria o Exchange (a "sala de triagem") do tipo Topic, que roteia mensagens
     * com base em um padrão de routing key.
     */
    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange(EXCHANGE_NAME);
    }

    /**
     * Cria a Fila (a "caixa postal") onde as mensagens serão armazenadas.
     * O 'true' no construtor significa que a fila é "durável" (ela sobrevive a reinicializações do RabbitMQ).
     */
    @Bean
    public Queue durableQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    /**
     * Cria a Ligação (Binding) que conecta o Exchange à Fila.
     * Diz ao RabbitMQ: "Toda mensagem que chegar no 'radares_exchange' com uma
     * routing key que corresponda a 'radares.*' deve ser enviada para a 'radares_data_queue'".
     */
    @Bean
    public Binding binding(Queue queue, TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY_PATTERN);
    }
}
