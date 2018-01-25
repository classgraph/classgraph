package io.github.lukehutch.fastclasspathscanner.issues.issue171;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FilenameMatchProcessor;

public class Issue171Test {
    @Test
    public void springBootFullyExecutableJar() throws IOException {
        final URL jarURL = Issue171Test.class.getClassLoader().getResource("spring-boot-fully-executable-jar.jar");
        final String childPath = "BOOT-INF/classes";

        //        final File parentJarfile = new File(jarURL.getFile());
        //        try (ZipFile zipFile = new ZipFile(new File(jarURL.getFile()))) {
        //            final ZipEntry ent = zipFile.getEntry(childPath);
        //            System.out.println("Child path component " + childPath + " in jarfile " + parentJarfile
        //                    + " is a directory: " + ent.isDirectory());
        //        } catch (final IOException e) {
        //            e.printStackTrace();
        //        }
        final List<String> paths = new ArrayList<>();
        new FastClasspathScanner().overrideClasspath(jarURL + "!/" + childPath)
                .matchFilenamePattern(".*", (FilenameMatchProcessor) (classpathElt, relativePath) -> {
                    paths.add(relativePath);
                }).scan();
        assertThat(paths).containsOnly("hello/HelloController.class", "hello/Application.class");
    }
}
