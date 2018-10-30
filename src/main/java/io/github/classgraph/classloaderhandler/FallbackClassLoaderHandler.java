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
import io.github.classgraph.utils.ReflectionUtils;

/**
 * Fallback ClassLoaderHandler. Tries to get classpath from a range of possible method and field names.
 */
public class FallbackClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public String[] handledClassLoaders() {
        // The actual string "*" is unimportant here, it is ignored
        return new String[] { "*" };
    }

    @Override
    public ClassLoader getEmbeddedClassLoader(final ClassLoader outerClassLoaderInstance) {
        return null;
    }

    @Override
    public DelegationOrder getDelegationOrder(final ClassLoader classLoaderInstance) {
        return DelegationOrder.PARENT_FIRST;
    }

    @Override
    public void handle(final ScanSpec scanSpec, final ClassLoader classLoader,
            final ClasspathOrder classpathOrderOut, final LogNode log) {
        boolean valid = false;
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getClassPath", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getClasspath", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "classpath", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "classPath", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(ReflectionUtils.invokeMethod(classLoader, "cp", false),
                classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.getFieldVal(classLoader, "classpath", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.getFieldVal(classLoader, "classPath", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(ReflectionUtils.getFieldVal(classLoader, "cp", false),
                classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getPath", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getPaths", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "path", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "paths", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.getFieldVal(classLoader, "paths", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.getFieldVal(classLoader, "paths", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getDir", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getDirs", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "dir", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "dirs", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(ReflectionUtils.getFieldVal(classLoader, "dir", false),
                classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.getFieldVal(classLoader, "dirs", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getFile", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getFiles", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "file", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "files", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.getFieldVal(classLoader, "file", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.getFieldVal(classLoader, "files", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getJar", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getJars", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "jar", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "jars", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(ReflectionUtils.getFieldVal(classLoader, "jar", false),
                classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.getFieldVal(classLoader, "jars", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getURL", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getURLs", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getUrl", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "getUrls", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "url", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.invokeMethod(classLoader, "urls", false), classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(ReflectionUtils.getFieldVal(classLoader, "url", false),
                classLoader, log);
        valid |= classpathOrderOut.addClasspathElementObject(
                ReflectionUtils.getFieldVal(classLoader, "urls", false), classLoader, log);
        if (log != null) {
            log.log("FallbackClassLoaderHandler " + (valid ? "found" : "did not find")
                    + " classpath entries in unknown ClassLoader " + classLoader);
        }
    }
}
