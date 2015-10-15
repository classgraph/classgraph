package io.github.lukehutch.fastclasspathscanner.scanner.classloaderhandler;

import java.net.URL;
import java.net.URLClassLoader;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;

public class URLClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean handle(final ClassLoader classloader, final ClasspathFinder classpathFinder) {
        if (classloader instanceof URLClassLoader) {
            for (final URL url : ((URLClassLoader) classloader).getURLs()) {
                classpathFinder.addClasspathElement(url.toString());
            }
            return true;
        }
        return false;
    }
}
