package dev.andreydg.solarsystem.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built single-page app and falls back to its shell for client-side routes.
 *
 * <p>This runs at the static-resource layer (after controllers), so it serves real files when
 * they exist and only returns {@code index.html} for extension-less client routes. That handles
 * nested asset paths (e.g. {@code /textures/mars.jpg}) correctly without a per-directory route
 * whitelist — a request that names a missing file 404s instead of being handed the SPA shell.
 */
@Configuration
public class SpaResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(new SpaPathResourceResolver());
    }

    private static final class SpaPathResourceResolver extends PathResourceResolver {
        private final Resource index = new ClassPathResource("/static/index.html");

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            if (resourcePath.isEmpty() || "index.html".equals(resourcePath)) {
                return index;
            }

            // Never hand API/actuator requests the SPA shell — let them resolve or 404 normally.
            boolean reserved = resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/");

            String lastSegment = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            if (lastSegment.contains(".")) {
                // Looks like a file: serve it if present, otherwise 404 (don't fall back to the shell).
                if (reserved) {
                    return null;
                }
                Resource requested = location.createRelative(resourcePath);
                return requested.exists() && requested.isReadable() ? requested : null;
            }

            // Extension-less path: a client-side route → SPA shell (but not reserved API namespaces).
            return reserved ? null : index;
        }
    }
}
