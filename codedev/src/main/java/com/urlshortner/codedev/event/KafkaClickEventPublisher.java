package com.urlshortner.codedev.event;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaClickEventPublisher implements ClickEventPublisher {

    private static final String TOPIC = "url-clicks";

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaClickEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(String shortCode) {
        kafkaTemplate.send(TOPIC, shortCode, shortCode);
    }
}
