package io.github.carlosphenomenal.dr41;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class Dr41Application {

    public static void main(String[] args) {
        SpringApplication.run(Dr41Application.class, args);
    }

}
