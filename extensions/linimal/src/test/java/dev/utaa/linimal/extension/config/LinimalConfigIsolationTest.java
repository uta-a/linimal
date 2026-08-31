package dev.utaa.linimal.extension.config;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public final class LinimalConfigIsolationTest {
    @Test
    public void onlyConfigStoreReferencesSharedPreferences() throws IOException {
        Path sourceRoot = Paths.get("src", "main", "java");
        if (!Files.isDirectory(sourceRoot)) {
            sourceRoot = Paths.get("extensions", "linimal", "src", "main", "java");
        }
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(this::assertPersistenceIsolation);
        }
    }

    @Test
    public void everyPersistedFeatureKeyUsesLinimalNamespace() {
        for (LinimalFeature feature : LinimalFeature.values()) {
            assertTrue(LinimalConfigSchema.keyFor(feature).startsWith("linimal."));
        }
        assertTrue(LinimalConfigSchema.SCHEMA_VERSION_KEY.startsWith("linimal."));
        assertTrue(LinimalConfigSchema.READ_RECEIPT_MODE_KEY.startsWith("linimal."));
    }

    private void assertPersistenceIsolation(Path path) {
        try {
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            boolean referencesPreferences = source.contains("SharedPreferences")
                    || source.contains("getSharedPreferences(");
            if (referencesPreferences) {
                assertTrue(
                        "Only LinimalConfigStore may access SharedPreferences: " + path,
                        path.getFileName().toString().equals("LinimalConfigStore.java"));
            }
        } catch (IOException exception) {
            throw new AssertionError("Unable to inspect source: " + path, exception);
        }
    }
}
