package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * {@link ClassGraphClassLoader#findClass(String)} null-checks both classloader
 * delegation orders before iterating them, but {@code getResource},
 * {@code getResources} and {@code getResourceAsStream} did not:
 * {@code addedClassLoaderDelegationOrder} is null unless
 * {@link ClassGraph#addClassLoader(ClassLoader)} was called, and the first
 * entry of {@code environmentClassLoaderDelegationOrder} is a null
 * {@link ClassLoader} standing for the bootstrap classloader, so both were
 * dereferenced without a check.
 */
public class ClassGraphClassLoaderResourceTest {
    /** A resource that is present on the test classpath. */
    private static final String RESOURCE_PATH = "file-content-test.txt";

    /**
     * The resource accessors do not throw when no extra classloaders were added.
     */
    @Test
    public void resourceAccessorsWithNoAddedClassLoaders() {
        try (var scanResult = new ClassGraph().acceptPaths("").scan()) {
            final var classLoader = new ClassGraphClassLoader(scanResult);

            assertThatCode(() -> {
                final var resource = classLoader.getResource(RESOURCE_PATH);
                assertThat(resource).isNotNull();

                final var resources = classLoader.getResources(RESOURCE_PATH);
                assertThat(resources.hasMoreElements()).isTrue();

                final var inputStream = classLoader.getResourceAsStream(RESOURCE_PATH);
                assertThat(inputStream).isNotNull();
                inputStream.close();
            }).doesNotThrowAnyException();
        }
    }

    /**
     * The resource accessors do not throw when the classpath is overridden, which
     * leaves the env order null.
     */
    @Test
    public void resourceAccessorsWithOverriddenClasspath() throws IOException {
        try (var scanResult = new ClassGraph().overrideClasspath(System.getProperty("java.class.path")).acceptPaths("")
                .scan()) {
            final var classLoader = new ClassGraphClassLoader(scanResult);

            assertThatCode(() -> {
                classLoader.getResource(RESOURCE_PATH);
                classLoader.getResources(RESOURCE_PATH);
                final var inputStream = classLoader.getResourceAsStream(RESOURCE_PATH);
                if (inputStream != null) {
                    inputStream.close();
                }
            }).doesNotThrowAnyException();
        }
    }
}
