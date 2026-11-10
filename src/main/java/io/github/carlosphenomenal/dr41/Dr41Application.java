package io.github.carlosphenomenal.dr41;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Dr41Application {
    static void main(String[] args) {
        SpringApplication.run(Dr41Application.class, args);
    }
}
