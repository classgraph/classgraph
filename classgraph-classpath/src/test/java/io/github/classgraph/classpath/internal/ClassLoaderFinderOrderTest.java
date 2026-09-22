package io.github.classgraph.classpath.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

/** Tests for the order of the classloaders that {@link ClassLoaderFinder} finds. */
class ClassLoaderFinderOrderTest {
    /**
     * The context classloader is searched first, since it is how a thread overrides the classloaders it would
     * otherwise use -- even when it has fewer ancestors than a classloader it is unrelated to.
     *
     * @throws Exception
     *             if the classloader could not be closed.
     */
    @Test
    void theContextClassLoaderIsSearchedBeforeAnUnrelatedClassLoaderWithMoreAncestors() throws Exception {
        final var thread = Thread.currentThread();
        final var previousContextClassLoader = thread.getContextClassLoader();
        // A classloader with no parent other than the bootstrap classloader, which is unrelated to the classloader
        // that loaded ClassGraph, and has fewer ancestors than it
        try (var contextClassLoader = new URLClassLoader(new URL[0], /* parent = */ null)) {
            thread.setContextClassLoader(contextClassLoader);
            final var classLoaders = new ClassLoaderFinder(CallStackInfo.read(), /* log = */ null)
                    .getClassLoaders();
            assertThat(classLoaders).first().isSameAs(contextClassLoader);
        } finally {
            thread.setContextClassLoader(previousContextClassLoader);
        }
    }

    /**
     * A classloader is searched before its own ancestors, whatever order it was found in, since its handler decides
     * where its ancestors' classpath elements go relative to its own.
     *
     * @throws Exception
     *             if the classloader could not be closed.
     */
    @Test
    void aClassLoaderIsSearchedBeforeItsAncestors() throws Exception {
        final var thread = Thread.currentThread();
        final var previousContextClassLoader = thread.getContextClassLoader();
        final var classGraphClassLoader = ClassLoaderFinder.class.getClassLoader();
        try (var child = new URLClassLoader(new URL[0], classGraphClassLoader)) {
            thread.setContextClassLoader(child);
            final var classLoaders = new ClassLoaderFinder(CallStackInfo.read(), /* log = */ null)
                    .getClassLoaders();
            assertThat(classLoaders.indexOf(child)).isLessThan(classLoaders.indexOf(classGraphClassLoader));
            for (var ancestor = classGraphClassLoader; ancestor != null; ancestor = ancestor.getParent()) {
                if (classLoaders.contains(ancestor)) {
                    assertThat(classLoaders.indexOf(ancestor)).isGreaterThan(classLoaders.indexOf(child));
                }
            }
        } finally {
            thread.setContextClassLoader(previousContextClassLoader);
        }
    }
}
