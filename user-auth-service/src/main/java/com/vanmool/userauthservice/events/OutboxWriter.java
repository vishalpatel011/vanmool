package com.vanmool.userauthservice.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanmool.userauthservice.models.OutboxEvent;
import com.vanmool.userauthservice.repositories.OutboxEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

// Records an event in the outbox table, inside the caller's transaction.
//
// Why not send to Kafka directly? Sending after the database commit loses the event if Kafka is down or the service
// stops in between; sending before the commit announces a signup that may still be rolled back. Writing the event in
// the same transaction as the change makes both happen or neither; OutboxRelay sends it afterwards.
@Component
public class OutboxWriter {
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // MANDATORY: fails if there is no transaction, because an event saved on its own would defeat the purpose
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String topic, String messageKey, DomainEvent event) {
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(event.eventId());
        outboxEvent.setTopic(topic);
        outboxEvent.setMessageKey(messageKey);
        outboxEvent.setEventType(event.eventType());
        outboxEvent.setPayload(objectMapper.valueToTree(event));
        outboxEvent.setCreatedAt(clock.instant());
        outboxEventRepository.save(outboxEvent);
    }
}
