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

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathOrder;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

/**
 * A basic ClassLoaderHandler that is able to extract the URLs from Java 9+ classloaders.
 *
 * <p>
 * N.B. does not honor Java 9 module module encapsulation rules.
 */
public class Java9ClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public String[] handledClassLoaders() {
        return new String[] { //
                "jdk.internal.loader.ClassLoaders$AppClassLoader", //
                "jdk.internal.loader.BuiltinClassLoader" };
    }

    @Override
    public DelegationOrder getDelegationOrder(final ClassLoader classLoaderInstance) {
        return DelegationOrder.PARENT_FIRST;
    }

    @Override
    public void handle(final ScanSpec scanSpec, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final LogNode log) {

        // TODO: These fields cannot be queried, because Java 9 strictly enforces encapsulation:
        // https://stackoverflow.com/a/41265267 The JRE still seems to honor java.class.path for non-modular code.

        // // Type URLClassPath final Object ucp = ReflectionUtils.getFieldVal(classLoader, "ucp", false); if (ucp
        // != null) { // A list of classpath element URL strings. // TODO: each URL element can add additional URL
        // elements through the module-info mechanism, // so the URLClassPath class has a stack of opened URLs (and
        // opening a URL can push more // URLs onto the stack). Currently the manifest's Class-Path mechanism works
        // in a similar // way, but this should be abstracted to work with classloading too. Or alternatively, //
        // maybe the Java 9 module loader should simply be queried when running under Java 9.
        // @SuppressWarnings("unchecked") final List<String> path = (List<String>) ReflectionUtils.getFieldVal(ucp,
        // "path", false); if (path != null) { for (final String pathElt : path) {
        // classpathOrderOut.addClasspathElement(pathElt, classLoader, log); } } }
    }
}
