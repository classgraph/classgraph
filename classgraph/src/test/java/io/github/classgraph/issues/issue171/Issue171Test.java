package io.github.classgraph.issues.issue171;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue171Test {
    /**
     * Spring boot fully executable jar.
     */
    @Test
    public void springBootFullyExecutableJar() {
        final var jarURL = Issue171Test.class.getClassLoader().getResource("spring-boot-fully-executable-jar.jar");

        try (var scanResult = new ClassGraph().acceptPackagesNonRecursive("hello", "org.springframework.boot")
                .overrideClasspath("jar:" + jarURL + "!/BOOT-INF/classes") //
                .scan()) {
            final var classNames = scanResult.getAllClasses().getNames();
            assertThat(classNames).contains("hello.HelloController",
                    // BOOT-INF/lib should be added automatically to the classpath to be scanned
                    "org.springframework.boot.ApplicationHome");
        }
    }
}
