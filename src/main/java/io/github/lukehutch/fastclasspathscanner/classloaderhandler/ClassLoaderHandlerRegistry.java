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

import java.util.Arrays;
import java.util.List;

/** The registry for ClassLoaderHandler classes. */
public class ClassLoaderHandlerRegistry {
    /**
     * Default ClassLoaderHandlers. If a ClassLoaderHandler is added to FastClasspathScanner, it should be added to
     * this list.
     */
    public static final List<ClassLoaderHandlerRegistryEntry> DEFAULT_CLASS_LOADER_HANDLERS = Arrays.asList(
            // ClassLoaderHandlers for other ClassLoaders that are handled by FastClasspathScanner
            new ClassLoaderHandlerRegistryEntry(EquinoxClassLoaderHandler.HANDLED_CLASSLOADERS,
                    EquinoxClassLoaderHandler.class),
            new ClassLoaderHandlerRegistryEntry(FelixClassLoaderHandler.HANDLED_CLASSLOADERS,
                    FelixClassLoaderHandler.class),
            new ClassLoaderHandlerRegistryEntry(JBossClassLoaderHandler.HANDLED_CLASSLOADERS,
                    JBossClassLoaderHandler.class),
            new ClassLoaderHandlerRegistryEntry(WeblogicClassLoaderHandler.HANDLED_CLASSLOADERS,
                    WeblogicClassLoaderHandler.class),
            new ClassLoaderHandlerRegistryEntry(WebsphereLibertyClassLoaderHandler.HANDLED_CLASSLOADERS,
                    WebsphereLibertyClassLoaderHandler.class),
            new ClassLoaderHandlerRegistryEntry(WebsphereTraditionalClassLoaderHandler.HANDLED_CLASSLOADERS,
                    WebsphereTraditionalClassLoaderHandler.class),
            new ClassLoaderHandlerRegistryEntry(OSGiDefaultClassLoaderHandler.HANDLED_CLASSLOADERS,
                    OSGiDefaultClassLoaderHandler.class),

            // The main default ClassLoaderHandler -- URLClassLoader is the most common ClassLoader.
            // Call this last, so that specific handlers for subclasses can override this if necessary.
            new ClassLoaderHandlerRegistryEntry(URLClassLoaderHandler.HANDLED_CLASSLOADERS,
                    URLClassLoaderHandler.class));

    /**
     * A list of fully-qualified ClassLoader class names paired with the ClassLoaderHandler that can handle them.
     */
    public static class ClassLoaderHandlerRegistryEntry {
        public final String[] handledClassLoaderNames;
        public final Class<? extends ClassLoaderHandler> classLoaderHandlerClass;

        public ClassLoaderHandlerRegistryEntry(final String[] handledClassLoaders,
                final Class<? extends ClassLoaderHandler> classLoaderHandlerClass) {
            this.handledClassLoaderNames = handledClassLoaders;
            this.classLoaderHandlerClass = classLoaderHandlerClass;
        }
    }
}
