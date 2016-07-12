package io.github.lukehutch.fastclasspathscanner.classpath.classloaderhandlers;

import java.lang.reflect.Array;
import java.lang.reflect.Field;

import io.github.lukehutch.fastclasspathscanner.classpath.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;

public class EquinoxClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean handle(ClassLoader classloader, ClasspathFinder classpathFinder) throws Exception {
        boolean foundFile = false;
        for (Class<?> c = classloader.getClass(); c != null; c = c.getSuperclass()) {
            if ("org.eclipse.osgi.internal.loader.EquinoxClassLoader".equals(c.getName())) {
                final Field managerField = c.getDeclaredField("manager");
                if (!managerField.isAccessible()) {
                    managerField.setAccessible(true);
                }
                // type ClasspathManager
                final Object manager = managerField.get(classloader);
                if (manager != null) {
                    // type ClasspathEntry[]
                    final Field entriesField = manager.getClass().getDeclaredField("entries");
                    if (!entriesField.isAccessible()) {
                        entriesField.setAccessible(true);
                    }
                    final Object entries = entriesField.get(manager);
                    if (entries != null) {
                        for (int i = 0, n = Array.getLength(entries); i < n; i++) {
                            // type ClasspathEntry
                            final Object entry = Array.get(entries, i);
                            // type BundleFile
                            final Field bundlefileField = entry.getClass().getDeclaredField("bundlefile");
                            if (!bundlefileField.isAccessible()) {
                                bundlefileField.setAccessible(true);
                            }
                            final Object bundlefile = bundlefileField.get(entry);
                            if (bundlefile != null) {
                                // Need to iterate through superclasses until we find the relevant fields
                                Object basefile = null;
                                for (Class<?> bundlefileClass = bundlefile
                                        .getClass(); bundlefileClass != null; bundlefileClass = bundlefileClass
                                                .getSuperclass()) {
                                    try {
                                        // type File
                                        final Field basefileField = bundlefileClass.getDeclaredField("basefile");
                                        if (!basefileField.isAccessible()) {
                                            basefileField.setAccessible(true);
                                        }
                                        basefile = basefileField.get(bundlefile);
                                        break;
                                    } catch (NoSuchFieldException e) {
                                        // Try parent
                                    }
                                }
                                Object cp = null;
                                for (Class<?> bundlefileClass = bundlefile
                                        .getClass(); bundlefileClass != null; bundlefileClass = bundlefileClass
                                                .getSuperclass()) {
                                    try {
                                        // type String
                                        final Field cpField = bundlefileClass.getDeclaredField("cp");
                                        if (!cpField.isAccessible()) {
                                            cpField.setAccessible(true);
                                        }
                                        cp = cpField.get(bundlefile);
                                        break;
                                    } catch (NoSuchFieldException e) {
                                        // Try parent
                                    }
                                }
                                if (basefile != null) {
                                    if (cp != null) {
                                        // We found the base file and a classpath element, e.g. "bin/"
                                        classpathFinder
                                                .addClasspathElement(basefile.toString() + "/" + cp.toString());
                                    } else {
                                        // No classpath element found, just use basefile
                                        classpathFinder.addClasspathElement(basefile.toString());
                                    }
                                    foundFile = true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return foundFile;
    }
}
