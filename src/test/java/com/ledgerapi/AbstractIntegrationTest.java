package com.ledgerapi;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that need a real MySQL database and a real Kafka broker.
 * Both containers are started once, statically, and shared across every test class that extends
 * this one - Testcontainers' Ryuk resource reaper kills them when the JVM exits, so the cost of
 * starting them is paid once per test run, not once per test class.
 *
 * <p>Liquibase runs for real against the MySQL container with the very same changelog used in
 * production ({@code db/changelog/db.changelog-master.yaml}), so these tests exercise the actual
 * schema - including the seeded settlement accounts - not an approximation of it.
 */
@Testcontainers
public abstract class AbstractIntegrationTest {

    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("ledger_api")
                    .withReuse(true);

    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))
                    .withReuse(true);

    static {
        MYSQL.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.liquibase.enabled", () -> true);
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}

