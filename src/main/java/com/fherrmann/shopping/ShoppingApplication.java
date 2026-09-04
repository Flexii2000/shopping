package com.fherrmann.shopping;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

@SpringBootApplication
@EnableScheduling
public class ShoppingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShoppingApplication.class, args);
    }

    /** Injizierbar, damit Tests "jetzt" festnageln koennen. */
    @Bean
    public Clock clock(@Value("${shopping.zone}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
