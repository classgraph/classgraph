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
package io.github.lukehutch.fastclasspathscanner.issues.issue209;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

public class Issue209Test {
    @Test
    public void issue209Test() {
        final ClassLoader classLoader = Issue209Test.class.getClassLoader();
        final String aJarName = "issue209.jar";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        final ScanResult result = new FastClasspathScanner("com.foo") //
                .overrideClassLoaders(overrideClassLoader) //
                .verbose() //
                .scan();

        final Collection<ClassInfo> cis = result.getClassNameToClassInfo().values();
        final List<String> classNames = new ArrayList<>();
        for (final ClassInfo ci : cis) {
            final Class<?> classRef = ci.getClassRef();
            classNames.add(classRef.getName());
        }
        assertThat(classNames).containsOnly("com.foo.externalApp.ExternalAppApplication",
                "com.foo.externalApp.SomeClass");
    }
}
