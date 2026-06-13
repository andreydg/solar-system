package dev.andreydg.solarsystem.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({FirestoreProperties.class, JplProperties.class})
public class AppPropertiesConfig {
}
