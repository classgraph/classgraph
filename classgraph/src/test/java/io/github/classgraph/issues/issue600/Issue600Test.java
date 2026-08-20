package io.github.classgraph.issues.issue600;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ResourceList;

class Issue600Test {
    private static final int BUFFER_SIZE = 8192;
    private static final int EOF = -1;

    private final ClassGraph classGraph = new ClassGraph().enableClassInfo()
            .acceptPackages(getClass().getPackage().getName());

    @Test
    void testResourcesCanBeOpened() {
        try (var scanResult = classGraph.scan()) {
            final var resources = scanResult.getAllResources();
            assertFalse(resources.isEmpty(), "Test is meaningless without resources to open.");

            // Check we can open the resources.
            assertOpenCloseResources(resources);

            // And check we can reopen the resources.
            assertOpenCloseResources(resources);
        }
    }

    @Test
    void testResourcesCanBeRead() {
        try (var scanResult = classGraph.scan()) {
            final var resources = scanResult.getAllResources();
            assertFalse(resources.isEmpty(), "Test is meaningless without resources to open.");

            // Check we can read the resources.
            assertReadCloseResources(resources);

            // Check we can reread the resources.
            assertReadCloseResources(resources);
        }
    }

    private static void assertOpenCloseResources(final ResourceList resources) {
        for (final Resource resource : resources) {
            assertDoesNotThrow((Executable) () -> {
                try (var input = resource.open()) {
                    assertThat(consume(input)).isGreaterThan(0);
                }
            }, "Resource " + resource.getPath() + " should be closed.");
        }
    }

    private static int consume(final InputStream input) throws IOException {
        final var buffer = new byte[BUFFER_SIZE];
        var totalBytes = 0;
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != EOF) {
            totalBytes += bytesRead;
        }
        return totalBytes;
    }

    private static void assertReadCloseResources(final ResourceList resources) {
        for (final Resource resource : resources) {
            assertDoesNotThrow((Executable) () -> {
                final var buffer = resource.read().getByteBuffer();
                try {
                    assertTrue(buffer.hasRemaining());
                } finally {
                    resource.close();
                }
            }, "Resource " + resource.getPath() + " should be closed.");
        }
    }

    public interface Api {
    }

    public static class Example implements Api {
    }
}
