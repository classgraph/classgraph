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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import io.github.lukehutch.fastclasspathscanner.classloaderhandler.ClassLoaderHandler;
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
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.scanner.Scanner;
import io.github.lukehutch.fastclasspathscanner.utils.AutoCloseableExecutorService;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.VersionFinder;

/**
 * Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary format
 * directly rather than by using reflection. (Reflection causes the classloader to load each class, which can take
 * an order of magnitude more time than parsing the classfile directly.)
 * 
 * Documentation:
 * 
 * https://github.com/lukehutch/fast-classpath-scanner/wiki
 */
public class FastClasspathScanner {
    /** The scanning specification (whitelisted and blacklisted packages, etc.), as passed into the constructor. */
    private final String[] scanSpecArgs;

    /** The scanning specification, parsed. */
    private ScanSpec scanSpec;

    /** The unique classpath elements. */
    private List<File> classpathElts;

    /** The FastClasspathScanner version. */
    private static String version;

    /**
     * The default number of worker threads to use while scanning. This number gave the best results on a relatively
     * modern laptop with SSD, while scanning a large classpath.
     */
    private static final int DEFAULT_NUM_WORKER_THREADS = 6;

    /** If non-null, log while scanning */
    private LogNode log;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Construct a FastClasspathScanner instance. You can pass a scanning specification to the constructor to
     * describe what should be scanned -- see the docs for info:
     * 
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor
     * 
     * The scanSpec, if non-empty, prevents irrelevant classpath entries from being unecessarily scanned, which can
     * be time-consuming.
     * 
     * Note that calling the constructor does not start the scan, you must separately call .scan() to perform the
     * actual scan.
     * 
     * @param scanSpec
     *            The constructor accepts a list of whitelisted package prefixes / jar names to scan, as well as
     *            blacklisted packages/jars not to scan, where blacklisted entries are prefixed with the '-'
     *            character. See https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor for info.
     */
    public FastClasspathScanner(final String... scanSpec) {
        this.scanSpecArgs = scanSpec;
    }

    /**
     * Get the version number of FastClasspathScanner.
     *
     * @return the FastClasspathScanner version, or "unknown" if it could not be determined.
     */
    public synchronized static final String getVersion() {
        if (version == null) {
            version = VersionFinder.getVersion();
        }
        return version;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Lazy initializer for scanSpec. (This is lazy so that you have a chance to call verbose() before the ScanSpec
     * constructor tries to log something.)
     */
    private synchronized ScanSpec getScanSpec() {
        if (scanSpec == null) {
            scanSpec = new ScanSpec(scanSpecArgs, log == null ? null : log.log("Parsing scan spec"));
        }
        return scanSpec;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Switches on verbose mode for debugging purposes if verbose == true. Call immediately after calling the
     * constructor if you want full log output. Prints debug info to System.err.
     * 
     * @param verbose
     *            Whether or not to give verbose output.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner verbose(final boolean verbose) {
        if (verbose) {
            if (log == null) {
                log = new LogNode();
            }
        } else {
            log = null;
        }
        return this;
    }

    /**
     * Switches on verbose mode for debugging purposes. Call immediately after calling the constructor if you want
     * full log output. Prints debug info to System.err.
     * 
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner verbose() {
        verbose(true);
        return this;
    }

    /**
     * If ignoreFieldVisibility is true, causes FastClasspathScanner to ignore field visibility, enabling it to see
     * private, package-private and protected fields. This affects finding classes with fields of a given type, as
     * well as matching static final fields with constant initializers. If false, fields must be public to be
     * indexed/matched.
     * 
     * @param ignoreFieldVisibility
     *            Whether or not to ignore the field visibility modifier.
     * @return this (for method chaining).
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
     * 
     * @return this (for method chaining).
     */
    public FastClasspathScanner ignoreFieldVisibility() {
        ignoreFieldVisibility(true);
        return this;
    }

    /**
     * If enableFieldIndexing is true, enables field type indexing, which allows you to call
     * ScanResult#getClassesWithFieldsOfType(type). (If you add a field type match processor, this method is called
     * for you.) Field type indexing is disabled by default, because it is expensive in terms of time (adding ~10%
     * to the scan time) and memory, and it is not needed for most uses of FastClasspathScanner.
     * 
     * @param enableFieldTypeIndexing
     *            Whether or not to enable field type indexing.
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableFieldTypeIndexing(final boolean enableFieldTypeIndexing) {
        getScanSpec().enableFieldTypeIndexing = enableFieldTypeIndexing;
        return this;
    }

    /**
     * Enables field type indexing, which allows you to call ScanResult#getClassesWithFieldsOfType(type). (If you
     * add a field type match processor, this method is called for you.) Field type indexing is disabled by default,
     * because it is expensive in terms of time (adding ~10% to the scan time) and memory, and it is not needed for
     * most uses of FastClasspathScanner.
     * 
     * @return this (for method chaining).
     */
    public FastClasspathScanner enableFieldTypeIndexing() {
        enableFieldTypeIndexing(true);
        return this;
    }

    /**
     * If strictWhitelist is true, switches FastClasspathScanner to strict mode, which disallows searching/matching
     * based on blacklisted classes, and removes "external" classes from result lists returned by ScanSpec#get...()
     * methods. (External classes are classes outside of whitelisted packages that are directly referred to by
     * classes within whitelisted packages as a superclass, implemented interface or annotation.)
     * 
     * See the following for info on external classes, and strict mode vs. non-strict mode:
     * 
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor#external-classes
     * 
     * @param strictWhitelist
     *            Whether or not to switch to strict mode.
     * @return this (for method chaining).
     */
    public FastClasspathScanner strictWhitelist(final boolean strictWhitelist) {
        getScanSpec().strictWhitelist = strictWhitelist;
        return this;
    }

    /**
     * Switches FastClasspathScanner to strict mode, which disallows searching/matching based on blacklisted
     * classes, and removes "external" classes from result lists returned by ScanSpec#get...() methods. (External
     * classes are classes outside of whitelisted packages that are directly referred to by classes within
     * whitelisted packages as a superclass, implemented interface or annotation.)
     * 
     * See the following for info on external classes, and strict mode vs. non-strict mode:
     * 
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/2.-Constructor#external-classes
     * 
     * @return this (for method chaining).
     */
    public FastClasspathScanner strictWhitelist() {
        strictWhitelist(true);
        return this;
    }

    /**
     * Add an extra ClassLoaderHandler. Needed if FastClasspathScanner doesn't know how to extract classpath entries
     * from your runtime environment's ClassLoader. See:
     * 
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/4.-Working-with-nonstandard-ClassLoaders
     * 
     * @param extraClassLoaderHandler
     *            The ClassLoaderHandler class to register.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner registerClassLoaderHandler(
            final Class<? extends ClassLoaderHandler> classLoaderHandlerClass) {
        getScanSpec().extraClassLoaderHandlers.add(classLoaderHandlerClass);
        return this;
    }

    /**
     * Override the automatically-detected classpath with a custom search path. You can specify multiple elements,
     * separated by File.pathSeparatorChar. If this method is called, nothing but the provided classpath will be
     * scanned.
     * 
     * @param classpath
     *            The custom classpath to use for scanning, with path elements separated by File.pathSeparatorChar.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner overrideClasspath(final String classpath) {
        getScanSpec().overrideClasspath = classpath;
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassMatchProcessor for all standard classes, interfaces and annotations found in
     * whitelisted packages on the classpath.
     * 
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchAllClasses(final ClassMatchProcessor classMatchProcessor) {
        getScanSpec().matchAllClasses(classMatchProcessor);
        return this;
    }

    /**
     * Calls the provided ClassMatchProcessor for all standard classes (i.e. non-interface, non-annotation classes)
     * found in whitelisted packages on the classpath.
     * 
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchAllStandardClasses(
            final ClassMatchProcessor classMatchProcessor) {
        getScanSpec().matchAllStandardClasses(classMatchProcessor);
        return this;
    }

    /**
     * Calls the provided ClassMatchProcessor for all interface classes (interface definitions) found in whitelisted
     * packages on the classpath.
     * 
     * @param ClassMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchAllInterfaceClasses(
            final ClassMatchProcessor ClassMatchProcessor) {
        getScanSpec().matchAllInterfaceClasses(ClassMatchProcessor);
        return this;
    }

    /**
     * Calls the provided ClassMatchProcessor for all annotation classes (annotation definitions) found in
     * whitelisted packages on the classpath.
     * 
     * @param ClassMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchAllAnnotationClasses(
            final ClassMatchProcessor ClassMatchProcessor) {
        getScanSpec().matchAllAnnotationClasses(ClassMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubclassMatchProcessor if classes are found on the classpath that extend the specified
     * superclass.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @param subclassMatchProcessor
     *            the SubclassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public synchronized <T> FastClasspathScanner matchSubclassesOf(final Class<T> superclass,
            final SubclassMatchProcessor<T> subclassMatchProcessor) {
        getScanSpec().matchSubclassesOf(superclass, subclassMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided SubinterfaceMatchProcessor if an interface that extends a given superinterface is found on
     * the classpath.
     * 
     * @param superinterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @param subinterfaceMatchProcessor
     *            the SubinterfaceMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public synchronized <T> FastClasspathScanner matchSubinterfacesOf(final Class<T> superinterface,
            final SubinterfaceMatchProcessor<T> subinterfaceMatchProcessor) {
        getScanSpec().matchSubinterfacesOf(superinterface, subinterfaceMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided InterfaceMatchProcessor for classes on the classpath that implement the specified
     * interface or a subinterface, or whose superclasses implement the specified interface or a sub-interface.
     * 
     * @param implementedInterface
     *            The interface that classes need to implement.
     * @param interfaceMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
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
     * fields of parameterized type that have a type parameter of the given type.
     * 
     * Calls enableFieldTypeIndexing() for you.
     * 
     * @param fieldType
     *            The type of the field to match..
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     * @return this (for method chaining).
     */
    public synchronized <T> FastClasspathScanner matchClassesWithFieldOfType(final Class<T> fieldType,
            final ClassMatchProcessor classMatchProcessor) {
        enableFieldTypeIndexing();
        getScanSpec().matchClassesWithFieldOfType(fieldType, classMatchProcessor);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the provided ClassAnnotationMatchProcessor if classes are found on the classpath that have the
     * specified annotation.
     * 
     * @param annotation
     *            The class annotation to match.
     * @param classAnnotationMatchProcessor
     *            the ClassAnnotationMatchProcessor to call when a match is found.
     * @return this (for method chaining).
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
     * Field values are obtained from the constant pool in classfiles, not from a loaded class using reflection.
     * This allows you to detect changes to the classpath and then run another scan that picks up the new values of
     * selected static constants without reloading the class. (Class reloading is fraught with issues, see:
     * http://tutorials.jenkov.com/java-reflection/dynamic-class-loading-reloading.html )
     * 
     * Note: Only static final fields with constant-valued literals are matched, not fields with initializer values
     * that are the result of an expression or reference, except for cases where the compiler is able to simplify an
     * expression into a single constant at compiletime, such as in the case of string concatenation.
     * 
     * Note that the visibility of the fields is not checked if ignoreFieldVisibility() was called before scan().
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The set of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     * @return this (for method chaining).
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
     * Note that the visibility of the fields is not checked if ignoreFieldVisibility() was called before scan().
     * 
     * @param fullyQualifiedStaticFinalFieldName
     *            The fully-qualified static field name to match
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     * @return this (for method chaining).
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
     * Note that the visibility of the fields is not checked if ignoreFieldVisibility() was called before scan().
     * 
     * @param fullyQualifiedStaticFinalFieldNames
     *            The list of fully-qualified static field names to match.
     * @param staticFinalFieldMatchProcessor
     *            the StaticFinalFieldMatchProcessor to call when a match is found.
     * @return this (for method chaining).
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
     * Calls the given FileMatchProcessor if files are found on the classpath with the given regexp pattern in their
     * path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchProcessor fileMatchProcessor) {
        getScanSpec().matchFilenamePattern(pathRegexp, fileMatchProcessor);
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
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        getScanSpec().matchFilenamePattern(pathRegexp, fileMatchContentsProcessor);
        return this;
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath with the given regexp
     * pattern in their path.
     * 
     * @param pathRegexp
     *            The regexp to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        getScanSpec().matchFilenamePattern(pathRegexp, fileMatchProcessorWithContext);
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
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenamePattern(final String pathRegexp,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        getScanSpec().matchFilenamePattern(pathRegexp, fileMatchContentsProcessorWithContext);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that exactly match the given relative
     * path.
     * 
     * @param relativePathToMatch
     *            The complete path to match relative to the classpath entry, e.g.
     *            "app/templates/WidgetTemplate.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        getScanSpec().matchFilenamePath(relativePathToMatch, fileMatchProcessor);
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
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        getScanSpec().matchFilenamePath(relativePathToMatch, fileMatchContentsProcessor);
        return this;
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
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        getScanSpec().matchFilenamePath(relativePathToMatch, fileMatchProcessorWithContext);
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
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenamePath(final String relativePathToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        getScanSpec().matchFilenamePath(relativePathToMatch, fileMatchContentsProcessorWithContext);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that exactly match the given path
     * leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        getScanSpec().matchFilenamePathLeaf(pathLeafToMatch, fileMatchProcessor);
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
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        getScanSpec().matchFilenamePathLeaf(pathLeafToMatch, fileMatchContentsProcessor);
        return this;
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that exactly match the
     * given path leafname.
     * 
     * @param pathLeafToMatch
     *            The complete path leaf to match, e.g. "WidgetTemplate.html"
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        getScanSpec().matchFilenamePathLeaf(pathLeafToMatch, fileMatchProcessorWithContext);
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
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenamePathLeaf(final String pathLeafToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        getScanSpec().matchFilenamePathLeaf(pathLeafToMatch, fileMatchContentsProcessorWithContext);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that have the given file extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html" and "WIDGET.HTML".
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessor fileMatchProcessor) {
        getScanSpec().matchFilenameExtension(extensionToMatch, fileMatchProcessor);
        return this;
    }

    /**
     * Calls the given FileMatchProcessor if files are found on the classpath that have the given file extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html".
     * @param fileMatchContentsProcessor
     *            The FileMatchContentsProcessor to call when each match is found.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchContentsProcessor fileMatchContentsProcessor) {
        getScanSpec().matchFilenameExtension(extensionToMatch, fileMatchContentsProcessor);
        return this;
    }

    /**
     * Calls the given FileMatchProcessorWithContext if files are found on the classpath that have the given file
     * extension.
     * 
     * @param extensionToMatch
     *            The extension to match, e.g. "html" matches "WidgetTemplate.html" and "WIDGET.HTML".
     * @param fileMatchProcessorWithContext
     *            The FileMatchProcessorWithContext to call when each match is found.
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchProcessorWithContext fileMatchProcessorWithContext) {
        getScanSpec().matchFilenameExtension(extensionToMatch, fileMatchProcessorWithContext);
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
     * @return this (for method chaining).
     */
    public synchronized FastClasspathScanner matchFilenameExtension(final String extensionToMatch,
            final FileMatchContentsProcessorWithContext fileMatchContentsProcessorWithContext) {
        getScanSpec().matchFilenameExtension(extensionToMatch, fileMatchContentsProcessorWithContext);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Asynchronously scans the classpath for matching files, and calls any MatchProcessors if a match is
     * identified. Returns a Future object immediately after starting the scan. To block on scan completion, get the
     * result of the returned Future. Uses the provided ExecutorService, and divides the work according to the
     * requested degree of parallelism. This method should be called after all required MatchProcessors have been
     * added.
     * 
     * Note on thread safety: MatchProcessors are all run on a separate thread from the thread that calls this
     * method (although the MatchProcessors are all run on one thread). You will need to add your own
     * synchronization logic if MatchProcessors interact with the main thread. See the following for more info:
     * 
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/1.-Usage#multithreading-issues
     * 
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @return a Future<ScanResult> object, that when resolved using get() yields a new ScanResult object. This
     *         ScanResult object contains info about the class graph within whitelisted packages encountered during
     *         the scan.
     */
    public synchronized Future<ScanResult> scanAsync(final ExecutorService executorService,
            final int numParallelTasks) {
        return executorService.submit(new Scanner(getScanSpec(), executorService, numParallelTasks,
                /* enableRecursiveScanning = */ true, log));
    }

    /**
     * Scans the classpath for matching files, and calls any MatchProcessors if a match is identified. Uses the
     * provided ExecutorService, and divides the work according to the requested degree of parallelism. Blocks and
     * waits for the scan to complete before returning a ScanResult. This method should be called after all required
     * MatchProcessors have been added.
     * 
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks. This ExecutorService should start
     *            tasks in FIFO order to avoid a deadlock during scan, i.e. be sure to construct the ExecutorService
     *            with a LinkedBlockingQueue as its task queue. (This is the default for
     *            Executors.newFixedThreadPool().)
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @throws ScanInterruptedException
     *             if the scan was interrupted by the interrupt status being set on worker threads. If you care
     *             about thread interruption, you should catch this exception. If you don't plan to interrupt the
     *             scan, you probably don't need to catch this.
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception.
     * @return a new ScanResult object, containing info about the class graph within whitelisted packages
     *         encountered during the scan.
     */
    public synchronized ScanResult scan(final ExecutorService executorService, final int numParallelTasks) {
        try {
            // Start the scan, and then wait for scan completion
            return scanAsync(executorService, numParallelTasks).get();
        } catch (final InterruptedException e) {
            throw new ScanInterruptedException();
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof InterruptedException) {
                throw new ScanInterruptedException();
            } else {
                throw new RuntimeException(e.getCause());
            }
        }
    }

    /**
     * Scans the classpath for matching files, and calls any MatchProcessors if a match is identified. Temporarily
     * starts up a new fixed thread pool for scanning, with the requested number of threads. Blocks and waits for
     * the scan to complete before returning a ScanResult. This method should be called after all required
     * MatchProcessors have been added.
     * 
     * @param numThreads
     *            The number of worker threads to start up.
     * @throws ScanInterruptedException
     *             if the scan was interrupted by the interrupt status being set on worker threads. If you care
     *             about thread interruption, you should catch this exception. If you don't plan to interrupt the
     *             scan, you probably don't need to catch this.
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception.
     * @return a new ScanResult object, containing info about the class graph within whitelisted packages
     *         encountered during the scan.
     */
    public synchronized ScanResult scan(final int numThreads) {
        try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(numThreads)) {
            return scan(executorService, numThreads);
        }
    }

    /**
     * Scans the classpath for matching files, and calls any MatchProcessors if a match is identified. Temporarily
     * starts up a new fixed thread pool for scanning, with the default number of threads. Blocks and waits for the
     * scan to complete before returning a ScanResult. This method should be called after all required
     * MatchProcessors have been added.
     * 
     * @throws ScanInterruptedException
     *             if the scan was interrupted by the interrupt status being set on worker threads. If you care
     *             about thread interruption, you should catch this exception.
     * @throws RuntimeException
     *             if any of the worker threads throws an uncaught exception.
     * @return a new ScanResult object, containing info about the class graph within whitelisted packages
     *         encountered during the scan.
     */
    public synchronized ScanResult scan() {
        return scan(DEFAULT_NUM_WORKER_THREADS);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Asynchronously returns the list of all unique File objects representing directories or zip/jarfiles on the
     * classpath, in classloader resolution order. Classpath elements that do not exist are not included in the
     * list.
     * 
     * See the following for info on thread safety:
     * 
     * https://github.com/lukehutch/fast-classpath-scanner/wiki/1.-Usage#multithreading-issues
     * 
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @return a Future<List<File>>, that when resolved with get() returns a list of the unique directories and
     *         jarfiles on the classpath, in classpath resolution order.
     */
    public Future<List<File>> getUniqueClasspathElementsAsync(final ExecutorService executorService,
            final int numParallelTasks) {
        final Future<ScanResult> scanResult = executorService.submit(
                new Scanner(getScanSpec(), executorService, numParallelTasks, /* enableRecursiveScanning = */ false,
                        log == null ? null : log.log("Getting unique classpath elements")));
        final Future<List<File>> future = executorService.submit(new Callable<List<File>>() {
            @Override
            public List<File> call() throws Exception {
                if (log != null) {
                    log.log("Getting classpath elements");
                }
                return scanResult.get().getUniqueClasspathElements();
            }
        });
        if (log != null) {
            log.flush();
        }
        return future;
    }

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist are not included in the list. Blocks until
     * the result can be returned, when all classpath elements have been found and tested to see if they exist in
     * the filesystem.
     * 
     * @param executorService
     *            A custom ExecutorService to use for scheduling worker tasks.
     * @param numParallelTasks
     *            The number of parallel tasks to break the work into during the most CPU-intensive stage of
     *            classpath scanning. Ideally the ExecutorService will have at least this many threads available.
     * @return a List<File> consisting of the unique directories and jarfiles on the classpath, in classpath
     *         resolution order.
     */
    public List<File> getUniqueClasspathElements(final ExecutorService executorService,
            final int numParallelTasks) {
        if (classpathElts == null) {
            try {
                classpathElts = getUniqueClasspathElementsAsync(executorService, numParallelTasks).get();
            } catch (final InterruptedException e) {
                if (log != null) {
                    log.log("Thread interrupted while getting classpath elements");
                }
                throw new ScanInterruptedException();
            } catch (final ExecutionException e) {
                if (log != null) {
                    log.log("Exception while getting classpath elements", e);
                }
                throw new RuntimeException(e.getCause());
            }
            if (log != null) {
                log.flush();
            }
        }
        return classpathElts;
    }

    /**
     * Returns the list of all unique File objects representing directories or zip/jarfiles on the classpath, in
     * classloader resolution order. Classpath elements that do not exist are not included in the list. Blocks until
     * the result can be returned, when all classpath elements have been found and tested to see if they exist in
     * the filesystem.
     * 
     * @return a List<File> consisting of the unique directories and jarfiles on the classpath, in classpath
     *         resolution order.
     */
    public List<File> getUniqueClasspathElements() {
        if (classpathElts == null) {
            try (AutoCloseableExecutorService executorService = new AutoCloseableExecutorService(
                    DEFAULT_NUM_WORKER_THREADS)) {
                return getUniqueClasspathElements(executorService, DEFAULT_NUM_WORKER_THREADS);
            }
        }
        return classpathElts;
    }
}
