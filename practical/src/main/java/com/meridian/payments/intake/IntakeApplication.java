package com.meridian.payments.intake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IntakeApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntakeApplication.class, args);
    }
}
