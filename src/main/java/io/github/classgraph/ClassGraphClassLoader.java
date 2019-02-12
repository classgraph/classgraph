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
import java.util.Enumeration;

import nonapi.io.github.classgraph.utils.JarUtils;

/** {@link ClassLoader} for classes found by ClassGraph during scanning. */
class ClassGraphClassLoader extends ClassLoader {

    /** The scan result. */
    private final ScanResult scanResult;

    /**
     * Constructor.
     *
     * @param scanResult
     *            The ScanResult.
     */
    ClassGraphClassLoader(final ScanResult scanResult) {
        super(null);
        this.scanResult = scanResult;
        registerAsParallelCapable();
    }

    /* (non-Javadoc)
     * @see java.lang.ClassLoader#findClass(java.lang.String)
     */
    @Override
    protected Class<?> findClass(final String className)
            throws ClassNotFoundException, LinkageError, SecurityException {
        // Don't use class' specific classloader if the classpath was overridden, or the ScanResult was
        // produced by deserialization
        final boolean classpathOverridden = scanResult.scanSpec.overrideClasspath != null
                && !scanResult.scanSpec.overrideClasspath.isEmpty();
        ClassInfo classInfo = null;
        if (!classpathOverridden && !scanResult.scanResultCameFromDeserialization) {
            // Get ClassInfo for named class
            classInfo = scanResult.getClassInfo(className);
            // Try specific classloader for class
            if (classInfo != null && classInfo.classLoader != null) {
                try {
                    return Class.forName(className, scanResult.scanSpec.initializeLoadedClasses,
                            classInfo.classLoader);
                } catch (final ReflectiveOperationException | LinkageError e) {
                    // Ignore
                }
            }
        }
        // Try environment classloaders next, if the classpath was not overridden, or the scan result
        // came from deserialization (since in this case, a new URLClassLoader was created for the
        // classpath entries that were found in the serialized JSON doc)
        if (!classpathOverridden || scanResult.scanResultCameFromDeserialization) {
            if (scanResult.envClassLoaderOrder != null) {
                // Try environment classloaders
                for (final ClassLoader envClassLoader : scanResult.envClassLoaderOrder) {
                    if (classInfo == null || envClassLoader != classInfo.classLoader) {
                        try {
                            return Class.forName(className, scanResult.scanSpec.initializeLoadedClasses,
                                    envClassLoader);
                        } catch (ReflectiveOperationException | LinkageError e) {
                            // Ignore
                        }
                    }
                }
            }

            // If class came from a module, and it was not able to be loaded by the environment classloader,
            // then it is possible it was a non-public class, and ClassGraph found it by ignoring class visibility
            // when reading the resources in exported packages directly. Force ClassGraph to respect JPMS
            // encapsulation rules by refusing to load modular classes that the context/system classloaders
            // could not load. (A SecurityException should be thrown above, but this is here for completeness.)
            if (classInfo != null && classInfo.classpathElement instanceof ClasspathElementModule
                    && !classInfo.isPublic()) {
                throw new ClassNotFoundException("Classfile for class " + className + " was found in a module, "
                        + "but the context and system classloaders could not load the class, probably because "
                        + "the class is not public.");
            }
        }

        // Try obtaining the classfile as a resource, and defining the class from the resource content
        final ResourceList classfileResources = scanResult
                .getResourcesWithPath(JarUtils.classNameToClassfilePath(className));
        if (classfileResources != null) {
            for (final Resource resource : classfileResources) {
                // Iterate through resources (only loading of first resource in the list will be attempted)
                try {
                    // Load the content of the resource, and define a class from it
                    final byte[] resourceContent = resource.load();
                    return defineClass(className, resourceContent, 0, resourceContent.length);
                } catch (final IOException e) {
                    throw new ClassNotFoundException("Could not load classfile for class " + className + " : " + e);
                } finally {
                    resource.close();
                }
            }
        }

        // Fallback
        return Class.forName(className);
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
