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
package io.github.lukehutch.fastclasspathscanner.classloaderhandler;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

/**
 * A ClassLoader handler.
 * 
 * Custom ClassLoaderHandlers can be registered by listing their fully-qualified class name in the file:
 * META-INF/services/io.github.lukehutch.fastclasspathscanner.scanner.classloaderhandler.ClassLoaderHandler
 * 
 * However, if you do create a custom ClassLoaderHandler, please consider submitting a patch upstream for
 * incorporation into FastClasspathScanner.
 */
public interface ClassLoaderHandler {
    // ** The following field is required in all implementing classes: **
    // static final String[] HANDLED_CLASSLOADERS = { /* fully qualified classnames of handled ClassLoaders */ }; 

    /**
     * The delegation order configuration for a given ClassLoader instance (this is usually PARENT_FIRST for most
     * ClassLoaders, but this can be overridden by some ClassLoaders, e.g. WebSphere).
     */
    public enum DelegationOrder {
        PARENT_FIRST, PARENT_LAST;
    }

    /**
     * The delegation order configuration for a given ClassLoader instance (this is usually PARENT_FIRST for most
     * ClassLoaders, since you don't generally want to be able to override system classes with user classes, but
     * this can be overridden by some ClassLoaders, e.g. WebSphere).
     */
    public abstract DelegationOrder getDelegationOrder(ClassLoader classLoaderInstance);

    /**
     * Determine if a given ClassLoader can be handled (meaning that its classpath elements can be extracted from
     * it), and if it can, extract the classpath elements from the ClassLoader and register them with the
     * ClasspathFinder using classpathFinder.addClasspathElement(pathElement) or
     * classpathFinder.addClasspathElements(path).
     * 
     * @param classLoader
     *            The ClassLoader class to attempt to handle. If you can't directly use instanceof (because you are
     *            using introspection so that your ClassLoaderHandler implementation can be added to the upstream
     *            FastClasspathScanner project), you should iterate through the ClassLoader's superclass lineage to
     *            ensure subclasses of the target ClassLoader are correctly detected.
     * @param classpathFinder
     *            The ClasspathFinder to register any discovered classpath elements with.
     * @param scanSpec
     *            the scanning specification, in case it is needed, e.g. this could be used to reduce the number of
     *            classpath elements returned in cases where it is very costly for a given classloader to return the
     *            entire classpath. (The ScanSpec can be safely ignored, however, and the returned paths will be
     *            filtered by FastClasspathScanner.)
     * @param log
     *            A logger instance -- if this is non-null, write debug information using log.log("message").
     */
    public abstract void handle(final ClassLoader classLoader, final ClasspathFinder classpathFinder,
            ScanSpec scanSpec, LogNode log) throws Exception;
}
