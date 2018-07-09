package io.github.lukehutch.fastclasspathscanner.issues.issue171;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.util.Set;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue171Test {
    @Test
    public void springBootFullyExecutableJar() throws IOException {
        final URL jarURL = Issue171Test.class.getClassLoader().getResource("spring-boot-fully-executable-jar.jar");

        final Set<String> classNames = new FastClasspathScanner("hello", "org.springframework.boot")
                .overrideClasspath(jarURL + "!/" + "BOOT-INF/classes") //
                .disableRecursiveScanning() //
                .scan() //
                .getClassNameToClassInfo().keySet();

        assertThat(classNames).contains("hello.HelloController",
                // BOOT-INF/lib should be added automatically to the classpath to be scanned
                "org.springframework.boot.ApplicationHome");
    }
}
