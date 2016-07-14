package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;

/**
 * The combination of a classpath element and a relative path within this classpath element, and the
 * FileMatchProcessor that will handle the file at this relative path.
 */
class ClasspathResource {
    final File classpathElt;
    final boolean classpathEltIsJar;
    final String relativePath;
    final FileMatchProcessorWrapper fileMatchProcessorWrapper;

    /** Used as an end-of-queue marker in the work queue. */
    final static ClasspathResource END_OF_QUEUE = new ClasspathResource(null, false, null, null);

    public ClasspathResource(final File classpathElt, final boolean classpathEltIsJar, final String relativePath,
            final FileMatchProcessorWrapper fileMatchProcessorWrapper) {
        this.classpathElt = classpathElt;
        this.classpathEltIsJar = classpathEltIsJar;
        this.relativePath = relativePath;
        this.fileMatchProcessorWrapper = fileMatchProcessorWrapper;
    }

    public ClasspathResource(final File classpathElt, final boolean classpathEltIsJar, final String relativePath) {
        this(classpathElt, classpathEltIsJar, relativePath, null);
    }
}