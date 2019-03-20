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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import com.xyz.meta.A;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;

/**
 * ClassLoadingWorksWithParentLastLoaders.
 */
public class ClassLoadingWorksWithParentLastLoaders {
    /**
     * Assert correct class loaders.
     *
     * @param parentClassLoader
     *            the parent class loader
     * @param expectedClassLoader
     *            the expected class loader
     * @throws Exception
     *             the exception
     */
    public void assertCorrectClassLoaders(final String parentClassLoader, final String expectedClassLoader)
            throws Exception {
        final A a = new A();
        // Checking the precondition here: We forced our classloader onto "everything"
        assertThat(ClassLoadingWorksWithParentLastLoaders.class.getClassLoader().getClass().getSimpleName())
                .isEqualTo(expectedClassLoader);
        assertThat(a.getClass().getClassLoader().getClass().getSimpleName()).isEqualTo(expectedClassLoader);

        final ClassGraph classGraph = new ClassGraph().whitelistPackages("com.xyz.meta").enableAllInfo();

        // ClassGraph is in that setup not part of the RestartClass loader. That one takes by default only
        // URLs from the current project into consideration and can only be modified by adding additional
        // directories, see https://github.com/spring-projects/spring-boot/issues/12869
        assertThat(classGraph.getClass().getClassLoader().getClass().getSimpleName()).isEqualTo(parentClassLoader);

        // Now use ClassGraph to find everything
        try (ScanResult scanResult = classGraph.scan()) {
            // Skip the rest of the test if scan() round-tripped through JSON serialization (which is done to test
            // the correctness of JSON serialization and deserialization), since this replaces the classloader with
            // ClassGraphClassLoader.
            if (scanResult.isObtainedFromDeserialization()) {
                return;
            }
            final ClassInfo classInfo = scanResult.getAllClasses().filter(new ClassInfoList.ClassInfoFilter() {
                @Override
                public boolean accept(final ClassInfo classInfo) {
                    return "A".equals(classInfo.getSimpleName());
                }
            }).get(0);

            // ClassGraph finds "A" through the RestartClass Loader
            final Field classLoadersField = classInfo.getClass().getDeclaredField("classLoader");
            classLoadersField.setAccessible(true);
            assertThat(((ClassLoader) classLoadersField.get(classInfo)).getClass().getSimpleName())
                    .isEqualTo(expectedClassLoader);

            // And it should load it through the same class loader it found it with
            final Class<?> aClassLoadedThroughClassGraph = classInfo.loadClass();
            assertThat(aClassLoadedThroughClassGraph.getClassLoader().getClass().getSimpleName())
                    .isEqualTo(expectedClassLoader);
            // and thus assignable
            assertThat(a.getClass().isAssignableFrom(aClassLoadedThroughClassGraph)).isTrue();
        }
    }
}
