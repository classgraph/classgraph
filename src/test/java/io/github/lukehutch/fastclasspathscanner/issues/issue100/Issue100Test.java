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
package io.github.lukehutch.fastclasspathscanner.issues.issue100;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Issue100Test {
    @Test
    public void issue100Test() {
        final ClassLoader classLoader = Issue100Test.class.getClassLoader();
        final String aJarName = "issue100-has-field-a.zip";
        final URL aJarURL = classLoader.getResource(aJarName);
        final String bJarName = "issue100-has-field-b.zip";
        final URL bJarURL = classLoader.getResource(bJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL, bJarURL });

        // Class issue100.Test with field "a" should mask class of same name with field "b", because "...a.jar" is
        // earlier in classpath than "...b.jar"
        final ArrayList<String> fieldNames1 = new ArrayList<>();
        new FastClasspathScanner("issue100") //
                .overrideClassLoaders(overrideClassLoader).matchAllClasses(klass -> {
                    for (final Field f : klass.getFields()) {
                        fieldNames1.add(f.getName());
                    }
                }).scan();
        assertThat(fieldNames1).containsOnly("a");

        // However, if "...b.jar" is specifically whitelisted, "...a.jar" should not be visible. Originally, the
        // version of the class in "...a.jar" was supposed to mask the same class in "...b.jar" (#100). However,
        // this resulted in a slowdown in scan time (#117). Also, even if "...b.jar" is whitelisted, scanning with a
        // MatchProcessor (which calls the current classloader) will actually end up loading "...a.jar". Therefore,
        // the official stance is that using jar whitelisting/blacklisting in cases where a class is defined
        // multiple times will result in undefined behavior.
        final ArrayList<String> fieldNames2 = new ArrayList<>();
        new FastClasspathScanner("issue100", "jar:" + bJarName) //
                .overrideClassLoaders(overrideClassLoader).matchAllClasses(klass -> {
                    for (final Field f : klass.getFields()) {
                        fieldNames2.add(f.getName());
                    }
                }).scan();
        assertThat(fieldNames2).containsOnly("a");
    }
}
