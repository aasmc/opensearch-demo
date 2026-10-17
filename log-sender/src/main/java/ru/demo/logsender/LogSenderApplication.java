package ru.demo.logsender;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LogSenderApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogSenderApplication.class, args);
    }
}
