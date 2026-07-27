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
 * Copyright (c) 2019 Luke Hutchison
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
package nonapi.io.github.classgraph.classloaderhandler;

/**
 * A ClassLoader handler.
 *
 * <p>
 * Implementations must declare the following {@code static} methods, which are looked up reflectively by
 * {@link ClassLoaderHandlerRegistry.ClassLoaderHandlerRegistryEntry}:
 *
 * <ul>
 * <li>{@code boolean canHandle(Class<?> classLoaderClass, LogNode log)}
 * <li>{@code void findClassLoaderOrder(ClassLoader classLoader, ClassLoaderOrder classLoaderOrder, LogNode log)}
 * <li>{@code void findClasspathOrder(ClassLoader classLoader, ClasspathOrder classpathOrder, ScanSpec scanSpec,
 * LogNode log)}
 * <li>{@code String[] getPackageRootPrefixes()} -- the automatic package root prefixes (e.g.
 * {@code "BOOT-INF/classes/"}) to look for and strip within classpath elements obtained from this classloader, or
 * {@link ClassLoaderHandlerRegistry#NO_PACKAGE_ROOT_PREFIXES} if this classloader's classpath elements always have
 * their classes at the root. Package roots must only be declared here if the classloader really can produce
 * classpath elements in that layout, since a package root prefix that is also a legal package name (e.g.
 * {@code "classes/"}) will otherwise cause real packages of that name to be misread as package roots (#929).
 * </ul>
 *
 * <p>
 * If you create a custom ClassLoaderHandler, please consider submitting it to the ClassGraph open source project.
 */
public interface ClassLoaderHandler {
}
