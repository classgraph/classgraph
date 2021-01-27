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
package io.github.classgraph.issues.issue495;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;

/**
 * Test.
 */
public class Issue495Test {
    /**
     * Test.
     *
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    @Test
    public void testScalaTypeSignatures() throws Exception {
        final URL resourceURL = Issue495Test.class.getClassLoader().getResource("scalapackage.zip");
        assertThat(resourceURL).isNotNull();
        assertThat(new File(resourceURL.toURI())).canRead();
        final ClassLoader classLoader = new URLClassLoader(new URL[] { resourceURL }, null);
        try (ScanResult scanResult = new ClassGraph() //
                .enableClassInfo().enableInterClassDependencies() //
                .acceptPackages("scalapackage") //
                .overrideClassLoaders(classLoader) //
                .scan()) {
            final ClassInfoList allClasses = scanResult.getAllClasses();
            assertThat(allClasses.getNames()).containsOnly("scalapackage.ScalaClass");
            final ClassInfo scalaClassInfo = allClasses.get(0);
            assertThat(scalaClassInfo.getTypeSignature()).isNotNull();
            final Class<?> scalaClassClass = scalaClassInfo.loadClass();
            assertThat(scalaClassClass).isNotNull();
        }
    }
}
