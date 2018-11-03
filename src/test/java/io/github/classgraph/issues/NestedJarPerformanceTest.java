package io.github.classgraph.issues;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.junit.Before;
import org.junit.Test;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;

public class NestedJarPerformanceTest {
    private URL jarURL;

    @Before
    public void setUp() {
        jarURL = NestedJarPerformanceTest.class.getClassLoader().getResource("spring-boot-fully-executable-jar.jar");
    }

    @Test
    public void springBootFullyExecutableJar() {

        long start = System.currentTimeMillis();

        for (int i = 0; i < 10; i++) {
            performFullNestedJarScan();
        }

        long end = System.currentTimeMillis();

        System.out.println("Full nested jar scan took ~" + ((end - start) / 10) + "ms");
    }

    private void performFullNestedJarScan() {
        try (ScanResult scanResult = new ClassGraph()
                .enableClassInfo()
                .overrideClasspath(jarURL)
                .scan()) {
            assertThat(scanResult.getAllClasses()).isNotEmpty();
        }
    }
}
