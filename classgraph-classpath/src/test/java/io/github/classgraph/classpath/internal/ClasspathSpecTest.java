package io.github.classgraph.classpath.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URL;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
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
