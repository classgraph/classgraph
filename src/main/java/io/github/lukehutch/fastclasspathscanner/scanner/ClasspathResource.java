package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;

import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.FileMatchProcessorWrapper;

/**
 * The combination of a classpath element and a relative path within this classpath element, and the
 * FileMatchProcessor that will handle the file at this relative path.
 */
class ClasspathResource {
    File classpathElt;
    String relativePath;
    FileMatchProcessorWrapper fileMatchProcessorWrapper;

    ClasspathResource() {
    }

    public ClasspathResource(final File classpathElt, final String relativePath,
            final FileMatchProcessorWrapper fileMatchProcessorWrapper) {
        this.classpathElt = classpathElt;
        this.relativePath = relativePath;
        this.fileMatchProcessorWrapper = fileMatchProcessorWrapper;
    }

    public ClasspathResource(final File classpathElt, final String relativePath) {
        this(classpathElt, relativePath, null);
    }
}