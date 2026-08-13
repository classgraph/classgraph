package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link FastPathResolver}. */
public class FastPathResolverTest {
    /**
     * {@link FastPathResolver#resolve(String, String)}, loaded in a class loader that saw {@code os.name} set to
     * Windows, so that the Windows-only branches can be tested on any platform.
     */
    private static Method resolveAsWindowsMethod;

    /**
     * {@link FastPathResolver#resolve(String, String)}, loaded in a class loader that saw {@code os.name} set to
     * Linux, so that the non-Windows branches can be tested on any platform other than Windows itself (null on
     * Windows -- see {@link #loadResolverForEachOS()}).
     */
    private static Method resolveAsLinuxMethod;

    /** True if a copy of the resolver that believes it is running on Linux could be loaded. */
    private static boolean canSimulateLinux;

    /**
     * A class loader that defines the ClassGraph classes itself rather than delegating them to its parent, so that
     * they get a second, independent copy of their static state. {@code VersionFinder.OS} is a {@code static final}
     * field initialized from the {@code os.name} system property, so a copy of {@link FastPathResolver} loaded here
     * while {@code os.name} names Windows takes the Windows branches on every platform.
     */
    private static final class SeparateStaticStateClassLoader extends ClassLoader {
        /** Constructor. */
        SeparateStaticStateClassLoader() {
            super(FastPathResolverTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("nonapi.io.github.classgraph.")) {
                // Everything else, including the JDK classes, must stay shared with the parent
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> cls = findLoadedClass(name);
                if (cls == null) {
                    final byte[] classfileBytes;
                    try (InputStream inputStream = getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (inputStream == null) {
                            throw new ClassNotFoundException(name);
                        }
                        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        final byte[] buf = new byte[8192];
                        for (int numRead = inputStream.read(buf); numRead != -1; numRead = inputStream.read(buf)) {
                            byteArrayOutputStream.write(buf, 0, numRead);
                        }
                        classfileBytes = byteArrayOutputStream.toByteArray();
                    } catch (final IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                    cls = defineClass(name, classfileBytes, 0, classfileBytes.length);
                }
                if (resolve) {
                    resolveClass(cls);
                }
                return cls;
            }
        }
    }

    /**
     * Load a second copy of {@link FastPathResolver} that believes it is running on Windows, and, unless the real
     * platform is Windows, a third that believes it is running on Linux.
     *
     * @throws Exception
     *             if the extra copies could not be loaded.
     */
    @BeforeAll
    static void loadResolverForEachOS() throws Exception {
        resolveAsWindowsMethod = loadResolverForOS("Windows 10", "Windows");
        // VersionFinder takes a backslash file separator as Windows before it consults os.name, and
        // File.separatorChar is fixed when the JDK initializes File, so on Windows a copy of the resolver cannot
        // be made to believe it is running on Linux. The tests that need one are skipped there.
        canSimulateLinux = File.separatorChar != '\\';
        resolveAsLinuxMethod = canSimulateLinux ? loadResolverForOS("Linux", "Linux") : null;
    }

    /**
     * Load a copy of {@link FastPathResolver} that believes it is running on the named operating system.
     *
     * @param osName
     *            the value to give the {@code os.name} system property while the copy is initialized.
     * @param expectedOS
     *            the name that {@code VersionFinder.OS} is expected to take as a result.
     * @return the copy's {@code resolve(String, String)} method.
     * @throws Exception
     *             if the copy could not be loaded.
     */
    private static Method loadResolverForOS(final String osName, final String expectedOS) throws Exception {
        final String osNameOrig = System.getProperty("os.name");
        final ClassLoader classLoader = new SeparateStaticStateClassLoader();
        try {
            System.setProperty("os.name", osName);
            // Class initialization has to happen while the property is set, since VersionFinder.OS is static final
            final Class<?> versionFinderCls = Class.forName(VersionFinder.class.getName(), /* initialize = */ true,
                    classLoader);
            assertThat(versionFinderCls.getField("OS").get(null)).hasToString(expectedOS);
            final Class<?> resolverCls = Class.forName(FastPathResolver.class.getName(), /* initialize = */ true,
                    classLoader);
            return resolverCls.getMethod("resolve", String.class, String.class);
        } finally {
            if (osNameOrig != null) {
                System.setProperty("os.name", osNameOrig);
            }
        }
    }

    /**
     * Resolve a path as if running on Windows.
     *
     * @param resolveBasePath
     *            the base path, or null to resolve against nothing.
     * @param relativePath
     *            the path to resolve.
     * @return the resolved path.
     */
    private static String resolveAsWindows(final String resolveBasePath, final String relativePath) {
        return invokeResolve(resolveAsWindowsMethod, resolveBasePath, relativePath);
    }

    /**
     * Resolve a path as if running on Linux.
     *
     * @param resolveBasePath
     *            the base path, or null to resolve against nothing.
     * @param relativePath
     *            the path to resolve.
     * @return the resolved path.
     */
    private static String resolveAsLinux(final String resolveBasePath, final String relativePath) {
        return invokeResolve(resolveAsLinuxMethod, resolveBasePath, relativePath);
    }

    /**
     * Invoke one of the reflectively-loaded copies of {@code FastPathResolver#resolve(String, String)}.
     *
     * @param resolveMethod
     *            the copy's resolve method.
     * @param resolveBasePath
     *            the base path, or null to resolve against nothing.
     * @param relativePath
     *            the path to resolve.
     * @return the resolved path.
     */
    private static String invokeResolve(final Method resolveMethod, final String resolveBasePath,
            final String relativePath) {
        try {
            return (String) resolveMethod.invoke(null, resolveBasePath, relativePath);
        } catch (final IllegalAccessException e) {
            throw new IllegalArgumentException(e);
        } catch (final InvocationTargetException e) {
            throw new IllegalArgumentException(e.getCause());
        }
    }

    /**
     * A {@code "jrt:"} URL must have its scheme recognized, so that the scheme is normalized to lowercase and the
     * path after it is treated as an absolute path.
     */
    @Test
    public void jrtSchemeIsRecognized() {
        assertThat(FastPathResolver.resolve("jrt:/modules/java.base")).isEqualTo("jrt:/modules/java.base");
        // The scheme is case-insensitive, and is normalized to lowercase
        assertThat(FastPathResolver.resolve("JRT:/modules/java.base")).isEqualTo("jrt:/modules/java.base");
        // (A doubled separator after the scheme, "jrt://modules/java.base", is not tested here: on Windows a
        // path starting with "//" is deliberately kept doubled, since it may be a UNC path (#736))
    }

    /**
     * A doubled {@code "jar:"} prefix (produced by some servlet containers for a jar nested within a WAR file) must
     * not send the scheme-stripping loop round forever.
     */
    @Test
    public void doubledJarSchemeTerminates() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                // The doubled prefix must resolve to the same path as the single prefix
                assertThat(FastPathResolver.resolve("jar:jar:file:/a/b.war!/WEB-INF/lib/c.jar!/"))
                        .isEqualTo(FastPathResolver.resolve("jar:file:/a/b.war!/WEB-INF/lib/c.jar!/"));
            }
        });
    }

    /**
     * The root path is an absolute path, so it must neither be resolved against the base path nor be reduced to the
     * empty string by the removal of its trailing separator.
     */
    @Test
    public void rootPathIsAbsolute() {
        assertThat(FastPathResolver.resolve("/")).isEqualTo("/");
        assertThat(FastPathResolver.resolve("/base", "/")).isEqualTo("/");
        // A backslash is a separator too
        assertThat(FastPathResolver.resolve("/base", "\\")).isEqualTo("/");
        // A path of one separator plus one path segment was already treated as absolute, and still is
        assertThat(FastPathResolver.resolve("/base", "/a")).isEqualTo("/a");
        // The "file:" URL spellings of the root path resolve to the root path too
        assertThat(FastPathResolver.resolve("/base", "file:/")).isEqualTo("/");
        assertThat(FastPathResolver.resolve("/base", "file:///")).isEqualTo("/");
    }

    /**
     * A URL scheme may contain digits, so a scheme such as {@code "s3:"} must be recognized, and the number of
     * slashes after it preserved.
     */
    @Test
    public void schemeContainingDigitIsRecognized() {
        assertThat(FastPathResolver.resolve("s3://bucket/key")).isEqualTo("s3://bucket/key");
        assertThat(FastPathResolver.resolve("vfs2:/a/b")).isEqualTo("vfs2:/a/b");
        // The path after the scheme is absolute, so the base path is ignored
        assertThat(FastPathResolver.resolve("/base", "s3://bucket/key")).isEqualTo("s3://bucket/key");
    }

    /**
     * A {@code "file:"} URL with an empty authority ({@code "file:///path"}) is the spelling that
     * {@link java.nio.file.Path#toUri()} produces. On Windows the two slashes of the empty authority must not be
     * read as the start of a UNC path, since {@code "///C:/a/b"} names neither a drive nor a network share.
     */
    @Test
    public void emptyAuthorityFileUrlIsNotAUncPathOnWindows() {
        assertThat(resolveAsWindows(null, "file:///C:/a/b")).isEqualTo("C:/a/b");
        assertThat(resolveAsWindows("C:/base", "file:///C:/a/b")).isEqualTo("C:/a/b");
        assertThat(resolveAsWindows(null, "jar:file:///C:/a/b.jar!/c")).isEqualTo("C:/a/b.jar!/c");
        assertThat(resolveAsWindows(null, "file:///a/b")).isEqualTo("/a/b");
        // A UNC path spelled as a "file:" URL has a non-empty authority, so it keeps both of its slashes
        assertThat(resolveAsWindows(null, "file://server/share/a")).isEqualTo("//server/share/a");
        // ... and so does the empty-authority spelling of a UNC path, which has four slashes in total
        assertThat(resolveAsWindows(null, "file:////server/share/a")).isEqualTo("//server/share/a");
    }

    /**
     * A {@code "file:"} URL names the local machine either with an empty authority or with the authority
     * {@code "localhost"}, and both name the same local path (RFC 8089 section 2). Off Windows the authority has to
     * be dropped, since folding it into the path names a directory that does not exist.
     */
    @Test
    public void localhostAuthorityNamesTheLocalMachine() {
        assumeTrue(canSimulateLinux, "the resolver cannot be made to believe it is running on Linux on Windows");
        assertThat(resolveAsLinux(null, "file://localhost/tmp/a")).isEqualTo("/tmp/a");
        assertThat(resolveAsLinux("/base", "file://localhost/tmp/a")).isEqualTo("/tmp/a");
        assertThat(resolveAsLinux(null, "jar:file://localhost/tmp/a.jar!/b")).isEqualTo("/tmp/a.jar!/b");
        // The host name is case-insensitive
        assertThat(resolveAsLinux(null, "file://LocalHost/tmp/a")).isEqualTo("/tmp/a");
        // Only the whole host name matches, not a host whose name merely starts with "localhost"
        assertThat(resolveAsLinux(null, "file://localhostile/tmp/a")).isEqualTo("/localhostile/tmp/a");
    }

    /**
     * On Windows the {@code "localhost"} authority of a {@code "file:"} URL is kept, so that it becomes a UNC path
     * -- which is both what {@code Path#of(URI)} produces there and what RFC 8089 appendix B.3 specifies.
     */
    @Test
    public void localhostAuthorityBecomesAUncPathOnWindows() {
        assertThat(resolveAsWindows(null, "file://localhost/tmp/a")).isEqualTo("//localhost/tmp/a");
    }

    /** On Windows, a bare drive designation is an absolute path, however it is spelled. */
    @Test
    public void bareDriveDesignationIsAbsoluteOnWindows() {
        assertThat(resolveAsWindows("C:/base", "C:")).isEqualTo("C:");
        assertThat(resolveAsWindows("C:/base", "/C:")).isEqualTo("C:");
        // A path on a drive is unaffected
        assertThat(resolveAsWindows("C:/base", "C:\\a\\b")).isEqualTo("C:/a/b");
        assertThat(resolveAsWindows("C:/base", "/C:/a/b")).isEqualTo("C:/a/b");
    }

    /**
     * On Windows, the root directory of a drive keeps its final separator, for the same reason that {@code "/"}
     * does: the separator is the whole of the directory's name. The separator is also what distinguishes the root
     * from the bare drive designation, since {@code "C:"} names the current directory on drive C.
     */
    @Test
    public void driveRootKeepsItsFinalSeparatorOnWindows() {
        assertThat(resolveAsWindows("C:/base", "C:/")).isEqualTo("C:/");
        // Both separators spell the same path, so both must resolve to the same root
        assertThat(resolveAsWindows("C:/base", "C:\\")).isEqualTo("C:/");
        // ... as do the doubled and leading-separator spellings, and the "file:" URL spellings
        assertThat(resolveAsWindows("C:/base", "C://")).isEqualTo("C:/");
        assertThat(resolveAsWindows("C:/base", "/C:/")).isEqualTo("C:/");
        assertThat(resolveAsWindows("C:/base", "file:/C:/")).isEqualTo("C:/");
        assertThat(resolveAsWindows("C:/base", "file:///C:/")).isEqualTo("C:/");
        assertThat(resolveAsWindows("C:/base", "file:///C:\\")).isEqualTo("C:/");
        // A drive is only a drive on Windows -- off Windows "C:" is an ordinary relative directory name, so its
        // trailing separator is stripped like any other directory's
        assumeTrue(canSimulateLinux, "the resolver cannot be made to believe it is running on Linux on Windows");
        assertThat(resolveAsLinux(null, "C:/")).isEqualTo("C:");
        assertThat(resolveAsLinux("/base", "C:/")).isEqualTo("/base/C:");
    }

    /**
     * A path consisting of nothing but separators names a root, so it must not be reduced to the empty string. On
     * Windows a doubled separator is kept doubled, since it may be the start of a UNC path: a degenerate UNC prefix
     * that names no share is left for the classpath element to fail to open and be logged, rather than being
     * collapsed to {@code "/"}, which would silently turn it into a scan of the whole current drive.
     */
    @Test
    public void aPathOfNothingButSeparatorsIsARoot() {
        assertThat(resolveAsWindows(null, "/")).isEqualTo("/");
        assertThat(resolveAsWindows(null, "\\")).isEqualTo("/");
        assertThat(resolveAsWindows(null, "file:///")).isEqualTo("/");
        assertThat(resolveAsWindows(null, "//")).isEqualTo("//");
        assertThat(resolveAsWindows(null, "///")).isEqualTo("//");
        assertThat(resolveAsWindows(null, "\\\\")).isEqualTo("//");
        assertThat(resolveAsWindows(null, "file://")).isEqualTo("//");
        assertThat(resolveAsWindows(null, "file:////")).isEqualTo("//");
        // Off Windows there are no UNC paths, so every spelling names the one root directory
        assumeTrue(canSimulateLinux, "the resolver cannot be made to believe it is running on Linux on Windows");
        for (final String rootPath : new String[] { "/", "//", "///", "\\", "\\\\", "file://", "file:///",
                "file:////" }) {
            assertThat(resolveAsLinux("/base", rootPath)).as(rootPath).isEqualTo("/");
        }
    }

    /**
     * Percent encoding is decoded only when what is left after the scheme prefixes have been stripped is a
     * filesystem path. A path that is still a URL keeps its encoding, since a decoded remote URL can no longer be
     * fetched -- it does not even parse as a {@link java.net.URI}.
     */
    // #255
    @Test
    public void onlyAPathThatStopsBeingAUrlIsPercentDecoded() {
        // A "jar:" URL wrapping a "file:" URL, or wrapping a bare path, resolves to a filesystem path
        assertThat(FastPathResolver.resolve("jar:file:/a%20b.jar!/c%20d")).isEqualTo("/a b.jar!/c d");
        assertThat(FastPathResolver.resolve("jar:/a%20b.jar!/c%20d")).isEqualTo("/a b.jar!/c d");
        assertThat(FastPathResolver.resolve("file:/a%20b/c")).isEqualTo("/a b/c");
        // A "jar:" URL wrapping a remote or custom-scheme URL stays a URL, so it keeps its encoding
        assertThat(FastPathResolver.resolve("jar:http://h/a%20b.jar!/c%20d"))
                .isEqualTo("http://h/a%20b.jar!/c%20d");
        assertThat(FastPathResolver.resolve("jar:https://h/a%20b.jar!/c%20d"))
                .isEqualTo("https://h/a%20b.jar!/c%20d");
        assertThat(FastPathResolver.resolve("jar:s3://bucket/a%20b.jar!/c")).isEqualTo("s3://bucket/a%20b.jar!/c");
        // A URL that was never wrapped in "jar:" is unaffected, and so is a bare path, where '%' is just a
        // character in a filename
        assertThat(FastPathResolver.resolve("http://h/a%20b.jar")).isEqualTo("http://h/a%20b.jar");
        assertThat(FastPathResolver.resolve("/plain/a%20b")).isEqualTo("/plain/a%20b");
    }

    /**
     * A URL scheme must be at least two characters long, so that a Windows drive designation is never read as a
     * scheme. Single-letter schemes are unusable in practice for exactly that reason, so nothing is given up: off
     * Windows a path such as {@code "C:/a/b"} is an ordinary relative path, which will not exist, so the classpath
     * element is logged and skipped during scanning.
     */
    @Test
    public void aSingleLetterSchemeIsNotAScheme() {
        // On Windows the drive designation branch handles it, and the drive letter's case is preserved
        assertThat(resolveAsWindows("C:/base", "C:/a/b")).isEqualTo("C:/a/b");
        assertThat(resolveAsWindows("C:/base", "c:/a/b")).isEqualTo("c:/a/b");
        // Both separators spell the same drive-absolute path, so the base path is ignored either way
        assertThat(resolveAsWindows("C:/base", "C:\\a\\b")).isEqualTo("C:/a/b");
        // A scheme of two or more characters is still a scheme on every platform
        assertThat(resolveAsWindows("C:/base", "s3://bucket/key")).isEqualTo("s3://bucket/key");

        assumeTrue(canSimulateLinux, "the resolver cannot be made to believe it is running on Linux on Windows");
        // Off Windows there are no drives, so what looks like a single-letter scheme is a relative path
        assertThat(resolveAsLinux("/base", "C:/a/b")).isEqualTo("/base/C:/a/b");
        assertThat(resolveAsLinux("/base", "x:/a/b")).isEqualTo("/base/x:/a/b");
        // ... which is the same answer the backslash spelling has always given
        assertThat(resolveAsLinux("/base", "C:\\a\\b")).isEqualTo("/base/C:/a/b");
        assertThat(resolveAsLinux("/base", "s3://bucket/key")).isEqualTo("s3://bucket/key");
    }

    /**
     * A {@code ".."} segment in a path that names a file on disk must be left in the path, for the filesystem to
     * resolve. Only the filesystem knows what it means: after a symlinked directory, {@code ".."} names the parent
     * of the directory the symlink points at, not the parent of the symlink, so collapsing it here would name a
     * different file than the one the JVM's own classloader reaches through the same path.
     */
    @Test
    public void aParentSegmentInAFilePathIsLeftForTheFilesystem() {
        assertThat(FastPathResolver.resolveFilePath(null, "/a/link/../b")).isEqualTo("/a/link/../b");
        assertThat(FastPathResolver.resolveFilePath("/base", "link/../b")).isEqualTo("/base/link/../b");
        assertThat(FastPathResolver.resolveFilePath(null, "file:/a/link/../b")).isEqualTo("/a/link/../b");
        // A "." segment and a doubled separator mean the same thing to the filesystem as to the resolver, so they
        // are still collapsed
        assertThat(FastPathResolver.resolveFilePath(null, "/a/./link//../b")).isEqualTo("/a/link/../b");
        // A path that still has a URL scheme in front of it has no filesystem to ask
        assertThat(FastPathResolver.resolveFilePath(null, "https://host/a/../b")).isEqualTo("https://host/b");
        assertThat(FastPathResolver.resolveFilePath(null, "jrt:/modules/a/../b")).isEqualTo("jrt:/modules/b");
        // A path that is not known to name a file on disk keeps the textual resolution it has always had
        assertThat(FastPathResolver.resolve("/a/link/../b")).isEqualTo("/a/b");
        assertThat(FastPathResolver.resolve("/base", "link/../b")).isEqualTo("/base/b");
    }

    /**
     * Only the outermost section of a path names a file on disk. Everything after a nested jar separator is an
     * entry name within an archive, which has no symlinks and no filesystem to ask, so a {@code ".."} there is
     * collapsed -- which is what stops a "zip slip" entry name from escaping the archive.
     *
     * @param tempDir
     *            a temporary directory
     * @throws IOException
     *             if the jarfile could not be created
     */
    @Test
    public void aParentSegmentWithinANestedJarIsStillCollapsed(@TempDir final Path tempDir) throws IOException {
        // The '!' is only a nested jar separator if the path before it names an existing file (#903)
        final Path jarFile = Files.createFile(tempDir.resolve("b.jar"));
        final String jarPath = jarFile.toString().replace(File.separatorChar, '/');

        assertThat(FastPathResolver.resolveFilePath(null, "jar:file:" + jarPath + "!/x/../y"))
                .isEqualTo(jarPath + "!/y");
        // A ".." cannot climb out of the archive it is in, however many of them there are
        assertThat(FastPathResolver.resolveFilePath(null, "jar:file:" + jarPath + "!/../../x"))
                .isEqualTo(jarPath + "!/x");
    }

    /**
     * A base path may itself be a URL, e.g. the directory of a jarfile that was fetched over http, and a relative
     * path resolved against it must still be a URL that can be fetched. The empty segment between a scheme and the
     * authority that follows it is part of the scheme's spelling, not a doubled separator, so it must survive.
     */
    @Test
    public void aRelativePathIsResolvedAgainstAUrlBasePath() {
        assertThat(FastPathResolver.resolve("http://host/dir", "x.jar")).isEqualTo("http://host/dir/x.jar");
        assertThat(FastPathResolver.resolve("https://host:8080/dir", "x.jar"))
                .isEqualTo("https://host:8080/dir/x.jar");
        assertThat(FastPathResolver.resolve("s3://bucket/dir", "x.jar")).isEqualTo("s3://bucket/dir/x.jar");
        // A scheme with a single slash keeps its single slash
        assertThat(FastPathResolver.resolve("jrt:/modules/java.base", "x")).isEqualTo("jrt:/modules/java.base/x");
        // A scheme that is stripped rather than kept still leaves a resolvable path
        assertThat(FastPathResolver.resolve("file:/a/b", "x.jar")).isEqualTo("/a/b/x.jar");
        // Relative segments are resolved against the URL's path, and cannot climb above its authority
        assertThat(FastPathResolver.resolve("http://host/a/b", "../x.jar")).isEqualTo("http://host/a/x.jar");
        assertThat(FastPathResolver.resolve("http://host/a", "../../x.jar")).isEqualTo("http://host/x.jar");
        // An absolute path or a URL of its own ignores the base path, as always
        assertThat(FastPathResolver.resolve("http://host/dir", "https://other/x.jar"))
                .isEqualTo("https://other/x.jar");
    }
}
