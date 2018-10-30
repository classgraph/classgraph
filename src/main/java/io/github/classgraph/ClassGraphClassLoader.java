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
 * Copyright (c) 2018 Luke Hutchison
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
import java.util.Arrays;
import java.util.Enumeration;

import io.github.classgraph.utils.JarUtils;

/** {@link ClassLoader} for classes found by ClassGraph during scanning. */
class ClassGraphClassLoader extends ClassLoader {
    private final ScanResult scanResult;

    /**
     * @param scanResult
     *            The ScanResult.
     */
    ClassGraphClassLoader(final ScanResult scanResult) {
        super(null);
        this.scanResult = scanResult;
        registerAsParallelCapable();
    }

    @Override
    protected Class<?> findClass(final String className)
            throws ClassNotFoundException, LinkageError, ExceptionInInitializerError, SecurityException {
        // Get ClassInfo for named class
        final ClassInfo classInfo = scanResult.getClassInfo(className);
        if (classInfo != null) {
            // Try specific classloader(s) for class
            final ClassLoader[] classLoaders = classInfo.classLoaders;
            if (classLoaders != null) {
                for (final ClassLoader classLoader : classLoaders) {
                    try {
                        return Class.forName(className, scanResult.scanSpec.initializeLoadedClasses, classLoader);
                    } catch (final ClassNotFoundException | NoClassDefFoundError e) {
                        // Ignore
                    }
                }
            }
        } else {
            // For classes not found during the scan, try context/system classloaders
            if (scanResult.envClassLoaderOrder == null || scanResult.envClassLoaderOrder.length == 0) {
                // Environment classloaders are not known, just try default
                return Class.forName(className);
            }
        }
        if (scanResult.envClassLoaderOrder != null
                && (classInfo == null || !Arrays.equals(classInfo.classLoaders, scanResult.envClassLoaderOrder))) {
            // Try environment classloaders
            for (final ClassLoader envClassLoader : scanResult.envClassLoaderOrder) {
                try {
                    return Class.forName(className, scanResult.scanSpec.initializeLoadedClasses, envClassLoader);
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    // Ignore
                }
            }
        }

        // If class came from a module, and it was not able to be loaded by the environment classloader,
        // then it is possible it was a non-public class, and ClassGraph found it by ignoring class visibility
        // when reading the resources in exported packages directly. Force ClassGraph to respect JPMS
        // encapsulation rules by refusing to load modular classes that the context/system classloaders
        // could not load. (A SecurityException should be thrown above, but this is here for completeness.)
        if (classInfo != null && classInfo.classpathElementFile == null && !classInfo.isPublic()) {
            throw new ClassNotFoundException("Classfile for class " + className + " was found in a module, "
                    + "but the context and system classloaders could not load the class, probably because "
                    + "the class is not public.");
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
        throw new ClassNotFoundException("Classfile for class " + className + " not found");
    }

    @Override
    public URL getResource(final String path) {
        final ResourceList resourceList = scanResult.getResourcesWithPath(path);
        if (resourceList == null || resourceList.isEmpty()) {
            return super.getResource(path);
        } else {
            return resourceList.get(0).getURL();
        }
    }

    @Override
    public Enumeration<URL> getResources(final String path) throws IOException {
        final ResourceList resourceList = scanResult.getResourcesWithPath(path);
        if (resourceList == null || resourceList.isEmpty()) {
            return super.getResources(path);
        } else {
            return new Enumeration<URL>() {
                int idx = 0;

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
