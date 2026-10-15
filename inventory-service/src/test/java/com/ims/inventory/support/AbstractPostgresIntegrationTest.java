package com.ims.inventory.support;

import com.ims.inventory.entity.Product;
import com.ims.inventory.event.InventoryEventPublisher;
import com.ims.inventory.repository.ProductRepository;
import com.ims.inventory.repository.StockReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;

/**
 * Base class for the stock-reservation integration tests, which need a real Postgres rather than
 * the H2 the rest of this module runs on. Both guarantees they cover are database behaviour, not
 * application logic:
 *
 * <ul>
 *   <li>{@code @Version} optimistic locking - whether a lost update is actually detected depends on
 *       the database's write-conflict handling, so "20 threads, one unit of stock" only means
 *       something against the engine that runs in production.</li>
 *   <li>Unique-constraint naming - {@code ProductServiceImpl} decides whether a
 *       {@code DataIntegrityViolationException} is a replayable duplicate by inspecting the
 *       constraint <em>name</em>, and H2 and Postgres do not name constraints the same way.</li>
 * </ul>
 *
 * <p>The container is a singleton started from a static initialiser rather than a {@code @Container}
 * field, so every subclass shares one Postgres instead of paying a fresh startup each. Nothing stops
 * it: Testcontainers' Ryuk sidecar removes it when the JVM exits. Because the dynamic properties and
 * the mock below are identical for every subclass, Spring also caches a single application context
 * across them - keep them that way, or each subclass starts paying for its own context.
 *
 * <p>{@code @Testcontainers(disabledWithoutDocker = true)} makes these tests skip, not fail, where
 * there is no Docker environment. The image tag matches {@code docker-compose.yml}. For why
 * {@code src/test/resources/docker-java.properties} has to exist for any of this to start at all,
 * read that file.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
public abstract class AbstractPostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The module's test application.yml hard-codes org.h2.Driver, so it has to be overridden
        // here - the whole point of these tests is to run somewhere other than H2.
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    protected ProductRepository productRepository;

    @Autowired
    protected StockReservationRepository reservationRepository;

    /**
     * Reserving the last unit of stock leaves the product at or below its reorder threshold, which
     * makes {@code StockReservationExecutor} publish a low-stock event. Mocked rather than backed by
     * a Kafka container: there is no broker here, and {@code KafkaTemplate.send} blocks on metadata
     * for {@code max.block.ms} before failing, which would dominate the timing these tests measure.
     * The event is not what is under test - {@code InventoryEventPublisher} has its own coverage.
     */
    @MockBean
    protected InventoryEventPublisher eventPublisher;

    /** The container outlives each test, so the tables have to be emptied between them. */
    @BeforeEach
    void clearInventoryTables() {
        reservationRepository.deleteAll();
        productRepository.deleteAll();
    }

    protected Product givenProduct(String sku, int quantity, int reorderThreshold) {
        return productRepository.save(Product.builder()
                .sku(sku)
                .name("Product " + sku)
                .description("fixture")
                .price(new BigDecimal("9.99"))
                .quantity(quantity)
                .reorderThreshold(reorderThreshold)
                .build());
    }

    protected int quantityOf(String sku) {
        return productRepository.findBySku(sku).orElseThrow().getQuantity();
    }
}
