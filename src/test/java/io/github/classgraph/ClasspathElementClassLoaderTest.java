package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * The same directory or jar can be referenced by more than one work unit, e.g. through a parent-last classloader and
 * through its parent, or both from the toplevel classpath and from the {@code Class-Path} manifest entry of another
 * jar. Only one of those work units creates the {@link ClasspathElement} singleton, and which one wins that race is
 * nondeterministic, so the classloader must be taken from the reference that determines the classpath element's
 * position in the classpath order, not from whichever work unit happened to create the element.
 *
 * <p>
 * Otherwise {@code ClassInfo#loadClass()} intermittently loads a class through the wrong classloader -- which is
 * what {@code io.github.classgraph.issues.issue267.ClassLoadingWorksWithParentLastLoadersStubTest} tests
 * end-to-end, and why that test failed intermittently on macOS and Windows.
 */
class ClasspathElementClassLoaderTest {
    /** Build a classpath element as if it had been created by the given work unit. */
    private static ClasspathElement classpathElementCreatedBy(final ClassLoader classLoader,
            final boolean isToplevelRef, final int idx) {
        final Path dir = Paths.get(".");
        final ClasspathEntryWorkUnit workUnit = new ClasspathEntryWorkUnit(dir, classLoader,
                /* parentClasspathElement = */ null, idx, /* packageRootPrefix = */ "",
                ClassLoaderHandlerRegistry.NO_PACKAGE_ROOT_PREFIXES);
        final ClasspathElement classpathElement = new ClasspathElementDir(workUnit,
                /* nestedJarHandler = */ null, new ScanSpec());
        classpathElement.addReference(isToplevelRef, idx, classLoader);
        return classpathElement;
    }

    /** A toplevel reference beats a reference from a parent classpath element, and brings its classloader. */
    @Test
    void toplevelReferenceSuppliesTheClassLoader() {
        final ClassLoader childRefClassLoader = new URLClassLoader(new URL[0]);
        final ClassLoader toplevelRefClassLoader = new URLClassLoader(new URL[0]);

        // The work unit that creates the element is the one referencing it from a parent classpath element
        final ClasspathElement classpathElement = classpathElementCreatedBy(childRefClassLoader,
                /* isToplevelRef = */ false, /* idx = */ 0);
        assertThat(classpathElement.getClassLoader()).isSameAs(childRefClassLoader);

        // A later toplevel reference wins, even though its index is higher, so it supplies the classloader
        classpathElement.addReference(/* isToplevelRef = */ true, /* idx = */ 5, toplevelRefClassLoader);
        assertThat(classpathElement.getClassLoader()).isSameAs(toplevelRefClassLoader);
    }

    /** Between references of the same kind, the earliest one wins, and brings its classloader. */
    @Test
    void earliestReferenceOfTheSameKindSuppliesTheClassLoader() {
        final ClassLoader laterRefClassLoader = new URLClassLoader(new URL[0]);
        final ClassLoader earlierRefClassLoader = new URLClassLoader(new URL[0]);

        // The work unit that creates the element is the later of the two toplevel references
        final ClasspathElement classpathElement = classpathElementCreatedBy(laterRefClassLoader,
                /* isToplevelRef = */ true, /* idx = */ 3);
        assertThat(classpathElement.getClassLoader()).isSameAs(laterRefClassLoader);

        // An earlier toplevel reference wins, so it supplies the classloader
        classpathElement.addReference(/* isToplevelRef = */ true, /* idx = */ 1, earlierRefClassLoader);
        assertThat(classpathElement.getClassLoader()).isSameAs(earlierRefClassLoader);

        // ...and a still later reference does not take it back
        classpathElement.addReference(/* isToplevelRef = */ true, /* idx = */ 9, laterRefClassLoader);
        assertThat(classpathElement.getClassLoader()).isSameAs(earlierRefClassLoader);
    }
}
