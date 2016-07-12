package io.github.lukehutch.fastclasspathscanner.classpath.classloaderhandlers;

import java.lang.reflect.Array;

import io.github.lukehutch.fastclasspathscanner.classpath.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

public class EquinoxClassLoaderHandler implements ClassLoaderHandler {
    private boolean readSystemBundles = false;

    @Override
    public boolean handle(ClassLoader classloader, ClasspathFinder classpathFinder) throws Exception {
        for (Class<?> c = classloader.getClass(); c != null; c = c.getSuperclass()) {
            if ("org.eclipse.osgi.internal.loader.EquinoxClassLoader".equals(c.getName())) {
                // type ClasspathManager
                final Object manager = ReflectionUtils.getFieldVal(classloader, "manager");
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
                                // We found the base file and a classpath
                                // element, e.g. "bin/"
                                classpathFinder.addClasspathElement(basefile.toString() + "/" + cp.toString());
                            } else {
                                // No classpath element found, just use basefile
                                classpathFinder.addClasspathElement(basefile.toString());
                            }
                        }
                    }
                }
                // Only read system bundles once (all bundles should give the same results for this).
                // We assume there is only one separate Equinox instance on the classpath.
                if (!readSystemBundles) {
                    // type BundleLoader
                    final Object delegate = ReflectionUtils.getFieldVal(classloader, "delegate");
                    // type EquinoxContainer
                    final Object container = ReflectionUtils.getFieldVal(delegate, "container");
                    // type Storage
                    final Object storage = ReflectionUtils.getFieldVal(container, "storage");
                    // type ModuleContainer
                    final Object moduleContainer = ReflectionUtils.getFieldVal(storage, "moduleContainer");
                    // type ModuleDatabase
                    final Object moduleDatabase = ReflectionUtils.getFieldVal(moduleContainer, "moduleDatabase");
                    // type HashMap<Integer, EquinoxModule>
                    final Object modulesById = ReflectionUtils.getFieldVal(moduleDatabase, "modulesById");
                    // type EquinoxSystemModule (module 0 is always the system module)
                    final Object module0 = ReflectionUtils.invokeMethod(modulesById, "get", Object.class, 0L);
                    // type Bundle
                    final Object bundle = ReflectionUtils.invokeMethod(module0, "getBundle");
                    // type BundleContext
                    final Object bundleContext = ReflectionUtils.invokeMethod(bundle, "getBundleContext");
                    // type Bundle[]
                    final Object bundles = ReflectionUtils.invokeMethod(bundleContext, "getBundles");
                    if (bundles != null) {
                        for (int i = 0, n = Array.getLength(bundles); i < n; i++) {
                            // type EquinoxBundle
                            final Object equinoxBundle = Array.get(bundles, i);
                            // type EquinoxModule
                            final Object module = ReflectionUtils.getFieldVal(equinoxBundle, "module");
                            // type String
                            String location = (String) ReflectionUtils.getFieldVal(module, "location");
                            if (location != null) {
                                int fileIdx = location.indexOf("file:");
                                if (fileIdx >= 0) {
                                    location = location.substring(fileIdx);
                                    classpathFinder.addClasspathElement(location);
                                }
                            }
                        }
                    }
                    readSystemBundles = true;
                }
                return true;
            }
        }
        return false;
    }
}
