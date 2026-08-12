package org.jboss.modules;

import java.io.File;

/** Stand-in for JBoss' {@code JarFileResourceLoader}, which reads a module's classes out of a jarfile. */
public class JarFileResourceLoader {
    /** The jarfile that the module's classes are read from. */
    public final File fileOfJar;

    /**
     * Constructor.
     *
     * @param fileOfJar
     *            the jarfile that the module's classes are read from.
     */
    public JarFileResourceLoader(final File fileOfJar) {
        this.fileOfJar = fileOfJar;
    }
}
