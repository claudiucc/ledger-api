package com.ledgerapi;

import com.ledgerapi.config.LedgerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableKafka
@EnableConfigurationProperties(LedgerProperties.class)
@SpringBootApplication
public class LedgerApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(LedgerApiApplication.class, args);
  }

}
