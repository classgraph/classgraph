package io.github.lukehutch.fastclasspathscanner.scanner.classloaderhandler;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;

public class JBossClassLoaderHandler extends ClassLoaderHandler {
    public JBossClassLoaderHandler(final ClasspathFinder classpathFinder) {
        super(classpathFinder);
    }

    // See:
    // https://github.com/jboss-modules/jboss-modules/blob/master/src/main/java/org/jboss/modules/ ...
    // ModuleClassLoader.java
    @Override
    public boolean handle(final ClassLoader classloader) throws Exception {
        for (Class<?> c = classloader.getClass(); c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals("org.jboss.modules.ModuleClassLoader")) {
                final Method getResourceLoaders = c.getDeclaredMethod("getResourceLoaders");
                if (!getResourceLoaders.isAccessible()) {
                    getResourceLoaders.setAccessible(true);
                }
                final Object result = getResourceLoaders.invoke(classloader);
                for (int i = 0, n = Array.getLength(result); i < n; i++) {
                    final Object resourceLoader = Array.get(result, i); // type VFSResourceLoader
                    final Field root = resourceLoader.getClass().getDeclaredField("root");
                    if (!root.isAccessible()) {
                        root.setAccessible(true);
                    }
                    final Object rootVal = root.get(resourceLoader); // type VirtualFile
                    final Method getPathName = rootVal.getClass().getDeclaredMethod("getPathName");
                    if (!getPathName.isAccessible()) {
                        getPathName.setAccessible(true);
                    }
                    final String pathName = (String) getPathName.invoke(rootVal);
                    classpathFinder.addClasspathElement(pathName);
                }
                return true;
            }
        }
        return false;
    }
}
