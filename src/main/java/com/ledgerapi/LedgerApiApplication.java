package com.ledgerapi;

// import com.ledgerapi.config.LedgerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// import org.springframework.boot.context.properties.EnableConfigurationProperties;
// import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling (outbox poller) and ledger.* config properties are disabled for now,
// being wired back in once the plain API layer works end-to-end.
// @EnableScheduling
// @EnableConfigurationProperties(LedgerProperties.class)
@SpringBootApplication
public class LedgerApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(LedgerApiApplication.class, args);
  }

}
