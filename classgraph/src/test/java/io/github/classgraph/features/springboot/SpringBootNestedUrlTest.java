package io.github.classgraph.features.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.base.internal.utils.FastPathResolver;

/**
 * Spring Boot 3.2 and later address an entry within an executable jar or war using their own {@code "nested:"} URL
 * protocol, which separates the path of the outer archive from the name of the entry within it using {@code "/!"}
 * rather than {@code "!&#47;"}, e.g. {@code "jar:nested:/path/to/app.jar/!BOOT-INF/lib/dep.jar!/"}. Those are the
 * URLs that the Spring Boot launcher hands to its classloader, so unless they are understood, nothing in a Spring
 * Boot executable archive is scanned.
 */
public class SpringBootNestedUrlTest {
    /** An executable jar containing {@code BOOT-INF/classes/} and {@code BOOT-INF/lib/mylib.jar}. */
    private static File jar;

    /** The {@code "file:"} URL of {@link #jar}. */
    private static String jarUrl;

    /** The path of {@link #jar} as it appears in a {@code "nested:"} URL. */
    private static String jarRawPath;

    /**
     * Build the test executable jar.
     *
     * @param tempDir
     *            a temporary directory to build the jarfile in.
     * @throws IOException
     *             if the jarfile could not be written.
     */
    @BeforeAll
    public static void buildJar(@TempDir final File tempDir) throws IOException {
        final var classfilePath = AppWidget.class.getName().replace('.', '/') + ".class";
        final var libClassfilePath = LibWidget.class.getName().replace('.', '/') + ".class";

        // BOOT-INF/lib/mylib.jar
        final var libJar = new ByteArrayOutputStream();
        try (var zipOut = new ZipOutputStream(libJar)) {
            zipOut.putNextEntry(new ZipEntry(libClassfilePath));
            copyClassfile(libClassfilePath, zipOut);
            zipOut.closeEntry();
        }

        jar = new File(tempDir, "myapp.jar");
        try (var zipOut = new ZipOutputStream(new FileOutputStream(jar))) {
            zipOut.putNextEntry(new ZipEntry("BOOT-INF/classes/" + classfilePath));
            copyClassfile(classfilePath, zipOut);
            zipOut.closeEntry();
            zipOut.putNextEntry(new ZipEntry("BOOT-INF/lib/mylib.jar"));
            zipOut.write(libJar.toByteArray());
            zipOut.closeEntry();
        }
        jarUrl = jar.toURI().toString();
        // Mirrors org.springframework.boot.loader.net.protocol.jar.JarUrl#getJarReference
        jarRawPath = jar.toURI().getRawPath();
    }

    /**
     * Copy a classfile from the test classpath to an output stream.
     *
     * @param classfilePath
     *            the path of the classfile.
     * @param out
     *            the stream to copy the classfile to.
     * @throws IOException
     *             if the classfile could not be read.
     */
    private static void copyClassfile(final String classfilePath, final OutputStream out) throws IOException {
        try (var in = SpringBootNestedUrlTest.class.getClassLoader().getResourceAsStream(classfilePath)) {
            assertThat(in).as(classfilePath).isNotNull();
            final var buf = new byte[8192];
            for (int numRead; (numRead = in.read(buf)) > 0;) {
                out.write(buf, 0, numRead);
            }
        }
    }

    /** A Spring Boot {@code "nested:"} URL should be resolved to the equivalent path within a jarfile. */
    @Test
    public void nestedUrlsAreResolvedToJarPaths() {
        // resolve() normalizes away the trailing slash
        final var jarPath = FastPathResolver.resolve(jarUrl);
        assertThat(FastPathResolver.resolve("jar:nested:" + jarRawPath + "/!BOOT-INF/classes/!/"))
                .isEqualTo(jarPath + "!/BOOT-INF/classes");
        assertThat(FastPathResolver.resolve("jar:nested:" + jarRawPath + "/!BOOT-INF/lib/mylib.jar!/"))
                .isEqualTo(jarPath + "!/BOOT-INF/lib/mylib.jar");
        // A resource within the nested entry
        assertThat(FastPathResolver.resolve("jar:nested:" + jarRawPath + "/!BOOT-INF/lib/mylib.jar!/x/y.txt"))
                .isEqualTo(jarPath + "!/BOOT-INF/lib/mylib.jar!/x/y.txt");
        // A "nested:" URL without the usual "jar:" wrapper, and with no entry within the outer archive
        assertThat(FastPathResolver.resolve("nested:" + jarRawPath)).isEqualTo(jarPath);
    }

    /** A classpath element given as a Spring Boot {@code "nested:"} URL should be scanned. */
    @Test
    public void nestedUrlClasspathElementIsScanned() {
        try (var scanResult = new ClassGraph()
                .overrideClasspath(List.of("jar:nested:" + jarRawPath + "/!BOOT-INF/classes/!/")).enableClassInfo()
                .scan()) {
            // Both classes are found, not just the one in BOOT-INF/classes/, because ClassGraph already treats
            // "BOOT-INF/classes/" as a package root and "BOOT-INF/lib/" as a library directory within a Spring Boot
            // executable jar, so the whole application is scanned. That matches what its classloader can load.
            assertThat(scanResult.getAllClasses().getNames()).containsExactlyInAnyOrder(AppWidget.class.getName(),
                    LibWidget.class.getName());
        }
        try (var scanResult = new ClassGraph()
                .overrideClasspath(List.of("jar:nested:" + jarRawPath + "/!BOOT-INF/lib/mylib.jar!/"))
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(LibWidget.class.getName());
        }
    }
}
