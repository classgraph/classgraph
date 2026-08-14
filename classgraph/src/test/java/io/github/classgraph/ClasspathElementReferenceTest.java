package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.classpath.internal.classloaderhandler.ClassLoaderHandlerRegistry;
import io.github.classgraph.internal.scanspec.ScanSpec;
import io.github.classgraph.vfs.Vfs;

/**
 * The same directory or jar can be referenced by more than one work unit, e.g. through a parent-last classloader
 * and through its parent, or both from the toplevel classpath and from the {@code Class-Path} manifest entry of
 * another jar. Only one of those work units creates the {@link ClasspathElement} singleton, and which one wins that
 * race is nondeterministic, so both the classpath ordering key and the classloader must be taken from the winning
 * reference, not from whichever work unit happened to create the element.
 *
 * <p>
 * Otherwise the classpath order is nondeterministic, and {@link ClassInfo#getClassLoaderString()} intermittently
 * reports the wrong classloader for a class (which is what made
 * {@code io.github.classgraph.issues.issue267.ClassLoadingWorksWithParentLastLoadersStubTest} fail intermittently
 * on macOS and Windows). The end-to-end races are timing-dependent and cannot be forced, so the precedence rule
 * that they depend on is tested directly here.
 */
// #810
class ClasspathElementReferenceTest {
    /** The virtual filesystem the classpath elements would read through, if the tests read anything from them. */
    @AutoClose
    private static final Vfs VFS = new Vfs();

    /**
     * Build a classpath element as if it had been created by the work unit for the given reference.
     */
    private static ClasspathElement classpathElementCreatedBy(final String classLoaderStr,
            final boolean isToplevelRef, final int idx) {
        final var dir = Path.of(".");
        final var workUnit = new ClasspathEntryWorkUnit(dir, classLoaderStr, /* parentClasspathElement = */ null,
                idx, /* packageRootPrefix = */ "", ClassLoaderHandlerRegistry.NO_PACKAGE_ROOT_PREFIXES);
        final ClasspathElement classpathElement = new ClasspathElementDir(workUnit, VFS, new ScanSpec());
        classpathElement.addReference(isToplevelRef, idx, classLoaderStr);
        return classpathElement;
    }

    /**
     * A toplevel reference beats a reference from a parent classpath element, even if the classpath element was
     * created by the work unit for the reference from the parent, and even if the toplevel reference has a higher
     * index.
     */
    @Test
    void toplevelReferenceWins() {
        final var childRefClassLoader = "child-ref-classloader";
        final var toplevelRefClassLoader = "toplevel-ref-classloader";

        // The classpath element is created by the work unit that references it from a parent classpath element
        final var classpathElement = classpathElementCreatedBy(childRefClassLoader, /* isToplevelRef = */ false,
                /* idx = */ 0);
        assertThat(classpathElement.getClassLoaderString()).isEqualTo(childRefClassLoader);

        // A toplevel reference then wins, even though its index is higher
        classpathElement.addReference(/* isToplevelRef = */ true, /* idx = */ 5, toplevelRefClassLoader);
        assertThat(classpathElement.getClassLoaderString()).isEqualTo(toplevelRefClassLoader);

        // The classpath element must now be ordered as toplevel element 5, i.e. after toplevel element 4 and before
        // toplevel element 6 -- rather than as child element 0, which would sort after every toplevel element
        assertThat(classpathElement)
                .isGreaterThan(classpathElementCreatedBy(toplevelRefClassLoader, /* isToplevelRef = */ true, 4));
        assertThat(classpathElement)
                .isLessThan(classpathElementCreatedBy(toplevelRefClassLoader, /* isToplevelRef = */ true, 6));
    }

    /** Between references of the same kind, the earliest reference wins. */
    @Test
    void earliestReferenceOfTheSameKindWins() {
        final var laterRefClassLoader = "later-ref-classloader";
        final var earlierRefClassLoader = "earlier-ref-classloader";

        // The classpath element is created by the work unit for the later of two toplevel references
        final var classpathElement = classpathElementCreatedBy(laterRefClassLoader, /* isToplevelRef = */ true,
                /* idx = */ 3);
        assertThat(classpathElement.getClassLoaderString()).isEqualTo(laterRefClassLoader);

        // An earlier toplevel reference then wins
        classpathElement.addReference(/* isToplevelRef = */ true, /* idx = */ 1, earlierRefClassLoader);
        assertThat(classpathElement.getClassLoaderString()).isEqualTo(earlierRefClassLoader);
        assertThat(classpathElement)
                .isLessThan(classpathElementCreatedBy(earlierRefClassLoader, /* isToplevelRef = */ true, 2));

        // ...and a still later reference does not take it back
        classpathElement.addReference(/* isToplevelRef = */ true, /* idx = */ 9, laterRefClassLoader);
        assertThat(classpathElement.getClassLoaderString()).isEqualTo(earlierRefClassLoader);
        assertThat(classpathElement)
                .isLessThan(classpathElementCreatedBy(earlierRefClassLoader, /* isToplevelRef = */ true, 2));
    }
}
