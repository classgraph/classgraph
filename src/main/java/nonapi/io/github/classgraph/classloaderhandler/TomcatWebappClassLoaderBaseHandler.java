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
package nonapi.io.github.classgraph.classloaderhandler;

import java.io.File;
import java.util.List;

import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.classpath.ClasspathOrder;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ReflectionUtils;

/** Extract classpath entries from the Tomcat/Catalina WebappClassLoaderBase. */
public class TomcatWebappClassLoaderBaseHandler implements ClassLoaderHandler {
    @Override
    public String[] handledClassLoaders() {
        return new String[] { //
                "org.apache.catalina.loader.WebappClassLoaderBase", //
        };
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
        // type StandardRoot (implements WebResourceRoot)
        final Object resources = ReflectionUtils.invokeMethod(classLoader, "getResources", false);
        // type List<URL>
        final Object baseURLs = ReflectionUtils.invokeMethod(resources, "getBaseUrls", false);
        classpathOrderOut.addClasspathElementObject(baseURLs, classLoader, log);
        // type List<List<WebResourceSet>>
        // members: preResources, mainResources, classResources, jarResources, postResources
        @SuppressWarnings("unchecked")
        final List<List<?>> allResources = (List<List<?>>) ReflectionUtils.getFieldVal(resources, "allResources",
                false);
        if (allResources != null) {
            // type List<WebResourceSet> 
            for (final List<?> webResourceSetList : allResources) {
                // type WebResourceSet
                for (final Object webResourceSet : webResourceSetList) {
                    final String className = webResourceSet.getClass().getName();
                    switch (className) {
                    case "java.org.apache.catalina.webresources.DirResourceSet.java":
                        final File file = (File) ReflectionUtils.invokeMethod(webResourceSet, "getFileBase", false);
                        classpathOrderOut.addClasspathElementObject(file, classLoader, log);
                        break;
                    case "java.org.apache.catalina.webresources.JarResourceSet.java":
                    case "java.org.apache.catalina.webresources.JarWarResourceSet.java":
                        // The absolute path to the WAR file on the file system in which the JAR is located
                        final String baseURLString = (String) ReflectionUtils.invokeMethod(webResourceSet,
                                "getBaseUrlString", false);
                        // The path within this WebResourceSet where resources will be served from,
                        // e.g. for a resource JAR, this would be "META-INF/resources"
                        final String internalPath = (String) ReflectionUtils.invokeMethod(webResourceSet,
                                "getInternalPath", false);
                        // The path within the web application at which this WebResourceSet will be mounted
                        final String webAppMount = (String) ReflectionUtils.invokeMethod(webResourceSet,
                                "getWebAppMount", false);
                        // For JarWarResourceSet: the path within the WAR file where the JAR file is located
                        final String archivePath = className
                                .equals("java.org.apache.catalina.webresources.JarWarResourceSet.java")
                                        ? (String) ReflectionUtils.getFieldVal(webResourceSet, "archivePath", false)
                                        : null;
                        if (baseURLString != null) {
                            // If archivePath is non-null, this is a jar within a war
                            final String jarURLString = archivePath == null ? baseURLString
                                    : baseURLString + "!"
                                            + (archivePath.startsWith("/") ? archivePath : "/" + archivePath);
                            if (internalPath != null) {
                                classpathOrderOut.addClasspathElementObject(jarURLString + "!"
                                        + (internalPath.startsWith("/") ? internalPath : "/" + internalPath),
                                        classLoader, log);
                            }
                            if (webAppMount != null) {
                                classpathOrderOut.addClasspathElementObject(
                                        jarURLString + "!"
                                                + (webAppMount.startsWith("/") ? webAppMount : "/" + webAppMount),
                                        classLoader, log);
                            }
                            if (internalPath == null && webAppMount == null) {
                                classpathOrderOut.addClasspathElementObject(jarURLString, classLoader, log);
                            }
                        }
                        break;
                    }
                }
            }
        }
        final Object urls = ReflectionUtils.invokeMethod(classLoader, "getURLs", false);
        classpathOrderOut.addClasspathElementObject(urls, classLoader, log);
    }
}
