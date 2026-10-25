package com.vanmool.orderservice.events;

import com.vanmool.orderservice.models.OutboxEvent;
import com.vanmool.orderservice.repositories.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

// Sends the outbox to Kafka: every second, the unpublished events oldest first, each one confirmed by the broker
// (acks=all) before it is marked published. Delivery is at least once: if the service stops after Kafka accepted an
// event but before it was marked, the event is sent again, so consumers recognise repeats by eventId.
// With several instances of this service, each relay could send the same rows (still at least once); a
// SELECT ... FOR UPDATE SKIP LOCKED would share the rows out between them.
@Slf4j
@Component
public class OutboxRelay {
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final Duration retention;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate,
                       Clock clock, @Value("${outbox.retention}") Duration retention) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${outbox.relay-interval}")
    public void publishPending() {
        for (OutboxEvent event : outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload().toString())
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Kafka is down or refused it: stop here and try again next round, so events keep their order
                log.warn("Outbox event {} ({}) not sent yet: {}", event.getId(), event.getEventType(), e.getMessage());
                return;
            }
            event.setPublishedAt(clock.instant());
            outboxEventRepository.save(event);
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void deleteOldPublished() {
        int deleted = outboxEventRepository.deletePublishedBefore(clock.instant().minus(retention));
        if (deleted > 0) {
            log.info("Removed {} outbox events published more than {} ago", deleted, retention);
        }
    }
}
