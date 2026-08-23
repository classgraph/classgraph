package io.github.classgraph.issues.issue171;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue171Test {
    /**
     * The classes and the dependency jarfiles of a Spring Boot fully executable jar can be scanned by naming them
     * on an overridden classpath. An overridden classpath is scanned exactly as it is written: no classloader was
     * involved in finding it, so nothing is added to it, and {@code BOOT-INF/lib} is only scanned if it is named.
     */
    @Test
    public void springBootFullyExecutableJar() {
        final var jarURL = Issue171Test.class.getClassLoader().getResource("spring-boot-fully-executable-jar.jar");

        try (var scanResult = new ClassGraph().acceptPackagesNonRecursive("hello", "org.springframework.boot")
                .overrideClasspath(List.of("jar:" + jarURL + "!/BOOT-INF/classes",
                        "jar:" + jarURL + "!/BOOT-INF/lib/spring-boot-1.5.9.RELEASE.jar")) //
                .scan()) {
            final var classNames = scanResult.getAllClasses().getNames();
            assertThat(classNames).contains("hello.HelloController", "org.springframework.boot.ApplicationHome");
        }
    }

    /** Naming only {@code BOOT-INF/classes} scans only the application's own classes. */
    @Test
    public void springBootClassesRootWithoutTheLibDir() {
        final var jarURL = Issue171Test.class.getClassLoader().getResource("spring-boot-fully-executable-jar.jar");

        try (var scanResult = new ClassGraph().acceptPackagesNonRecursive("hello", "org.springframework.boot")
                .overrideClasspath("jar:" + jarURL + "!/BOOT-INF/classes") //
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactlyInAnyOrder("hello.HelloController",
                    "hello.Application");
        }
    }
}
