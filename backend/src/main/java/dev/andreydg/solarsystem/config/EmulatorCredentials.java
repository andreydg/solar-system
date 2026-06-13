package dev.andreydg.solarsystem.config;

import com.google.auth.Credentials;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

public class EmulatorCredentials extends Credentials {

    @Override
    public String getAuthenticationType() {
        return "emulator";
    }

    @Override
    public Map<String, List<String>> getRequestMetadata(URI uri) {
        return Map.of();
    }

    @Override
    public boolean hasRequestMetadata() {
        return false;
    }

    @Override
    public boolean hasRequestMetadataOnly() {
        return false;
    }

    @Override
    public void refresh() throws IOException {
    }
}
