package io.github.classgraph.issues.issue600;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ScanResult;

class Issue600Test {
    private static final int BUFFER_SIZE = 8192;
    private static final int EOF = -1;

    private final ClassGraph classGraph = new ClassGraph().enableClassInfo()
            .acceptPackages(getClass().getPackage().getName());

    @Test
    void testResourcesCanBeOpened() {
        try (ScanResult scanResult = classGraph.scan()) {
            final ResourceList resources = scanResult.getAllResources();
            assertFalse(resources.isEmpty(), "Test is meaningless without resources to open.");

            // Check we can open the resources.
            assertOpenCloseResources(resources);

            // And check we can reopen the resources.
            assertOpenCloseResources(resources);
        }
    }

    @Test
    void testResourcesCanBeRead() {
        try (ScanResult scanResult = classGraph.scan()) {
            final ResourceList resources = scanResult.getAllResources();
            assertFalse(resources.isEmpty(), "Test is meaningless without resources to open.");

            // Check we can read the resources.
            assertReadCloseResources(resources);

            // Check we can reread the resources.
            assertReadCloseResources(resources);
        }
    }

    private void assertOpenCloseResources(final ResourceList resources) {
        for (final Resource resource : resources) {
            assertDoesNotThrow(new Executable() {
                @Override
                public void execute() throws Throwable {
                    try (InputStream input = resource.open()) {
                        assertThat(consume(input)).isGreaterThan(0);
                    }
                }
            }, "Resource " + resource.getPath() + " should be closed.");
        }
    }

    private int consume(final InputStream input) throws IOException {
        final byte[] buffer = new byte[BUFFER_SIZE];
        int totalBytes = 0;
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != EOF) {
            totalBytes += bytesRead;
        }
        return totalBytes;
    }

    private void assertReadCloseResources(final ResourceList resources) {
        for (final Resource resource : resources) {
            assertDoesNotThrow(new Executable() {
                @Override
                public void execute() throws Throwable {
                    final ByteBuffer buffer = resource.read();
                    try {
                        assertTrue(buffer.hasRemaining());
                    } finally {
                        resource.close();
                    }
                }
            }, "Resource " + resource.getPath() + " should be closed.");
        }
    }

    public interface Api {
    }

    @SuppressWarnings("unused")
    public static class Example implements Api {
    }
}
