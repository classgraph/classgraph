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

import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/** The registry for ClassLoaderHandler classes. */
public class ClassLoaderHandlerRegistry {
    /**
     * Default ClassLoaderHandlers. If a ClassLoaderHandler is added to FastClasspathScanner, it should be added to
     * this list.
     */
    public static final List<ClassLoaderHandlerRegistryEntry> DEFAULT_CLASS_LOADER_HANDLERS = Arrays.asList(
            // ClassLoaderHandlers for other ClassLoaders that are handled by FastClasspathScanner
            new ClassLoaderHandlerRegistryEntry(EquinoxClassLoaderHandler.class),
            new ClassLoaderHandlerRegistryEntry(FelixClassLoaderHandler.class),
            new ClassLoaderHandlerRegistryEntry(JBossClassLoaderHandler.class),
            new ClassLoaderHandlerRegistryEntry(WeblogicClassLoaderHandler.class),
            new ClassLoaderHandlerRegistryEntry(WebsphereLibertyClassLoaderHandler.class),
            new ClassLoaderHandlerRegistryEntry(WebsphereTraditionalClassLoaderHandler.class),
            new ClassLoaderHandlerRegistryEntry(OSGiDefaultClassLoaderHandler.class),

            // Java 9 support
            new ClassLoaderHandlerRegistryEntry(Java9ClassLoaderHandler.class),

            // Java 7/8 support
            new ClassLoaderHandlerRegistryEntry(URLClassLoaderHandler.class));

    /**
     * A list of fully-qualified ClassLoader class names paired with the ClassLoaderHandler that can handle them.
     */
    public static class ClassLoaderHandlerRegistryEntry {
        public final String[] handledClassLoaderNames;
        public final Class<? extends ClassLoaderHandler> classLoaderHandlerClass;

        public ClassLoaderHandlerRegistryEntry(final Class<? extends ClassLoaderHandler> classLoaderHandlerClass) {
            final String fieldName = "HANDLED_CLASSLOADERS";
            Object handledClassLoaders;
            try {
                handledClassLoaders = ReflectionUtils.getStaticFieldVal(classLoaderHandlerClass, fieldName);
            } catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException
                    | SecurityException e) {
                throw new RuntimeException("Could not read field " + classLoaderHandlerClass + "." + fieldName, e);
            }
            if (handledClassLoaders == null) {
                throw new RuntimeException("Class " + classLoaderHandlerClass
                        + " needs a non-null static String[] field " + fieldName);
            }
            if (!handledClassLoaders.getClass().isArray()
                    || handledClassLoaders.getClass().getComponentType() != String.class) {
                throw new RuntimeException("Field " + classLoaderHandlerClass + "." + fieldName
                        + " has incorrect type, should be String[]");
            }
            this.handledClassLoaderNames = (String[]) handledClassLoaders;
            this.classLoaderHandlerClass = classLoaderHandlerClass;
        }
    }
}
