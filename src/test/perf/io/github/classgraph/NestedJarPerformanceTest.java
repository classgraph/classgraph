package io.github.classgraph;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;

@State(Scope.Benchmark)
public class NestedJarPerformanceTest {
    private URL jarURL;

    @Setup
    public void setUp() {
        jarURL = NestedJarPerformanceTest.class.getClassLoader().getResource("spring-boot-fully-executable-jar.jar");
    }

    @Benchmark
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
