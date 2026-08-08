package io.github.classgraph.issues.issue925;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import nonapi.io.github.classgraph.utils.FastPathResolver;

/**
 * Nothing in a webapp deployed to Tomcat as a non-exploded WAR file (i.e. with
 * {@code unpackWARs="false"}) was scanned (#925).
 *
 * <p>
 * Tomcat serves a non-exploded WAR through its own {@code "war:"} URL protocol,
 * which separates the path of the WAR file from the path within it using
 * {@code "*&#47;"} rather than {@code "!&#47;"}, e.g.
 * {@code "war:file:/path/to/app.war*&#47;WEB-INF/classes/"}. ClassGraph read
 * the {@code '*'} as a wildcard and rejected the whole classpath element.
 */
public class Issue925Test {
    /**
     * A WAR file containing {@code WEB-INF/classes/} and
     * {@code WEB-INF/lib/mylib.jar}.
     */
    private static File war;

    /** The {@code "file:"} URL of {@link #war}, without a trailing slash. */
    private static String warUrl;

    /**
     * Build the test WAR file.
     *
     * @param tempDir a temporary directory to build the WAR file in.
     * @throws IOException if the WAR file could not be written.
     */
    @BeforeAll
    public static void buildWar(@TempDir final File tempDir) throws IOException {
        final var classfilePath = Widget.class.getName().replace('.', '/') + ".class";
        final var libClassfilePath = LibWidget.class.getName().replace('.', '/') + ".class";

        // WEB-INF/lib/mylib.jar
        final var libJar = new ByteArrayOutputStream();
        try (var zipOut = new ZipOutputStream(libJar)) {
            zipOut.putNextEntry(new ZipEntry(libClassfilePath));
            copyClassfile(libClassfilePath, zipOut);
            zipOut.closeEntry();
        }

        war = new File(tempDir, "myapp.war");
        try (var zipOut = new ZipOutputStream(new FileOutputStream(war))) {
            zipOut.putNextEntry(new ZipEntry("WEB-INF/classes/" + classfilePath));
            copyClassfile(classfilePath, zipOut);
            zipOut.closeEntry();
            zipOut.putNextEntry(new ZipEntry("WEB-INF/lib/mylib.jar"));
            zipOut.write(libJar.toByteArray());
            zipOut.closeEntry();
        }
        // Strip the trailing slash that File#toURI() does not add for a file, for
        // clarity at the use sites
        warUrl = war.toURI().toString();
    }

    /**
     * Copy a classfile from the test classpath to an output stream.
     *
     * @param classfilePath the path of the classfile.
     * @param out           the stream to copy the classfile to.
     * @throws IOException if the classfile could not be read.
     */
    private static void copyClassfile(final String classfilePath, final OutputStream out) throws IOException {
        try (var in = Issue925Test.class.getClassLoader().getResourceAsStream(classfilePath)) {
            assertThat(in).as(classfilePath).isNotNull();
            final var buf = new byte[8192];
            for (int numRead; (numRead = in.read(buf)) > 0;) {
                out.write(buf, 0, numRead);
            }
        }
    }

    /**
     * A Tomcat {@code "war:"} URL should be resolved to the equivalent path within
     * a jarfile, whichever of the separators Tomcat may use is present.
     */
    @Test
    public void warUrlsAreResolvedToJarPaths() {
        // resolve() normalizes away the trailing slash
        final var expected = FastPathResolver.resolve(warUrl) + "!/WEB-INF/classes";
        assertThat(FastPathResolver.resolve("war:" + warUrl + "*/WEB-INF/classes/")).isEqualTo(expected);
        assertThat(FastPathResolver.resolve("war:" + warUrl + "^/WEB-INF/classes/")).isEqualTo(expected);
        // A "war:" URL for the WAR file itself, with no path within it
        assertThat(FastPathResolver.resolve("war:" + warUrl)).isEqualTo(FastPathResolver.resolve(warUrl));
    }

    /**
     * A classpath element given as a Tomcat {@code "war:"} URL should be scanned.
     */
    @Test
    public void warUrlClasspathElementIsScanned() {
        try (var scanResult = new ClassGraph().overrideClasspath("war:" + warUrl + "*/WEB-INF/classes/")
                .enableClassInfo().scan()) {
            // Both classes are found, not just the one in WEB-INF/classes/, because
            // ClassGraph already treats
            // "WEB-INF/classes/" as a package root and "WEB-INF/lib/" as a library
            // directory within a WAR file,
            // so the whole webapp is scanned. That matches what the webapp's classloader
            // can load.
            assertThat(scanResult.getAllClasses().getNames()).containsExactlyInAnyOrder(Widget.class.getName(),
                    LibWidget.class.getName());
        }
        try (var scanResult = new ClassGraph().overrideClasspath("war:" + warUrl + "*/WEB-INF/lib/mylib.jar")
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(LibWidget.class.getName());
        }
    }

    /**
     * Scanning the WAR file itself should find both the classes in
     * {@code WEB-INF/classes/} and the classes in the jarfiles in
     * {@code WEB-INF/lib/}, which is the classpath element that Tomcat's main
     * resource set yields for a non-exploded WAR.
     */
    @Test
    public void warFileClasspathElementIsScanned() {
        try (var scanResult = new ClassGraph().overrideClasspath(war.getPath()).enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactlyInAnyOrder(Widget.class.getName(),
                    LibWidget.class.getName());
        }
    }
}
