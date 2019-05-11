package io.github.classgraph.issues.issue345;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.AbstractQueue;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue345.
 */
public class Issue345 {
    /**
     * Issue 345.
     */
    @Test
    public void issue345() {
        try (ScanResult scanResult = new ClassGraph().enableClassInfo().enableSystemJarsAndModules()
                .overrideClassLoaders(new URLClassLoader(
                        new URL[] { Issue345.class.getResource("/java/util/ArrayBlockingQueue.class"),
                                Issue345.class.getResource("/java/util/AbstractQueue.class"), }))
                .scan()) {
            assertThat(scanResult.getClassInfo(AbstractQueue.class.getName()).getResource()).isNotNull();
        }
    }
}
