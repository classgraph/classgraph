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
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.VersionFinder;
import nonapi.io.github.classgraph.utils.VersionFinder.OperatingSystem;

/** {@link ClassLoader} for classes found by ClassGraph during scanning. */
public class ClassGraphClassLoader extends ClassLoader {

    /** The scan result. */
    private final ScanResult scanResult;

    /** Whether or not to initialize loaded classes. */
    private final boolean initializeLoadedClasses;

    /** The ordered set of environment classloaders to try delegating to. */
    private Set<ClassLoader> environmentClassLoaderDelegationOrder;

    /** Any override classloader(s). */
    private List<ClassLoader> overrideClassLoaders;

    /** A {@link URLClassLoader} consisting of URLs on the classpath. */
    private final ClassLoader classpathClassLoader;

    /** The ordered set of overridden or added classloaders to try delegating to. */
    private Set<ClassLoader> addedClassLoaderDelegationOrder;

    /**
     * Constructor.
     *
     * @param scanResult
     *            The ScanResult.
     */
    ClassGraphClassLoader(final ScanResult scanResult) {
        super(null);
        registerAsParallelCapable();

        this.scanResult = scanResult;
        final ScanSpec scanSpec = scanResult.scanSpec;
        initializeLoadedClasses = scanSpec.initializeLoadedClasses;

        final boolean classpathOverridden = scanSpec.overrideClasspath != null
                && !scanSpec.overrideClasspath.isEmpty();
        final boolean classloadersOverridden = scanSpec.overrideClassLoaders != null
                && !scanSpec.overrideClassLoaders.isEmpty();
        final boolean clasloadersAdded = scanSpec.addedClassLoaders != null
                && !scanSpec.addedClassLoaders.isEmpty();

        // Only try environment classloaders if classpath and/or classloaders are not overridden
        if (!classpathOverridden && !classloadersOverridden) {
            // Try the null classloader first (this will default to the context classloader of the class
            // that called ClassGraph)
            environmentClassLoaderDelegationOrder = new LinkedHashSet<>();
            environmentClassLoaderDelegationOrder.add(null);

            // Try environment classloaders
            final ClassLoader[] envClassLoaderOrder = scanResult.getClassLoaderOrderRespectingParentDelegation();
            if (envClassLoaderOrder != null) {
                // Try environment classloaders
                environmentClassLoaderDelegationOrder.addAll(Arrays.asList(envClassLoaderOrder));
            }
        }

        // Create classloader from URLs on classpath
        final List<URL> classpathURLs = scanResult.getClasspathURLs();
        classpathClassLoader = classpathURLs.isEmpty() ? null
                : new URLClassLoader(classpathURLs.toArray(new URL[0]));

        // If the classloaders were overridden, just use the override classloaders, and then fail if the
        // class couldn't be found.
        overrideClassLoaders = classloadersOverridden ? scanSpec.overrideClassLoaders : null;

        // If the classpath is overridden, and classloaders are not overridden, try loading class from
        // classpath URLs, as the override classloader, then fail if the class couldn't be found.
        //
        // N.B. Some classpath URLs might be invalid if the ScanResult has been closed (e.g. in the rare
        // case that an inner jar had to be extracted to a temporary file on disk).
        if (overrideClassLoaders == null && classpathOverridden && classpathClassLoader != null) {
            overrideClassLoaders = Collections.singletonList(classpathClassLoader);
        }

        // If classloaders were added, try loading through those classloaders
        if (clasloadersAdded) {
            addedClassLoaderDelegationOrder = new LinkedHashSet<>();
            addedClassLoaderDelegationOrder.addAll(scanSpec.addedClassLoaders);
            // Remove duplicates
            if (environmentClassLoaderDelegationOrder != null) {
                addedClassLoaderDelegationOrder.removeAll(environmentClassLoaderDelegationOrder);
            }
        }
    }

    /* (non-Javadoc)
     * @see java.lang.ClassLoader#findClass(java.lang.String)
     */
    @Override
    protected Class<?> findClass(final String className)
            throws ClassNotFoundException, LinkageError, SecurityException {
        // First delegate to outer nested ClassGraphClassLoader, if any (#485)
        final ClassGraphClassLoader delegateClassGraphClassLoader = scanResult.classpathFinder
                .getDelegateClassGraphClassLoader();
        LinkageError linkageError = null;
        if (delegateClassGraphClassLoader != null) {
            try {
                return Class.forName(className, initializeLoadedClasses, delegateClassGraphClassLoader);
            } catch (final ClassNotFoundException e) {
                // Ignore
            } catch (final LinkageError e) {
                linkageError = e;
            }
        }

        // If overrideClassLoaders is set, only use the override loaders
        if (overrideClassLoaders != null) {
            for (final ClassLoader overrideClassLoader : overrideClassLoaders) {
                try {
                    return Class.forName(className, initializeLoadedClasses, overrideClassLoader);
                } catch (final ClassNotFoundException e) {
                    // Ignore
                } catch (final LinkageError e) {
                    if (linkageError == null) {
                        linkageError = e;
                    }
                }
            }
        }

        // Try environment classloader(s) first, since this is the usual default
        if (overrideClassLoaders == null && environmentClassLoaderDelegationOrder != null
                && !environmentClassLoaderDelegationOrder.isEmpty()) {
            for (final ClassLoader envClassLoader : environmentClassLoaderDelegationOrder) {
                try {
                    return Class.forName(className, initializeLoadedClasses, envClassLoader);
                } catch (final ClassNotFoundException e) {
                    // Ignore
                } catch (final LinkageError e) {
                    if (linkageError == null) {
                        linkageError = e;
                    }
                }
            }
        }

        // Try getting the ClassInfo for the named class, then the ClassLoader from the ClassInfo.
        // This should still be valid if the ScanResult was closed, since ScanResult#close() leaves
        // the classNameToClassInfo map intact, but still, this is only attempted if all the above
        // efforts failed, to avoid accessing ClassInfo objects after the ScanResult is closed (#399).
        ClassLoader classInfoClassLoader = null;
        final ClassInfo classInfo = scanResult.classNameToClassInfo == null ? null
                : scanResult.classNameToClassInfo.get(className);
        if (classInfo != null) {
            classInfoClassLoader = classInfo.classLoader;
            // Try specific classloader for the classpath element that the classfile was obtained from,
            // as long as it wasn't already tried
            if (classInfoClassLoader != null && (environmentClassLoaderDelegationOrder == null
                    || !environmentClassLoaderDelegationOrder.contains(classInfoClassLoader))) {
                try {
                    return Class.forName(className, initializeLoadedClasses, classInfoClassLoader);
                } catch (final ClassNotFoundException e) {
                    // Ignore
                } catch (final LinkageError e) {
                    if (linkageError == null) {
                        linkageError = e;
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

        // Try loading from classpath URLs
        if (overrideClassLoaders == null && classpathClassLoader != null) {
            try {
                return Class.forName(className, initializeLoadedClasses, classpathClassLoader);
            } catch (final ClassNotFoundException e) {
                // Ignore
            } catch (final LinkageError e) {
                if (linkageError == null) {
                    linkageError = e;
                }
            }
        }

        // Try any added classloader(s)
        if (addedClassLoaderDelegationOrder != null && !addedClassLoaderDelegationOrder.isEmpty()) {
            for (final ClassLoader additionalClassLoader : addedClassLoaderDelegationOrder) {
                if (additionalClassLoader != classInfoClassLoader) {
                    try {
                        return Class.forName(className, initializeLoadedClasses, additionalClassLoader);
                    } catch (final ClassNotFoundException e) {
                        // Ignore
                    } catch (final LinkageError e) {
                        if (linkageError == null) {
                            linkageError = e;
                        }
                    }
                }
            }
        }

        // As a last-ditch attempt, if the above efforts all failed, try obtaining the classfile as a
        // resource, and define the class from the resource content. This should be performed after
        // environment classloading is attempted, so that classes are not loaded by a mix of environment
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
                // Load the content of the resource, and define a class from it
                try (Resource resourceToClose = resource) {
                    // TODO: is there any need to try java.lang.invoke.MethodHandles.Lookup.defineClass
                    // via reflection (it's implemented in JDK 9), if the following fails?
                    // See: https://bugs.openjdk.java.net/browse/JDK-8202999
                    return defineClass(className, resourceToClose.read(), (ProtectionDomain) null);
                } catch (final IOException e) {
                    throw new ClassNotFoundException("Could not load classfile for class " + className + " : " + e);
                } catch (final LinkageError e) {
                    if (linkageError == null) {
                        linkageError = e;
                    }
                }
            }
        }

        if (linkageError != null) {
            if (VersionFinder.OS == OperatingSystem.Windows) {
                // LinkageError indicates that a classfile was found, but the class couldn't be loaded.
                // Hackily detect the situation where there are two classfiles with the same case insensitive name
                // on Windows filesystems (#494).
                final String msg = linkageError.getMessage();
                if (msg != null) {
                    final String wrongName = "(wrong name: ";
                    final int wrongNameIdx = msg.indexOf(wrongName);
                    if (wrongNameIdx > -1) {
                        final String theWrongName = msg.substring(wrongNameIdx + wrongName.length(),
                                msg.length() - 1);
                        if (theWrongName.replace('/', '.').equalsIgnoreCase(className)) {
                            throw new LinkageError("You appear to have two classfiles with the same "
                                    + "case-insensitive name in the same directory on a case-insensitive "
                                    + "filesystem -- this is not allowed on Windows, and therefore your "
                                    + "code is not portable. Class name: " + className, linkageError);
                        }
                    }
                }
            }
            throw linkageError;
        }

        throw new ClassNotFoundException("Could not find or load classfile for class " + className);
    }

    /**
     * Get classpath URLs.
     * 
     * @return The classpath URLs in the {@link ScanResult} handled by this {@link ClassLoader}.
     */
    public URL[] getURLs() {
        return scanResult.getClasspathURLs().toArray(new URL[0]);
    }

    /* (non-Javadoc)
     * @see java.lang.ClassLoader#getResource(java.lang.String)
     */
    @Override
    public URL getResource(final String path) {
        // This order should match the order in findClass(String)

        // Try loading resource from environment classloader(s)
        if (!environmentClassLoaderDelegationOrder.isEmpty()) {
            for (final ClassLoader envClassLoader : environmentClassLoaderDelegationOrder) {
                final URL resource = envClassLoader.getResource(path);
                if (resource != null) {
                    return resource;
                }
            }
        }

        // Try loading resource from overridden or added classloader(s)
        if (!addedClassLoaderDelegationOrder.isEmpty()) {
            for (final ClassLoader additionalClassLoader : addedClassLoaderDelegationOrder) {
                final URL resource = additionalClassLoader.getResource(path);
                if (resource != null) {
                    return resource;
                }
            }
        }

        // If the above attempts fail, try retrieving resource from ScanResult.
        // This will throw an exception if ScanResult has already been closed (#399).
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
        // This order should match the order in findClass(String)

        // Try loading resources from environment classloader(s)
        if (!environmentClassLoaderDelegationOrder.isEmpty()) {
            for (final ClassLoader envClassLoader : environmentClassLoaderDelegationOrder) {
                final Enumeration<URL> resources = envClassLoader.getResources(path);
                if (resources != null && resources.hasMoreElements()) {
                    return resources;
                }
            }
        }

        // Try loading resources from overridden or added classloader(s)
        if (!addedClassLoaderDelegationOrder.isEmpty()) {
            for (final ClassLoader additionalClassLoader : addedClassLoaderDelegationOrder) {
                final Enumeration<URL> resources = additionalClassLoader.getResources(path);
                if (resources != null && resources.hasMoreElements()) {
                    return resources;
                }
            }
        }

        // If the above attempts fail, try retrieving resource from ScanResult.
        // This will throw an exception if ScanResult has already been closed (#399).
        final ResourceList resourceList = scanResult.getResourcesWithPath(path);
        if (resourceList == null || resourceList.isEmpty()) {
            return Collections.emptyEnumeration();
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
        // This order should match the order in findClass(String)

        // Try opening resource from environment classloader(s)
        if (!environmentClassLoaderDelegationOrder.isEmpty()) {
            for (final ClassLoader envClassLoader : environmentClassLoaderDelegationOrder) {
                final InputStream inputStream = envClassLoader.getResourceAsStream(path);
                if (inputStream != null) {
                    return inputStream;
                }
            }
        }

        // Try opening resource from overridden or added classloader(s)
        if (!addedClassLoaderDelegationOrder.isEmpty()) {
            for (final ClassLoader additionalClassLoader : addedClassLoaderDelegationOrder) {
                final InputStream inputStream = additionalClassLoader.getResourceAsStream(path);
                if (inputStream != null) {
                    return inputStream;
                }
            }
        }

        // If the above attempts fail, try opening resource from ScanResult.
        // This will throw an exception if ScanResult has already been closed (#399).
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
