package dev.andreydg.solarsystem.config;

import com.google.cloud.firestore.Firestore;
import dev.andreydg.solarsystem.storage.FirestoreIndexMigration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "solar-system.migration.enabled", havingValue = "true")
public class CatalogMigrationRunner implements CommandLineRunner {
    private final Firestore firestore;
    private final FirestoreProperties properties;
    private final boolean apply;
    public CatalogMigrationRunner(Firestore firestore, FirestoreProperties properties,
        @Value("${solar-system.migration.apply:false}") boolean apply) {
        this.firestore = firestore;
        this.properties = properties;
        this.apply = apply;
    }
    @Override public void run(String... args) throws Exception {
        var result = new FirestoreIndexMigration(firestore, properties.collection()).run(apply);
        System.out.println("Catalog migration " + (apply ? "APPLY" : "DRY RUN") + ": " + result);
        if (result.malformed() > 0) throw new IllegalStateException("Repair malformed records and rerun; migration was not activated");
    }
}
