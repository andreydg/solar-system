package dev.andreydg.solarsystem.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnProperty(name = "solar-system.storage", havingValue = "firestore", matchIfMissing = true)
public class FirestoreConfig {

    @Bean
    public Firestore firestore(FirestoreProperties properties) throws IOException {
        FirestoreOptions.Builder builder = FirestoreOptions.getDefaultInstance().toBuilder();

        if (StringUtils.hasText(properties.databaseId())) {
            builder.setDatabaseId(properties.databaseId());
        }

        if (StringUtils.hasText(properties.emulatorHost())) {
            builder
                .setHost(properties.emulatorHost())
                .setCredentials(new EmulatorCredentials());
        } else {
            builder.setCredentials(GoogleCredentials.getApplicationDefault());
        }

        return builder.build().getService();
    }
}
