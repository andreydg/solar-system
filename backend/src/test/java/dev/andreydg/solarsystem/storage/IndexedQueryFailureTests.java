package dev.andreydg.solarsystem.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.cloud.firestore.*;
import dev.andreydg.solarsystem.catalog.*;
import dev.andreydg.solarsystem.config.FirestoreProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class IndexedQueryFailureTests {
    @Test void buildingIndexIsServiceUnavailableNotAnInternalError(org.springframework.boot.test.system.CapturedOutput output) {
        var firestore = mock(Firestore.class);
        var collection = mock(CollectionReference.class, RETURNS_SELF);
        var metadata = mock(DocumentReference.class);
        var snapshot = mock(DocumentSnapshot.class);
        when(firestore.collection(anyString())).thenReturn(collection);
        when(collection.document("events")).thenReturn(metadata);
        when(metadata.get()).thenReturn(com.google.api.core.ApiFutures.immediateFuture(snapshot));
        when(snapshot.getBoolean("indexedQueriesReady")).thenReturn(true);
        when(collection.get()).thenReturn(com.google.api.core.ApiFutures.immediateFailedFuture(
            new com.google.api.gax.rpc.FailedPreconditionException("Index building", null,
                com.google.api.gax.grpc.GrpcStatusCode.of(io.grpc.Status.Code.FAILED_PRECONDITION), false)));
        var repository = new FirestoreEventCatalogRepository(firestore, new FirestoreProperties("events", "(default)", ""));
        var query = new CatalogQuery(null, null, null, Instant.parse("2027-01-01T00:00:00Z"),
            Instant.parse("2028-01-01T00:00:00Z"), false, false, 1, null);
        assertThatThrownBy(() -> repository.search(query))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("503");
        assertThat(output.getAll()).contains("Catalog index query is not ready", "Index building");
    }
}
