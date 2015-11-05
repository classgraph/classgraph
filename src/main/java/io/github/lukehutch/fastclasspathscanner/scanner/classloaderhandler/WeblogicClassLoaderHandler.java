package io.github.lukehutch.fastclasspathscanner.scanner.classloaderhandler;

import java.lang.reflect.Method;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;

public class WeblogicClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean handle(final ClassLoader classloader, final ClasspathFinder classpathFinder) throws Exception {
        for (Class<?> c = classloader.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("weblogic.utils.classloaders.ChangeAwareClassLoader")) {
                final Method getClassPath = c.getDeclaredMethod("getClassPath");
                if (!getClassPath.isAccessible()) {
                    getClassPath.setAccessible(true);
                }
                final String classpath = (String) getClassPath.invoke(classloader);
                classpathFinder.addClasspathElements(classpath);
                return true;
            }
        }
        return false;
    }
}
