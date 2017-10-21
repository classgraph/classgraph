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

import java.util.List;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/**
 * A basic ClassLoaderHandler that is able to extract the URLs from Java 9+ classloaders.
 * 
 * N.B. does not honor Java 9 module module encapsulation rules.
 */
public class Java9ClassLoaderHandler implements ClassLoaderHandler {
    public static final String[] HANDLED_CLASSLOADERS = { //
            "jdk.internal.loader.ClassLoaders$AppClassLoader", //
            "jdk.internal.loader.BuiltinClassLoader" //
    };

    @Override
    public DelegationOrder getDelegationOrder(final ClassLoader classLoaderInstance) {
        return DelegationOrder.PARENT_FIRST;
    }

    @Override
    public void handle(final ClassLoader classLoader, final ClasspathFinder classpathFinder,
            final ScanSpec scanSpec, final LogNode log) {
        try {
            // Type URLClassPath
            final Object ucp = ReflectionUtils.getFieldVal(classLoader, "ucp");
            if (ucp != null) {
                // A list of classpath element URL strings.
                // TODO: each URL element can add additional URL elements through the module-info mechanism,
                // so the URLClassPath class has a stack of opened URLs (and opening a URL can push more
                // URLs onto the stack). Currently the manifest's Class-Path mechanism works in a similar
                // way, but this should be abstracted to work with classloading too. Or alternatively,
                // maybe the Java 9 module loader should simply be queried when running under Java 9.
                @SuppressWarnings("unchecked")
                final List<String> path = (List<String>) ReflectionUtils.getFieldVal(ucp, "path");
                if (path != null) {
                    for (final String pathElt : path) {
                        classpathFinder.addClasspathElement(pathElt, classLoader, log);
                    }
                }
            }
        } catch (final Exception e) {
            if (log != null) {
                log.log("Exception while trying to get paths from ClassLoader " + classLoader, e);
            }
        }
    }
}
