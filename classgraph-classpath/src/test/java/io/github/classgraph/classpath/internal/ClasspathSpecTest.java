package io.github.classgraph.classpath.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.classpath.ClassLoaderOrder;
import io.github.classgraph.classpath.ClasspathOrder;

/** Tests for {@link ClasspathSpec}. */
public class ClasspathSpecTest {
    /** A valid URL scheme is accepted, in any case, and stored in lowercase. */
    @Test
    public void aValidURLSchemeIsAllowed() {
        final var classpathSpec = new ClasspathSpec();
        classpathSpec.allowURLScheme("s3");
        classpathSpec.allowURLScheme("HTTP");
        classpathSpec.allowURLScheme("view-source");
        classpathSpec.allowURLScheme("ms-help");
        classpathSpec.allowURLScheme("z39.50r");
        assertThat(classpathSpec.getAllowedURLSchemes()).containsExactlyInAnyOrder("s3", "http", "view-source",
                "ms-help", "z39.50r");
    }

    /**
     * Create a {@link ClassLoaderHandler} that handles no classloader.
     *
     * @return the handler.
     */
    private static ClassLoaderHandler newHandler() {
        return new ClassLoaderHandler() {
            @Override
            public boolean canHandle(final Class<?> classLoaderClass, final @Nullable ClassGraphLog log) {
                return false;
            }

            @Override
            public void findClassLoaderOrder(final ClassLoader classLoader, final ClassLoaderOrder classLoaderOrder,
                    final @Nullable ClassGraphLog log) {
                // Never called
            }

            @Override
            public void findClasspathOrder(final ClassLoader classLoader, final ClasspathOrder classpathOrder,
                    final @Nullable ClassGraphLog log) {
                // Never called
            }
        };
    }

    /** A copy has every setting of the original, and changing the copy does not change the original. */
    @Test
    public void aCopyIsIndependentOfTheOriginal() {
        final Predicate<String> pathFilter = path -> !path.endsWith("rejected-by-path.jar");
        final Predicate<URL> urlFilter = url -> !url.getPath().endsWith("rejected-by-url.jar");
        final var handler = newHandler();
        final var original = new ClasspathSpec();
        original.allowURLScheme("s3");
        original.filterClasspathElements(pathFilter);
        original.filterClasspathElementsByURL(urlFilter);
        original.addClassLoaderHandler(handler);
        original.enableSystemModules();
        original.ignoreParentClassLoaders();

        final var copy = original.copy();
        assertThat(copy).isNotSameAs(original);
        assertThat(copy.getAllowedURLSchemes()).containsExactly("s3");
        assertThat(copy.acceptsClasspathElement(null, "/lib/accepted.jar")).isTrue();
        assertThat(copy.acceptsClasspathElement(null, "/lib/rejected-by-path.jar")).isFalse();
        assertThat(copy.acceptsClasspathElement(null, "/lib/rejected-by-url.jar")).isFalse();
        assertThat(copy.getClassLoaderHandlers()).containsExactly(handler);
        assertThat(copy.isSystemModulesEnabled()).isTrue();
        assertThat(copy.isNonSystemModulesEnabled()).isFalse();
        assertThat(copy.isParentClassLoadersIgnored()).isTrue();
        assertThat(copy.isParentModuleLayersIgnored()).isFalse();

        copy.allowURLScheme("gs");
        copy.filterClasspathElements(path -> false);
        copy.filterClasspathElementsByURL(url -> false);
        copy.addClassLoaderHandler(newHandler());
        copy.enableNonSystemModules();
        copy.ignoreParentModuleLayers();
        assertThat(original.getAllowedURLSchemes()).containsExactly("s3");
        // The filters that reject everything were added to the copy only
        assertThat(copy.acceptsClasspathElement(null, "/lib/accepted.jar")).isFalse();
        assertThat(original.acceptsClasspathElement(null, "/lib/accepted.jar")).isTrue();
        assertThat(original.getClassLoaderHandlers()).containsExactly(handler);
        assertThat(original.isNonSystemModulesEnabled()).isFalse();
        assertThat(original.isParentModuleLayersIgnored()).isFalse();
    }

    /**
     * A classpath element whose path has no URL form, such as the {@code Path#toUri()} form of a path in a
     * filesystem whose URI scheme has no URL handler, is not tested by the URL filters, only by the path filters.
     */
    @Test
    public void anElementWithNoURLFormIsTestedOnlyByThePathFilters() {
        final var urlsTested = new ArrayList<URL>();
        final var classpathSpec = new ClasspathSpec();
        classpathSpec.filterClasspathElementsByURL(url -> {
            urlsTested.add(url);
            return false;
        });
        // A plain path is converted to a file: URL and rejected by the URL filter
        assertThat(classpathSpec.acceptsClasspathElement(null, "/lib/a.jar")).isFalse();
        assertThat(urlsTested).hasSize(1);
        // No URL handler is registered for this scheme, so the path cannot be converted to a URL
        urlsTested.clear();
        assertThat(classpathSpec.acceptsClasspathElement(null, "nohandlerscheme://fs/cp")).isTrue();
        assertThat(urlsTested).isEmpty();
        // The path filters still apply
        classpathSpec.filterClasspathElements(path -> !path.startsWith("nohandlerscheme:"));
        assertThat(classpathSpec.acceptsClasspathElement(null, "nohandlerscheme://fs/cp")).isFalse();
        assertThat(urlsTested).isEmpty();
    }

    /**
     * The URL that the URL filters are given for a nested classpath element names the same inner path as the
     * element's path, even when that inner path holds characters a URL has to escape.
     *
     * @param tempDir
     *            a temporary directory to hold the outer jarfile.
     * @throws Exception
     *             if the URL cannot be converted to a URI.
     */
    @Test
    public void theURLOfANestedElementEscapesItsInnerPath(@TempDir final Path tempDir) throws Exception {
        // The '!' is a separator only if the path before it names an existing file
        final var outerPath = Files.write(tempDir.resolve("out er.jar"), new byte[] { 'P', 'K' }).toString()
                .replace(File.separatorChar, '/');
        final var innerPath = "/in ner/a%41b?c#d.jar";
        final var urlsTested = new ArrayList<URL>();
        final var classpathSpec = new ClasspathSpec();
        classpathSpec.filterClasspathElementsByURL(url -> {
            urlsTested.add(url);
            return true;
        });
        assertThat(classpathSpec.acceptsClasspathElement(null, outerPath + "!" + innerPath)).isTrue();
        assertThat(urlsTested).hasSize(1);
        final var uri = urlsTested.get(0).toURI();
        assertThat(uri.getScheme()).isEqualTo("jar");
        assertThat(uri.getSchemeSpecificPart())
                .isEqualTo("file:" + new File(outerPath).toURI().getPath() + "!" + innerPath);
    }

    /**
     * The URL that the URL filters are given for a jarfile in a directory whose name ends in '!' is the file URL of
     * the jarfile, since that '!' is part of the directory name, not a nested jar separator. A '!' that does follow
     * a jarfile is a separator, however it is spelled.
     *
     * @param tempDir
     *            a temporary directory to hold the jarfiles.
     * @throws Exception
     *             if a file cannot be written or a URL cannot be formed.
     */
    @Test
    public void theURLOfAJarInADirectoryWhoseNameEndsInAPlingIsAFileURL(@TempDir final Path tempDir)
            throws Exception {
        final var jarPath = Files
                .write(Files.createDirectory(tempDir.resolve("build!")).resolve("lib.jar"), new byte[] { 'P', 'K' })
                .toString().replace(File.separatorChar, '/');
        final var urlsTested = new ArrayList<URL>();
        final var classpathSpec = new ClasspathSpec();
        classpathSpec.filterClasspathElementsByURL(url -> {
            urlsTested.add(url);
            return true;
        });
        assertThat(classpathSpec.acceptsClasspathElement(null, jarPath)).isTrue();
        assertThat(urlsTested).containsExactly(new File(jarPath).toURI().toURL());

        // A trailing '!' and a '!' with no '/' after it, both of which ClassGraph accepts, are separators, and
        // are given to the URL filters in the "!/" form that a jar URL requires. The "!/" of the directory name
        // is percent-encoded in a jar URL, since the jar URL would otherwise end the jarfile's path there.
        final var jarURL = "jar:" + new File(jarPath).toURI().toString().replace("build!/", "build%21/");
        urlsTested.clear();
        assertThat(classpathSpec.acceptsClasspathElement(null, jarPath + "!")).isTrue();
        assertThat(classpathSpec.acceptsClasspathElement(null, jarPath + "!inner.jar")).isTrue();
        assertThat(urlsTested).extracting(URL::toString).containsExactly(jarURL + "!/", jarURL + "!/inner.jar");
        // ... so the JDK's jar URL handler finds the jarfile and the entry within it
        final var jarURLConnection = (JarURLConnection) urlsTested.get(1).openConnection();
        assertThat(Path.of(jarURLConnection.getJarFileURL().toURI())).isEqualTo(Path.of(jarPath));
        assertThat(jarURLConnection.getEntryName()).isEqualTo("inner.jar");
    }

    /** A string that is not a URL scheme is rejected, rather than being stored where it can never match. */
    @Test
    public void aStringThatIsNotAURLSchemeIsRejected() {
        final var classpathSpec = new ClasspathSpec();
        // The commonest mistake: including the scheme's trailing ':'
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.allowURLScheme("s3:"));
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.allowURLScheme("s3://"));
        // A scheme has to start with a letter
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.allowURLScheme("3s"));
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.allowURLScheme("-s3"));
        // A scheme cannot contain a space or a path separator
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.allowURLScheme("my scheme"));
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.allowURLScheme("a/b"));
        // A one-character scheme is indistinguishable from a Windows drive letter
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.allowURLScheme("s"));
        assertThat(classpathSpec.getAllowedURLSchemes()).isEmpty();
    }
}
