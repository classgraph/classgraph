/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassfileBinaryParser;
import io.github.lukehutch.fastclasspathscanner.classgraph.ClassGraphBuilder;
import io.github.lukehutch.fastclasspathscanner.classpath.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassAnnotationMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.ClassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchContentsProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchContentsProcessorWithContext;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessorWithContext;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.InterfaceMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubclassMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.SubinterfaceMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.RecursiveScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.RecursiveScanner.FilePathMatcher;
import io.github.lukehutch.fastclasspathscanner.scanner.RecursiveScanner.FilePathTester;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.Log;

/**
 * Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary format
 * directly rather than by using reflection. (Reflection causes the classloader to load each class, which can take
 * an order of magnitude more time than parsing the classfile directly.) See the accompanying README.md file for
 * documentation.
 */
public class FastClasspathScanner {
    /** The scanning specification (whitelisted and blacklisted packages, etc.). */
    private final ScanSpec scanSpec;

    /** The classpath finder. */
    private final ClasspathFinder classpathFinder;

    /** The class that recursively scans the classpath. */
    private final RecursiveScanner recursiveScanner;

    /**
     * A map from (className + "." + staticFinalFieldName) to StaticFinalFieldMatchProcessor(s) that should be
     * called if that class name and static final field name is encountered with a static constant initializer
     * during scan.
     */
    private final HashMap<String, ArrayList<StaticFinalFieldMatchProcessor>> //
    fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors = new HashMap<>();

    /** A map from className to a list of static final fields to match with StaticFinalFieldMatchProcessors. */
    private final HashMap<String, HashSet<String>> classNameToStaticFinalFieldsToMatch = new HashMap<>();

    /**
     * A map from class name to the information extracted from the class. If the class name is encountered more than
     * once (i.e. if the same class is defined in multiple classpath elements), the second and subsequent class
     * definitions are ignored, because they are masked by the earlier definition.
     */
    private final HashMap<String, ClassInfo> classNameToClassInfo = new HashMap<>();

    /** The class, interface and annotation graph builder. */
    private ClassGraphBuilder classGraphBuilder;

    /** An interface used for testing if a class matches specified criteria. */
    public static interface ClassMatcher {
        public abstract void lookForMatches();
    }

    /** A list of class matchers to call once all classes have been read in from classpath. */
    private final ArrayList<ClassMatcher> classMatchers = new ArrayList<>();

    /** If set to true, print info while scanning */
    public static boolean verbose = false;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructs a FastClasspathScanner instance. You can pass a scanning specification to the constructor to
     * describe what should be scanned. This prevents irrelevant classpath entries from being unecessarily scanned,
     * which can be time-consuming. (Note that calling the constructor does not start the scan, you must separately
     * call .scan() to perform the actual scan.)
     * 
     * @param scanSpec
     *            The constructor accepts a list of whitelisted package prefixes / jar names to scan, as well as
     *            blacklisted packages/jars not to scan, where blacklisted entries are prefixed with the '-'
     *            character. See https://github.com/lukehutch/fast-classpath-scanner#constructor for info.
     */
    public FastClasspathScanner(final String... scanSpec) {
        this.classpathFinder = new ClasspathFinder();
        this.scanSpec = new ScanSpec(scanSpec);
        this.recursiveScanner = new RecursiveScanner(classpathFinder, this.scanSpec);
        FastClasspathScanner.verbose = false; // By default

        // Read classfile headers for all filenames ending in ".class" on classpath
        final ScanSpec scanSpecParsed = this.scanSpec;
        this.matchFilenameExtension("class", new FileMatchProcessor() {
            @Override
            public void processMatch(final String relativePath, final InputStream inputStream, //
                    final int lengthBytes) throws IOException {
                ClassfileBinaryParser.readClassInfoFromClassfileHeader(relativePath, inputStream,
                        classNameToStaticFinalFieldsToMatch, scanSpecParsed, classNameToClassInfo);
            }
        });
    }

    /**
     * Add an extra ClassLoaderHandler. Needed if the ServiceLoader framework is not able to find the
     * ClassLoaderHandler for your specific ClassLoader, or if you want to manually register your own
     * ClassLoaderHandler rather than using the ServiceLoader framework.
     */
    public void registerClassLoaderHandler(final ClassLoaderHandler extraClassLoaderHandler) {
        classpathFinder.registerClassLoaderHandler(extraClassLoaderHandler);
    }

    /** Override the automatically-detected classpath with a custom search path. */
    public FastClasspathScanner overrideClasspath(final String classpath) {
        classpathFinder.overrideClasspath(classpath);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Call the classloader using Class.forName(className). Re-throws classloading exceptions as RuntimeException.
     */
    private <T> Class<? extends T> loadClass(final String className) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends T> cls = (Class<? extends T>) Class.forName(className);
            return cls;
        } catch (ClassNotFoundException | NoClassDefFoundError | ExceptionInInitializerError e) {
            throw new RuntimeException("Exception while loading or initializing class " + className, e);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Checks that the named class is not blacklisted. Throws IllegalArgumentException otherwise. (This is to
     * prevent significant overhead of tracking fields of common types like java.lang.String etc.)
     */
    private void checkClassNameIsNotBlacklisted(final String className) {
        if (!scanSpec.classIsNotBlacklisted(className)) {
            throw new IllegalArgumentException("Can't scan for " + className + ", it is in a blacklisted package. "
                    + "You can explicitly override this by naming the class in the scan spec when you call the "
                    + FastClasspathScanner.class.getSimpleName() + " constructor.");
        }
    }

    /**
     * Check a class is an annotation, and that it is in a whitelisted package. Throws IllegalArgumentException
     * otherwise. Returns the name of the annotation.
     */
    private String annotationName(final Class<?> annotation) {
        final String annotationName = annotation.getName();
        checkClassNameIsNotBlacklisted(annotationName);
        if (!annotation.isAnnotation()) {
            throw new IllegalArgumentException(annotationName + " is not an annotation");
        }
        return annotation.getName();
    }

    /**
     * Check each element of an array of classes is an annotation, and that it is in a whitelisted package. Throws
     * IllegalArgumentException otherwise. Returns the names of the classes as an array of strings.
     */
    private String[] annotationNames(final Class<?>[] annotations) {
        final String[] annotationNames = new String[annotations.length];
        for (int i = 0; i < annotations.length; i++) {
            annotationNames[i] = annotationName(annotations[i]);
        }
        return annotationNames;
    }

    /**
     * Check a class is an interface, and that it is in a whitelisted package. Throws IllegalArgumentException
     * otherwise. Returns the name of the interface.
     */
    private String interfaceName(final Class<?> iface) {
        final String ifaceName = iface.getName();
        checkClassNameIsNotBlacklisted(ifaceName);
        if (!iface.isInterface()) {
            throw new IllegalArgumentException(ifaceName + " is not an interface");
        }
        return iface.getName();
    }

    /**
     * Check each element of an array of classes is an interface, and that it is in a whitelisted package. Throws
     * IllegalArgumentException otherwise. Returns the names of the classes as an array of strings.
     */
    private String[] interfaceNames(final Class<?>[] interfaces) {
        final String[] interfaceNames = new String[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) {
            interfaceNames[i] = interfaceName(interfaces[i]);
        }
        return interfaceNames;
    }

    /**
     * Check a class is a regular class or interface and not an annotation, and that it is in a whitelisted package.
     * Throws IllegalArgumentException otherwise. Returns the name of the class or interface.
     */
    private String classOrInterfaceName(final Class<?> classOrInterface) {
        final String classOrIfaceName = classOrInterface.getName();
        checkClassNameIsNotBlacklisted(classOrIfaceName);
        if (classOrInterface.isAnnotation()) {
            throw new IllegalArgumentException(
                    classOrIfaceName + " is an annotation, not a regular class or interface");
        }
        return classOrInterface.getName();
    }

    /**
     * Check a class is a standard class (not an interface or annotation), and that it is in a whitelisted package.
     * Returns the name of the class if it is a standard class and it is in a whitelisted package, otherwise throws
     * an IllegalArgumentException.
     */
    private String standardClassName(final Class<?> cls) {
        final String className = cls.getName();
        checkClassNameIsNotBlacklisted(className);
        if (cls.isAnnotation()) {
            throw new IllegalArgumentException(className + " is an annotation, not a standard class");
        } else if (cls.isInterface()) {
            throw new IllegalArgumentException(cls.getName() + " is an interface, not a standard class");
        }
        return className;
    }

    /**
     * Check a class is in a whitelisted package. Returns the name of the class if it is in a whitelisted package,
     * otherwise throws an IllegalArgumentException.
     */
    private String className(final Class<?> cls) {
        final String className = cls.getName();
        checkClassNameIsNotBlacklisted(className);
        return className;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the sorted names of all standard classes, interface classes and annotation classes found in a
     * whitelisted (and non-blacklisted) package during the scan.
     */
    public List<String> getNamesOfAllClasses() {
        return getScanResults().getNamesOfAllClasses();
    }

    /**
     * Returns the sorted names of all standard classes found in a whitelisted (and non-blacklisted) package during
     * the scan.
     */
    public List<String> getNamesOfAllStandardClasses() {
        return getScanResults().getNamesOfAllStandardClasses();
    }

    /**
     * Returns the sorted names of all interface classes (interfaces) found in a whitelisted (and non-blacklisted)
     * package during the scan.
     */
    public List<String> getNamesOfAllInterfaceClasses() {
        return getScanResults().getNamesOfAllInterfaceClasses();
    }

    /**
     * Returns the sorted names of all annotation classes found in a whitelisted (and non-blacklisted) package
     * during the scan.
     */
    public List<String> getNamesOfAllAnnotationClasses() {
        return getScanResults().getNamesOfAllAnnotationClasses();
    }

    /**
     * Calls the provided ClassEnumerationMatchProcessor for all standard classes, interfaces and annotations found
     * in whitelisted packages on the classpath. Calls the class loader on each matching class (using
     * Class.forName()) before calling the ClassEnumerationMatchProcessor.
     * 
     * @param classEnumerationMatchProcessor
     *            the ClassEnumerationMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchAllClasses(final ClassMatchProcessor classEnumerationMatchProcessor) {
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                for (final String className : getNamesOfAllClasses()) {
                    if (verbose) {
                        Log.log("Enumerating class: " + className);
                    }
                    // Call classloader
                    final Class<?> cls = loadClass(className);
                    // Process match
                    classEnumerationMatchProcessor.processMatch(cls);
                }
            }
        });
        return this;
    }

    /**
     * Calls the provided ClassEnumerationMatchProcessor for all standard classes (i.e. non-interface,
     * non-annotation classes) found in whitelisted packages on the classpath. Calls the class loader on each
     * matching class (using Class.forName()) before calling the ClassEnumerationMatchProcessor.
     * 
     * @param classEnumerationMatchProcessor
     *            the ClassEnumerationMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchAllStandardClasses(final ClassMatchProcessor classEnumerationMatchProcessor) {
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                for (final String className : getNamesOfAllStandardClasses()) {
                    if (verbose) {
                        Log.log("Enumerating standard class: " + className);
                    }
                    // Call classloader
                    final Class<?> cls = loadClass(className);
                    // Process match
                    classEnumerationMatchProcessor.processMatch(cls);
                }
            }
        });
        return this;
    }

    /**
     * Calls the provided ClassEnumerationMatchProcessor for all interface classes (interface definitions) found in
     * whitelisted packages on the classpath. Calls the class loader on each matching interface class (using
     * Class.forName()) before calling the ClassEnumerationMatchProcessor.
     * 
     * @param classEnumerationMatchProcessor
     *            the ClassEnumerationMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchAllInterfaceClasses(final ClassMatchProcessor classEnumerationMatchProcessor) {
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                for (final String className : getNamesOfAllInterfaceClasses()) {
                    if (verbose) {
                        Log.log("Enumerating interface class: " + className);
                    }
                    // Call classloader
                    final Class<?> cls = loadClass(className);
                    // Process match
                    classEnumerationMatchProcessor.processMatch(cls);
                }
            }
        });
        return this;
    }

    /**
     * Calls the provided ClassEnumerationMatchProcessor for all annotation classes (annotation definitions) found
     * in whitelisted packages on the classpath. Calls the class loader on each matching annotation class (using
     * Class.forName()) before calling the ClassEnumerationMatchProcessor.
     * 
     * @param classEnumerationMatchProcessor
     *            the ClassEnumerationMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchAllAnnotationClasses(
            final ClassMatchProcessor classEnumerationMatchProcessor) {
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                for (final String className : getNamesOfAllAnnotationClasses()) {
                    if (verbose) {
                        Log.log("Enumerating annotation class: " + className);
                    }
                    // Call classloader
                    final Class<?> cls = loadClass(className);
                    // Process match
                    classEnumerationMatchProcessor.processMatch(cls);
                }
            }
        });
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubclassMatchProcessor if classes are found on the classpath that extend the specified
     * superclass. Calls the class loader on each matching class (using Class.forName()) before calling the
     * SubclassMatchProcessor. Does not call the classloader on non-matching classes or interfaces.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @param subclassMatchProcessor
     *            the SubclassMatchProcessor to call when a match is found.
     */
    public <T> FastClasspathScanner matchSubclassesOf(final Class<T> superclass,
            final SubclassMatchProcessor<T> subclassMatchProcessor) {
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                final String superclassName = standardClassName(superclass);
                for (final String subclassName : getNamesOfSubclassesOf(superclassName)) {
                    if (verbose) {
                        Log.log("Found subclass of " + superclassName + ": " + subclassName);
                    }
                    // Call classloader
                    final Class<? extends T> cls = loadClass(subclassName);
                    // Process match
                    subclassMatchProcessor.processMatch(cls);
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
    public List<String> getNamesOfSubclassesOf(final Class<?> superclass) {
        return getNamesOfSubclassesOf(standardClassName(superclass));
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
        return getScanResults().getNamesOfSubclassesOf(superclassName);
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
    public List<String> getNamesOfSuperclassesOf(final Class<?> subclass) {
        return getNamesOfSuperclassesOf(standardClassName(subclass));
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
        return getScanResults().getNamesOfSuperclassesOf(subclassName);
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
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                final String superinterfaceName = interfaceName(superinterface);
                for (final String subinterfaceName : getNamesOfSubinterfacesOf(superinterfaceName)) {
                    if (verbose) {
                        Log.log("Found subinterface of " + superinterfaceName + ": " + subinterfaceName);
                    }
                    // Call classloader
                    final Class<? extends T> cls = loadClass(subinterfaceName);
                    // Process match
                    subinterfaceMatchProcessor.processMatch(cls);
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
    public List<String> getNamesOfSubinterfacesOf(final Class<?> superInterface) {
        return getNamesOfSubinterfacesOf(interfaceName(superInterface));
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
        return getScanResults().getNamesOfSubinterfacesOf(superInterfaceName);
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
    public List<String> getNamesOfSuperinterfacesOf(final Class<?> subInterface) {
        return getNamesOfSuperinterfacesOf(interfaceName(subInterface));
    }

    /**
     * Returns the names of interfaces on the classpath that are superinterfaces of a given subinterface. Should be
     * called after scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to
     * the scanner before the call to scan(). Does not call the classloader on the matching interfaces, just returns
     * their
     * 
     * @param subinterfaceName
     *            The name of the superinterface to match (i.e. the name of the interface that subinterfaces need to
     *            extend).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public List<String> getNamesOfSuperinterfacesOf(final String subinterfaceName) {
        return getScanResults().getNamesOfSuperinterfacesOf(subinterfaceName);
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
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                final String implementedInterfaceName = interfaceName(implementedInterface);
                for (final String implClass : getNamesOfClassesImplementing(implementedInterfaceName)) {
                    if (verbose) {
                        Log.log("Found class implementing interface " + implementedInterfaceName + ": "
                                + implClass);
                    }
                    // Call classloader
                    final Class<? extends T> cls = loadClass(implClass);
                    // Process match
                    interfaceMatchProcessor.processMatch(cls);
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
    public List<String> getNamesOfClassesImplementing(final Class<?> implementedInterface) {
        return getNamesOfClassesImplementing(interfaceName(implementedInterface));
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
        return getScanResults().getNamesOfClassesImplementing(implementedInterfaceName);
    }

    /**
     * Returns the names of classes on the classpath that implement (or have superclasses that implement) all of the
     * specified interfaces or their subinterfaces. Should be called after scan(), and returns matching interfaces
     * whether or not an InterfaceMatchProcessor was added to the scanner before the call to scan(). Does not call
     * the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterfaces
     *            The name of the interfaces that classes need to implement.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfClassesImplementingAllOf(final Class<?>... implementedInterfaces) {
        return getNamesOfClassesImplementingAllOf(interfaceNames(implementedInterfaces));
    }

    /**
     * Returns the names of classes on the classpath that implement (or have superclasses that implement) all of the
     * specified interfaces or their subinterfaces. Should be called after scan(), and returns matching interfaces
     * whether or not an InterfaceMatchProcessor was added to the scanner before the call to scan(). Does not call
     * the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterfaceNames
     *            The name of the interfaces that classes need to implement.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public List<String> getNamesOfClassesImplementingAllOf(final String... implementedInterfaceNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (int i = 0; i < implementedInterfaceNames.length; i++) {
            final String implementedInterfaceName = implementedInterfaceNames[i];
            final List<String> namesOfImplementingClasses = getNamesOfClassesImplementing(implementedInterfaceName);
            if (i == 0) {
                classNames.addAll(namesOfImplementingClasses);
            } else {
                classNames.retainAll(namesOfImplementingClasses);
            }
        }
        return new ArrayList<>(classNames);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassMatchProcessor for classes on the classpath that have a field of the given type.
     * Matches classes that have fields of the given type, array fields with an element type of the given type, and
     * fields of parameterized type that have a type parameter of the given type. (Does not call the classloader on
     * non-matching classes.) The field type must be declared in a package that is whitelisted (and not
     * blacklisted).
     * 
     * @param fieldType
     *            The type of the field to match..
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public <T> FastClasspathScanner matchClassesWithFieldOfType(final Class<T> fieldType,
            final ClassMatchProcessor classMatchProcessor) {
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                final String fieldTypeName = className(fieldType);
                for (final String klass : getNamesOfClassesWithFieldOfType(fieldTypeName)) {
                    if (verbose) {
                        Log.log("Found class with field of type " + fieldTypeName + ": " + klass);
                    }
                    // Call classloader
                    final Class<? extends T> cls = loadClass(klass);
                    // Process match
                    classMatchProcessor.processMatch(cls);
                }
            }
        });
        return this;
    }

    /**
     * Returns the names of classes that have a field of the given type. Returns classes that have fields of the
     * named type itself, array fields with an element type that matches the named type, and fields of parameterized
     * type that have a type parameter of the named type. The field type must be declared in a package that is
     * whitelisted (and not blacklisted).
     */
    public List<String> getNamesOfClassesWithFieldOfType(final String fieldTypeName) {
        return getScanResults().getNamesOfClassesWithFieldOfType(fieldTypeName);
    }

    /**
     * Returns the names of classes that have a field of the given type. Returns classes that have fields with the
     * same type as the requested type, array fields with an element type that matches the requested type, and
     * fields of parameterized type that have a type parameter of the requested type. The field type must be
     * declared in a package that is whitelisted (and not blacklisted).
     */
    public List<String> getNamesOfClassesWithFieldOfType(final Class<?> fieldType) {
        final String fieldTypeName = fieldType.getName();
        checkClassNameIsNotBlacklisted(fieldTypeName);
        return getScanResults().getNamesOfClassesWithFieldOfType(fieldTypeName);
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
        classMatchers.add(new ClassMatcher() {
            @Override
            public void lookForMatches() {
                final String annotationName = annotationName(annotation);
                for (final String classWithAnnotation : getNamesOfClassesWithAnnotation(annotationName)) {
                    if (verbose) {
                        Log.log("Found class with annotation " + annotationName + ": " + classWithAnnotation);
                    }
                    // Call classloader
                    final Class<?> cls = loadClass(classWithAnnotation);
                    // Process match
                    classAnnotationMatchProcessor.processMatch(cls);
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
    public List<String> getNamesOfClassesWithAnnotation(final Class<?> annotation) {
        return getNamesOfClassesWithAnnotation(annotationName(annotation));
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
        return getScanResults().getNamesOfClassesWithAnnotation(annotationName);
    }

    /**
     * Returns the names of classes on the classpath that have all of the specified annotations. Should be called
     * after scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param annotations
     *            The annotations.
     * @return A list of the names of classes that have all of the annotations, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAllOf(final Class<?>... annotations) {
        return getNamesOfClassesWithAnnotationsAllOf(annotationNames(annotations));
    }

    /**
     * Returns the names of classes on the classpath that have all of the specified annotations. Should be called
     * after scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param annotationNames
     *            The annotation names.
     * @return A list of the names of classes that have all of the annotations, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAllOf(final String... annotationNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (int i = 0; i < annotationNames.length; i++) {
            final String annotationName = annotationNames[i];
            final List<String> namesOfClassesWithMetaAnnotation = getNamesOfClassesWithAnnotation(annotationName);
            if (i == 0) {
                classNames.addAll(namesOfClassesWithMetaAnnotation);
            } else {
                classNames.retainAll(namesOfClassesWithMetaAnnotation);
            }
        }
        return new ArrayList<>(classNames);
    }

    /**
     * Returns the names of classes on the classpath that have any of the specified annotations. Should be called
     * after scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param annotations
     *            The annotations.
     * @return A list of the names of classes that have one or more of the annotations, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAnyOf(final Class<?>... annotations) {
        return getNamesOfClassesWithAnnotationsAnyOf(annotationNames(annotations));
    }

    /**
     * Returns the names of classes on the classpath that have any of the specified annotations. Should be called
     * after scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param annotationNames
     *            The annotation names.
     * @return A list of the names of classes that have one or more of the annotations, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotationsAnyOf(final String... annotationNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (final String annotationName : annotationNames) {
            classNames.addAll(getNamesOfClassesWithAnnotation(annotationName));
        }
        return new ArrayList<>(classNames);
    }

    /**
     * Return the names of all annotations that are annotated with the specified meta-annotation.
     * 
     * @param metaAnnotation
     *            The specified meta-annotation.
     * @return A list of the names of annotations that are annotated with the specified meta annotation, or the
     *         empty list if none.
     */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation(final Class<?> metaAnnotation) {
        return getNamesOfAnnotationsWithMetaAnnotation(annotationName(metaAnnotation));
    }

    /**
     * Return the names of all annotations that are annotated with the specified meta-annotation.
     * 
     * @param metaAnnotationName
     *            The name of the specified meta-annotation.
     * @return A list of the names of annotations that are annotated with the specified meta annotation, or the
     *         empty list if none.
     */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation(final String metaAnnotationName) {
        return getScanResults().getNamesOfAnnotationsWithMetaAnnotation(metaAnnotationName);
    }

    /**
     * Return the names of all annotations and meta-annotations on the specified class or interface.
     * 
     * @param classOrInterface
     *            The class or interface.
     * @return A list of the names of annotations and meta-annotations on the class, or the empty list if none.
     */
    public List<String> getNamesOfAnnotationsOnClass(final Class<?> classOrInterface) {
        return getNamesOfAnnotationsOnClass(classOrInterfaceName(classOrInterface));
    }

    /**
     * Return the names of all annotations and meta-annotations on the specified class or interface.
     * 
     * @param classOrInterfaceName
     *            The name of the class or interface.
     * @return A list of the names of annotations and meta-annotations on the class, or the empty list if none.
     */
    public List<String> getNamesOfAnnotationsOnClass(final String classOrInterfaceName) {
        return getScanResults().getNamesOfAnnotationsOnClass(classOrInterfaceName);
    }

    /**
     * Return the names of all meta-annotations on the specified annotation.
     * 
     * @param annotation
     *            The specified annotation.
     * @return A list of the names of meta-annotations on the specified annotation, or the empty list if none.
     */
    public List<String> getNamesOfMetaAnnotationsOnAnnotation(final Class<?> annotation) {
        return getNamesOfMetaAnnotationsOnAnnotation(annotationName(annotation));
    }

    /**
     * Return the names of all meta-annotations on the specified annotation.
     * 
     * @param annotationName
     *            The name of the specified annotation.
     * @return A list of the names of meta-annotations on the specified annotation, or the empty list if none.
     */
    public List<String> getNamesOfMetaAnnotationsOnAnnotation(final String annotationName) {
        return getScanResults().getNamesOfMetaAnnotationsOnAnnotation(annotationName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a StaticFinalFieldMatchProcessor that should be called if a static final field with the given name is
     * encountered with a constant initializer value while reading a classfile header.
     */
    private void addStaticFinalFieldProcessor(final String className, final String fieldName,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        final String fullyQualifiedFieldName = className + "." + fieldName;
        ArrayList<StaticFinalFieldMatchProcessor> matchProcessorList = //
                fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors.get(fullyQualifiedFieldName);
        if (matchProcessorList == null) {
            fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors.put(fullyQualifiedFieldName,
                    matchProcessorList = new ArrayList<>(1));
        }
        matchProcessorList.add(staticFinalFieldMatchProcessor);
        HashSet<String> staticFinalFieldsToMatch = classNameToStaticFinalFieldsToMatch.get(className);
        if (staticFinalFieldsToMatch == null) {
            classNameToStaticFinalFieldsToMatch.put(className, staticFinalFieldsToMatch = new HashSet<>());
        }
        staticFinalFieldsToMatch.add(fieldName);
    }

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
    public FastClasspathScanner matchStaticFinalFieldNames(final Set<String> fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        for (final String fullyQualifiedFieldName : fullyQualifiedStaticFinalFieldNames) {
            final int lastDotIdx = fullyQualifiedFieldName.lastIndexOf('.');
            if (lastDotIdx > 0) {
                final String className = fullyQualifiedFieldName.substring(0, lastDotIdx);
                final String fieldName = fullyQualifiedFieldName.substring(lastDotIdx + 1);
                addStaticFinalFieldProcessor(className, fieldName, staticFinalFieldMatchProcessor);
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

    /**
     * Wrap a FileMatchContentsProcessorWithContext in a FileMatchProcessorWithContext that reads the entire stream
     * content and passes it to the wrapped class as a byte array.
     */
    private static FileMatchProcessorWithContext fetchStreamContentsAndSendTo(
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        return new FileMatchProcessorWithContext() {
            @Override
            public void processMatch(final File classpathElt, final String relativePath,
                    final InputStream inputStream, final int lengthBytes) throws IOException {
                // Read the file contents into a byte[] array
                final byte[] contents = new byte[lengthBytes];
                final int bytesRead = Math.max(0, inputStream.read(contents));
                // For safety, truncate the array if the file was truncated before we finish reading it
                final byte[] contentsRead = bytesRead == lengthBytes ? contents
                        : Arrays.copyOf(contents, bytesRead);
                // Pass file contents to the wrapped FileMatchContentsProcessor
                fileMatchContentsProcessorWithContext.processMatch(classpathElt, relativePath, contentsRead);
            }
        };
    }

    /** Wrap a FileMatchProcessor in a FileMatchProcessorWithContext, and ignore the classpath context. */
    private static FileMatchProcessorWithContext ignoreClasspathContext( //
            final FileMatchProcessor fileMatchProcessor) {
        return new FileMatchProcessorWithContext() {
            @Override
            public void processMatch(final File classpathElement, final String relativePath,
                    final InputStream inputStream, final int lengthBytes) throws IOException {
                fileMatchProcessor.processMatch(relativePath, inputStream, lengthBytes);
            }
        };
    }

    /**
     * Wrap a FileMatchContentsProcessor in a FileMatchContentsProcessorWithContext, and ignore the classpath
     * context.
     */
    private static FileMatchContentsProcessorWithContext ignoreClasspathContext( //
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        return new FileMatchContentsProcessorWithContext() {
            @Override
            public void processMatch(final File classpathElement, final String relativePath,
                    final byte[] fileContents) throws IOException {
                fileMatchContentsProcessor.processMatch(relativePath, fileContents);
            }
        };
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath with the given regexp
     * pattern in their path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        recursiveScanner.addFilePathMatcher(new FilePathMatcher(new FilePathTester() {
            private final Pattern pattern = Pattern.compile(pathRegexp);

            @Override
            public boolean filePathMatches(final File classpathElt, final String relativePath) {
                return pattern.matcher(relativePath).matches();
            }
        }, fileMatchProcessorWithContext));
        return this;
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
        return matchFilenamePattern(pathRegexp, ignoreClasspathContext(fileMatchProcessor));
    }

    /**
     * Calls the given FileMatchContentsProcessorWithContext if files are found on the classpath with the given
     * regexp pattern in their path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchContentsProcessorWithContext
     *            The FileMatchContentsProcessorWithContext to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        return matchFilenamePattern(pathRegexp, fetchStreamContentsAndSendTo( //
                fileMatchContentsProcessorWithContext));
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
        return matchFilenamePattern(pathRegexp, ignoreClasspathContext(fileMatchContentsProcessor));
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that exactly match the
     * given relative path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        recursiveScanner.addFilePathMatcher(new FilePathMatcher(new FilePathTester() {
            @Override
            public boolean filePathMatches(final File classpathElt, final String relativePath) {
                return relativePath.equals(relativePathToMatch);
            }
        }, fileMatchProcessorWithContext));
        return this;
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
        return matchFilenamePath(relativePathToMatch, ignoreClasspathContext(fileMatchProcessor));
    }

    /**
     * Calls the given FileMatchContentsProcessorWithContext if files are found on the classpath that exactly match
     * the given relative path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchContentsProcessorWithContext
     *            The FileMatchContentsProcessorWithContext to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        return matchFilenamePath(relativePathToMatch,
                fetchStreamContentsAndSendTo(fileMatchContentsProcessorWithContext));
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
        return matchFilenamePath(relativePathToMatch, ignoreClasspathContext(fileMatchContentsProcessor));
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that exactly match the
     * given path leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        recursiveScanner.addFilePathMatcher(new FilePathMatcher(new FilePathTester() {
            private final String leafToMatch = pathLeafToMatch.substring(pathLeafToMatch.lastIndexOf('/') + 1);

            @Override
            public boolean filePathMatches(final File classpathElt, final String relativePath) {
                final String relativePathLeaf = relativePath.substring(relativePath.lastIndexOf('/') + 1);
                return relativePathLeaf.equals(leafToMatch);
            }
        }, fileMatchProcessorWithContext));
        return this;
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
        return matchFilenamePathLeaf(pathLeafToMatch, ignoreClasspathContext(fileMatchProcessor));
    }

    /**
     * Calls the given FileMatchContentsProcessorWithContext if files are found on the classpath that exactly match
     * the given path leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchContentsProcessorWithContext
     *            The FileMatchContentsProcessorWithContext to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        return matchFilenamePathLeaf(pathLeafToMatch,
                fetchStreamContentsAndSendTo(fileMatchContentsProcessorWithContext));
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
        return matchFilenamePathLeaf(pathLeafToMatch, ignoreClasspathContext(fileMatchContentsProcessor));
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that have the given file
     * extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html" and "WIDGET.HTML".
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     */
    public FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        recursiveScanner.addFilePathMatcher(new FilePathMatcher(new FilePathTester() {
            private final String suffixToMatch = "." + extensionToMatch.toLowerCase();

            @Override
            public boolean filePathMatches(final File classpathElt, final String relativePath) {
                return relativePath.toLowerCase().endsWith(suffixToMatch);
            }
        }, fileMatchProcessorWithContext));
        return this;
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that have the given file extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html" and "WIDGET.HTML".
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        return matchFilenameExtension(extensionToMatch, ignoreClasspathContext(fileMatchProcessor));
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that have the given file
     * extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html".
     * @param fileMatchContentsProcessorWithContext
     *            The FileMatchContentsProcessorWithContext to call when each match is found.
     */
    public FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        return matchFilenameExtension(extensionToMatch,
                fetchStreamContentsAndSendTo(fileMatchContentsProcessorWithContext));
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
        return matchFilenameExtension(extensionToMatch, ignoreClasspathContext(fileMatchContentsProcessor));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist are not included in the list.
     */
    public List<File> getUniqueClasspathElements() {
        return classpathFinder.getUniqueClasspathElements();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Generates a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * sizeX and sizeY parameters are the image output size to use (in inches) when GraphViz is asked to render the
     * .dot file.
     */
    public String generateClassGraphDotFile(final float sizeX, final float sizeY) {
        return getScanResults().generateClassGraphDotFile(sizeX, sizeY);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns the ClassGraphBuilder created by calling .scan(), or throws RuntimeException if .scan() has not yet
     * been called.
     */
    public ClassGraphBuilder getScanResults() {
        if (classGraphBuilder == null) {
            throw new RuntimeException("Must call .scan() before attempting to get the results of the scan");
        }
        return classGraphBuilder;
    }

    /**
     * Scans the classpath for matching files, and calls any match processors if a match is identified.
     * 
     * This method should be called after all required match processors have been added.
     * 
     * This method should be called before any "getNamesOf" methods (e.g. getNamesOfSubclassesOf()).
     */
    public FastClasspathScanner scan() {
        if (FastClasspathScanner.verbose) {
            Log.log("Classpath elements: " + this.classpathFinder.getUniqueClasspathElements());
        }

        final long scanStart = System.currentTimeMillis();

        classNameToClassInfo.clear();

        // Scan classpath, calling FilePathMatchers if any matching paths are found, including the matcher
        // that calls the classfile binary parser when the extension ".class" is found on a filename,
        // producing a ClassInfo object for each encountered class.
        recursiveScanner.scan();

        // Build class, interface and annotation graph out of all the ClassInfo objects.
        classGraphBuilder = new ClassGraphBuilder(classNameToClassInfo);

        // Call any class, interface and annotation MatchProcessors
        for (final ClassMatcher classMatcher : classMatchers) {
            classMatcher.lookForMatches();
        }

        // Call static final field match processors on matching fields
        for (final ClassInfo classInfo : classNameToClassInfo.values()) {
            if (classInfo.fieldValues != null) {
                for (final Entry<String, Object> ent : classInfo.fieldValues.entrySet()) {
                    final String fieldName = ent.getKey();
                    final Object constValue = ent.getValue();
                    final String fullyQualifiedFieldName = classInfo.className + "." + fieldName;
                    final ArrayList<StaticFinalFieldMatchProcessor> staticFinalFieldMatchProcessors = //
                            fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors.get(fullyQualifiedFieldName);
                    if (staticFinalFieldMatchProcessors != null) {
                        if (FastClasspathScanner.verbose) {
                            Log.log("Found static final field " + classInfo.className + "." + fieldName + " = "
                                    + constValue);
                        }
                        for (final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor : //
                        staticFinalFieldMatchProcessors) {
                            staticFinalFieldMatchProcessor.processMatch(classInfo.className, fieldName, constValue);
                        }
                    }
                }
            }
        }

        if (FastClasspathScanner.verbose) {
            Log.log("*** Time taken by .scan(): " //
                    + (System.currentTimeMillis() - scanStart) + " ms ***");
        }
        return this;
    }

    /**
     * Returns true if the classpath contents have been changed since scan() was last called. Only considers
     * classpath prefixes whitelisted in the call to the constructor. Returns true if scan() has not yet been run.
     * Much faster than standard classpath scanning, because only timestamps are checked, and jarfiles don't have to
     * be opened.
     */
    public boolean classpathContentsModifiedSinceScan() {
        final long scanStart = System.currentTimeMillis();

        final boolean modified = recursiveScanner.classpathContentsModifiedSinceScan();

        if (FastClasspathScanner.verbose) {
            Log.log("*** Time taken by .classpathContentsModifiedSinceScan(): "
                    + (System.currentTimeMillis() - scanStart) + " ms ***");
        }
        return modified;
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
        return recursiveScanner.classpathContentsLastModifiedTime();
    }

    /** Switch on verbose mode (prints debug info to System.out). */
    public FastClasspathScanner verbose() {
        verbose = true;
        return this;
    }
}
