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
package io.github.classgraph.issues.issue267;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

import org.junit.Test;

public class ClassLoadingWorksWithParentLastLoadersStub {

    @Test
    public void sameClassLoaderThatFoundAClassShouldLoadIt() throws Exception {
        final String currentClassLoadersName = Thread.currentThread().getContextClassLoader().getClass()
                .getSimpleName();

        new ClassLoadingWorksWithParentLastLoaders().assertCorrectClassLoaders(currentClassLoadersName,
                currentClassLoadersName);

        final TestLauncher launcher = new TestLauncher(currentClassLoadersName);
        launcher.start();
        launcher.join();
    }
}

class TestLauncher extends Thread {

    private final String parentClassLoader;

    TestLauncher(final String parentClassLoader) {
        this.parentClassLoader = parentClassLoader;
        setDaemon(false);
        setContextClassLoader(new FakeRestartClassLoader());
    }

    @Override
    public void run() {
        try {
            final Class<?> mainClass = getContextClassLoader()
                    .loadClass("io.github.classgraph.issues.issue267.ClassLoadingWorksWithParentLastLoaders");
            final Method mainMethod = mainClass.getDeclaredMethod("assertCorrectClassLoaders", String.class,
                    String.class);
            mainMethod.invoke(mainClass.getDeclaredConstructor().newInstance(),
                    new Object[] { parentClassLoader, "FakeRestartClassLoader" });
        } catch (final Throwable ex) {
            getUncaughtExceptionHandler().uncaughtException(this, ex);
        }
    }
}

class FakeRestartClassLoader extends ClassLoader {
    private Class getClass(final String name) throws ClassNotFoundException {
        try {
            final byte[] b = loadClassFileData(name.replace('.', File.separatorChar) + ".class");
            return defineClass(name, b, 0, b.length);
        } catch (final IOException e) {
            throw new ClassNotFoundException(name);
        }
    }

    @Override
    public Class loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("com.xyz.meta.A")
                || name.startsWith("io.github.classgraph.issues.issue267.ClassLoadingWorksWithParentLastLoaders")) {
            final Class clazz = getClass(name);
            if (resolve) {
                resolveClass(clazz);
            }
        }
        return super.loadClass(name, resolve);
    }

    private byte[] loadClassFileData(final String name) throws IOException {
        try (final DataInputStream in = new DataInputStream(
                getClass().getClassLoader().getResourceAsStream(name))) {
            final int size = in.available();
            final byte buff[] = new byte[size];
            in.readFully(buff);
            return buff;
        }
    }
}
