/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Luke Hutchison
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

package io.github.lukehutch.fastclasspathscanner;

import io.github.lukehutch.fastclasspathscanner.classgraph.ClassGraphBuilder;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchContentsProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.InterfaceMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubclassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubinterfaceMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.utils.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary format
 * directly rather than by using reflection. (Reflection causes the classloader to load each class, which can take
 * an order of magnitude more time than parsing the classfile directly.) See the accompanying README.md file for
 * documentation.
 */
public class FastClasspathScanner {
    /** The unique elements of the classpath, as an ordered list. */
    private final ArrayList<File> classpathElements = new ArrayList<>();

    /** The unique elements of the classpath, as a set. */
    private final HashSet<String> classpathElementsSet = new HashSet<>();

    /**
     * List of directory path prefixes to scan (produced from list of package prefixes passed into the constructor)
     */
    private final String[] whitelistedPaths, blacklistedPaths;

    /**
     * The latest last-modified timestamp of any file, directory or sub-directory in the classpath, in millis since
     * the Unix epoch. Does not consider timestamps inside zipfiles/jarfiles, but the timestamp of the zip/jarfile
     * itself is considered.
     */
    private long lastModified = 0;

    /**
     * If this is set to true, then the timestamps of zipfile entries should be used to determine when files inside
     * a zipfile have changed; if set to false, then the timestamp of the zipfile itself is used. Itis recommended
     * to leave this set to false, since zipfile timestamps are less trustworthy than filesystem timestamps.
     */
    private static final boolean USE_ZIPFILE_ENTRY_MODIFICATION_TIMES = false;

    /** A list of class matchers to call once all classes have been read in from classpath. */
    private final ArrayList<ClassMatcher> classMatchers = new ArrayList<>();

    /**
     * A list of file path matchers to call when a directory or subdirectory on the classpath matches a given
     * regexp.
     */
    private final ArrayList<FilePathMatcher> filePathMatchers = new ArrayList<>();

    /** The class and interface graph builder. */
    private final ClassGraphBuilder classGraphBuilder = new ClassGraphBuilder();

    /** If set to true, print info while scanning */
    private boolean verbose = false;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructs a FastClasspathScanner instance.
     * 
     * @param packagesToScan
     *            the whitelist of package prefixes to scan, e.g. "com.xyz.widget", "com.xyz.gizmo". If no
     *            whitelisted packages are given (i.e. if the constructor is called with zero arguments), or a
     *            whitelisted package is "", then all packages on the classpath are whitelisted. If a package name
     *            is prefixed with "-", e.g. "-com.xyz.otherthing", then that package is blacklisted, rather than
     *            whitelisted. The final list of packages scanned is the set of whitelisted packages minus the set
     *            of blacklisted packages.
     */
    public FastClasspathScanner(final String... packagesToScan) {
        parseSystemClasspath();

        final HashSet<String> uniqueWhitelistedPaths = new HashSet<>();
        final HashSet<String> uniqueBlacklistedPaths = new HashSet<>();
        boolean scanAll = false;
        if (packagesToScan.length == 0) {
            scanAll = true;
        } else {
            for (final String packageToScan : packagesToScan) {
                if (packageToScan.isEmpty()) {
                    scanAll = true;
                    break;
                }
                String pkg = packageToScan.replace('.', '/') + "/";
                final boolean blacklisted = pkg.startsWith("-");
                if (blacklisted) {
                    pkg = pkg.substring(1);
                }
                (blacklisted ? uniqueBlacklistedPaths : uniqueWhitelistedPaths).add(pkg);
            }
        }
        uniqueWhitelistedPaths.removeAll(uniqueBlacklistedPaths);
        if (scanAll) {
            this.whitelistedPaths = new String[] { "/" };
        } else {
            this.whitelistedPaths = new String[uniqueWhitelistedPaths.size()];
            int i = 0;
            for (final String path : uniqueWhitelistedPaths) {
                this.whitelistedPaths[i++] = path;
            }
        }
        this.blacklistedPaths = new String[uniqueBlacklistedPaths.size()];
        int i = 0;
        for (final String path : uniqueBlacklistedPaths) {
            this.blacklistedPaths[i++] = path;
        }

        // Read classfile headers for all filenames ending in ".class" on classpath
        this.matchFilenameExtension("class", new FileMatchProcessor() {
            @Override
            public void processMatch(String relativePath, InputStream inputStream, int lengthBytes)
                    throws IOException {
                classGraphBuilder.readClassInfoFromClassfileHeader(inputStream, verbose);
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Call the classloader using Class.forName(className). Re-throws classloading exceptions as RuntimeException.
     */
    public <T> Class<? extends T> loadClass(final String className) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends T> klass = (Class<? extends T>) Class.forName(className);
            return klass;
        } catch (ClassNotFoundException | NoClassDefFoundError | ExceptionInInitializerError e) {
            throw new RuntimeException("Exception while loading or initializing class " + className, e);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubclassMatchProcessor if classes are found on the classpath that extend the specified
     * superclass. Will call the class loader on each matching class (using Class.forName()) before calling the
     * SubclassMatchProcessor. Does not call the classloader on non-matching classes or interfaces.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @param subclassMatchProcessor
     *            the SubclassMatchProcessor to call when a match is found.
     */
    public <T> FastClasspathScanner matchSubclassesOf(final Class<T> superclass,
            final SubclassMatchProcessor<T> subclassMatchProcessor) {
        if (superclass.isInterface()) {
            throw new IllegalArgumentException(superclass.getName() + " is an interface, not a regular class");
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                for (final String subclass : classGraphBuilder.getNamesOfSubclassesOf(superclass.getName())) {
                    if (verbose) {
                        Log.log("Found subclass of " + superclass.getName() + ": " + subclass);
                    }
                    // Call classloader
                    final Class<? extends T> klass = loadClass(subclass);
                    // Process match
                    subclassMatchProcessor.processMatch(klass);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of classes on the classpath that extend the specified superclass. Should be called after
     * scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the scanner before
     * the call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public <T> List<String> getNamesOfSubclassesOf(final Class<T> superclass) {
        if (superclass.isInterface()) {
            throw new IllegalArgumentException(superclass.getName() + " is an interface, not a regular class");
        }
        return classGraphBuilder.getNamesOfSubclassesOf(superclass.getName());
    }

    /**
     * Returns the names of classes on the classpath that extend the specified superclass. Should be called after
     * scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the scanner before
     * the call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param superclassName
     *            The name of the superclass to match (i.e. the name of the class that subclasses need to extend).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfSubclassesOf(final String superclassName) {
        return classGraphBuilder.getNamesOfSubclassesOf(superclassName);
    }

    /**
     * Returns the names of classes on the classpath that are superclasses of the specified subclass. Should be
     * called after scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param subclass
     *            The subclass to match (i.e. the class that needs to extend a superclass for the superclass to
     *            match).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public <T> List<String> getNamesOfSuperclassesOf(final Class<T> subclass) {
        if (subclass.isInterface()) {
            throw new IllegalArgumentException(subclass.getName() + " is an interface, not a regular class");
        }
        return classGraphBuilder.getNamesOfSuperclassesOf(subclass.getName());
    }

    /**
     * Returns the names of classes on the classpath that are superclasses of the specified subclass. Should be
     * called after scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param subclassName
     *            The subclass to match (i.e. the class that needs to extend a superclass for the superclass to
     *            match).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfSuperclassesOf(final String subclassName) {
        return classGraphBuilder.getNamesOfSuperclassesOf(subclassName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubinterfaceMatchProcessor if an interface that extends a given superinterface is found on
     * the classpath. Will call the class loader on each matching interface (using Class.forName()) before calling
     * the SubinterfaceMatchProcessor. Does not call the classloader on non-matching classes or interfaces.
     * 
     * @param superinterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @param subinterfaceMatchProcessor
     *            the SubinterfaceMatchProcessor to call when a match is found.
     */
    public <T> FastClasspathScanner matchSubinterfacesOf(final Class<T> superinterface,
            final SubinterfaceMatchProcessor<T> subinterfaceMatchProcessor) {
        if (!superinterface.isInterface()) {
            throw new IllegalArgumentException(superinterface.getName() + " is not an interface");
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                for (final String subinterface : classGraphBuilder.getNamesOfSubinterfacesOf( //
                        superinterface.getName())) {
                    if (verbose) {
                        Log.log("Found subinterface of " + superinterface.getName() + ": " + subinterface);
                    }
                    // Call classloader
                    final Class<? extends T> klass = loadClass(subinterface);
                    // Process match
                    subinterfaceMatchProcessor.processMatch(klass);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of interfaces on the classpath that extend a given superinterface. Should be called after
     * scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching interfaces, just returns their
     * names.
     * 
     * @param superInterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public <T> List<String> getNamesOfSubinterfacesOf(final Class<T> superInterface) {
        if (!superInterface.isInterface()) {
            throw new IllegalArgumentException(superInterface.getName() + " is not an interface");
        }
        return classGraphBuilder.getNamesOfSubinterfacesOf(superInterface.getName());
    }

    /**
     * Returns the names of interfaces on the classpath that extend a given superinterface. Should be called after
     * scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching interfaces, just returns their
     * names.
     * 
     * @param superInterfaceName
     *            The name of the superinterface to match (i.e. the name of the interface that subinterfaces need to
     *            extend).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public List<String> getNamesOfSubinterfacesOf(final String superInterfaceName) {
        return classGraphBuilder.getNamesOfSubinterfacesOf(superInterfaceName);
    }

    /**
     * Returns the names of interfaces on the classpath that are superinterfaces of a given subinterface. Should be
     * called after scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to
     * the scanner before the call to scan(). Does not call the classloader on the matching interfaces, just returns
     * their names.
     * 
     * @param subInterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public <T> List<String> getNamesOfSuperinterfacesOf(final Class<T> subInterface) {
        if (!subInterface.isInterface()) {
            throw new IllegalArgumentException(subInterface.getName() + " is not an interface");
        }
        return classGraphBuilder.getNamesOfSuperinterfacesOf(subInterface.getName());
    }

    /**
     * Returns the names of interfaces on the classpath that are superinterfaces of a given subinterface. Should be
     * called after scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to
     * the scanner before the call to scan(). Does not call the classloader on the matching interfaces, just returns
     * their
     * 
     * @param subInterfaceName
     *            The name of the superinterface to match (i.e. the name of the interface that subinterfaces need to
     *            extend).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public List<String> getNamesOfSuperinterfacesOf(final String subInterfaceName) {
        return classGraphBuilder.getNamesOfSuperinterfacesOf(subInterfaceName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided InterfaceMatchProcessor for classes on the classpath that implement the specified
     * interface or a subinterface, or whose superclasses implement the specified interface or a sub-interface. Will
     * call the class loader on each matching interface (using Class.forName()) before calling the
     * InterfaceMatchProcessor. Does not call the classloader on non-matching classes or interfaces.
     * 
     * @param implementedInterface
     *            The interface that classes need to implement.
     * @param interfaceMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public <T> FastClasspathScanner matchClassesImplementing(final Class<T> implementedInterface,
            final InterfaceMatchProcessor<T> interfaceMatchProcessor) {
        if (!implementedInterface.isInterface()) {
            throw new IllegalArgumentException(implementedInterface.getName() + " is not an interface");
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                // For all classes implementing the given interface
                for (final String implClass : classGraphBuilder.getNamesOfClassesImplementing(implementedInterface
                        .getName())) {
                    if (verbose) {
                        Log.log("Found class implementing interface " + implementedInterface.getName() + ": "
                                + implClass);
                    }
                    // Call classloader
                    final Class<? extends T> klass = loadClass(implClass);
                    // Process match
                    interfaceMatchProcessor.processMatch(klass);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of classes on the classpath that implement the specified interface or a subinterface, or
     * whose superclasses implement the specified interface or a sub-interface. Should be called after scan(), and
     * returns matching interfaces whether or not an InterfaceMatchProcessor was added to the scanner before the
     * call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterface
     *            The interface that classes need to implement to match.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public <T> List<String> getNamesOfClassesImplementing(final Class<T> implementedInterface) {
        if (!implementedInterface.isInterface()) {
            throw new IllegalArgumentException(implementedInterface.getName() + " is not an interface");
        }
        return classGraphBuilder.getNamesOfClassesImplementing(implementedInterface.getName());
    }

    /**
     * Returns the names of classes on the classpath that implement the specified interface or a subinterface, or
     * whose superclasses implement the specified interface or a sub-interface. Should be called after scan(), and
     * returns matching interfaces whether or not an InterfaceMatchProcessor was added to the scanner before the
     * call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterfaceName
     *            The name of the interface that classes need to implement.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfClassesImplementing(final String implementedInterfaceName) {
        return classGraphBuilder.getNamesOfClassesImplementing(implementedInterfaceName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassMatchProcessor if classes are found on the classpath that have the specified
     * annotation.
     * 
     * @param annotation
     *            The class annotation to match.
     * @param classAnnotationMatchProcessor
     *            the ClassAnnotationMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchClassesWithAnnotation(final Class<?> annotation,
            final ClassAnnotationMatchProcessor classAnnotationMatchProcessor) {
        if (!annotation.isAnnotation()) {
            throw new IllegalArgumentException("Class " + annotation.getName() + " is not an annotation");
        }
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                // For all classes with the given annotation
                for (final String classWithAnnotation : classGraphBuilder
                        .getNamesOfClassesWithAnnotation(annotation.getName())) {
                    if (verbose) {
                        Log.log("Found class with annotation " + annotation.getName() + ": " + classWithAnnotation);
                    }
                    // Call classloader
                    final Class<?> klass = loadClass(classWithAnnotation);
                    // Process match
                    classAnnotationMatchProcessor.processMatch(klass);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of classes on the classpath that have the specified annotation. Should be called after
     * scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param annotation
     *            The class annotation.
     * @return A list of the names of classes with the class annotation, or the empty list if none.
     */
    public <T> List<String> getNamesOfClassesWithAnnotation(final Class<?> annotation) {
        if (!annotation.isAnnotation()) {
            throw new IllegalArgumentException("Class " + annotation.getName() + " is not an annotation");
        }
        return classGraphBuilder.getNamesOfClassesWithAnnotation(annotation.getName());
    }

    /**
     * Returns the names of classes on the classpath that have the specified annotation. Should be called after
     * scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param annotationName
     *            The name of the class annotation.
     * @return A list of the names of classes that have the named annotation, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        return classGraphBuilder.getNamesOfClassesWithAnnotation(annotationName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match one of a set of fully-qualified field names, e.g.
     * "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked; the value of the field in the classfile is returned
     * whether or not it should be visible to the caller.
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The set of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchStaticFinalFieldNames(
            final HashSet<String> fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        for (final String fullyQualifiedFieldName : fullyQualifiedStaticFinalFieldNames) {
            final int lastDotIdx = fullyQualifiedFieldName.lastIndexOf('.');
            if (lastDotIdx > 0) {
                final String className = fullyQualifiedFieldName.substring(0, lastDotIdx);
                final String fieldName = fullyQualifiedFieldName.substring(lastDotIdx + 1);
                classGraphBuilder
                        .addStaticFinalFieldProcessor(className, fieldName, staticFinalFieldMatchProcessor);
            }
        }
        return this;
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match a fully-qualified field name, e.g. "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked; the value of the field in the classfile is returned
     * whether or not it should be visible to the caller.
     * 
     * @param fullyQualifiedStaticFinalFieldName
     *            The fully-qualified static field name to match
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchStaticFinalFieldNames(final String fullyQualifiedStaticFinalFieldName,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        final HashSet<String> fullyQualifiedStaticFinalFieldNamesSet = new HashSet<>();
        fullyQualifiedStaticFinalFieldNamesSet.add(fullyQualifiedStaticFinalFieldName);
        return matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNamesSet, staticFinalFieldMatchProcessor);
    }

    /**
     * Calls the given StaticFinalFieldMatchProcessor if classes are found on the classpath that contain static
     * final fields that match one of a list of fully-qualified field names, e.g.
     * "com.package.ClassName.STATIC_FIELD_NAME".
     * 
     * Field values are obtained from the constant pool in classfiles, *not* from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked; the value of the field in the classfile is returned
     * whether or not it should be visible to the caller.
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The list of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchStaticFinalFieldNames(final String[] fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        final HashSet<String> fullyQualifiedStaticFinalFieldNamesSet = new HashSet<>();
        for (final String fullyQualifiedFieldName : fullyQualifiedStaticFinalFieldNames) {
            fullyQualifiedStaticFinalFieldNamesSet.add(fullyQualifiedFieldName);
        }
        return matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNamesSet, staticFinalFieldMatchProcessor);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Wrap a FileMatchContentsProcessor in a FileMatchProcessor that reads the file content from the stream. */
    private static FileMatchProcessor wrapFileMatchContentsProcessor(
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        return new FileMatchProcessor() {
            @Override
            public void processMatch(final String relativePath, final InputStream inputStream, //
                    final int lengthBytes) throws IOException {
                // Read the file contents into a byte[] array
                final byte[] contents = new byte[lengthBytes];
                final int bytesRead = Math.max(0, inputStream.read(contents));
                // For safety, truncate the array if the file was truncated before we finish reading it
                final byte[] contentsRead = bytesRead == lengthBytes ? contents : Arrays
                        .copyOf(contents, bytesRead);
                // Pass file contents to the wrapped FileMatchContentsProcessor
                fileMatchContentsProcessor.processMatch(relativePath, contentsRead);
            }
        };
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath with the given regexp pattern in their
     * path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchProcessor fileMatchProcessor) {
        filePathMatchers.add(new FilePathMatcher(new FilePathTester() {
            private Pattern pattern = Pattern.compile(pathRegexp);

            @Override
            public boolean filePathMatches(String relativePath) {
                return pattern.matcher(relativePath).matches();
            }
        }, fileMatchProcessor));
        return this;
    }

    /**
     * Calls the given FileMatchContentsProcessor if files are found on the classpath with the given regexp pattern
     * in their path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        return matchFilenamePattern(pathRegexp, wrapFileMatchContentsProcessor(fileMatchContentsProcessor));
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that exactly match the given relative
     * path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        filePathMatchers.add(new FilePathMatcher(new FilePathTester() {
            @Override
            public boolean filePathMatches(String relativePath) {
                return relativePath.equals(relativePathToMatch);
            }
        }, fileMatchProcessor));
        return this;
    }

    /**
     * Calls the given FileMatchContentsProcessor if files are found on the classpath that exactly match the given
     * relative path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        return matchFilenamePath(relativePathToMatch, wrapFileMatchContentsProcessor(fileMatchContentsProcessor));
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that exactly match the given path
     * leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        filePathMatchers.add(new FilePathMatcher(new FilePathTester() {
            private String leafToMatch = pathLeafToMatch.substring(pathLeafToMatch.lastIndexOf('/') + 1);

            @Override
            public boolean filePathMatches(String relativePath) {
                String relativePathLeaf = relativePath.substring(relativePath.lastIndexOf('/') + 1);
                return relativePathLeaf.equals(leafToMatch);
            }
        }, fileMatchProcessor));
        return this;
    }

    /**
     * Calls the given FileMatchContentsProcessor if files are found on the classpath that exactly match the given
     * path leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        return matchFilenamePathLeaf(pathLeafToMatch, wrapFileMatchContentsProcessor(fileMatchContentsProcessor));
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that have the given file extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html".
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        filePathMatchers.add(new FilePathMatcher(new FilePathTester() {
            private String suffixToMatch = "." + extensionToMatch;

            @Override
            public boolean filePathMatches(String relativePath) {
                return relativePath.endsWith(suffixToMatch);
            }
        }, fileMatchProcessor));
        return this;
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that have the given file extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html".
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        return matchFilenameExtension(extensionToMatch, wrapFileMatchContentsProcessor(fileMatchContentsProcessor));
    }

    // -------------------------------------------------------------------------------------------------------------

    /** An interface used to test whether a file's relative path matches a given specification. */
    private static interface FilePathTester {
        public boolean filePathMatches(final String relativePath);
    }

    /** A class used for associating a FilePathTester with a FileMatchProcessor. */
    private static class FilePathMatcher {
        private FilePathTester filePathTester;
        private FileMatchProcessor fileMatchProcessor;

        public FilePathMatcher(final FilePathTester filePathTester, final FileMatchProcessor fileMatchProcessor) {
            this.filePathTester = filePathTester;
            this.fileMatchProcessor = fileMatchProcessor;
        }

        public boolean filePathMatches(final String relativePath) {
            return filePathTester.filePathMatches(relativePath);
        }

        public void processMatch(String relativePath, InputStream inputStream, int inputStreamLengthBytes)
                throws IOException {
            fileMatchProcessor.processMatch(relativePath, inputStream, inputStreamLengthBytes);
        }
    }

    /** An interface used for testing if a class matches specified criteria. */
    private static interface ClassMatcher {
        public abstract void lookForMatches();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the names of all classes and interfaces processed during the scan, i.e. all classes reachable after
     * taking into account the package whitelist and blacklist criteria.
     */
    public <T> Set<String> getNamesOfAllClasses() {
        return classGraphBuilder.getNamesOfAllClasses();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a file.
     */
    private void scanFile(final File file, final String absolutePath, final String relativePath,
            final boolean scanTimestampsOnly) throws IOException {
        lastModified = Math.max(lastModified, file.lastModified());
        if (!scanTimestampsOnly) {
            // Match file paths against path patterns
            boolean filePathMatches = false;
            for (final FilePathMatcher fileMatcher : filePathMatchers) {
                if (fileMatcher.filePathMatches(relativePath)) {
                    // If there's a match, open the file as a stream and call the match processor
                    try (final InputStream inputStream = new FileInputStream(file)) {
                        fileMatcher.processMatch(relativePath, inputStream, (int) file.length());
                    }
                    filePathMatches = true;
                }
            }
            if (verbose && filePathMatches) {
                Log.log("Found file:    " + relativePath);
            }
        }
    }

    /**
     * Scan a directory for matching file path patterns.
     */
    private void scanDir(final File dir, final int ignorePrefixLen, boolean inWhitelistedPath,
            final boolean scanTimestampsOnly) throws IOException {
        String relativePath = (ignorePrefixLen > dir.getPath().length() ? "" : dir.getPath() //
                .substring(ignorePrefixLen)) + "/";
        if (File.separatorChar != '/') {
            // Fix scanning on Windows
            relativePath = relativePath.replace(File.separatorChar, '/');
        }
        if (verbose) {
            Log.log("Scanning path: " + relativePath);
        }
        for (final String blacklistedPath : blacklistedPaths) {
            if (relativePath.equals(blacklistedPath)) {
                if (verbose) {
                    Log.log("Reached blacklisted path: " + relativePath);
                }
                // Reached a blacklisted path -- stop scanning files and dirs
                return;
            }
        }
        boolean keepRecursing = false;
        if (!inWhitelistedPath) {
            // If not yet within a subtree of a whitelisted path, see if the current path is at least a prefix of
            // a whitelisted path, and if so, keep recursing until we hit a whitelisted path.
            for (final String whitelistedPath : whitelistedPaths) {
                if (relativePath.equals(whitelistedPath)) {
                    // Reached a whitelisted path -- can start scanning directories and files from this point
                    if (verbose) {
                        Log.log("Reached whitelisted path: " + relativePath);
                    }
                    inWhitelistedPath = true;
                    break;
                } else if (whitelistedPath.startsWith(relativePath) || relativePath.equals("/")) {
                    // In a path that is a prefix of a whitelisted path -- keep recursively scanning dirs
                    // in case we can reach a whitelisted path.
                    keepRecursing = true;
                }
            }
        }
        if (keepRecursing || inWhitelistedPath) {
            lastModified = Math.max(lastModified, dir.lastModified());
            final File[] subFiles = dir.listFiles();
            if (subFiles != null) {
                for (final File subFile : subFiles) {
                    if (subFile.isDirectory()) {
                        // Recurse into subdirectory
                        scanDir(subFile, ignorePrefixLen, inWhitelistedPath, scanTimestampsOnly);
                    } else if (inWhitelistedPath && subFile.isFile()) {
                        // Scan file
                        scanFile(subFile, dir.getPath() + "/" + subFile.getName(),
                                relativePath.equals("/") ? subFile.getName() : relativePath + subFile.getName(),
                                scanTimestampsOnly);
                    }
                }
            }
        }
    }

    /**
     * Scan a zipfile for matching file path patterns. (Does not recurse into zipfiles within zipfiles.)
     */
    private void scanZipfile(final String zipfilePath, final ZipFile zipFile, final long zipFileLastModified,
            final boolean scanTimestampsOnly) throws IOException {
        if (verbose) {
            Log.log("Scanning jar:  " + zipfilePath);
        }
        boolean timestampWarning = false;
        for (final Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            // Scan for matching filenames
            final ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                // Only process file entries (zipfile indices contain both directory entries and
                // separate file entries for files within each directory, in lexicographic order)
                final String path = entry.getName();
                boolean scanFile = false;
                for (final String whitelistedPath : whitelistedPaths) {
                    if (path.startsWith(whitelistedPath) //
                            || whitelistedPath.equals("/")) {
                        // File path has a whitelisted path as a prefix -- can scan file
                        scanFile = true;
                        break;
                    }
                }
                for (final String blacklistedPath : blacklistedPaths) {
                    if (path.startsWith(blacklistedPath)) {
                        // File path has a blacklisted path as a prefix -- don't scan it
                        scanFile = false;
                        break;
                    }
                }
                if (scanFile) {
                    // If USE_ZIPFILE_ENTRY_MODIFICATION_TIMES is true, use zipfile entry timestamps,
                    // otherwise use the modification time of the zipfile itself. Using zipfile entry
                    // timestamps assumes that the timestamp on zipfile entries was properly added, and
                    // that the clock of the machine adding the zipfile entries is in sync with the 
                    // clock used to timestamp regular file and directory entries in the current
                    // classpath. USE_ZIPFILE_ENTRY_MODIFICATION_TIMES is set to false by default,
                    // as zipfile entry timestamps are less trustworthy than filesystem timestamps.
                    final long entryTime = USE_ZIPFILE_ENTRY_MODIFICATION_TIMES //
                    ? entry.getTime()
                            : zipFileLastModified;
                    lastModified = Math.max(lastModified, entryTime);
                    if (entryTime > System.currentTimeMillis() && !timestampWarning) {
                        final String msg = zipfilePath + " contains modification timestamps after the current time";
                        // Log.warning(msg);
                        System.err.println(msg);
                        // Only warn once
                        timestampWarning = true;
                    }
                    if (!scanTimestampsOnly) {
                        // Match file paths against path patterns
                        for (final FilePathMatcher fileMatcher : filePathMatchers) {
                            if (fileMatcher.filePathMatches(path)) {
                                // There's a match -- open the file as a stream and
                                // call the match processor
                                try (final InputStream inputStream = zipFile.getInputStream(entry)) {
                                    fileMatcher.processMatch(path, inputStream, (int) entry.getSize());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Clear the classpath. */
    private void clearClasspath() {
        classpathElements.clear();
        classpathElementsSet.clear();
    }

    /** Add a classpath element. */
    private void addClasspathElement(String pathElement) {
        if (!pathElement.isEmpty()) {
            final File file = new File(pathElement);
            if (file.exists()) {
                // Canonicalize path so that we don't get stuck in a redirect loop due to softlinks
                String canonicalPath;
                try {
                    canonicalPath = file.getCanonicalPath();
                } catch (IOException | SecurityException e) {
                    canonicalPath = pathElement;
                }
                if (classpathElementsSet.add(canonicalPath)) {
                    // This is the first time this classpath element has been encountered
                    classpathElements.add(file);

                    // If this classpath element is a jar or zipfile, look for Class-Path entries in the manifest
                    // file. OpenJDK scans manifest-defined classpath elements after the jar that listed them, so
                    // we recursively call addClasspathElement if needed each time a jar is encountered. 
                    String pathLower = pathElement.toLowerCase();
                    if (pathLower.endsWith(".jar") || pathLower.endsWith(".zip")) {
                        String manifestUrlStr = "jar:file:" + pathElement + "!/META-INF/MANIFEST.MF";
                        try (InputStream stream = new URL(manifestUrlStr).openStream()) {
                            // Look for Class-Path keys within manifest files
                            Manifest manifest = new Manifest(stream);
                            String manifestClassPath = manifest.getMainAttributes().getValue("Class-Path");
                            if (manifestClassPath != null) {
                                // Class-Path elements are space-delimited
                                for (String manifestClassPathElement : manifestClassPath.split(" ")) {
                                    addClasspathElement(manifestClassPathElement);
                                }
                            }
                        } catch (IOException e) {
                        }
                    }
                } else if (verbose) {
                    Log.log("Classpath element does not exist: " + pathElement);
                }
            }
        }
    }

    /** Parse the system classpath. */
    private void parseSystemClasspath() {
        clearClasspath();

        // Look for all unique classloaders.
        // Keep them in an order that (hopefully) reflects the order in which class resolution occurs.
        ArrayList<ClassLoader> classLoaders = new ArrayList<>();
        HashSet<ClassLoader> classLoadersSet = new HashSet<>();
        classLoadersSet.add(Thread.currentThread().getContextClassLoader());
        classLoaders.add(Thread.currentThread().getContextClassLoader());
        if (classLoadersSet.add(ClassLoader.getSystemClassLoader())) {
            classLoaders.add(ClassLoader.getSystemClassLoader());
        }
        // Dirty method for looking for other classloaders on the call stack
        try {
            // Generate stacktrace
            throw new Exception();
        } catch (Exception e) {
            StackTraceElement[] stacktrace = e.getStackTrace();
            if (stacktrace.length >= 3) {
                StackTraceElement caller = stacktrace[2];
                ArrayList<ClassLoader> callerClassLoaders = new ArrayList<>();
                for (ClassLoader cl = caller.getClass().getClassLoader(); cl != null; cl = cl.getParent()) {
                    callerClassLoaders.add(cl);
                }
                // OpenJDK calls classloaders in a top-down order
                for (int i = callerClassLoaders.size() - 1; i >= 0; --i) {
                    ClassLoader cl = callerClassLoaders.get(i);
                    if (classLoadersSet.add(cl)) {
                        classLoaders.add(cl);
                    }
                }
            }
        }
        clearClasspath();
        
        // Another dirty method for looking for JAR "Class-Path" attribute jars within URLClassLoader
        // https://stackoverflow.com/questions/25729319/how-does-a-classloader-load-classes-reference-in-the-manifest-classpath
        try {
        	Field secretClasspathField;
        	Object secretClasspathObject;
        	HashMap<String, Object> secretLoaderMap;
        	
        	secretClasspathField = URLClassLoader.class.getDeclaredField("ucp");
        	secretClasspathField.setAccessible(true);
        	secretClasspathObject = secretClasspathField.get((URLClassLoader) ClassLoader.getSystemClassLoader());
        	
        	secretClasspathField = secretClasspathObject.getClass().getDeclaredField("lmap");
        	secretClasspathField.setAccessible(true);
        	secretLoaderMap = (HashMap<String, Object>) secretClasspathField.get(secretClasspathObject);

        	// secretLoaderMap is of Hashmap<String, URLClassPath.Jarloader>, String is a file url.
        	for(Object secretJar : secretLoaderMap.keySet()) {
        		URL jarURL = new URL((String) secretJar);
        		if("file".equals(jarURL.getProtocol())) {
        			addClasspathElement(jarURL.getFile());
        		}
        	}        	

        } catch (NoSuchFieldError | 
        		SecurityException | 
        		NoSuchFieldException | 
        		IllegalAccessException |
        		MalformedURLException e) {
        	// A lot can go wrong with this method. Should be stable for JDK6->8 (current)
        }

        // Get file paths for URLs of each classloader.
        for (ClassLoader cl : classLoaders) {
            if (cl != null) {
                for (URL url : ((URLClassLoader) cl).getURLs()) {
                    String protocol = url.getProtocol();
                    if (protocol == null || protocol.equals("file")) {
                        // "file:" URL found in classpath
                        addClasspathElement(url.getFile());
                    }
                }
            }
        }
    }

    /** Override the system classpath with a custom classpath to search. */
    public FastClasspathScanner overrideClasspath(String classpath) {
        clearClasspath();
        for (String pathElement : classpath.split(File.pathSeparator)) {
            addClasspathElement(pathElement);
        }
        return this;
    }

    /**
     * Get a list of unique elements on the classpath (directories and files) as File objects, preserving order.
     * Classpath elements that do not exist are not included in the list.
     */
    public ArrayList<File> getUniqueClasspathElements() {
        return classpathElements;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scans the classpath for matching files, and calls any match processors if a match is identified.
     * 
     * This method should be called after all required match processors have been added.
     * 
     * This method should be called before any "get" methods (e.g. getSubclassesOf()).
     */
    private FastClasspathScanner scan(final boolean scanTimestampsOnly) {
        if (verbose) {
            Log.log("*** Starting scan" + (scanTimestampsOnly ? " (scanning classpath timestamps only)" : "")
                    + " ***");
            Log.log("Classpath elements: " + getUniqueClasspathElements());
            Log.log("Whitelisted paths:  " + Arrays.toString(whitelistedPaths));
            Log.log("Blacklisted paths:  " + Arrays.toString(blacklistedPaths));
        }

        long scanStart = System.currentTimeMillis();

        if (!scanTimestampsOnly) {
            classGraphBuilder.reset();
        }

        try {
            // Iterate through path elements and recursively scan within each directory and zipfile
            for (final File pathElt : getUniqueClasspathElements()) {
                final String path = pathElt.getPath();
                if (verbose) {
                    Log.log("=> Scanning classpath element: " + path);
                }
                if (pathElt.isDirectory()) {
                    // Scan within dir path element
                    scanDir(pathElt, path.length() + 1, false, scanTimestampsOnly);
                } else if (pathElt.isFile()) {
                    final String pathLower = path.toLowerCase();
                    if (pathLower.endsWith(".jar") || pathLower.endsWith(".zip")) {
                        // Scan within jar/zipfile path element
                        scanZipfile(path, new ZipFile(pathElt), pathElt.lastModified(), scanTimestampsOnly);
                    } else {
                        // File listed directly on classpath
                        scanFile(pathElt, path, pathElt.getName(), scanTimestampsOnly);
                    }
                } else if (verbose) {
                    Log.log("Skipping non-file/non-dir on classpath: " + pathElt.getPath());
                }
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }

        if (!scanTimestampsOnly) {
            // Finalize class and interface DAGs
            classGraphBuilder.finalizeGraph();
            // Look for class and interface matches
            for (final ClassMatcher classMatcher : classMatchers) {
                classMatcher.lookForMatches();
            }
        }
        if (verbose) {
            Log.log("*** Scanning took: " + (System.currentTimeMillis() - scanStart) + " ms ***");
        }
        return this;
    }

    /**
     * Scans the classpath for matching files, and calls any match processors if a match is identified.
     * 
     * This method should be called after all required match processors have been added.
     * 
     * This method should be called before any "getNamesOf" methods (e.g. getNamesOfSubclassesOf()).
     */
    public FastClasspathScanner scan() {
        return scan(/* scanTimestampsOnly = */false);
    }

    /**
     * Returns true if the classpath contents have been changed since scan() was last called. Only considers
     * classpath prefixes whitelisted in the call to the constructor. Returns true if scan() has not yet been run.
     */
    public boolean classpathContentsModifiedSinceScan() {
        final long oldLastModified = this.lastModified;
        if (oldLastModified == 0) {
            return true;
        } else {
            scan(/* scanTimestampsOnly = */true);
            final long newLastModified = this.lastModified;
            return newLastModified > oldLastModified;
        }
    }

    /**
     * Returns the maximum "last modified" timestamp in the classpath (in epoch millis), or zero if scan() has not
     * yet been called (or if nothing was found on the classpath).
     * 
     * The returned timestamp should be less than the current system time if the timestamps of files on the
     * classpath and the system time are accurate. Therefore, if anything changes on the classpath, this value
     * should increase.
     */
    public long classpathContentsLastModifiedTime() {
        return this.lastModified;
    }

    /** Switch on verbose mode (prints debug info to System.out). */
    public FastClasspathScanner verbose() {
        this.verbose = true;
        return this;
    }
}
