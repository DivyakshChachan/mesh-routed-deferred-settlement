package com.demo.upimesh.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Automatically supports Docker Secrets (_FILE suffixed environment variables).
 * For any environment variable ending in _FILE (e.g. SPRING_DATASOURCE_PASSWORD_FILE),
 * reads the file content and populates the corresponding Spring property (e.g. spring.datasource.password).
 */
public class DockerSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DockerSecretsEnvironmentPostProcessor.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> secretsMap = new HashMap<>();

        Map<String, String> envVars = System.getenv();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("_FILE") && entry.getValue() != null && !entry.getValue().isBlank()) {
                Path filePath = Path.of(entry.getValue());
                if (Files.exists(filePath)) {
                    try {
                        String content = Files.readString(filePath).trim();
                        // Convert SPRING_DATASOURCE_PASSWORD_FILE -> spring.datasource.password
                        String targetProp = key.substring(0, key.length() - 5)
                                .toLowerCase()
                                .replace('_', '.');
                        secretsMap.put(targetProp, content);
                        log.info("Loaded Docker secret from file {} into property {}", entry.getValue(), targetProp);
                    } catch (IOException e) {
                        log.warn("Failed to read Docker secret file {}: {}", entry.getValue(), e.getMessage());
                    }
                }
            }
        }

        if (!secretsMap.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("dockerSecrets", secretsMap));
        }
    }
}
