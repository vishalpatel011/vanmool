package com.vanmool.productservice;

import com.vanmool.productservice.configs.KafkaConfig;
import com.vanmool.productservice.dtos.ProductRequestDto;
import com.vanmool.productservice.dtos.ReservationItemRequestDto;
import com.vanmool.productservice.exceptions.ProductNotFoundException;
import com.vanmool.productservice.services.ProductService;
import com.vanmool.productservice.services.StockService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// The order-events listener against the Kafka broker started by docker compose and the MySQL in DB_URL. Events are
// published here exactly as order-service publishes them.
@SpringBootTest
class OrderEventsIT {
    private final String category = "it-events-" + UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private UUID basketId;

    @Autowired
    private ProductService productService;

    @Autowired
    private StockService stockService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int stock() throws ProductNotFoundException {
        return stockService.getStock(basketId).quantity();
    }

    private String orderCancelled() {
        return """
                { "eventId": "%s", "eventType": "OrderCancelled", "occurredAt": "%s", "orderId": "%s",
                  "userId": "%s", "customerEmail": "it@example.com", "totalAmount": 900.00 }"""
                .formatted(UUID.randomUUID(), Instant.now(), orderId, UUID.randomUUID());
    }

    @BeforeEach
    void reserveTwoBaskets() throws ProductNotFoundException {
        ProductRequestDto request = new ProductRequestDto();
        request.setName("Bamboo Basket");
        request.setPrice(new BigDecimal("450.00"));
        request.setCategory(category);
        basketId = productService.createProduct(request).getId();
        stockService.adjustStock(basketId, 5);
        ReservationItemRequestDto item = new ReservationItemRequestDto();
        item.setProductId(basketId);
        item.setQuantity(2);
        stockService.reserve(orderId, List.of(item));
    }

    @AfterEach
    void removeEverything() {
        jdbcTemplate.update("DELETE FROM stock_reservation_item WHERE order_id = UUID_TO_BIN(?)", orderId.toString());
        jdbcTemplate.update("DELETE FROM stock_reservation WHERE order_id = UUID_TO_BIN(?)", orderId.toString());
        jdbcTemplate.update("DELETE s FROM stock s JOIN product p ON p.id = s.product_id JOIN category c ON c.id = p.category_id WHERE c.name = ?", category);
        jdbcTemplate.update("DELETE p FROM product p JOIN category c ON c.id = p.category_id WHERE c.name = ?", category);
        jdbcTemplate.update("DELETE FROM category WHERE name = ?", category);
    }

    @Test
    void orderCancelled_putsTheReservedUnitsBack_once_evenWhenTheEventArrivesTwice() throws Exception {
        // Arrange
        assertEquals(3, stock());

        // Act: the same cancellation twice, as at-least-once delivery allows
        kafkaTemplate.send("order-events", orderId.toString(), orderCancelled()).get();
        kafkaTemplate.send("order-events", orderId.toString(), orderCancelled()).get();

        // Assert
        await().atMost(Duration.ofSeconds(20)).until(() -> stock() == 5);
        Thread.sleep(1_000);
        assertEquals(5, stock());
        assertEquals("RELEASED", jdbcTemplate.queryForObject(
                "SELECT status FROM stock_reservation WHERE order_id = UUID_TO_BIN(?)", String.class, orderId.toString()));
    }

    @Test
    void messageThatIsNotJson_goesToTheDeadLetterTopic_withTheReason() throws Exception {
        // Act
        String key = "dlt-it-" + UUID.randomUUID();
        kafkaTemplate.send("order-events", key, "this is not an event").get();

        // Assert: read the dead-letter topic until the message shows up
        ConsumerRecord<String, String> parked = null;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(KafkaConfig.ORDER_EVENTS_DLT));
            Instant deadline = Instant.now().plusSeconds(20);
            while (parked == null && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        parked = record;
                    }
                }
            }
        }
        assertNotNull(parked, "the message did not reach " + KafkaConfig.ORDER_EVENTS_DLT);
        assertEquals("this is not an event", parked.value());
        // The headers say why: the listener failed because the text could not be parsed as JSON
        assertEquals("com.fasterxml.jackson.core.JsonParseException", new String(
                parked.headers().lastHeader("kafka_dlt-exception-cause-fqcn").value(), StandardCharsets.UTF_8));
        // The reservation was not touched
        assertEquals(3, stock());
    }
}
