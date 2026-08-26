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
 * Copyright (c) 2026 Luke Hutchison
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
package io.github.classgraph.classpath.internal.classloaderhandler;

import java.util.List;

import io.github.classgraph.classpath.ClassLoaderHandler;

/** The registry for ClassLoaderHandler classes. */
public final class ClassLoaderHandlerRegistry {
    /**
     * The built-in {@link ClassLoaderHandler}s. If a {@link ClassLoaderHandler} is added to ClassGraph, it should
     * be added to this list.
     *
     * <p>
     * The order of this list does not affect the result of a scan: when several handlers can handle the same
     * classloader, the caller keeps only the ones that handle the most specific classloader class, so a handler for
     * a subclass of {@link java.net.URLClassLoader} always wins over the handler for
     * {@link java.net.URLClassLoader} itself. The list is therefore kept in alphabetical order, which is the
     * easiest order to check a handler's presence in.
     *
     * <p>
     * {@code FallbackClassLoaderHandler} is not in this list -- it is registered separately as
     * {@link #FALLBACK_HANDLER}, since it is only used when no other handler can handle a classloader.
     */
    public static final List<ClassLoaderHandler> CLASS_LOADER_HANDLERS = List.of(new AntClassLoaderHandler(),
            new CxfContainerClassLoaderHandler(), new EquinoxClassLoaderHandler(),
            new EquinoxContextFinderClassLoaderHandler(), new FelixClassLoaderHandler(),
            new JBossClassLoaderHandler(), new JPMSClassLoaderHandler(), new OSGiDefaultClassLoaderHandler(),
            new PlexusClassWorldsClassRealmClassLoaderHandler(), new QuarkusClassLoaderHandler(),
            new SpringBootRestartClassLoaderHandler(), new TomcatWebappClassLoaderBaseHandler(),
            new UnoOneJarClassLoaderHandler(), new URLClassLoaderHandler(), new WeblogicClassLoaderHandler(),
            new WebsphereLibertyClassLoaderHandler(), new WebsphereTraditionalClassLoaderHandler());

    /** Fallback ClassLoaderHandler. */
    public static final ClassLoaderHandler FALLBACK_HANDLER = new FallbackClassLoaderHandler();

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     */
    private ClassLoaderHandlerRegistry() {
        // Cannot be constructed
    }
}
