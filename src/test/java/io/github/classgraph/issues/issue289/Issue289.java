package io.github.classgraph.issues.issue289;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ScanResult;

public class Issue289 {
    @Test
    public void multiReleaseJar() {
        try (ScanResult scanResult = new ClassGraph()
                .overrideClassLoaders(
                        new URLClassLoader(new URL[] { Issue289.class.getClassLoader().getResource("zip64.zip") }))
                .verbose().scan()) {
            try {
                for (int i = 1; i < 90000; i++) {
                    final ResourceList resources = scanResult.getResourcesWithPath(i + "");
                    if (resources.isEmpty()) {
                        throw new RuntimeException("Couldn't find resource " + i);
                    }
                    System.out.println(resources.get(0));
                }
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
