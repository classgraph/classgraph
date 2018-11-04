package io.github.classgraph;

import org.openjdk.jmh.annotations.*;

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
    @BenchmarkMode(Mode.SingleShotTime)
    public void springBootFullyExecutableJar() {
        try (ScanResult scanResult = new ClassGraph()
                .enableClassInfo()
                .overrideClasspath(jarURL)
                .scan()) {
            assertThat(scanResult.getAllClasses()).isNotEmpty();
        }
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(new String[]{NestedJarPerformanceTest.class.getName()});
    }
}
