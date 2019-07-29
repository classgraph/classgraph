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
package io.github.classgraph.issues.issue267;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;

import org.junit.jupiter.api.Test;

import com.xyz.meta.A;

/**
 * ClassLoadingWorksWithParentLastLoadersStub.
 */
public class ClassLoadingWorksWithParentLastLoadersStubTest {

    /**
     * Same class loader that found A class should load it.
     *
     * @throws Throwable
     *             the throwable
     */
    @Test
    public void sameClassLoaderThatFoundAClassShouldLoadIt() throws Throwable {
        final String currentClassLoadersName = Thread.currentThread().getContextClassLoader().getClass()
                .getSimpleName();

        new ClassLoadingWorksWithParentLastLoaders().assertCorrectClassLoaders(currentClassLoadersName,
                currentClassLoadersName);

        final TestLauncher launcher = new TestLauncher(currentClassLoadersName);
        launcher.start();
        launcher.join();
        if (launcher.thrown != null) {
            throw launcher.thrown;
        }
    }
}

class TestLauncher extends Thread {

    private final String parentClassLoader;

    Throwable thrown;

    TestLauncher(final String parentClassLoader) {
        this.parentClassLoader = parentClassLoader;
        setDaemon(false);
        setContextClassLoader(new FakeRestartClassLoader());
    }

    @Override
    public void run() {
        try {
            final Class<?> mainClass = getContextClassLoader()
                    .loadClass(ClassLoadingWorksWithParentLastLoaders.class.getName());
            final Method mainMethod = mainClass.getDeclaredMethod("assertCorrectClassLoaders", String.class,
                    String.class);
            mainMethod.invoke(mainClass.getDeclaredConstructor().newInstance(), parentClassLoader,
                    "FakeRestartClassLoader");
        } catch (final Throwable t) {
            thrown = t;
        }
    }
}

class FakeRestartClassLoader extends ClassLoader {
    private Class<?> getClass(final String name) throws ClassNotFoundException {
        try {
            final byte[] b = loadClassFileData(name.replace('.', File.separatorChar) + ".class");
            return defineClass(name, b, 0, b.length);
        } catch (final IOException e) {
            throw new ClassNotFoundException(name);
        }
    }

    @Override
    public Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(A.class.getName())
                || name.startsWith(ClassLoadingWorksWithParentLastLoaders.class.getName())) {
            final Class<?> clazz = getClass(name);
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

    public String getClasspath() {
        final String classfileName = A.class.getName().replace('.', '/') + ".class";
        final URL classfileResource = getClass().getClassLoader().getResource(classfileName);
        if (classfileResource == null) {
            throw new IllegalArgumentException("Could not find classfile " + classfileName);
        }
        final String classfilePath = classfileResource.getFile();
        final String packageRoot = classfilePath.substring(0, classfilePath.length() - classfileName.length() - 1);
        return packageRoot;
    }
}
