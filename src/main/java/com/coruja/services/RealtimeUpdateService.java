package com.coruja.services;

import com.coruja.config.RabbitMQConfig;
import com.coruja.dto.RadarDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RealtimeUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(RealtimeUpdateService.class);

    // Ferramenta do Spring para enviar mensagens para os clientes WebSocket
    private final SimpMessagingTemplate messagingTemplate;

    // Mapa para armazenar o último radar de cada concessionária.
    // Usamos ConcurrentHashMap para segurança em ambientes com múltiplas threads.
    private final Map<String, RadarDTO> lastRadarByConcessionaria = new ConcurrentHashMap<>();

    @Autowired
    public RealtimeUpdateService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // Este método "ouve" a fila que recebe os dados de todos os radares
    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME) // IMPORTANTE: Este deve ser o nome da sua fila principal
    public void receiveRadarMessage(String message) {
        logger.info("Mensagem recebida do RabbitMQ: {}", message);
        try {
            if (message == null || message.isBlank()) {
                logger.warn("Mensagem vazia recebida do RabbitMQ. Ignorando.");
                return;
            }

            String[] parts = message.split("\\|");
            if (parts.length < 4) { // Validação mínima (Concessionária, Data, Hora, Placa)
                logger.warn("Mensagem malformada recebida, número de partes insuficiente. Mensagem: {}", message);
                return;
            }

            RadarDTO radarData = new RadarDTO();
            String concessionaria = parts[0].toUpperCase();

            // Usamos um switch para lidar com os diferentes formatos de cada concessionária
            switch (concessionaria) {
                case "RONDON":
                    if (parts.length >= 7) {
                        radarData.setConcessionaria("Rondon");
                        radarData.setData(LocalDate.parse(parts[1]));
                        radarData.setHora(LocalTime.parse(parts[2]));
                        radarData.setPlaca(parts[3]);
                        radarData.setRodovia(parts[4]);
                        radarData.setKm(parts[5]);
                        radarData.setSentido(parts[6]);
                        radarData.setPraca("N/A"); // Rondon não envia praça neste formato
                    }
                    break;

                case "ENTREVIAS":
                    if (parts.length >= 8) {
                        radarData.setConcessionaria("Entrevias");
                        radarData.setData(LocalDate.parse(parts[1]));
                        radarData.setHora(LocalTime.parse(parts[2]));
                        radarData.setPlaca(parts[3]);
                        radarData.setPraca(parts[4]);
                        radarData.setRodovia(parts[5]);
                        radarData.setKm(parts[6]);
                        radarData.setSentido(parts[7]);
                    }
                    break;

                case "CART":
                    if (parts.length >= 8) {
                        radarData.setConcessionaria("Cart");
                        radarData.setData(LocalDate.parse(parts[1]));
                        radarData.setHora(LocalTime.parse(parts[2]));
                        radarData.setPlaca(parts[3]);
                        radarData.setPraca(parts[4]);
                        radarData.setRodovia(parts[5]);
                        radarData.setKm(parts[6]);
                        radarData.setSentido(parts[7]);
                    }
                    break;

                case "EIXO":
                    // Adicione aqui a lógica de mapeamento para CART e EIXO se forem diferentes
                    // Assumindo que eles também usam o formato de 8 partes como o Entrevias
                    if (parts.length >= 8) {
                        radarData.setConcessionaria(concessionaria);
                        radarData.setData(LocalDate.parse(parts[1]));
                        radarData.setHora(LocalTime.parse(parts[2]));
                        radarData.setPlaca(parts[3]);
                        radarData.setPraca(parts[4]);
                        radarData.setRodovia(parts[5]);
                        radarData.setKm(parts[6]);
                        radarData.setSentido(parts[7]);
                    }
                    break;

                default:
                    logger.warn("Formato de mensagem desconhecido para a concessionária: {}", concessionaria);
                    return; // Pula para a próxima mensagem
            }

            // Se o objeto foi populado (placa não é nula), envia para o WebSocket
            if (radarData.getPlaca() != null) {
                // 1. Guarda o último radar no nosso mapa interno.
                lastRadarByConcessionaria.put(radarData.getConcessionaria().toUpperCase(), radarData);
                // 2. Envia para o frontend via WebSocket.
                messagingTemplate.convertAndSend("/topic/last-radar", radarData);
                logger.info("Último radar da {} enviado para o frontend via WebSocket.", radarData.getConcessionaria());
            }

        } catch (Exception e) {
            logger.error("Erro ao processar mensagem do RabbitMQ e enviar via WebSocket. Mensagem: '{}'", message, e);
        }
    }

    // Método público para que outros serviços possam buscar o estado atual.
    public Map<String, RadarDTO> getLatestRadars() {
        return lastRadarByConcessionaria;
    }
}
