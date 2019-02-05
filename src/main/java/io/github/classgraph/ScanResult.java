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
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.classpath.ClassLoaderAndModuleFinder;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.json.JSONDeserializer;
import nonapi.io.github.classgraph.json.JSONSerializer;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** The result of a scan. */
public final class ScanResult implements Closeable, AutoCloseable {
    /** The order of raw classpath elements. */
    private final List<String> rawClasspathEltOrderStrs;

    /** The order of classpath elements, after inner jars have been extracted to temporary files, etc. */
    private List<ClasspathElement> classpathOrder;

    /** A list of all files that were found in whitelisted packages. */
    private ResourceList allWhitelistedResources;

    /**
     * The map from path (relative to package root) to a list of {@link Resource} elements with the matching path.
     */
    private Map<String, ResourceList> pathToWhitelistedResourceList;

    /** The map from class name to {@link ClassInfo}. */
    private Map<String, ClassInfo> classNameToClassInfo;

    /** The map from package name to {@link PackageInfo}. */
    private Map<String, PackageInfo> packageNameToPackageInfo;

    /** The map from class name to {@link ClassInfo}. */
    private Map<String, ModuleInfo> moduleNameToModuleInfo;

    /**
     * The file, directory and jarfile resources timestamped during a scan, along with their timestamp at the time
     * of the scan. For jarfiles, the timestamp represents the timestamp of all files within the jar. May be null,
     * if this ScanResult object is the result of a call to ClassGraph#getUniqueClasspathElementsAsync().
     */
    private Map<File, Long> fileToLastModified;

    /** A custom ClassLoader that can load classes found during the scan. */
    private ClassGraphClassLoader classGraphClassLoader;

    /**
     * The default order in which ClassLoaders are called to load classes. Used when a specific class does not have
     * a record of which ClassLoader provided the URL used to locate the class (e.g. if the class is found using
     * java.class.path).
     */
    ClassLoader[] envClassLoaderOrder;

    /** The nested jar handler instance. */
    private NestedJarHandler nestedJarHandler;

    /** The scan spec. */
    final ScanSpec scanSpec;

    /** If true, this ScanResult has already been closed. */
    private volatile AtomicBoolean closed = new AtomicBoolean(false);

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
            if (classpathElt.whitelistedResources != null) {
                if (allWhitelistedResources == null) {
                    allWhitelistedResources = new ResourceList();
                    pathToWhitelistedResourceList = new HashMap<>();
                }
                allWhitelistedResources.addAll(classpathElt.whitelistedResources);
                for (final Resource resource : classpathElt.whitelistedResources) {
                    final String path = resource.getPath();
                    ResourceList resourceList = pathToWhitelistedResourceList.get(path);
                    if (resourceList == null) {
                        pathToWhitelistedResourceList.put(path, resourceList = new ResourceList());
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

            // If inter-class dependencies are enabled, create placeholder ClassInfo objects for any referenced
            // classes that were not scanned
            if (scanSpec.enableInterClassDependencies) {
                for (final ClassInfo ci : new ArrayList<>(classNameToClassInfo.values())) {
                    final Set<ClassInfo> refdClasses = new HashSet<>();
                    for (final String refdClassName : ci.getReferencedClassNames()) {
                        // Don't add circular dependencies
                        if (!ci.getName().equals(refdClassName)) {
                            // Get ClassInfo object for the named class, or create one if it doesn't exist
                            final ClassInfo refdClassInfo = ClassInfo.getOrCreateClassInfo(refdClassName,
                                    /* classModifiers are unknown */ 0, classNameToClassInfo);
                            refdClassInfo.setScanResult(this);
                            if (!refdClassInfo.isExternalClass() || scanSpec.enableExternalClasses) {
                                // Only add class to result if it is whitelisted, or external classes are enabled
                                refdClasses.add(refdClassInfo);
                            }
                        }
                    }
                    ci.setReferencedClasses(new ClassInfoList(refdClasses, /* sortByName = */ true));
                }
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
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final List<File> classpathElementOrderFiles = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            final File file = classpathElement instanceof ClasspathElementModule
                    ? ((ClasspathElementModule) classpathElement).getModuleRef().getLocationFile()
                    : classpathElement instanceof ClasspathElementDir
                            ? ((ClasspathElementDir) classpathElement).getDirFile()
                            : classpathElement instanceof ClasspathElementZip
                                    ? ((ClasspathElementZip) classpathElement).getZipFile()
                                    : null;
            if (file != null) {
                classpathElementOrderFiles.add(file);
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
        if (closed.get()) {
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
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final List<URL> classpathElementOrderURLs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            try {
                if (classpathElement instanceof ClasspathElementModule) {
                    // Get the URL for a module, if it has a location
                    final URI location = ((ClasspathElementModule) classpathElement).getModuleRef().getLocation();
                    if (location != null) {
                        classpathElementOrderURLs.add(location.toURL());
                    }

                } else if (classpathElement instanceof ClasspathElementDir) {
                    // Get the URL for a classpath directory
                    classpathElementOrderURLs
                            .add(((ClasspathElementDir) classpathElement).getDirFile().toURI().toURL());

                } else if (classpathElement instanceof ClasspathElementZip) {
                    // Get the URL for a jarfile, with "!/" separating any nested jars, and optional package root
                    classpathElementOrderURLs.add(((ClasspathElementZip) classpathElement).getZipFileURL());
                }
            } catch (final MalformedURLException e) {
                // Skip malformed URLs
            }
        }
        return classpathElementOrderURLs;
    }

    /**
     * @return {@link ModuleRef} references for all the visible modules.
     */
    public List<ModuleRef> getModules() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final List<ModuleRef> moduleRefs = new ArrayList<>();
        for (final ClasspathElement classpathElement : classpathOrder) {
            if (classpathElement instanceof ClasspathElementModule) {
                moduleRefs.add(((ClasspathElementModule) classpathElement).getModuleRef());
            }
        }
        return moduleRefs;
    }

    /**
     * Get the module path info provided on the commandline with {@code --module-path}, {@code --add-modules},
     * {@code --patch-module}, {@code --add-exports}, {@code --add-opens}, and {@code --add-reads}, and also the
     * {@code Add-Exports} and {@code Add-Opens} entries from jarfile manifest files encountered during scanning.
     * 
     * <p>
     * Note that the returned {@link ModulePathInfo} object does not include classpath entries from the traditional
     * classpath or system modules. Use {@link #getModules()} to get all visible modules, including anonymous,
     * automatic and system modules.
     * 
     * @return The {@link ModulePathInfo}.
     */
    public ModulePathInfo getModulePathInfo() {
        return scanSpec.modulePathInfo;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Resources

    /** @return A list of all resources (including classfiles and non-classfiles) found in whitelisted packages. */
    public ResourceList getAllResources() {
        if (allWhitelistedResources == null || allWhitelistedResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            return allWhitelistedResources;
        }
    }

    /**
     * @param resourcePath
     *            A complete resource path, relative to the classpath entry package root.
     * @return A list of all resources found in whitelisted packages that have the given path, relative to the
     *         package root of the classpath element. May match several resources, up to one per classpath element.
     */
    public ResourceList getResourcesWithPath(final String resourcePath) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (allWhitelistedResources == null || allWhitelistedResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            final String path = FileUtils.sanitizeEntryPath(resourcePath, /* removeInitialSlash = */ true);
            final ResourceList resourceList = pathToWhitelistedResourceList.get(path);
            return (resourceList == null ? new ResourceList(1) : resourceList);
        }
    }

    /**
     * @param resourcePath
     *            A complete resource path, relative to the classpath entry package root.
     * @return A list of all resources found in any classpath element, <i>whether in whitelisted packages or not<i>,
     *         that have the given path, relative to the package root of the classpath element. (Resources will not
     *         be returned if their path is blacklisted.) May match several resources, up to one per classpath
     *         element.
     */
    public ResourceList getResourcesWithPathIgnoringWhitelist(final String resourcePath) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        final String path = FileUtils.sanitizeEntryPath(resourcePath, /* removeInitialSlash = */ true);
        final ResourceList matchingResources = new ResourceList();
        for (final ClasspathElement classpathElt : classpathOrder) {
            final Resource matchingResource = classpathElt.getResource(path);
            if (matchingResource != null) {
                matchingResources.add(matchingResource);
            }
        }
        return matchingResources;
    }

    /**
     * @param leafName
     *            A resource leaf filename.
     * @return A list of all resources found in whitelisted packages that have the requested leafname.
     */
    public ResourceList getResourcesWithLeafName(final String leafName) {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (allWhitelistedResources == null || allWhitelistedResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allWhitelistedResources) {
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
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (allWhitelistedResources == null || allWhitelistedResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            String bareExtension = extension;
            while (bareExtension.startsWith(".")) {
                bareExtension = bareExtension.substring(1);
            }
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allWhitelistedResources) {
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
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (allWhitelistedResources == null || allWhitelistedResources.isEmpty()) {
            return new ResourceList(1);
        } else {
            final ResourceList filteredResources = new ResourceList();
            for (final Resource classpathResource : allWhitelistedResources) {
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
        if (closed.get()) {
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
        if (closed.get()) {
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
        if (closed.get()) {
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
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return new PackageInfoList(packageNameToPackageInfo.values());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Class dependencies

    /**
     * @return A map from a {@link ClassInfo} object for each whitelisted class to a list of the classes referenced
     *         by that class (i.e. returns a map from dependents to dependencies). Each map value is the result of
     *         calling {@link ClassInfo#getClassDependencies()} on the corresponding key. Note that you need to call
     *         {@link ClassGraph#enableInterClassDependencies()} before {@link ClassGraph#scan()} for this method to
     *         work. You should also call {@link ClassGraph#enableExternalClasses()} before
     *         {@link ClassGraph#scan()} if you want non-whitelisted classes to appear in the result. See also
     *         {@link #getReverseClassDependencyMap()}, which inverts the map.
     */
    public Map<ClassInfo, ClassInfoList> getClassDependencyMap() {
        final Map<ClassInfo, ClassInfoList> map = new HashMap<>();
        for (final ClassInfo ci : getAllClasses()) {
            map.put(ci, ci.getClassDependencies());
        }
        return map;
    }

    /**
     * @return A mapping from a {@link ClassInfo} object for each dependency class (whitelisted or not) to a list of
     *         the whitelisted classes that referenced that class as a dependency (i.e. returns a map from
     *         dependencies to dependents). Note that you need to call
     *         {@link ClassGraph#enableInterClassDependencies()} before {@link ClassGraph#scan()} for this method to
     *         work. You should also call {@link ClassGraph#enableExternalClasses()} before
     *         {@link ClassGraph#scan()} if you want non-whitelisted classes to appear in the result. See also
     *         {@link #getClassDependencyMap}.
     */
    public Map<ClassInfo, ClassInfoList> getReverseClassDependencyMap() {
        final Map<ClassInfo, Set<ClassInfo>> revMapSet = new HashMap<>();
        for (final ClassInfo ci : getAllClasses()) {
            for (final ClassInfo dep : ci.getClassDependencies()) {
                Set<ClassInfo> set = revMapSet.get(dep);
                if (set == null) {
                    revMapSet.put(dep, set = new HashSet<ClassInfo>());
                }
                set.add(ci);
            }
        }
        final Map<ClassInfo, ClassInfoList> revMapList = new HashMap<>();
        for (final Entry<ClassInfo, Set<ClassInfo>> ent : revMapSet.entrySet()) {
            revMapList.put(ent.getKey(), new ClassInfoList(ent.getValue(), /* sortByName = */ true));
        }
        return revMapList;
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
        if (closed.get()) {
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
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all standard (non-interface/non-annotation) classes found during the scan.
     *
     * @return A list of all whitelisted standard classes found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllStandardClasses() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllStandardClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all subclasses of the named superclass.
     *
     * @param superclassName
     *            The name of the superclass.
     * @return A list of subclasses of the named superclass, or the empty list if none.
     */
    public ClassInfoList getSubclasses(final String superclassName) {
        if (closed.get()) {
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
        if (closed.get()) {
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
        if (closed.get()) {
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
        if (closed.get()) {
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
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return ClassInfo.getAllImplementedInterfaceClasses(classNameToClassInfo.values(), scanSpec);
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
        if (closed.get()) {
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
        if (closed.get()) {
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
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableClassInfo() and #enableAnnotationInfo() before #scan()");
        }
        return ClassInfo.getAllAnnotationClasses(classNameToClassInfo.values(), scanSpec);
    }

    /**
     * Get all interface or annotation classes found during the scan. (Annotations are technically interfaces, and
     * they can be implemented.)
     *
     * @return A list of all whitelisted interfaces found during the scan, or the empty list if none.
     */
    public ClassInfoList getAllInterfacesAndAnnotations() {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (!scanSpec.enableClassInfo || !scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableClassInfo() and #enableAnnotationInfo() before #scan()");
        }
        return ClassInfo.getAllInterfacesOrAnnotationClasses(classNameToClassInfo.values(), scanSpec);
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
        if (closed.get()) {
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
        if (closed.get()) {
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
        if (closed.get()) {
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
        if (closed.get()) {
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
    public Class<?> loadClass(final String className, final boolean returnNullIfClassNotFound)
            throws IllegalArgumentException {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("className cannot be null or empty");
        }
        try {
            return Class.forName(className, scanSpec.initializeLoadedClasses, classGraphClassLoader);
        } catch (final ClassNotFoundException | LinkageError e) {
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
    public <T> Class<T> loadClass(final String className, final Class<T> superclassOrInterfaceType,
            final boolean returnNullIfClassNotFound) throws IllegalArgumentException {
        if (closed.get()) {
            throw new IllegalArgumentException("Cannot use a ScanResult after it has been closed");
        }
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("className cannot be null or empty");
        }
        if (superclassOrInterfaceType == null) {
            throw new IllegalArgumentException("superclassOrInterfaceType parameter cannot be null");
        }
        final Class<?> loadedClass;
        try {
            loadedClass = Class.forName(className, scanSpec.initializeLoadedClasses, classGraphClassLoader);
        } catch (final ClassNotFoundException | LinkageError e) {
            if (returnNullIfClassNotFound) {
                return null;
            } else {
                throw new IllegalArgumentException("Could not load class " + className + " : " + e);
            }
        }
        if (loadedClass != null && !superclassOrInterfaceType.isAssignableFrom(loadedClass)) {
            if (returnNullIfClassNotFound) {
                return null;
            } else {
                throw new IllegalArgumentException("Loaded class " + loadedClass.getName() + " cannot be cast to "
                        + superclassOrInterfaceType.getName());
            }
        }
        @SuppressWarnings("unchecked")
        final Class<T> castClass = (Class<T>) loadedClass;
        return castClass;

    }

    // -------------------------------------------------------------------------------------------------------------
    // Serialization / deserialization

    /** The current serialization format. */
    private static final String CURRENT_SERIALIZATION_FORMAT = "8";

    /** A class to hold a serialized ScanResult along with the ScanSpec that was used to scan. */
    private static class SerializationFormat {
        public String format;
        public ScanSpec scanSpec;
        public List<String> classpath;
        public List<ClassInfo> classInfo;
        public List<PackageInfo> packageInfo;
        public List<ModuleInfo> moduleInfo;

        @SuppressWarnings("unused")
        public SerializationFormat() {
        }

        public SerializationFormat(final String serializationFormatStr, final ScanSpec scanSpec,
                final List<ClassInfo> classInfo, final List<PackageInfo> packageInfo,
                final List<ModuleInfo> moduleInfo, final List<String> classpath) {
            this.format = serializationFormatStr;
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
        final Matcher matcher = Pattern.compile("\\{[\\n\\r ]*\"format\"[ ]?:[ ]?\"([^\"]+)\"").matcher(json);
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
        if (!deserialized.format.equals(CURRENT_SERIALIZATION_FORMAT)) {
            // Probably the deserialization failed before now anyway, if fields have changed, etc.
            throw new IllegalArgumentException("JSON was serialized by newer version of ClassGraph");
        }

        // Get the classpath that produced the serialized JSON, and extract inner jars, download remote jars, etc.
        final List<URL> urls = new ClassGraph().overrideClasspath(deserialized.classpath).getClasspathURLs();

        // Define a custom URLClassLoader with the result that delegates to the first environment classloader
        final ClassLoader[] envClassLoaderOrder = new ClassLoaderAndModuleFinder(deserialized.scanSpec,
                /* log = */ null).getContextClassLoaders();
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
        return new ScanResult(deserialized.scanSpec,
                /* classpathOrder = */ Collections.<ClasspathElement> emptyList(), deserialized.classpath,
                classLoaderOrder, classNameToClassInfo, packageNameToPackageInfo, moduleNameToModuleInfo,
                /* fileToLastModified = */ null, /* nestedJarHandler = */ null, /* log = */ null);
    }

    /**
     * Serialize a ScanResult to JSON.
     * 
     * @param indentWidth
     *            If greater than 0, JSON will be formatted (indented), otherwise it will be minified (un-indented).
     * @return This {@link ScanResult}, serialized as a JSON string.
     */
    public String toJSON(final int indentWidth) {
        if (closed.get()) {
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
                for (final WeakReference<ScanResult> nonClosedWeakReference : new ArrayList<>(
                        nonClosedWeakReferences)) {
                    final ScanResult scanResult = nonClosedWeakReference.get();
                    if (scanResult != null) {
                        scanResult.close();
                    }
                    nonClosedWeakReferences.remove(nonClosedWeakReference);
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
        if (!closed.getAndSet(true)) {
            if (classpathOrder != null) {
                classpathOrder.clear();
                classpathOrder = null;
            }
            if (allWhitelistedResources != null) {
                for (final Resource classpathResource : allWhitelistedResources) {
                    classpathResource.close();
                }
                allWhitelistedResources.clear();
                allWhitelistedResources = null;
            }
            if (pathToWhitelistedResourceList != null) {
                pathToWhitelistedResourceList.clear();
                pathToWhitelistedResourceList = null;
            }
            classGraphClassLoader = null;
            if (classNameToClassInfo != null) {
                classNameToClassInfo.clear();
                classNameToClassInfo = null;
            }
            if (packageNameToPackageInfo != null) {
                packageNameToPackageInfo.clear();
                packageNameToPackageInfo = null;
            }
            if (moduleNameToModuleInfo != null) {
                moduleNameToModuleInfo.clear();
                moduleNameToModuleInfo = null;
            }
            if (fileToLastModified != null) {
                fileToLastModified.clear();
                fileToLastModified = null;
            }
            classGraphClassLoader = null;
            envClassLoaderOrder = null;
            // nestedJarHandler should be closed last, since it needs to have all MappedByteBuffer refs
            // dropped before it tries to delete any temporary files that were written to disk
            if (nestedJarHandler != null) {
                nestedJarHandler.close(log);
                nestedJarHandler = null;
            }
            nonClosedWeakReferences.remove(weakReference);
            if (log != null) {
                log.flush();
            }
        }
    }
}
