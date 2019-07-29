package io.github.classgraph.issues.issue171;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue171Test.
 */
public class Issue171Test {
    /**
     * Spring boot fully executable jar.
     */
    @Test
    public void springBootFullyExecutableJar() {
        final URL jarURL = Issue171Test.class.getClassLoader().getResource("spring-boot-fully-executable-jar.jar");

        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackagesNonRecursive("hello", "org.springframework.boot")
                .overrideClasspath(jarURL + "!BOOT-INF/classes") //
                .scan()) {
            final List<String> classNames = scanResult.getAllClasses().getNames();
            assertThat(classNames).contains("hello.HelloController",
                    // BOOT-INF/lib should be added automatically to the classpath to be scanned
                    "org.springframework.boot.ApplicationHome");
        }
    }
}
