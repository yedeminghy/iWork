package dev.agentdesktop.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.host.bootstrap.BootstrapConfig;
import dev.agentdesktop.host.bootstrap.BootstrapReader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@SpringBootApplication
public class AgentHostApplication {
    public static void main(String[] args) {
        BufferedReader parentInput = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BootstrapConfig bootstrap = new BootstrapReader(new ObjectMapper()).read(
                parentInput, Duration.ofSeconds(10));
        SpringApplication application = new SpringApplication(AgentHostApplication.class);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("bootstrapConfig", bootstrap);
            context.getBeanFactory().registerSingleton("parentInputChannel", new ParentInputChannel(parentInput));
        });
        application.run(args);
    }
}
