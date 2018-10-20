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

import java.io.Closeable;
import java.io.File;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.classgraph.json.JSONDeserializer;
import io.github.classgraph.json.JSONSerializer;
import io.github.classgraph.utils.ClassLoaderAndModuleFinder;
import io.github.classgraph.utils.JarUtils;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.NestedJarHandler;

/** The result of a scan. */
public final class ScanResult implements Closeable, AutoCloseable {
    /** The scan spec. */
    final ScanSpec scanSpec;

    /** The order of raw classpath elements. */
    private final List<String> rawClasspathEltOrderStrs;

    /** The order of classpath elements, after inner jars have been extracted to temporary files, etc. */
    private final List<ClasspathElement> classpathOrder;

    /** A list of all files that were found in whitelisted packages. */
    private ResourceList allResources;

    /**
     * The default order in which ClassLoaders are called to load classes. Used when a specific class does not have
     * a record of which ClassLoader provided the URL used to locate the class (e.g. if the class is found using
     * java.class.path).
     */
    final ClassLoader[] envClassLoaderOrder;

    /** The nested jar handler instance. */
    private final NestedJarHandler nestedJarHandler;

    /**
     * The file, directory and jarfile resources timestamped during a scan, along with their timestamp at the time
     * of the scan. For jarfiles, the timestamp represents the timestamp of all files within the jar. May be null,
     * if this ScanResult object is the result of a call to ClassGraph#getUniqueClasspathElementsAsync().
     */
    private final Map<File, Long> fileToLastModified;

    /** The map from class name to {@link ClassInfo}. */
    private final Map<String, ClassInfo> classNameToClassInfo;

    /** The map from package name to {@link PackageInfo}. */
    private final Map<String, PackageInfo> packageNameToPackageInfo;

    /** The map from class name to {@link ClassInfo}. */
    private final Map<String, ModuleInfo> moduleNameToModuleInfo;

    /**
     * The map from path (relative to package root) to a list of {@link Resource} elements with the matching path.
     */
    private Map<String, ResourceList> pathToResourceList;

    /** A custom ClassLoader that can load classes found during the scan. */
    private ClassGraphClassLoader classGraphClassLoader;

    /** If true, this ScanResult has already been closed. */
    private volatile AtomicBoolean isClosed = new AtomicBoolean(false);

    /** The log. */
    final LogNode log;

    // -------------------------------------------------------------------------------------------------------------
    // Constructor

    /**
     * The result of a scan. Make sure you call complete() after calling the constructor.
     */
    ScanResult(final ScanSpec scanSpec, final List<ClasspathElement> classpathOrder,
            final List<String> rawClasspathEltOrderStrs, final ClassLoader[] envClassLoaderOrder,
            final Map<String, ClassInfo> classNameToClassInfo,
            final Map<String, PackageInfo> packageNameToPackageInfo,
            final Map<String, ModuleInfo> moduleNameToModuleInfo, final Map<File, Long> fileToLastModified,
            final NestedJarHandler nestedJarHandler, final LogNode log) {
        this.scanSpec = scanSpec;
        this.rawClasspathEltOrderStrs = rawClasspathEltOrderStrs;
        this.classpathOrder = classpathOrder;
        for (final ClasspathElement classpathElt : classpathOrder) {
            if (classpathElt.resourceMatches != null) {
                if (allResources == null) {
                    allResources = new ResourceList();
                    pathToResourceList = new HashMap<>();
                }
                allResources.addAll(classpathElt.resourceMatches);
                for (final Resource resource : classpathElt.resourceMatches) {
                    final String path = resource.getPath();
                    ResourceList resourceList = pathToResourceList.get(path);
                    if (resourceList == null) {
                        pathToResourceList.put(path, resourceList = new ResourceList());
                    }
                    resourceList.add(resource);
                }
            }
        }
        this.envClassLoaderOrder = envClassLoaderOrder;
        this.fileToLastModified = fileToLastModified;
        this.classNameToClassInfo = classNameToClassInfo;
        this.packageNameToPackageInfo = packageNameToPackageInfo;
        this.moduleNameToModuleInfo = moduleNameToModuleInfo;
        this.nestedJarHandler = nestedJarHandler;
        this.log = log;

        if (classNameToClassInfo != null) {
            final Collection<ClassInfo> allClassInfo = classNameToClassInfo.values();
            // Add backrefs from info objects back to this ScanResult
            for (final ClassInfo classInfo : allClassInfo) {
                classInfo.setScanResult(this);
            }
        }

        // Define a new ClassLoader that can load the classes found during the scan
        this.classGraphClassLoader = new ClassGraphClassLoader(this);

        // Provide the shutdown hook with a weak reference to this ScanResult
        this.weakReference = new WeakReference<>(this);
        nonClosedWeakReferences.add(this.weakReference);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classpath / module path

    /**
     * Returns the list of File objects for unique classpath elements (directories or jarfiles), in classloader
     * resolution order.
     *
     * @return The unique classpath elements.
     */
    public List<File> getClasspathFiles() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final List<File> classpathElementOrderFiles = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            final ModuleRef modRef = classpathElement.getClasspathElementModuleRef();
            if (modRef != null) {
                if (!modRef.isSystemModule()) {
                    // Add module files when they don't have a "jrt:/" scheme
                    final File moduleLocationFile = modRef.getLocationFile();
                    if (moduleLocationFile != null) {
                        classpathElementOrderFiles.add(moduleLocationFile);
                    }
                }
            } else {
                classpathElementOrderFiles.add(classpathElement.getClasspathElementFile(log));
            }
        }
        return classpathElementOrderFiles;
    }

    /**
     * Returns all unique directories or zip/jarfiles on the classpath, in classloader resolution order, as a
     * classpath string, delineated with the standard path separator character.
     *
     * @return a the unique directories and jarfiles on the classpath, in classpath resolution order, as a path
     *         string.
     */
    public String getClasspath() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        return JarUtils.pathElementsToPathStr(getClasspathFiles());
    }

    /**
     * Returns the list of unique classpath element paths as URLs, in classloader resolution order.
     *
     * @return The unique classpath element URLs.
     */
    public List<URL> getClasspathURLs() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final List<URL> classpathElementOrderURLs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            final ModuleRef modRef = classpathElement.getClasspathElementModuleRef();
            if (modRef != null) {
                // Add module URLs whether or not they have a "jrt:/" scheme
                try {
                    classpathElementOrderURLs.add(modRef.getLocation().toURL());
                } catch (final MalformedURLException e) {
                    // Skip malformed URLs (shouldn't happen)
                }
            } else {
                try {
                    if (scanSpec.performScan) {
                        // Return URL of classpath element, possibly employing temporary file as a base
                        // (for nested jarfiles that have been extracted)
                        final File classpathElementFile = classpathElement.getClasspathElementFile(log);
                        final String jarfilePackageRoot = classpathElement.getJarfilePackageRoot();
                        final boolean isJarURL = classpathElementFile.isFile() && !jarfilePackageRoot.isEmpty();
                        final String baseURLStr = (isJarURL ? "jar:" : "")
                                + classpathElementFile.toURI().toURL().toString()
                                + (isJarURL ? "!/" + jarfilePackageRoot : "");
                        classpathElementOrderURLs.add(new URL(baseURLStr));
                    } else {
                        // If not scanning, nested jarfiles were not extracted, so use the raw classpath
                        // element paths to form the resulting URL
                        String rawPath = classpathElement.getResolvedPath();
                        if (rawPath.startsWith("jrt:/") || rawPath.startsWith("http://")
                                || rawPath.startsWith("https://")) {
                            classpathElementOrderURLs.add(new URL(rawPath));
                        } else {
                            if (!rawPath.startsWith("file:") && !rawPath.startsWith("jar:")) {
                                rawPath = "file:" + rawPath;
                            }
                            if (rawPath.contains("!") && !rawPath.startsWith("jar:")) {
                                rawPath = "jar:" + rawPath;
                            }
                            // Any URL with the "jar:" prefix must have "/" after any "!"
                            rawPath = rawPath.replace("!/", "!").replace("!", "!/");
                            classpathElementOrderURLs.add(new URL(rawPath));
                        }
                    }
                } catch (final Exception e) {
                    // Skip
                }
            }
        }
        return classpathElementOrderURLs;
    }

    /**
     * @return {@link ModuleRef} references for all the visible modules.
     */
    public List<ModuleRef> getModules() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final List<ModuleRef> moduleRefs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            final ModuleRef moduleRef = classpathElement.getClasspathElementModuleRef();
            if (moduleRef != null) {
                moduleRefs.add(moduleRef);
            }
        }
        return moduleRefs;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Resources

    /** @return A list of all resources (including classfiles and non-classfiles) found in whitelisted packages. */
    public ResourceList getAllResources() {
        if (allResources == null || allResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            return allResources;
        }
    }

    /**
     * @param resourcePath
     *            A complete resource path, relative to the classpath entry package root.
     * @return A list of all resources found in whitelisted packages that have the given path, relative to the
     *         package root of the classpath element. May match several resources, up to one per classpath element.
     */
    public ResourceList getResourcesWithPath(final String resourcePath) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (allResources == null || allResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            String path = resourcePath;
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            final ResourceList resourceList = pathToResourceList.get(path);
            return (resourceList == null ? new ResourceList(1) : resourceList);
        }
    }

    /**
     * @param leafName
     *            A resource leaf filename.
     * @return A list of all resources found in whitelisted packages that have the requested leafname.
     */
    public ResourceList getResourcesWithLeafName(final String leafName) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (allResources == null || allResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allResources) {
                final String relativePath = classpathResource.getPath();
                final int lastSlashIdx = relativePath.lastIndexOf('/');
                if (relativePath.substring(lastSlashIdx + 1).equals(leafName)) {
                    filteredResources.add(classpathResource);
                }
            }
            return filteredResources;
        }
    }

    /**
     * @param extension
     *            A filename extension, e.g. "xml" to match all resources ending in ".xml".
     * @return A list of all resources found in whitelisted packages that have the requested filename extension.
     */
    public ResourceList getResourcesWithExtension(final String extension) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (allResources == null || allResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            String bareExtension = extension;
            while (bareExtension.startsWith(".")) {
                bareExtension = bareExtension.substring(1);
            }
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allResources) {
                final String relativePath = classpathResource.getPath();
                final int lastSlashIdx = relativePath.lastIndexOf('/');
                final int lastDotIdx = relativePath.lastIndexOf('.');
                if (lastDotIdx > lastSlashIdx) {
                    if (relativePath.substring(lastDotIdx + 1).equalsIgnoreCase(bareExtension)) {
                        filteredResources.add(classpathResource);
                    }
                }
            }
            return filteredResources;
        }
    }

    /**
     * @param pattern
     *            A pattern to match {@link Resource} paths with.
     * @return A list of all resources found in whitelisted packages that have a path matching the requested
     *         pattern.
     */
    public ResourceList getResourcesMatchingPattern(final Pattern pattern) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (allResources == null || allResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allResources) {
                final String relativePath = classpathResource.getPath();
                if (pattern.matcher(relativePath).matches()) {
                    filteredResources.add(classpathResource);
                }
            }
            return filteredResources;
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Modules

    /**
     * Get the {@link ModuleInfo} object for the named module, or null if no module of the requested name was found
     * during the scan.
     * 
     * @param moduleName
     *            The module name.
     * @return The {@link ModuleInfo} object for the named module, or null if the module was not found.
     */
    public ModuleInfo getModuleInfo(final String moduleName) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return moduleNameToModuleInfo.get(moduleName);
    }

    /**
     * Get all modules found during the scan.
     *
     * @return A list of all modules found during the scan, or the empty list if none.
     */
    public ModuleInfoList getModuleInfo() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return new ModuleInfoList(moduleNameToModuleInfo.values());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Packages

    /**
     * Get the {@link PackageInfo} object for the named package, or null if no package of the requested name was
     * found during the scan.
     * 
     * @param packageName
     *            The package name.
     * @return The {@link PackageInfo} object for the named package, or null if the package was not found.
     */
    public PackageInfo getPackageInfo(final String packageName) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return packageNameToPackageInfo.get(packageName);
    }

    /**
     * Get all packages found during the scan.
     *
     * @return A list of all packages found during the scan, or the empty list if none.
     */
    public PackageInfoList getPackageInfo() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return new PackageInfoList(packageNameToPackageInfo.values());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /**
     * Get the {@link ClassInfo} object for the named class, or null if no class of the requested name was found in
     * a whitelisted/non-blacklisted package during the scan.
     * 
     * @param className
     *            The class name.
     * @return The {@link ClassInfo} object for the named class, or null if the class was not found.
     */
    public ClassInfo getClassInfo(final String className) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return classNameToClassInfo.get(className);
    }

    /**
     * Get all classes, interfaces and annotations found during the scan.
     *
     * @return A list of all whitelisted classes found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllClasses() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllClasses(classNameToClassInfo.values(), scanSpec, this);
    }

    /**
     * Get all standard (non-interface/non-annotation) classes found during the scan.
     *
     * @return A list of all whitelisted standard classes found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllStandardClasses() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllStandardClasses(classNameToClassInfo.values(), scanSpec, this);
    }

    /**
     * Get all subclasses of the named superclass.
     *
     * @param superclassName
     *            The name of the superclass.
     * @return A list of subclasses of the named superclass, or the empty list if none.
     */
    public ClassInfoList getSubclasses(final String superclassName) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        if (superclassName.equals("java.lang.Object")) {
            // Return all standard classes (interfaces don't extend Object)
            return getAllStandardClasses();
        } else {
            final ClassInfo superclass = classNameToClassInfo.get(superclassName);
            return superclass == null ? ClassInfoList.EMPTY_LIST : superclass.getSubclasses();
        }
    }

    /**
     * Get superclasses of the named subclass.
     *
     * @param subclassName
     *            The name of the subclass.
     * @return A list of superclasses of the named subclass, or the empty list if none.
     */
    public ClassInfoList getSuperclasses(final String subclassName) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        final ClassInfo subclass = classNameToClassInfo.get(subclassName);
        return subclass == null ? ClassInfoList.EMPTY_LIST : subclass.getSuperclasses();
    }

    /**
     * Get classes that have a method with an annotation of the named type.
     *
     * @param methodAnnotationName
     *            the name of the method annotation.
     * @return A list of classes with a method that has an annotation of the named type, or the empty list if none.
     */
    public ClassInfoList getClassesWithMethodAnnotation(final String methodAnnotationName) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableMethodInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo(), #enableMethodInfo(), "
                    + "and #enableAnnotationInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(methodAnnotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithMethodAnnotation();
    }

    /**
     * Get classes that have a field with an annotation of the named type.
     *
     * @param fieldAnnotationName
     *            the name of the field annotation.
     * @return A list of classes that have a field with an annotation of the named type, or the empty list if none.
     */
    public ClassInfoList getClassesWithFieldAnnotation(final String fieldAnnotationName) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableFieldInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo(), #enableFieldInfo(), "
                    + "and #enableAnnotationInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(fieldAnnotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithFieldAnnotation();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /**
     * Get all interface classes found during the scan (not including annotations, which are also technically
     * interfaces). See also {@link #getAllInterfacesAndAnnotations()}.
     *
     * @return A list of all whitelisted interfaces found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllInterfaces() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllImplementedInterfaceClasses(classNameToClassInfo.values(), scanSpec, this);
    }

    /**
     * Get all interfaces implemented by the named class or by one of its superclasses, if this is a standard class,
     * or the superinterfaces extended by this interface, if this is an interface.
     *
     * @param className
     *            The class name.
     * @return A list of interfaces implemented by the named class (or superinterfaces extended by the named
     *         interface), or the empty list if none.
     */
    public ClassInfoList getInterfaces(final String className) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getInterfaces();
    }

    /**
     * Get all classes that implement (or have superclasses that implement) the named interface (or one of its
     * subinterfaces).
     *
     * @param interfaceName
     *            The interface name.
     * @return A list of all classes that implement the named interface, or the empty list if none.
     */
    public ClassInfoList getClassesImplementing(final String interfaceName) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(interfaceName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesImplementing();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /**
     * Get all annotation classes found during the scan. See also {@link #getAllInterfacesAndAnnotations()}.
     *
     * @return A list of all annotation classes found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllAnnotations() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableClassInfo() and #enableAnnotationInfo() before #scan()");
        }
        return ClassInfo.getAllAnnotationClasses(classNameToClassInfo.values(), scanSpec, this);
    }

    /**
     * Get all interface or annotation classes found during the scan. (Annotations are technically interfaces, and
     * they can be implemented.)
     *
     * @return A list of all whitelisted interfaces found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllInterfacesAndAnnotations() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableClassInfo() and #enableAnnotationInfo() before #scan()");
        }
        return ClassInfo.getAllInterfacesOrAnnotationClasses(classNameToClassInfo.values(), scanSpec, this);
    }

    /**
     * Get classes with the named class annotation or meta-annotation.
     *
     * @param annotationName
     *            The name of the class annotation or meta-annotation.
     * @return A list of all non-annotation classes that were found with the named class annotation during the scan,
     *         or the empty list if none.
     */
    public ClassInfoList getClassesWithAnnotation(final String annotationName) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableClassInfo() and #enableAnnotationInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(annotationName);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getClassesWithAnnotation();
    }

    /**
     * Get annotations on the named class. This only returns the annotating classes; to read annotation parameters,
     * call {@link #getClassInfo(String)} to get the {@link ClassInfo} object for the named class, then if the
     * {@link ClassInfo} object is non-null, call {@link ClassInfo#getAnnotationInfo()} to get detailed annotation
     * info.
     *
     * @param className
     *            The name of the class.
     * @return A list of all annotation classes that were found with the named class annotation during the scan, or
     *         the empty list if none.
     */
    public ClassInfoList getAnnotationsOnClass(final String className) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableClassInfo() and #enableAnnotationInfo() before #scan()");
        }
        final ClassInfo classInfo = classNameToClassInfo.get(className);
        return classInfo == null ? ClassInfoList.EMPTY_LIST : classInfo.getAnnotations();
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classpath modification tests

    /**
     * Determine whether the classpath contents have been modified since the last scan. Checks the timestamps of
     * files and jarfiles encountered during the previous scan to see if they have changed. Does not perform a full
     * scan, so cannot detect the addition of directories that newly match whitelist criteria -- you need to perform
     * a full scan to detect those changes.
     *
     * @return true if the classpath contents have been modified since the last scan.
     */
    public boolean classpathContentsModifiedSinceScan() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (fileToLastModified == null) {
            return true;
        } else {
            for (final Entry<File, Long> ent : fileToLastModified.entrySet()) {
                if (ent.getKey().lastModified() != ent.getValue()) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Find the maximum last-modified timestamp of any whitelisted file/directory/jarfile encountered during the
     * scan. Checks the current timestamps, so this should increase between calls if something changes in
     * whitelisted paths. Assumes both file and system timestamps were generated from clocks whose time was
     * accurate. Ignores timestamps greater than the system time.
     * 
     * <p>
     * This method cannot in general tell if classpath has changed (or modules have been added or removed) if it is
     * run twice during the same runtime session.
     *
     * @return the maximum last-modified time for whitelisted files/directories/jars encountered during the scan.
     */
    public long classpathContentsLastModifiedTime() {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        long maxLastModifiedTime = 0L;
        if (fileToLastModified != null) {
            final long currTime = System.currentTimeMillis();
            for (final long timestamp : fileToLastModified.values()) {
                if (timestamp > maxLastModifiedTime && timestamp < currTime) {
                    maxLastModifiedTime = timestamp;
                }
            }
        }
        return maxLastModifiedTime;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classloading

    /**
     * Load a class given a class name. If ignoreExceptions is false, and the class cannot be loaded (due to
     * classloading error, or due to an exception being thrown in the class initialization block), an
     * IllegalArgumentException is thrown; otherwise, the class will simply be skipped if an exception is thrown.
     *
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     *
     * @param className
     *            the class to load.
     * @param returnNullIfClassNotFound
     *            If true, null is returned if there was an exception during classloading, otherwise
     *            IllegalArgumentException is thrown if a class could not be loaded.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false, IllegalArgumentException is thrown if there were problems loading
     *             or initializing the class. (Note that class initialization on load is disabled by default, you
     *             can enable it with {@code ClassGraph#initializeLoadedClasses(true)} .) Otherwise exceptions are
     *             suppressed, and null is returned if any of these problems occurs.
     * @return a reference to the loaded class, or null if the class could not be loaded and ignoreExceptions is
     *         true.
     */
    Class<?> loadClass(final String className, final boolean returnNullIfClassNotFound)
            throws IllegalArgumentException {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("className cannot be null or empty");
        }
        try {
            return Class.forName(className, scanSpec.initializeLoadedClasses, classGraphClassLoader);
        } catch (final Throwable e) {
            if (returnNullIfClassNotFound) {
                return null;
            } else {
                throw new IllegalArgumentException("Could not load class " + className + " : " + e);
            }
        }
    }

    /**
     * Load a class given a class name. If ignoreExceptions is false, and the class cannot be loaded (due to
     * classloading error, or due to an exception being thrown in the class initialization block), an
     * IllegalArgumentException is thrown; otherwise, the class will simply be skipped if an exception is thrown.
     *
     * <p>
     * Enable verbose scanning to see details of any exceptions thrown during classloading, even if ignoreExceptions
     * is false.
     *
     * @param className
     *            the class to load.
     * @param superclassOrInterfaceType
     *            The class type to cast the result to.
     * @param returnNullIfClassNotFound
     *            If true, null is returned if there was an exception during classloading, otherwise
     *            IllegalArgumentException is thrown if a class could not be loaded.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false, IllegalArgumentException is thrown if there were problems loading
     *             the class, initializing the class, or casting it to the requested type. (Note that class
     *             initialization on load is disabled by default, you can enable it with
     *             {@code ClassGraph#initializeLoadedClasses(true)} .) Otherwise exceptions are suppressed, and null
     *             is returned if any of these problems occurs.
     * @return a reference to the loaded class, or null if the class could not be loaded and ignoreExceptions is
     *         true.
     */
    <T> Class<T> loadClass(final String className, final Class<T> superclassOrInterfaceType,
            final boolean returnNullIfClassNotFound) throws IllegalArgumentException {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("className cannot be null or empty");
        }
        if (superclassOrInterfaceType == null) {
            throw new IllegalArgumentException("superclassOrInterfaceType parameter cannot be null");
        }
        try {
            final Class<?> loadedClass = Class.forName(className, scanSpec.initializeLoadedClasses,
                    classGraphClassLoader);
            if (loadedClass != null && !superclassOrInterfaceType.isAssignableFrom(loadedClass)) {
                if (returnNullIfClassNotFound) {
                    return null;
                } else {
                    throw new IllegalArgumentException("Loaded class " + loadedClass.getName()
                            + " cannot be cast to " + superclassOrInterfaceType.getName());
                }
            }
            @SuppressWarnings("unchecked")
            final Class<T> castClass = (Class<T>) loadedClass;
            return castClass;
        } catch (final Throwable e) {
            if (returnNullIfClassNotFound) {
                return null;
            } else {
                throw new IllegalArgumentException("Could not load class " + className + " : " + e);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Serialization / deserialization

    /** The current serialization format. */
    private static final String CURRENT_SERIALIZATION_FORMAT = "5";

    /** A class to hold a serialized ScanResult along with the ScanSpec that was used to scan. */
    private static class SerializationFormat {
        public String serializationFormat;
        public ScanSpec scanSpec;
        public List<String> classpath;
        public List<ClassInfo> classInfo;
        public List<PackageInfo> packageInfo;
        public List<ModuleInfo> moduleInfo;

        @SuppressWarnings("unused")
        public SerializationFormat() {
        }

        public SerializationFormat(final String serializationFormat, final ScanSpec scanSpec,
                final List<ClassInfo> classInfo, final List<PackageInfo> packageInfo,
                final List<ModuleInfo> moduleInfo, final List<String> classpath) {
            this.serializationFormat = serializationFormat;
            this.scanSpec = scanSpec;
            this.classpath = classpath;
            this.classInfo = classInfo;
            this.packageInfo = packageInfo;
            this.moduleInfo = moduleInfo;
        }
    }

    /**
     * Deserialize a ScanResult from previously-serialized JSON.
     * 
     * @param json
     *            The JSON string for the serialized {@link ScanResult}.
     * @return The deserialized {@link ScanResult}.
     */
    public static ScanResult fromJSON(final String json) {
        final Matcher matcher = Pattern.compile("\\{[\\n\\r ]*\"serializationFormat\"[ ]?:[ ]?\"([^\"]+)\"")
                .matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("JSON is not in correct format");
        }
        if (!CURRENT_SERIALIZATION_FORMAT.equals(matcher.group(1))) {
            throw new IllegalArgumentException(
                    "JSON was serialized in a different format from the format used by the current version of "
                            + "ClassGraph -- please serialize and deserialize your ScanResult using "
                            + "the same version of ClassGraph");
        }

        // Deserialize the JSON
        final SerializationFormat deserialized = JSONDeserializer.deserializeObject(SerializationFormat.class,
                json);
        if (!deserialized.serializationFormat.equals(CURRENT_SERIALIZATION_FORMAT)) {
            // Probably the deserialization failed before now anyway, if fields have changed, etc.
            throw new IllegalArgumentException("JSON was serialized by newer version of ClassGraph");
        }

        // Get the classpath that produced the serialized JSON, and extract inner jars, download remote jars, etc.
        final List<URL> urls = new ClassGraph().overrideClasspath(deserialized.classpath).getClasspathURLs();

        // Define a custom URLClassLoader with the result that delegates to the first environment classloader
        final ClassLoader[] envClassLoaderOrder = new ClassLoaderAndModuleFinder(deserialized.scanSpec,
                /* log = */ null).getClassLoaders();
        final ClassLoader parentClassLoader = envClassLoaderOrder == null || envClassLoaderOrder.length == 0 ? null
                : envClassLoaderOrder[0];
        final URLClassLoader urlClassLoader = new URLClassLoader(urls.toArray(new URL[0]), parentClassLoader);
        final ClassLoader[] classLoaderOrder = new ClassLoader[] { urlClassLoader };

        // Index ClassInfo, PackageInfo and MethodInfo objects by name
        final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();
        for (final ClassInfo ci : deserialized.classInfo) {
            ci.classLoaders = classLoaderOrder;
            classNameToClassInfo.put(ci.getName(), ci);
        }
        final Map<String, PackageInfo> packageNameToPackageInfo = new HashMap<>();
        for (final PackageInfo pi : deserialized.packageInfo) {
            packageNameToPackageInfo.put(pi.getName(), pi);
        }
        final Map<String, ModuleInfo> moduleNameToModuleInfo = new HashMap<>();
        for (final ModuleInfo mi : deserialized.moduleInfo) {
            moduleNameToModuleInfo.put(mi.getName(), mi);
        }

        // Produce a new ScanResult
        final ScanResult scanResult = new ScanResult(deserialized.scanSpec,
                /* classpathOrder = */ Collections.<ClasspathElement> emptyList(), deserialized.classpath,
                classLoaderOrder, classNameToClassInfo, packageNameToPackageInfo, moduleNameToModuleInfo,
                /* fileToLastModified = */ null, /* nestedJarHandler = */ null, /* log = */ null);
        return scanResult;
    }

    /**
     * Serialize a ScanResult to JSON.
     * 
     * @param indentWidth
     *            If greater than 0, JSON will be formatted (indented), otherwise it will be minified (un-indented).
     * @return This {@link ScanResult}, serialized as a JSON string.
     */
    public String toJSON(final int indentWidth) {
        if (isClosed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        final List<ClassInfo> allClassInfo = new ArrayList<>(classNameToClassInfo.values());
        Collections.sort(allClassInfo);
        final List<PackageInfo> allPackageInfo = new ArrayList<>(packageNameToPackageInfo.values());
        Collections.sort(allPackageInfo);
        final List<ModuleInfo> allModuleInfo = new ArrayList<>(moduleNameToModuleInfo.values());
        Collections.sort(allModuleInfo);
        return JSONSerializer.serializeObject(new SerializationFormat(CURRENT_SERIALIZATION_FORMAT, scanSpec,
                allClassInfo, allPackageInfo, allModuleInfo, rawClasspathEltOrderStrs), indentWidth, false);
    }

    /**
     * Serialize a ScanResult to minified (un-indented) JSON.
     * 
     * @return This {@link ScanResult}, serialized as a JSON string.
     */
    public String toJSON() {
        return toJSON(0);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Shutdown hook / close()

    /** The {@link WeakReference} for this ScanResult. */
    private final WeakReference<ScanResult> weakReference;

    /**
     * The set of WeakReferences to non-closed ScanResult objects. Uses WeakReferences so that garbage collection is
     * not blocked. (Bug #233)
     */
    private static final Set<WeakReference<ScanResult>> nonClosedWeakReferences = Collections
            .newSetFromMap(new ConcurrentHashMap<WeakReference<ScanResult>, Boolean>());

    static {
        // Add runtime shutdown hook to remove temporary files on Ctrl-C or System.exit().
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                for (final WeakReference<ScanResult> weakReference : new ArrayList<>(nonClosedWeakReferences)) {
                    final ScanResult scanResult = weakReference.get();
                    if (scanResult != null) {
                        scanResult.close();
                    }
                    nonClosedWeakReferences.remove(weakReference);
                }
            }
        });
    }

    /**
     * Free any temporary files created by extracting jars or files from within jars. Without calling this method,
     * the temporary files created by extracting the inner jars will be removed in a finalizer, called by the
     * garbage collector (or at JVM shutdown). If you don't want to experience long GC pauses, make sure you call
     * this close method when you have finished with the {@link ScanResult}.
     */
    @Override
    public void close() {
        if (!isClosed.getAndSet(true)) {
            if (allResources != null) {
                for (final Resource classpathResource : allResources) {
                    classpathResource.close();
                }
            }
            if (nestedJarHandler != null) {
                nestedJarHandler.close(log);
            }
            if (classpathOrder != null) {
                for (final ClasspathElement classpathElement : classpathOrder) {
                    classpathElement.closeRecyclers();
                }
            }
            classGraphClassLoader = null;
            nonClosedWeakReferences.remove(weakReference);
            if (log != null) {
                log.flush();
            }
        }
    }
}
