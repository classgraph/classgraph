package io.github.classgraph.issues.issue925;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;
import org.jspecify.annotations.Nullable;

/**
 * Nothing in a webapp deployed to Tomcat as a non-exploded WAR file (i.e. with {@code unpackWARs="false"}) was
 * scanned.
 *
 * <p>
 * Tomcat serves a non-exploded WAR through its own {@code "war:"} URL protocol, which separates the path of the WAR
 * file from the path within it using {@code "*&#47;"} rather than {@code "!&#47;"}, e.g.
 * {@code "war:file:/path/to/app.war*&#47;WEB-INF/classes/"}. ClassGraph read the {@code '*'} as a wildcard and
 * rejected the whole classpath element.
 */
public class Issue925Test {
    /**
     * A WAR file containing {@code WEB-INF/classes/} and {@code WEB-INF/lib/mylib.jar}.
     */
    private static File war;

    /** The {@code "file:"} URL of {@link #war}, without a trailing slash. */
    private static String warUrl;

    /**
     * Build the test WAR file.
     *
     * @param tempDir
     *            a temporary directory to build the WAR file in.
     * @throws IOException
     *             if the WAR file could not be written.
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
        // Strip the trailing slash that File#toURI() does not add for a file, for clarity at the use sites
        warUrl = war.toURI().toString();
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
        try (var in = Issue925Test.class.getClassLoader().getResourceAsStream(classfilePath)) {
            assertThat(in).as(classfilePath).isNotNull();
            final var buf = new byte[8192];
            for (int numRead; (numRead = in.read(buf)) > 0;) {
                out.write(buf, 0, numRead);
            }
        }
    }

    /**
     * A Tomcat {@code "war:"} URL should be resolved to the equivalent path within a jarfile, whichever of the
     * separators Tomcat may use is present.
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
     * A classpath element given as a Tomcat {@code "war:"} URL should be scanned. Each of the two parts of the
     * webapp is a classpath element of its own, exactly as a Tomcat webapp classloader reports them.
     */
    @Test
    public void warUrlClasspathElementIsScanned() {
        try (var scanResult = new ClassGraph().enableClasspathEntries("war:" + warUrl + "*/WEB-INF/classes/")
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(Widget.class.getName());
        }
        try (var scanResult = new ClassGraph().enableClasspathEntries("war:" + warUrl + "*/WEB-INF/lib/mylib.jar")
                .enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(LibWidget.class.getName());
        }
    }

    /**
     * The WAR file itself contains no classes at its root: a webapp's classes are all beneath {@code WEB-INF/}, and
     * an overridden classpath is scanned exactly as it is written, with no classloader involved to say that a WAR
     * file's classes live under {@code WEB-INF/classes/} and {@code WEB-INF/lib/}.
     */
    @Test
    public void theWarFileItselfContainsNoClassesAtItsRoot() {
        try (var scanResult = new ClassGraph().enableClasspathEntries(war.getPath()).enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).isEmpty();
        }
    }

    /**
     * The same WAR file, reached through a classloader whose {@link ClassLoaderHandler} declares the two places a
     * servlet container loads a webapp's classes from, is scanned in both of them: {@code WEB-INF/classes/} is
     * scanned as a package root, and each jarfile in {@code WEB-INF/lib/} is scanned as a classpath element of its
     * own. This is the scanner's side of the contract that {@link ClassLoaderHandler#getPackageRootPrefixes()} and
     * {@link ClassLoaderHandler#getLibDirPrefixes()} define; that Tomcat's own handler declares these two prefixes
     * is tested in {@code classgraph-classpath}.
     *
     * @throws IOException
     *             if the classloader could not be closed.
     */
    @Test
    public void aClassLoaderCanDeclareAPackageRootAndALibDirWithinAClasspathElement() throws IOException {
        try (var classLoader = new WarClassLoader(war.toURI().toURL());
                var scanResult = new ClassGraph().enableClassLoaders(classLoader)
                        .registerClassLoaderHandler(new WarClassLoaderHandler()).enableClassInfo().scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactlyInAnyOrder(Widget.class.getName(),
                    LibWidget.class.getName());
        }
    }

    /**
     * A package root that ClassGraph finds for itself within a jarfile is named by the same kind of URI as one that
     * was named on the classpath: a {@code "jar:"} URI, which is the only form that names a directory inside a
     * jarfile and can be opened.
     *
     * @throws IOException
     *             if the classloader could not be closed.
     */
    @Test
    public void anAutomaticPackageRootIsNamedByAJarURI() throws IOException {
        try (var classLoader = new WarClassLoader(war.toURI().toURL());
                var scanResult = new ClassGraph().enableClassLoaders(classLoader)
                        .registerClassLoaderHandler(new WarClassLoaderHandler()).enableClassInfo().scan()) {
            assertThat(scanResult.getClasspathURIs()).extracting(URI::toString)
                    .anyMatch(uri -> uri.startsWith("jar:file:") && uri.endsWith("!/WEB-INF/classes"))
                    .noneMatch(uri -> uri.startsWith("file:") && uri.endsWith("!/WEB-INF/classes"));
        }
    }

    /** A stand-in for a servlet container's classloader, which serves a whole WAR file as one classpath element. */
    private static final class WarClassLoader extends URLClassLoader {
        /**
         * Constructor.
         *
         * @param war
         *            the URL of the WAR file.
         */
        WarClassLoader(final URL war) {
            super(new URL[] { war }, /* parent = */ null);
        }
    }

    /** A {@link ClassLoaderHandler} for {@link WarClassLoader}, declaring the two prefixes of a webapp layout. */
    private static final class WarClassLoaderHandler implements ClassLoaderHandler {
        /** Constructor. */
        WarClassLoaderHandler() {
        }

        @Override
        public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
            return classLoaderClass == WarClassLoader.class;
        }

        @Override
        public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                final @Nullable ClassGraphLog log) {
            classLoaderOrder.add(classLoader, log);
        }

        @Override
        public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                final @Nullable ClassGraphLog log) {
            for (final var url : ((WarClassLoader) classLoader).getURLs()) {
                classpathOrder.addClasspathEntry(url, classLoader, log);
            }
        }

        @Override
        public List<String> getPackageRootPrefixes() {
            return List.of("WEB-INF/classes/");
        }

        @Override
        public List<String> getLibDirPrefixes() {
            return List.of("WEB-INF/lib/");
        }
    }
}
