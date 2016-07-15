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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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
import io.github.lukehutch.fastclasspathscanner.scanner.ScanExecutor;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanInterruptedException;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.VersionFinder;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;

/**
 * Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary format
 * directly rather than by using reflection. (Reflection causes the classloader to load each class, which can take
 * an order of magnitude more time than parsing the classfile directly.) See the accompanying README.md file for
 * documentation.
 */
public class FastClasspathScanner {
    /** The scanning specification (whitelisted and blacklisted packages, etc.), as passed into the constructor. */
    private final String[] scanSpecArgs;

    /** The scanning specification, parsed. */
    private ScanSpec scanSpec;

    /** The unique classpath elements. */
    private List<File> classpathElts;

    /**
     * The default number of worker threads to use while scanning. This number gave the best results on a relatively
     * modern laptop with SSD, while scanning a large classpath.
     */
    private static final int DEFAULT_NUM_WORKER_THREADS = 7;

    // -------------------------------------------------------------------------------------------------------------

    /** If set to true, print info while scanning */
    public static boolean verbose = false;

    /**
     * Switch on verbose mode (prints debug info to System.out). Call immediately after the constructor if you want
     * full log output.
     */
    public synchronized FastClasspathScanner verbose() {
        verbose = true;
        return this;
    }

    /**
     * Switch on verbose mode if verbosity == true. (Prints debug info to System.out.) Call immediately after the
     * constructor if you want full log output.
     */
    public synchronized FastClasspathScanner verbose(final boolean verbosity) {
        verbose = verbosity;
        return this;
    }

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
        this.scanSpecArgs = scanSpec;
        // Switch off verbosity each time a new FastClassScanner is constructed, so that one setting of .verbose()
        // doesn't affect the next construction
        FastClasspathScanner.verbose = false;
    }

    /**
     * Add an extra ClassLoaderHandler. Needed if the ServiceLoader framework is not able to find the
     * ClassLoaderHandler for your specific ClassLoader, or if you want to manually register your own
     * ClassLoaderHandler rather than using the ServiceLoader framework.
     */
    public synchronized void registerClassLoaderHandler(final ClassLoaderHandler extraClassLoaderHandler) {
        getScanSpec().extraClassLoaderHandlers.add(extraClassLoaderHandler);
    }

    /** Override the automatically-detected classpath with a custom search path. */
    public synchronized FastClasspathScanner overrideClasspath(final String classpath) {
        getScanSpec().overrideClasspath = classpath;
        return this;
    }

    /** Lazy initializer for scanSpec. */
    private synchronized ScanSpec getScanSpec() {
        if (scanSpec == null) {
            scanSpec = new ScanSpec(scanSpecArgs);
        }
        return scanSpec;
    }

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist are not included in the list.
     */
    public List<File> getUniqueClasspathElements() {
        if (classpathElts == null) {
            try (final ThreadLog log = new ThreadLog()) {
                if (FastClasspathScanner.verbose) {
                    log.log("Starting scan");
                }
                classpathElts = new ClasspathFinder(getScanSpec(), log).getUniqueClasspathElements();
            }
        }
        return classpathElts;
    }

    /** Get the version number of FastClasspathScanner */
    public synchronized static final String getVersion() {
        return VersionFinder.getVersion();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * If ignoreFieldVisibility is true, causes FastClasspathScanner to ignore field visibility, enabling it to see
     * private, package-private and protected fields. This affects finding classes with fields of a given type, as
     * well as matching static final fields with constant initializers. If false, fields must be public to be
     * indexed/matched.
     */
    public FastClasspathScanner ignoreFieldVisibility(final boolean ignoreFieldVisibility) {
        getScanSpec().ignoreFieldVisibility = ignoreFieldVisibility;
        return this;
    }

    /**
     * This method causes FastClasspathScanner to ignore field visibility, enabling it to see private,
     * package-private and protected fields. This affects finding classes with fields of a given type, as well as
     * matching static final fields with constant initializers. If false, fields must be public to be
     * indexed/matched.
     */
    public FastClasspathScanner ignoreFieldVisibility() {
        ignoreFieldVisibility(true);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassMatchProcessor for all standard classes, interfaces and annotations found in
     * whitelisted packages on the classpath. Calls the class loader on each matching class (using Class.forName())
     * before calling the ClassMatchProcessor.
     * 
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public synchronized FastClasspathScanner matchAllClasses(final ClassMatchProcessor classMatchProcessor) {
        getScanSpec().matchAllClasses(classMatchProcessor);
        return this;
    }

    /**
     * Calls the provided ClassMatchProcessor for all standard classes (i.e. non-interface, non-annotation classes)
     * found in whitelisted packages on the classpath. Calls the class loader on each matching class (using
     * Class.forName()) before calling the ClassMatchProcessor.
     * 
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public synchronized FastClasspathScanner matchAllStandardClasses(
            final ClassMatchProcessor classMatchProcessor) {
        getScanSpec().matchAllStandardClasses(classMatchProcessor);
        return this;
    }

    /**
     * Calls the provided ClassMatchProcessor for all interface classes (interface definitions) found in whitelisted
     * packages on the classpath. Calls the class loader on each matching interface class (using Class.forName())
     * before calling the ClassMatchProcessor.
     * 
     * @param ClassMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public synchronized FastClasspathScanner matchAllInterfaceClasses(
            final ClassMatchProcessor ClassMatchProcessor) {
        getScanSpec().matchAllInterfaceClasses(ClassMatchProcessor);
        return this;
    }

    /**
     * Calls the provided ClassMatchProcessor for all annotation classes (annotation definitions) found in
     * whitelisted packages on the classpath. Calls the class loader on each matching annotation class (using
     * Class.forName()) before calling the ClassMatchProcessor.
     * 
     * @param ClassMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public synchronized FastClasspathScanner matchAllAnnotationClasses(
            final ClassMatchProcessor ClassMatchProcessor) {
        getScanSpec().matchAllAnnotationClasses(ClassMatchProcessor);
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
    public synchronized <T> FastClasspathScanner matchSubclassesOf(final Class<T> superclass,
            final SubclassMatchProcessor<T> subclassMatchProcessor) {
        getScanSpec().matchSubclassesOf(superclass, subclassMatchProcessor);
        return this;
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
    public synchronized <T> FastClasspathScanner matchSubinterfacesOf(final Class<T> superinterface,
            final SubinterfaceMatchProcessor<T> subinterfaceMatchProcessor) {
        getScanSpec().matchSubinterfacesOf(superinterface, subinterfaceMatchProcessor);
        return this;
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
    public synchronized <T> FastClasspathScanner matchClassesImplementing(final Class<T> implementedInterface,
            final InterfaceMatchProcessor<T> interfaceMatchProcessor) {
        getScanSpec().matchClassesImplementing(implementedInterface, interfaceMatchProcessor);
        return this;
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
    public synchronized <T> FastClasspathScanner matchClassesWithFieldOfType(final Class<T> fieldType,
            final ClassMatchProcessor classMatchProcessor) {
        getScanSpec().matchClassesWithFieldOfType(fieldType, classMatchProcessor);
        return this;
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
    public synchronized FastClasspathScanner matchClassesWithAnnotation(final Class<?> annotation,
            final ClassAnnotationMatchProcessor classAnnotationMatchProcessor) {
        getScanSpec().matchClassesWithAnnotation(annotation, classAnnotationMatchProcessor);
        return this;
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
    public synchronized FastClasspathScanner matchStaticFinalFieldNames(
            final Set<String> fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        getScanSpec().matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNames,
                staticFinalFieldMatchProcessor);
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
    public synchronized FastClasspathScanner matchStaticFinalFieldNames(
            final String fullyQualifiedStaticFinalFieldName,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        getScanSpec().matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldName,
                staticFinalFieldMatchProcessor);
        return this;
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
    public synchronized FastClasspathScanner matchStaticFinalFieldNames(
            final String[] fullyQualifiedStaticFinalFieldNames,
            final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor) {
        getScanSpec().matchStaticFinalFieldNames(fullyQualifiedStaticFinalFieldNames,
                staticFinalFieldMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath with the given regexp
     * pattern in their path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     */
    public synchronized FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        getScanSpec().matchFilenamePattern(pathRegexp, fileMatchProcessorWithContext);
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
    public synchronized FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchProcessor fileMatchProcessor) {
        getScanSpec().matchFilenamePattern(pathRegexp, fileMatchProcessor);
        return this;
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
    public synchronized FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        getScanSpec().matchFilenamePattern(pathRegexp, fileMatchContentsProcessorWithContext);
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
    public synchronized FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        getScanSpec().matchFilenamePattern(pathRegexp, fileMatchContentsProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

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
    public synchronized FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        getScanSpec().matchFilenamePath(relativePathToMatch, fileMatchProcessorWithContext);
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
    public synchronized FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        getScanSpec().matchFilenamePath(relativePathToMatch, fileMatchProcessor);
        return this;
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
    public synchronized FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        getScanSpec().matchFilenamePath(relativePathToMatch, fileMatchContentsProcessorWithContext);
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
    public synchronized FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        getScanSpec().matchFilenamePath(relativePathToMatch, fileMatchContentsProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that exactly match the
     * given path leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     */
    public synchronized FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        getScanSpec().matchFilenamePath(pathLeafToMatch, fileMatchProcessorWithContext);
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
    public synchronized FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        getScanSpec().matchFilenamePath(pathLeafToMatch, fileMatchProcessor);
        return this;
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
    public synchronized FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        getScanSpec().matchFilenamePath(pathLeafToMatch, fileMatchContentsProcessorWithContext);
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
    public synchronized FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        getScanSpec().matchFilenamePath(pathLeafToMatch, fileMatchContentsProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that have the given file
     * extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html" and "WIDGET.HTML".
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     */
    public synchronized FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        getScanSpec().matchFilenamePath(extensionToMatch, fileMatchProcessorWithContext);
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
    public synchronized FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        getScanSpec().matchFilenamePath(extensionToMatch, fileMatchProcessor);
        return this;
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
    public synchronized FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        getScanSpec().matchFilenamePath(extensionToMatch, fileMatchContentsProcessorWithContext);
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
    public synchronized FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        getScanSpec().matchFilenamePath(extensionToMatch, fileMatchContentsProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Asynchronously scans the classpath for matching files, and calls any MatchProcessors if a match is
     * identified. Returns after starting the scan. To block on scan completion, get the result of the returned
     * Future.
     * 
     * This method should be called after all required MatchProcessors have been added. Note that MatchProcessors
     * are all run on a separate thread from the thread that calls this method (although the MatchProcessors are all
     * run on one thread) -- you will need to add your own synchronization if MatchProcessors interact with the
     * caller thread.
     * 
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker threads.
     * @param numWorkerThreads
     *            The number of worker threads to use while scanning.
     */
    public synchronized Future<ScanResult> scanAsync(final ExecutorService executorService,
            final int numWorkerThreads) {
        return ScanExecutor.scan(getScanSpec(), getUniqueClasspathElements(), executorService,
                Math.max(numWorkerThreads, 1));
    }

    /**
     * Scans the classpath for matching files, and calls any MatchProcessors if a match is identified.
     * 
     * This method should be called after all required MatchProcessors have been added.
     * 
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker threads. This ExecutorService should start
     *            tasks in FIFO order to avoid a deadlock during scan, i.e. be sure to construct the ExecutorService
     *            with a LinkedBlockingQueue as its task queue. (This is the default for
     *            Executors.newFixedThreadPool().)
     * @param numWorkerThreads
     *            The number of worker threads to use while scanning.
     * @throws ScanInterruptedException
     *             if the scan was interrupted by the interrupt status being set on worker threads. If you care
     *             about thread interruption, you should catch this exception.
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception.
     */
    public synchronized ScanResult scan(final ExecutorService executorService, final int numWorkerThreads) {
        try {
            // Start the scan, and then wait for scan completion
            return scanAsync(executorService, numWorkerThreads).get();
        } catch (final InterruptedException e) {
            throw new ScanInterruptedException();
        } catch (final ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    /**
     * Asynchronously scans the classpath for matching files, and calls any MatchProcessors if a match is
     * identified.
     * 
     * This method should be called after all required MatchProcessors have been added.
     * 
     * @param numWorkerThreads
     *            The number of worker threads to use while scanning.
     * @throws ScanInterruptedException
     *             if the scan was interrupted by the interrupt status being set on worker threads. If you care
     *             about thread interruption, you should catch this exception.
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception.
     */
    public synchronized ScanResult scan(final int numWorkerThreads) {
        ExecutorService executorService = null;
        try {
            final AtomicInteger threadIdx = new AtomicInteger();
            executorService = Executors.newFixedThreadPool(Math.max(numWorkerThreads, 1), new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable r) {
                    final Thread t = new Thread(r, "FastClasspathScanner-worker-" + threadIdx.getAndIncrement());
                    // Kill worker threads if main thread dies
                    t.setDaemon(true);
                    return t;
                }
            });
            return scan(executorService, numWorkerThreads);
        } finally {
            if (executorService != null) {
                try {
                    executorService.shutdown();
                } catch (final Exception e) {
                    throw new RuntimeException("Exception shutting down ExecutorService: " + e);
                }
            }
        }
    }

    /**
     * Scans the classpath for matching files, and calls any MatchProcessors if a match is identified.
     * 
     * This method should be called after all required MatchProcessors have been added.
     * 
     * Uses the default number of worker threads and a default fixed thread pool for scanning.
     * 
     * @throws ScanInterruptedException
     *             if the scan was interrupted by the interrupt status being set on worker threads. If you care
     *             about thread interruption, you should catch this exception.
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception.
     */
    public synchronized ScanResult scan() {
        return scan(DEFAULT_NUM_WORKER_THREADS);
    }
}
