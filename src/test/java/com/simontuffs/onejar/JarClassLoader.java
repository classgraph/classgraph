package com.simontuffs.onejar;

/** Stand-in for One-Jar's {@code JarClassLoader}, which loads an application from a single jar. */
public class JarClassLoader extends ClassLoader {
    /** Constructor. */
    public JarClassLoader() {
        super(/* parent = */ null);
    }
}
