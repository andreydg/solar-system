package dev.andreydg.solarsystem.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({FirestoreProperties.class, JplProperties.class, TrajectoryCacheProperties.class})
public class AppPropertiesConfig {
}
