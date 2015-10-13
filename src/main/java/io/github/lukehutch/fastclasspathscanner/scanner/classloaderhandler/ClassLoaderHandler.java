package io.github.lukehutch.fastclasspathscanner.scanner.classloaderhandler;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;

public abstract class ClassLoaderHandler {
    protected final ClasspathFinder classpathFinder;

    public ClassLoaderHandler(final ClasspathFinder classpathFinder) {
        this.classpathFinder = classpathFinder;
    }

    public abstract boolean handle(ClassLoader classloader) throws Exception;
}
