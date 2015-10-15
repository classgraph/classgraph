package io.github.lukehutch.fastclasspathscanner.scanner.classloaderhandler;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;

/**
 * A ClassLoader handler.
 * 
 * Custom ClassLoaderHandlers can be registered by listing their fully-qualified class name in the file:
 * META-INF/services/io.github.lukehutch.fastclasspathscanner.scanner.classloaderhandler.ClassLoaderHandler
 * 
 * However, if you do create a custom ClassLoaderHandler, please consider submitting a patch upstream for
 * incorporation into FastClasspathScanner.
 */
public interface ClassLoaderHandler {
    /**
     * Determine if a given ClassLoader can be handled (meaning that its classpath elements can be extracted from
     * it), and if it can, extract the classpath elements from the ClassLoader and register them with the
     * ClasspathFinder using classpathFinder.addClasspathElement(pathElement) or
     * classpathFinder.addClasspathElements(path).
     * 
     * @param classloader
     *            The ClassLoader class to attempt to handle. You should iterate through its superclass line to
     *            determine if any superclasses can be handled by this ClassLoaderHandler.
     * @param classpathFinder
     *            The ClasspathFinder to register any discovered classpath elements with.
     * @return true if the passed ClassLoader was handled by this ClassLoaderHandler, else false.
     */
    public abstract boolean handle(final ClassLoader classloader, final ClasspathFinder classpathFinder)
            throws Exception;
}
