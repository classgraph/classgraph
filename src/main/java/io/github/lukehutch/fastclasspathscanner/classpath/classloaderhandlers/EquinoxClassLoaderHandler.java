package io.github.lukehutch.fastclasspathscanner.classpath.classloaderhandlers;

import java.lang.reflect.Array;

import io.github.lukehutch.fastclasspathscanner.classpath.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.utils.Log;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

public class EquinoxClassLoaderHandler implements ClassLoaderHandler {
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
                // type BundleLoader
                final Object delegate = ReflectionUtils.getFieldVal(c, "delegate"); 
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
                // type EquinoxSystemModule
                final Object module0 = ReflectionUtils.invokeMethod(modulesById, "get", 0);
                // type Bundle[]
                final Object bundles = ReflectionUtils.getFieldVal(module0, "getBundleContext");
                for (int i = 0, n = Array.getLength(bundles); i < n; i++) {
                    // type Bundle
                    final Object bundle = Array.get(bundles, i);
                    Log.log("Got bundle " + bundle);
                }                
                return true;
            }
        }
        return false;
    }
}
