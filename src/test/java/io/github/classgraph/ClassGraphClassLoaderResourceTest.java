package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * {@link ClassGraphClassLoader#findClass(String)} null-checks both classloader delegation orders before iterating
 * them, but {@code getResource}, {@code getResources} and {@code getResourceAsStream} did not:
 * {@code addedClassLoaderDelegationOrder} is null unless {@link ClassGraph#addClassLoader(ClassLoader)} was called,
 * and the first entry of {@code environmentClassLoaderDelegationOrder} is a null {@link ClassLoader} standing for
 * the bootstrap classloader, so both were dereferenced without a check.
 */
public class ClassGraphClassLoaderResourceTest {
    /** A resource that is present on the test classpath. */
    private static final String RESOURCE_PATH = "file-content-test.txt";

    /** The resource accessors do not throw when no extra classloaders were added. */
    @Test
    public void resourceAccessorsWithNoAddedClassLoaders() {
        try (ScanResult scanResult = new ClassGraph().acceptPaths("").scan()) {
            final ClassGraphClassLoader classLoader = new ClassGraphClassLoader(scanResult);

            assertThatCode(new ThrowingCallable() {
                @Override
                public void call() throws Exception {
                    final URL resource = classLoader.getResource(RESOURCE_PATH);
                    assertThat(resource).isNotNull();

                    final Enumeration<URL> resources = classLoader.getResources(RESOURCE_PATH);
                    assertThat(resources.hasMoreElements()).isTrue();

                    final InputStream inputStream = classLoader.getResourceAsStream(RESOURCE_PATH);
                    assertThat(inputStream).isNotNull();
                    inputStream.close();
                }
            }).doesNotThrowAnyException();
        }
    }

    /** The resource accessors do not throw when the classpath is overridden, which leaves the env order null. */
    @Test
    public void resourceAccessorsWithOverriddenClasspath() throws IOException {
        try (ScanResult scanResult = new ClassGraph()
                .overrideClasspath(System.getProperty("java.class.path")).acceptPaths("").scan()) {
            final ClassGraphClassLoader classLoader = new ClassGraphClassLoader(scanResult);

            assertThatCode(new ThrowingCallable() {
                @Override
                public void call() throws Exception {
                    classLoader.getResource(RESOURCE_PATH);
                    classLoader.getResources(RESOURCE_PATH);
                    final InputStream inputStream = classLoader.getResourceAsStream(RESOURCE_PATH);
                    if (inputStream != null) {
                        inputStream.close();
                    }
                }
            }).doesNotThrowAnyException();
        }
    }
}
