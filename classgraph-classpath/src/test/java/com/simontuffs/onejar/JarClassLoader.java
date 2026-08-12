package com.simontuffs.onejar;

/**
 * Stand-in for One-Jar's {@code JarClassLoader}, which loads an application from a single jar. Unlike Uno-Jar's
 * classloader, it does not report the path of that jar -- the jar and any extra classpath entries are named by
 * system properties instead.
 */
public class JarClassLoader extends ClassLoader {
    /** Constructor. */
    public JarClassLoader() {
        super(/* parent = */ null);
    }
}
