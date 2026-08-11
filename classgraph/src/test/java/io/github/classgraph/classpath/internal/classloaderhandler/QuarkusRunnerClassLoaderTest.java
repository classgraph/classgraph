package io.github.classgraph.classpath.internal.classloaderhandler;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.quarkus.bootstrap.runner.RunnerClassLoader;

/**
 * Test that {@code QuarkusClassLoaderHandler} does not throw when Quarkus' {@code RunnerClassLoader} does not have
 * the {@code resourceDirectoryMap} field that the handler reads by reflection (Quarkus renames these fields between
 * releases -- the handler already tolerates this for the {@code QuarkusClassLoader} field {@code elements}).
 */
public class QuarkusRunnerClassLoaderTest {
    /**
     * Scanning with a RunnerClassLoader that has no resourceDirectoryMap field should not throw.
     */
    @Test
    public void runnerClassLoaderWithoutResourceDirectoryMap() {
        assertThatCode(() -> {
            try (var scanResult = new ClassGraph().overrideClassLoaders(new RunnerClassLoader())
                    .acceptPackages("io.github.classgraph.classpath.internal.classloaderhandler").scan()) {
                scanResult.getAllClasses();
            }
        }).doesNotThrowAnyException();
    }
}
