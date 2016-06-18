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
package io.github.lukehutch.fastclasspathscanner.classpath.classloaderhandlers;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.lukehutch.fastclasspathscanner.classpath.ClassLoaderHandler;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;

// See:
// https://github.com/jboss-modules/jboss-modules/blob/master/src/main/java/org/jboss/modules/ModuleClassLoader.java
public class JBossClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean handle(final ClassLoader classloader, final ClasspathFinder classpathFinder) throws Exception {
        for (Class<?> c = classloader.getClass(); c != null; c = c.getSuperclass()) {
            if ("org.jboss.modules.ModuleClassLoader".equals(c.getName())) {
                final Method getResourceLoaders = c.getDeclaredMethod("getResourceLoaders");
                if (!getResourceLoaders.isAccessible()) {
                    getResourceLoaders.setAccessible(true);
                }
                final Object result = getResourceLoaders.invoke(classloader);
                for (int i = 0, n = Array.getLength(result); i < n; i++) {
                    final Object resourceLoader = Array.get(result, i); // type VFSResourceLoader
                    final Field root = resourceLoader.getClass().getDeclaredField("root");
                    if (!root.isAccessible()) {
                        root.setAccessible(true);
                    }
                    final Object rootVal = root.get(resourceLoader); // type VirtualFile
                    final Method getPathName = rootVal.getClass().getDeclaredMethod("getPathName");
                    if (!getPathName.isAccessible()) {
                        getPathName.setAccessible(true);
                    }
                    final String pathElement = (String) getPathName.invoke(rootVal);
                    classpathFinder.addClasspathElement(pathElement);
                }
                return true;
            }
        }
        return false;
    }
}
