package io.github.lukehutch.fastclasspathscanner.scanner.classloader;

import java.lang.reflect.Method;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;

public class WeblogicClassLoaderHandler extends ClassLoaderHandler {
    public WeblogicClassLoaderHandler(ClasspathFinder classpathFinder) {
        super(classpathFinder);
    }

    // See:
    // https://github.com/jboss-modules/jboss-modules/blob/master/src/main/java/org/jboss/modules/ ...
    // ModuleClassLoader.java
    @Override
    public boolean handle(ClassLoader classloader) throws Exception {
        for (Class<?> c = classloader.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
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
