package com.needhamsoftware.unojar;

/** Stand-in for Uno-Jar's {@code JarClassLoader}, which loads an application from a single jar. */
public class JarClassLoader extends ClassLoader {
    /** Constructor. */
    public JarClassLoader() {
        super(/* parent = */ null);
    }

    /**
     * The path of the jar the application runs from.
     *
     * @return null, since this classloader does not know it.
     */
    public String getOneJarPath() {
        return null;
    }
}
