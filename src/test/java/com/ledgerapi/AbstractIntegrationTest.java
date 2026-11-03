package com.ledgerapi;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that need a real MySQL database. The container is started
 * once, statically, and shared across every test class that extends this one - Testcontainers'
 * Ryuk resource reaper kills it when the JVM exits, so the cost of starting MySQL is paid once
 * per test run, not once per test class.
 *
 * <p>Liquibase runs for real against this container with the very same changelog used in
 * production ({@code db/changelog/db.changelog-master.yaml}), so these tests exercise the actual
 * schema - including the seeded settlement accounts - not an approximation of it.
 */
@Testcontainers
public abstract class AbstractIntegrationTest {

    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("ledger_api")
                    .withReuse(true);

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.liquibase.enabled", () -> true);
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}

