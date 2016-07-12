package io.github.lukehutch.fastclasspathscanner.classpath.classloaderhandlers;

import java.lang.reflect.Array;

import io.github.lukehutch.fastclasspathscanner.classpath.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

public class EquinoxClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean handle(ClassLoader classloader, ClasspathFinder classpathFinder) throws Exception {
        for (Class<?> c = classloader.getClass(); c != null; c = c.getSuperclass()) {
            if ("org.eclipse.osgi.internal.loader.EquinoxClassLoader".equals(c.getName())) {
                // type ClasspathManager
                final Object manager = ReflectionUtils.getFieldVal(c, "manager"); 
                // type ClasspathEntry[]
                final Object entries = ReflectionUtils.getFieldVal(manager, "entries"); 
                if (entries != null) {
                    for (int i = 0, n = Array.getLength(entries); i < n; i++) {
                        // type ClasspathEntry
                        final Object entry = Array.get(entries, i);
                        // type BundleFile
                        final Object bundlefile = ReflectionUtils.getFieldVal(entry, "bundlefile");
                        // type File
                        final Object basefile = ReflectionUtils.getFieldVal(bundlefile, "basefile");
                        if (basefile != null) {
                            // type String
                            final Object cp = ReflectionUtils.getFieldVal(bundlefile, "cp");
                            if (cp != null) {
                                // We found the base file and a classpath element, e.g. "bin/"
                                classpathFinder
                                        .addClasspathElement(basefile.toString() + "/" + cp.toString());
                            } else {
                                // No classpath element found, just use basefile
                                classpathFinder.addClasspathElement(basefile.toString());
                            }
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }
}
