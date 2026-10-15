package com.ims.order.outbox;

import com.ims.order.client.InventoryClientAdapter;
import com.ims.order.client.dto.StockReservationRequest;
import com.ims.order.client.dto.StockReservationResponse;
import com.ims.order.dto.OrderResponse;
import com.ims.order.entity.OutboxEvent;
import com.ims.order.entity.OutboxStatus;
import com.ims.order.repository.OutboxEventRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The only test that runs the relay's real SQL. Everything else in this module runs on H2, which
 * cannot parse {@code FOR UPDATE SKIP LOCKED}, so without this class the claim query would ship
 * having never been executed. It also pins the end-to-end property the outbox exists for: an order
 * placed while the broker is unreachable is still placed, its event waits in the table with a
 * climbing attempt count, and it is delivered - with the right key and the right type header - once
 * the broker is reachable again. Under the old fire-and-forget publish that event was simply gone,
 * with a log line as its only trace.
 *
 * <p>Skipped, not failed, when Testcontainers cannot reach a Docker environment. This used to skip
 * even <em>with</em> Docker Desktop running, and the cause was not what it looked like: the
 * Testcontainers version the Spring Boot 3.2.5 BOM manages (1.19.7) does not negotiate an API
 * version at all. {@code DockerClientProviderStrategy.getClientForConfig} falls back to a
 * hard-coded {@code RemoteApiVersion.VERSION_1_32} whenever none is configured, and Engine 29
 * raised its <em>minimum</em> supported API version to 1.40, so {@code /v1.32/info} came back
 * HTTP 400 and Testcontainers declared Docker unavailable. The fix is
 * {@code src/test/resources/docker-java.properties} with {@code api.version=1.44} - test-scoped
 * configuration, no dependency version touched, so CLAUDE.md's version freeze was never the
 * obstacle it appeared to be. See that file for the full reasoning.
 *
 * <p>If this class ever cannot run, the same properties can be checked by hand against the compose
 * stack - {@code docker compose up -d postgres-order zookeeper kafka},
 * {@code docker compose stop kafka}, place an order, watch the row stay NEW with climbing attempts,
 * {@code docker compose start kafka}, watch it flip to SENT.
 *
 * <p>"Kafka down, then up" is done by pointing the application at a dead address and later
 * re-pointing the producer factory at the live container, rather than by stopping the broker.
 * Stopping is the obvious approach and does not work: {@code KafkaContainer} boots waiting for a
 * start script that Testcontainers copies in only on the first {@code start()}, so a restarted
 * broker never comes back, and a restart would also move the advertised listener port. Pausing the
 * container instead leaves the socket hanging rather than refusing, which makes the timing a
 * function of the producer's {@code max.block.ms} and can leave an idempotent producer with
 * sequence-number errors - a second, unrelated failure mode. What this models, then, is "broker
 * unreachable", which is the dominant real failure; it does not model a broker that dies mid-flight.
 *
 * <p>Delivery is at-least-once by design - the relay's five-second wait can time out on a record
 * Kafka later accepts - so the assertions below say "at least one record for this key", never
 * "exactly one". Asserting otherwise would make the test state something the system does not promise.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxRelayPostgresKafkaIntegrationTest {

    private static final String ORDER_EVENTS = "order-events";
    private static final String TYPE_ID_HEADER = "__TypeId__";
    private static final String ORDER_BODY =
            "{\"customerEmail\":\"buyer@example.com\","
                    + "\"items\":[{\"sku\":\"SKU-1\",\"quantity\":2,\"unitPrice\":10}]}";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.3"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The module's test application.yml hard-codes org.h2.Driver, so it has to be overridden
        // here - the whole point of this class is to run against a database that has SKIP LOCKED.
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // Switched on only here: this is the one database that can run the claim query.
        registry.add("outbox.relay.enabled", () -> "true");
        registry.add("outbox.relay.fixed-delay-ms", () -> "500");
        // Kafka starts out unreachable; brought up below by re-pointing the producer factory.
        registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProducerFactory<String, Object> producerFactory;

    /** The saga's remote half is not what is under test here. */
    @MockBean
    private InventoryClientAdapter inventoryClientAdapter;

    @Test
    void anOrderPlacedWhileKafkaIsDown_isStillPlacedAndItsEventIsDeliveredOnceKafkaIsBack() {
        when(inventoryClientAdapter.reserveStock(any())).thenAnswer(invocation -> {
            StockReservationRequest request = invocation.getArgument(0);
            return new StockReservationResponse(true, request.sku(), 5, "Reserved", new BigDecimal("10.00"));
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<OrderResponse> placed = restTemplate.postForEntity(
                "/api/orders", new HttpEntity<>(ORDER_BODY, headers), OrderResponse.class);

        // 1. The order is placed even though nothing could be published, and the event is durable.
        assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderNumber = placed.getBody().orderNumber();
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        OutboxEvent written = outboxEventRepository.findAll().get(0);
        assertThat(written.getAggregateId()).isEqualTo(orderNumber);
        assertThat(written.getTopic()).isEqualTo(ORDER_EVENTS);
        assertThat(written.getEventId()).isNotBlank();
        Long rowId = written.getId();

        // 2. The relay really ran FOR UPDATE SKIP LOCKED against Postgres, and really failed to send.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            OutboxEvent polled = outboxEventRepository.findById(rowId).orElseThrow();
            assertThat(polled.getStatus()).isEqualTo(OutboxStatus.NEW);
            assertThat(polled.getAttempts()).isPositive();
            assertThat(polled.getSentAt()).isNull();
        });

        // 3. Kafka comes back: re-point the factory and drop its cached producer.
        DefaultKafkaProducerFactory<String, Object> factory =
                (DefaultKafkaProducerFactory<String, Object>) producerFactory;
        factory.updateConfigs(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
        factory.reset();

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            OutboxEvent polled = outboxEventRepository.findById(rowId).orElseThrow();
            assertThat(polled.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(polled.getSentAt()).isNotNull();
        });

        // 4. And what landed on the topic is what notification-service can actually read. Subscribing
        //    only now, with auto.offset.reset=earliest, avoids a topic-metadata race.
        try (Consumer<String, String> consumer = rawConsumer()) {
            consumer.subscribe(List.of(ORDER_EVENTS));
            ConsumerRecords<String, String> records =
                    KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(30), 1);
            List<ConsumerRecord<String, String>> delivered = new java.util.ArrayList<>();
            records.records(ORDER_EVENTS).forEach(delivered::add);

            assertThat(delivered).isNotEmpty();
            assertThat(delivered).allSatisfy(record -> {
                assertThat(record.key()).isEqualTo(orderNumber);            // partitioned by order
                assertThat(typeIdHeaderOf(record)).isEqualTo("order-event"); // the mapped token
                assertThat(record.value()).contains(written.getEventId());   // de-dup key on the wire
            });
        }
    }

    private static String typeIdHeaderOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(TYPE_ID_HEADER);
        return header == null ? null : new String(header.value());
    }

    private Consumer<String, String> rawConsumer() {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "outbox-it", "true");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }
}
