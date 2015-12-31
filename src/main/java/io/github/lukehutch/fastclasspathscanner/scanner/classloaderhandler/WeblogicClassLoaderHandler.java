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
package io.github.lukehutch.fastclasspathscanner.scanner.classloaderhandler;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;

import java.lang.reflect.Method;

public class WeblogicClassLoaderHandler implements ClassLoaderHandler {
    @Override
    public boolean handle(final ClassLoader classloader, final ClasspathFinder classpathFinder) throws Exception {
        for (Class<?> c = classloader.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("weblogic.utils.classloaders.ChangeAwareClassLoader")) {
                final Method getClassPath = c.getDeclaredMethod("getClassPath");
                if (!getClassPath.isAccessible()) {
                    getClassPath.setAccessible(true);
                }
                final String classpath = (String) getClassPath.invoke(classloader);
                classpathFinder.addClasspathElements(classpath);
                return true;
            }
        }
        return false;
    }
}
