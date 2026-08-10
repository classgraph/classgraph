package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tests for {@link FastPathResolver}. */
public class FastPathResolverTest {
    /**
     * {@link FastPathResolver#resolve(String, String)}, loaded in a class loader that saw {@code os.name} set to
     * Windows, so that the Windows-only branches can be tested on any platform.
     */
    private static Method resolveAsWindowsMethod;

    /**
     * {@link FastPathResolver#resolve(String, String)}, loaded in a class loader that saw {@code os.name} set to
     * Linux, so that the non-Windows branches can be tested on any platform.
     */
    private static Method resolveAsLinuxMethod;

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
     * Load a second copy of {@link FastPathResolver} that believes it is running on Windows, and a third that
     * believes it is running on Linux, so that both sets of branches can be tested whatever the real platform is.
     *
     * @throws Exception
     *             if the extra copies could not be loaded.
     */
    @BeforeAll
    static void loadResolverForEachOS() throws Exception {
        resolveAsWindowsMethod = loadResolverForOS("Windows 10", "Windows");
        resolveAsLinuxMethod = loadResolverForOS("Linux", "Linux");
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
     * be dropped, since folding it into the path names a directory that does not exist; on Windows it is kept, so
     * that it becomes a UNC path, which is what {@code Path#of(URI)} produces there.
     */
    @Test
    public void localhostAuthorityNamesTheLocalMachine() {
        assertThat(resolveAsLinux(null, "file://localhost/tmp/a")).isEqualTo("/tmp/a");
        assertThat(resolveAsLinux("/base", "file://localhost/tmp/a")).isEqualTo("/tmp/a");
        assertThat(resolveAsLinux(null, "jar:file://localhost/tmp/a.jar!/b")).isEqualTo("/tmp/a.jar!/b");
        // The host name is case-insensitive
        assertThat(resolveAsLinux(null, "file://LocalHost/tmp/a")).isEqualTo("/tmp/a");
        // Only the whole host name matches, not a host whose name merely starts with "localhost"
        assertThat(resolveAsLinux(null, "file://localhostile/tmp/a")).isEqualTo("/localhostile/tmp/a");
        // On Windows the authority is kept, giving the UNC path that the JDK produces for the same URL
        assertThat(resolveAsWindows(null, "file://localhost/tmp/a")).isEqualTo("//localhost/tmp/a");
    }

    /** On Windows, a bare drive designation is an absolute path, however it is spelled. */
    @Test
    public void bareDriveDesignationIsAbsoluteOnWindows() {
        assertThat(resolveAsWindows("C:/base", "C:")).isEqualTo("C:");
        assertThat(resolveAsWindows("C:/base", "C:\\")).isEqualTo("C:");
        assertThat(resolveAsWindows("C:/base", "/C:")).isEqualTo("C:");
        // The root of a drive keeps its trailing separator, which is what distinguishes it from the bare drive
        // designation: "C:" names the current directory on drive C:, but "C:/" names the root directory
        assertThat(resolveAsWindows("C:/base", "C:/")).isEqualTo("C:/");
        // A path on a drive is unaffected
        assertThat(resolveAsWindows("C:/base", "C:\\a\\b")).isEqualTo("C:/a/b");
        assertThat(resolveAsWindows("C:/base", "/C:/a/b")).isEqualTo("C:/a/b");
    }
}
