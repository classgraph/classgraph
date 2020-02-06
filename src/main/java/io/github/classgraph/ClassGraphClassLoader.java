/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.JarUtils;

/** {@link ClassLoader} for classes found by ClassGraph during scanning. */
class ClassGraphClassLoader extends ClassLoader {

    /** The scan result. */
    private final ScanResult scanResult;

    /** The environment classloader order. */
    private final ClassLoader[] envClassLoaderOrder;

    /** The classpath URLs. */
    private final URLClassLoader classpathLoader;

    /**
     * Constructor.
     *
     * @param scanResult
     *            The ScanResult.
     */
    ClassGraphClassLoader(final ScanResult scanResult) {
        super(null);
        this.scanResult = scanResult;
        this.envClassLoaderOrder = scanResult.getClassLoaderOrderRespectingParentDelegation();
        this.classpathLoader = new URLClassLoader(scanResult.getClasspathURLs().toArray(new URL[0]));
        registerAsParallelCapable();
    }

    /* (non-Javadoc)
     * @see java.lang.ClassLoader#findClass(java.lang.String)
     */
    @Override
    protected Class<?> findClass(final String className)
            throws ClassNotFoundException, LinkageError, SecurityException {
        // Use cached class, if it is already loaded
        final Class<?> loadedClass = findLoadedClass(className);
        if (loadedClass != null) {
            return loadedClass;
        }

        // Only try environment classloaders if classpath and/or classloaders are not overridden
        final ScanSpec scanSpec = scanResult.scanSpec;
        final Set<ClassLoader> triedClassLoaders = new HashSet<>();
        if ((scanSpec.overrideClasspath == null || scanSpec.overrideClasspath.isEmpty())
                && (scanSpec.overrideClassLoaders == null || scanSpec.overrideClassLoaders.isEmpty())) {
            // Try null classloader (the classloader that loaded this class, as the caller of Class.forName())
            try {
                return Class.forName(className, scanSpec.initializeLoadedClasses, null);
            } catch (ClassNotFoundException | LinkageError e) {
                // Ignore
            }

            // Try environment classloaders
            if (envClassLoaderOrder != null) {
                // Try environment classloaders
                for (final ClassLoader envClassLoader : envClassLoaderOrder) {
                    if (triedClassLoaders.add(envClassLoader)) {
                        try {
                            return Class.forName(className, scanSpec.initializeLoadedClasses, envClassLoader);
                        } catch (ClassNotFoundException | LinkageError e) {
                            // Ignore
                        }
                    }
                }
            }
        }

        // If the classpath is overridden, try loading class from the classpath URLs (this is done before
        // checking classloader overrides, since classpath override takes precedence over classloader
        // overrides in the ClasspathFinder class). Some of these URLs might be invalid if the ScanResult
        // has been closed (e.g. in the rare case that an inner jar had to be extracted to a temporary file
        // on disk).
        if (scanSpec.overrideClasspath != null && !scanSpec.overrideClasspath.isEmpty()) {
            try {
                return classpathLoader.loadClass(className);
            } catch (ClassNotFoundException | LinkageError e) {
                // Ignore
            }
        }

        // Try getting the ClassInfo for the named class, then the ClassLoader from the ClassInfo.
        // This requires the ScanResult not to have been already closed, so this is only attempted if all the
        // above efforts failed (#399).
        final ClassInfo classInfo = scanResult.classNameToClassInfo == null ? null
                : scanResult.classNameToClassInfo.get(className);
        if (classInfo != null) {
            // Try specific classloader for the classpath element that the classfile was obtained from
            if (classInfo.classLoader != null) {
                if (triedClassLoaders.add(classInfo.classLoader)) {
                    try {
                        return Class.forName(className, scanSpec.initializeLoadedClasses, classInfo.classLoader);
                    } catch (ClassNotFoundException | LinkageError e) {
                        // Ignore
                    }
                }
            }

            // If class came from a module, and it was not able to be loaded by the environment classloader,
            // then it is probable it was a non-public class, and ClassGraph found it by ignoring class visibility
            // when reading the resources in exported packages directly. Force ClassGraph to respect JPMS
            // encapsulation rules by refusing to load modular classes that the context/system classloaders
            // could not load. (A SecurityException should be thrown above, but this is here for completeness.)
            if (classInfo.classpathElement instanceof ClasspathElementModule && !classInfo.isPublic()) {
                throw new ClassNotFoundException("Classfile for class " + className + " was found in a module, "
                        + "but the context and system classloaders could not load the class, probably because "
                        + "the class is not public.");
            }
        }

        // If classloaders are overridden or added, try loading through those classloaders
        if (scanSpec.overrideClassLoaders != null && !scanSpec.overrideClassLoaders.isEmpty()) {
            for (final ClassLoader overrideClassLoader : scanSpec.overrideClassLoaders) {
                if (triedClassLoaders.add(overrideClassLoader)) {
                    try {
                        return overrideClassLoader.loadClass(className);
                    } catch (ClassNotFoundException | LinkageError e) {
                        // Ignore
                    }
                }
            }
        }
        if (scanSpec.addedClassLoaders != null && !scanSpec.addedClassLoaders.isEmpty()) {
            for (final ClassLoader addedClassLoader : scanSpec.addedClassLoaders) {
                if (triedClassLoaders.add(addedClassLoader)) {
                    try {
                        return addedClassLoader.loadClass(className);
                    } catch (ClassNotFoundException | LinkageError e) {
                        // Ignore
                    }
                }
            }
        }

        // If the classpath was not overridden, now that override classloaders have been attempted and failed,
        // still try to load the class from the classpath URLs before attempting direct classloading from resources
        if (scanSpec.overrideClasspath == null || scanSpec.overrideClasspath.isEmpty()) {
            try {
                return classpathLoader.loadClass(className);
            } catch (ClassNotFoundException | LinkageError e) {
                // Ignore
            }
        }

        // Try obtaining the classfile as a resource, and defining the class from the resource content.
        // This is a last-ditch attempt if the above efforts all failed. This should be performed after
        // envirnoment classloading is attempted, so that classes are not loaded by a mix of environment
        // classloaders and direct manual classloading, otherwise class compatibility issues can arise.
        // The ScanResult should only be accessed (to fetch resources) as a last resort, so that wherever
        // possible, linked classes can be loaded after the ScanResult is closed. Otherwise if you load
        // classes before a ScanResult is closed, then you close the ScanResult, then you try to access
        // fields of the ScanResult that have a type that has not yet been loaded, this can trigger an
        // exception that the ScanResult was accessed after it was closed (#399).
        final ResourceList classfileResources = scanResult
                .getResourcesWithPath(JarUtils.classNameToClassfilePath(className));
        if (classfileResources != null) {
            for (final Resource resource : classfileResources) {
                // Iterate through resources (only loading of first resource in the list will be attempted)
                try {
                    // Load the content of the resource, and define a class from it
                    final ByteBuffer resourceByteBuffer = resource.read();
                    return defineClass(className, resourceByteBuffer, null);
                } catch (final IOException e) {
                    throw new ClassNotFoundException("Could not load classfile for class " + className + " : " + e);
                } finally {
                    resource.close();
                }
            }
        }
        throw new ClassNotFoundException("Could not load classfile for class " + className);
    }

    /* (non-Javadoc)
     * @see java.lang.ClassLoader#getResource(java.lang.String)
     */
    @Override
    public URL getResource(final String path) {
        final ResourceList resourceList = scanResult.getResourcesWithPath(path);
        if (resourceList == null || resourceList.isEmpty()) {
            return super.getResource(path);
        } else {
            return resourceList.get(0).getURL();
        }
    }

    /* (non-Javadoc)
     * @see java.lang.ClassLoader#getResources(java.lang.String)
     */
    @Override
    public Enumeration<URL> getResources(final String path) throws IOException {
        final ResourceList resourceList = scanResult.getResourcesWithPath(path);
        if (resourceList == null || resourceList.isEmpty()) {
            return super.getResources(path);
        } else {
            return new Enumeration<URL>() {
                /** The idx. */
                int idx;

                @Override
                public boolean hasMoreElements() {
                    return idx < resourceList.size();
                }

                @Override
                public URL nextElement() {
                    return resourceList.get(idx++).getURL();
                }
            };
        }
    }

    /* (non-Javadoc)
     * @see java.lang.ClassLoader#getResourceAsStream(java.lang.String)
     */
    @Override
    public InputStream getResourceAsStream(final String path) {
        final ResourceList resourceList = scanResult.getResourcesWithPath(path);
        if (resourceList == null || resourceList.isEmpty()) {
            return super.getResourceAsStream(path);
        } else {
            try {
                return resourceList.get(0).open();
            } catch (final IOException e) {
                return null;
            }
        }
    }
}
