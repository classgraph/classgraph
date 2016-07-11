package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;

/** The combination of a classpath element and a relative path within this classpath element. */
class ClassfileResource {
    final File classpathElt;
    final String relativePath;
    
    /** Used as an end-of-queue marker in the work queue. */
    final static ClassfileResource END_OF_QUEUE = new ClassfileResource(null, null);

    public ClassfileResource(final File classpathElt, final String relativePath) {
        this.classpathElt = classpathElt;
        this.relativePath = relativePath;
    }
}