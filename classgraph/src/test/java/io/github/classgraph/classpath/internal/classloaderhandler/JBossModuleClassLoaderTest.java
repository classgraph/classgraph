package io.github.classgraph.classpath.internal.classloaderhandler;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.jboss.modules.ModuleClassLoader;
import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

/**
 * Test that {@code JBossClassLoaderHandler} does not throw when a {@code ModuleClassLoader} does not have the
 * {@code getPaths()} method that the handler calls by reflection. The handler already null-checks the
 * {@code moduleMap} field it reads just above, so it should tolerate a missing {@code getPaths()} too.
 */
public class JBossModuleClassLoaderTest {
    /**
     * Scanning with a ModuleClassLoader that has no getPaths() method should not throw.
     */
    @Test
    public void moduleClassLoaderWithoutGetPaths() {
        assertThatCode(() -> {
            try (var scanResult = new ClassGraph().overrideClassLoaders(new ModuleClassLoader())
                    .acceptPackages("io.github.classgraph.classpath.internal.classloaderhandler").scan()) {
                scanResult.getAllClasses();
            }
        }).doesNotThrowAnyException();
    }
}
