import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.base.internal.path.FastPathResolver;

/**
 * Records what each operating system actually does with the path and URL forms that ClassGraph has to handle, and
 * what FastPathResolver makes of the same inputs, so that the assumptions baked into the resolver can be checked
 * against all three platforms rather than inferred.
 *
 * Every line is "key<TAB>value", so the output of two platforms can be diffed directly.
 *
 * Run with: java -cp <classgraph-classes> PathProbe.java
 */
public class PathProbe {
    /** The number of checks that did not produce the expected answer. */
    private static int numFailures;

    public static void main(final String[] args) throws Exception {
        environment();
        jdkGroundTruth();
        resolverBehaviour();
        endToEnd();
        System.out.println();
        System.out.println("failures\t" + numFailures);
    }

    // -----------------------------------------------------------------------------------------------------------

    /** Print the platform's own idea of what a path looks like. */
    private static void environment() {
        section("environment");
        show("os.name", System.getProperty("os.name"));
        show("java.version", System.getProperty("java.version"));
        show("File.separator", File.separator);
        show("File.pathSeparator", File.pathSeparator);
        show("default FileSystem separator", java.nio.file.FileSystems.getDefault().getSeparator());
        show("file.encoding", System.getProperty("file.encoding"));
        show("sun.jnu.encoding", System.getProperty("sun.jnu.encoding"));
        for (final Path root : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
            show("root directory", root.toString() + "  -> URI " + root.toUri());
        }
    }

    /**
     * Print what the JDK does with each path form. These are the facts the resolver's Windows-only branches are
     * written against, so they are recorded rather than assumed.
     */
    private static void jdkGroundTruth() {
        section("jdk: Path.of(x).toString()");
        for (final String path : new String[] { "/", "//", "///", "\\", "\\\\", "/tmp", "/tmp/", "/tmp//a",
                "C:", "C:/", "C:\\", "C:/a/b", "C:\\a\\b", "C:a", "//server/share", "//server/share/a",
                "\\\\server\\share\\a", "a/b", "./a", "../a", "/a/../b", "x:/a/b" }) {
            show("Path.of(" + path + ")", tryGet(() -> Path.of(path).toString()));
        }

        section("jdk: Path.of(x).getRoot()");
        for (final String path : new String[] { "/", "/tmp", "C:", "C:/", "C:/a", "//server/share/a", "a/b" }) {
            show("Path.of(" + path + ").getRoot()", tryGet(() -> String.valueOf(Path.of(path).getRoot())));
        }

        section("jdk: Path.of(x).toUri()");
        for (final String path : new String[] { "/", "/tmp", "/tmp/", "/tmp/a b", "/tmp/a+b", "/tmp/\u00e9",
                "/tmp/a#b", "/tmp/a%20b", "C:/", "C:/a b", "//server/share/a" }) {
            show("Path.of(" + path + ").toUri()", tryGet(() -> Path.of(path).toUri().toString()));
        }

        section("jdk: new File(x).toURI()");
        for (final String path : new String[] { "/", "/tmp", "/tmp/", "/tmp/a b", "C:/", "C:/a b" }) {
            show("new File(" + path + ").toURI()", tryGet(() -> new File(path).toURI().toString()));
        }

        section("jdk: Path.of(URI)");
        for (final String uri : new String[] { "file:/tmp/a", "file:///tmp/a", "file://localhost/tmp/a",
                "file:///tmp/a%20b", "file:///", "file:/", "file:///C:/a/b", "file:/C:/a/b", "file://server/share/a",
                "file:////server/share/a" }) {
            show("Path.of(URI(" + uri + "))", tryGet(() -> Path.of(URI.create(uri)).toString()));
        }

        section("jdk: URI parsing of single-letter and custom schemes");
        for (final String uri : new String[] { "C:/a/b", "C:\\a\\b", "x:/a/b", "s3://bucket/key", "vfs:/a/b",
                "jar:file:/a.jar!/b", "war:file:/a.war*/WEB-INF/classes/" }) {
            show("URI.create(" + uri + ").getScheme()",
                    tryGet(() -> String.valueOf(URI.create(uri).getScheme()) + "  opaque="
                            + URI.create(uri).isOpaque() + "  path=" + URI.create(uri).getPath()));
        }
        for (final String url : new String[] { "file:/a/b", "x:/a/b", "s3://bucket/key", "jar:file:/a.jar!/b" }) {
            show("new URL(" + url + ")", tryGet(() -> {
                final URL parsed = URI.create(url).toURL();
                return "protocol=" + parsed.getProtocol() + "  path=" + parsed.getPath();
            }));
        }

        section("jdk: does a one-letter scheme have a handler?");
        for (final String scheme : new String[] { "c", "x", "file", "jar", "jrt" }) {
            show("URL handler for " + scheme + ":", tryGet(() -> {
                URI.create(scheme + ":/a/b").toURL();
                return "handler exists";
            }));
        }
    }

    // -----------------------------------------------------------------------------------------------------------

    /** Print what FastPathResolver makes of each path form, on this platform. */
    private static void resolverBehaviour() {
        section("FastPathResolver.resolve(path)");
        for (final String path : new String[] {
                // Root directories and trailing separators
                "/", "//", "///", "\\", "/tmp", "/tmp/", "/tmp//", "/tmp/a/", "/tmp//a",
                // Windows drives, in each of their spellings
                "C:", "C:/", "C:\\", "C:/a/b", "C:\\a\\b", "/C:", "/C:/", "/C:/a/b", "C:a",
                // UNC paths
                "//server/share", "//server/share/a", "\\\\server\\share\\a",
                // "file:" URLs
                "file:/", "file://", "file:///", "file:/tmp/a", "file:///tmp/a", "file://localhost/tmp/a",
                "file:/C:/a/b", "file:///C:/a/b", "file:C:/a/b", "file://server/share/a", "file:////server/share/a",
                // Percent encoding
                "file:///tmp/a%20b", "file:///tmp/a%2Bb", "file:///tmp/%C3%A9", "file:///tmp/a%2Fb",
                "/tmp/a%20b", "http://host/a%20b.jar", "jar:http://host/a%20b.jar!/c", "s3://bucket/a%20b",
                // "jar:" URLs, including nested and trailing separators
                "jar:file:/a.jar!/", "jar:file:/a.jar!/b", "jar:file:/a.war!/WEB-INF/lib/b.jar!/",
                "jar:jar:file:/a.war!/WEB-INF/lib/b.jar!/", "/a/b!", "/a/b.jar!/",
                // Other schemes
                "jrt:/modules/java.base", "JRT:/modules/java.base", "http://host/a.jar", "https://host/a.jar",
                "s3://bucket/key", "vfs:/a/b", "vfs2:/a/b", "x:/a/b", "c:/a/b",
                "bundleresource://4.fwk1/a/b", "war:file:/a.war*/WEB-INF/classes/",
                // Relative paths
                "a/b", "./a", "../a", "/a/../b", "" }) {
            show("resolve(" + path + ")", tryGet(() -> FastPathResolver.resolve(path)));
        }

        section("FastPathResolver.resolve(base, path)");
        final String[][] pairs = { { "/base", "/" }, { "/base", "a" }, { "/base", "./a" }, { "/base", "../a" },
                { "/base", "/a" }, { "/base", "file:/a" }, { "/base", "file:///a" }, { "/base", "C:/a" },
                { "/base/", "a" }, { "C:/base", "C:" }, { "C:/base", "C:/" }, { "C:/base", "/C:" },
                { "C:/base", "a" }, { "//server/share", "a" }, { "http://host/dir", "a" },
                { "jar:file:/a.jar!/dir", "b" } };
        for (final String[] pair : pairs) {
            show("resolve(" + pair[0] + " , " + pair[1] + ")",
                    tryGet(() -> FastPathResolver.resolve(pair[0], pair[1])));
        }
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Scan real directories and jars whose paths contain the characters that need escaping, and check that what
     * ClassGraph hands back can be turned into a readable file again. This is what actually matters to a user, and
     * it is where a platform difference in path handling shows up as a missing class rather than as an odd string.
     *
     * @throws Exception
     *             if the temporary files could not be created
     */
    private static void endToEnd() throws Exception {
        section("end-to-end scans of awkward paths");
        // Names that exercise the escaping rules: a space, a plus, a percent, a hash, and a non-ASCII letter.
        // ':' and '?' are not tested, since Windows forbids them in a filename.
        for (final String dirName : new String[] { "plain", "with space", "with+plus", "with%25percent",
                "with#hash", "caf\u00e9", "with!bang" }) {
            final Path dir;
            try {
                dir = Files.createTempDirectory("probe-").resolve(dirName);
                Files.createDirectories(dir);
            } catch (final Exception e) {
                show("dir " + dirName, "could not create: " + e);
                continue;
            }
            // A classfile in a directory classpath element, and the same classfile inside a jar
            final Path packageDir = dir.resolve("probepkg");
            Files.createDirectories(packageDir);
            Files.write(packageDir.resolve("Probe.class"), probeClassfile());
            final Path jar = dir.resolve("probe.jar");
            try (var jarOut = new JarOutputStream(Files.newOutputStream(jar))) {
                jarOut.putNextEntry(new JarEntry("probepkg/Probe.class"));
                jarOut.write(probeClassfile());
                jarOut.closeEntry();
            }

            check("dir  " + dirName, () -> scanFinds(dir.toString()));
            check("jar  " + dirName, () -> scanFinds(jar.toString()));
            check("dir URL  " + dirName, () -> scanFinds(dir.toUri().toString()));
            check("jar URL  " + dirName, () -> scanFinds(jar.toUri().toString()));
            check("jar: URL " + dirName, () -> scanFinds("jar:" + jar.toUri() + "!/"));
            // The path the resolver produces for this directory must still name the directory
            final String resolved = FastPathResolver.resolve(dir.toUri().toString());
            check("resolved dir names the same dir  " + dirName,
                    () -> Files.isDirectory(Path.of(resolved)) ? "yes" : "no: " + resolved);
            // A resource URL handed back by ClassGraph must be readable again
            check("resource URL round-trips  " + dirName, () -> {
                try (ScanResult scanResult = new ClassGraph().overrideClasspath(jar.toString()).scan()) {
                    final List<io.github.classgraph.Resource> resources = scanResult
                            .getResourcesWithExtension("class");
                    if (resources.isEmpty()) {
                        return "no: no resources found";
                    }
                    final URL url = resources.get(0).getURL();
                    try (var inputStream = url.openStream()) {
                        return inputStream.readAllBytes().length > 0 ? "yes" : "no: empty";
                    }
                }
            });
        }
    }

    /**
     * Scan the given classpath element and report whether the probe class was found.
     *
     * @param classpathElement
     *            the classpath element to scan
     * @return "yes", or "no" plus what was found instead
     */
    private static String scanFinds(final String classpathElement) {
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(classpathElement).enableClassInfo().scan()) {
            return scanResult.getClassInfo("probepkg.Probe") != null ? "yes"
                    : "no: found " + scanResult.getAllClasses().getNames();
        }
    }

    /**
     * The bytes of a minimal valid classfile for "probepkg.Probe", built by hand so that the probe needs no
     * compiler at run time.
     *
     * @return the classfile bytes
     */
    private static byte[] probeClassfile() {
        // Constant pool: 1=Utf8 "probepkg/Probe", 2=Class#1, 3=Utf8 "java/lang/Object", 4=Class#3
        final byte[] name = "probepkg/Probe".getBytes(StandardCharsets.UTF_8);
        final byte[] superName = "java/lang/Object".getBytes(StandardCharsets.UTF_8);
        final var out = new java.io.ByteArrayOutputStream();
        final var data = new java.io.DataOutputStream(out);
        try {
            data.writeInt(0xCAFEBABE);
            data.writeShort(0); // minor version
            data.writeShort(52); // major version (Java 8), so every supported JDK reads it
            data.writeShort(5); // constant pool count = entries + 1
            data.writeByte(1); // CONSTANT_Utf8
            data.writeShort(name.length);
            data.write(name);
            data.writeByte(7); // CONSTANT_Class
            data.writeShort(1);
            data.writeByte(1); // CONSTANT_Utf8
            data.writeShort(superName.length);
            data.write(superName);
            data.writeByte(7); // CONSTANT_Class
            data.writeShort(3);
            data.writeShort(0x0021); // public super
            data.writeShort(2); // this class
            data.writeShort(4); // super class
            data.writeShort(0); // interfaces
            data.writeShort(0); // fields
            data.writeShort(0); // methods
            data.writeShort(0); // attributes
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    // -----------------------------------------------------------------------------------------------------------

    /**
     * Print a section heading.
     *
     * @param title
     *            the heading
     */
    private static void section(final String title) {
        System.out.println();
        System.out.println("### " + title);
    }

    /**
     * Print one key/value line.
     *
     * @param key
     *            the key
     * @param value
     *            the value
     */
    private static void show(final String key, final String value) {
        System.out.println(key + "\t" + value);
    }

    /**
     * Print one key/value line, and count it as a failure unless the value is "yes".
     *
     * @param key
     *            the key
     * @param value
     *            supplies the value
     */
    private static void check(final String key, final ThrowingSupplier value) {
        final String result = tryGet(value);
        if (!"yes".equals(result)) {
            numFailures++;
        }
        show(key, result);
    }

    /**
     * Evaluate a supplier, returning the exception rather than throwing it.
     *
     * @param supplier
     *            supplies the value
     * @return the value, or a description of the exception it threw
     */
    private static String tryGet(final ThrowingSupplier supplier) {
        try {
            return supplier.get();
        } catch (final Throwable t) {
            return "THREW " + t.getClass().getName() + ": " + t.getMessage();
        }
    }

    /** A supplier that is allowed to throw, so that a probe can record the exception rather than die on it. */
    @FunctionalInterface
    private interface ThrowingSupplier {
        /**
         * Supply the value.
         *
         * @return the value
         * @throws Exception
         *             if the value could not be produced
         */
        String get() throws Exception;
    }
}
