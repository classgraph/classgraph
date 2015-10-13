package io.github.lukehutch.fastclasspathscanner.scanner.classloader;

import java.net.URL;
import java.net.URLClassLoader;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;

public class URLClassLoaderHandler extends ClassLoaderHandler {
    public URLClassLoaderHandler(ClasspathFinder classpathFinder) {
        super(classpathFinder);
    }

    @Override
    public boolean handle(ClassLoader classloader) {
        if (classloader instanceof URLClassLoader) {
            for (final URL url : ((URLClassLoader) classloader).getURLs()) {
                classpathFinder.addClasspathElement(url.toString());
            }
            return true;
        }
        return false;
    }
}
