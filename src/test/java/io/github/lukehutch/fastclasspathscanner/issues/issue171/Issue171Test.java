package io.github.lukehutch.fastclasspathscanner.issues.issue171;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue171Test {
    @Test
    public void springBootFullyExecutableJar() throws IOException {
        final URL jarURL = Issue171Test.class.getClassLoader().getResource("spring-boot-fully-executable-jar.jar");

        final List<String> classNames = new FastClasspathScanner()
                .whitelistPackagesNonRecursive("hello", "org.springframework.boot")
                .overrideClasspath(jarURL + "!/" + "BOOT-INF/classes") //
                .scan() //
                .getAllClasses().getNames();

        assertThat(classNames).contains("hello.HelloController",
                // BOOT-INF/lib should be added automatically to the classpath to be scanned
                "org.springframework.boot.ApplicationHome");
    }
}
