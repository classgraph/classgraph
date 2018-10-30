/*
 * This file is part of ClassGraph.
 *
 * Author: Michael J. Simons
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.classloaderhandler;

import io.github.classgraph.ScanSpec;
import io.github.classgraph.utils.ClasspathOrder;
import io.github.classgraph.utils.LogNode;

/**
 * This handler uses {@link io.github.classgraph.classloaderhandler.ClassLoaderHandler.DelegationOrder#PARENT_LAST}
 * to support the <code>RestartClassLoader</code> of Spring Boot's devtools. <code>RestartClassLoader</code>
 * provides parent last loading for specified URLs (those are all that are supposed to be changed during
 * development). Therefor the handler for that class loader also has to delegate in <code>PARENT_LAST</code> order.
 */
class SpringBootRestartClassLoaderHandler implements ClassLoaderHandler {

    // Spring Boots devtools class loader is an extension of URLClassLoader so there's no need
    // to use reflection to access the urls supported and we can delegate the handling to an
    // internal instance of URLClassLoaderHandler.
    private final URLClassLoaderHandler handlerDelegate = new URLClassLoaderHandler();

    @Override
    public String[] handledClassLoaders() {
        return new String[] { //
                "org.springframework.boot.devtools.restart.classloader.RestartClassLoader" };
    }

    @Override
    public ClassLoader getEmbeddedClassLoader(final ClassLoader outerClassLoaderInstance) {
        return null;
    }

    /**
     * Spring Boot's RestartClassLoader sits in front of the parent class loader and watches a given set of
     * directories for changes. While those classes are reachable from the parent class loader directly, they should
     * always be loaded through direct access from the RestartClassLoader until it's completely turned of by means
     * of Spring Boot Developer tools.
     * 
     * The RestartClassLoader shades only the project classes and additional directories that are configurable, so
     * itself needs access to parent, but last.
     * 
     * See: <a href="https://github.com/classgraph/classgraph/issues/267">#267</a>,
     * <a href="https://github.com/classgraph/classgraph/issues/268">#268</a>
     */
    @Override
    public ClassLoaderHandler.DelegationOrder getDelegationOrder(final ClassLoader classLoaderInstance) {
        return DelegationOrder.PARENT_LAST;
    }

    @Override
    public void handle(final ScanSpec scanSpec, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final LogNode log) {
        handlerDelegate.handle(scanSpec, classLoader, classpathOrderOut, log);
    }
}