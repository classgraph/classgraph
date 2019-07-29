package io.github.classgraph.issues.issue289;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ScanResult;

/**
 * Issue289.
 */
public class Issue289 {

    /**
     * Issue 289.
     *
     * @throws Exception
     *             the exception
     */
    @Test
    public void issue289() throws Exception {
        try (ScanResult scanResult = new ClassGraph()
                .overrideClassLoaders(
                        new URLClassLoader(new URL[] { Issue289.class.getClassLoader().getResource("zip64.zip") }))
                .scan()) {
            for (int i = 0; i < 90000; i++) {
                final ResourceList resources = scanResult.getResourcesWithPath(i + "");
                assertThat(resources).isNotEmpty();
            }
        }
    }
}
