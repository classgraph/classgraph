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

import com.xyz.meta.A;

import io.github.classgraph.ClassGraph;

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
        final var a = new A();
        // Checking the precondition here: We forced our classloader onto "everything"
        assertThat(ClassLoadingWorksWithParentLastLoaders.class.getClassLoader().getClass().getSimpleName())
                .isEqualTo(expectedClassLoader);
        assertThat(a.getClass().getClassLoader().getClass().getSimpleName()).isEqualTo(expectedClassLoader);

        final var classGraph = new ClassGraph().acceptPackages("com.xyz.meta").enableAllInfo();

        // ClassGraph is in that setup not part of the RestartClass loader. That one takes by default only URLs from
        // the current project into consideration and can only be modified by adding additional directories, see
        // https://github.com/spring-projects/spring-boot/issues/12869
        assertThat(classGraph.getClass().getClassLoader().getClass().getSimpleName()).isEqualTo(parentClassLoader);

        // Now use ClassGraph to find everything
        try (var scanResult = classGraph.scan()) {
            final var classInfo = scanResult.getAllClasses()
                    .filter(classInfo1 -> "A".equals(classInfo1.getSimpleName())).get(0);

            // ClassGraph finds "A" through the RestartClass Loader, and reports that classloader, so that the class
            // can be loaded through the same classloader that it was found with
            final var classInfoClassLoader = classInfo.getClassLoader();
            assertThat(classInfoClassLoader).isNotNull();
            assertThat(classInfoClassLoader.getClass().getSimpleName()).isEqualTo(expectedClassLoader);

            final var aClassLoadedThroughReportedClassLoader = Class.forName(classInfo.getName(),
                    /* initialize = */ false, classInfoClassLoader);
            assertThat(aClassLoadedThroughReportedClassLoader.getClassLoader().getClass().getSimpleName())
                    .isEqualTo(expectedClassLoader);
            // and thus assignable
            assertThat(a.getClass().isAssignableFrom(aClassLoadedThroughReportedClassLoader)).isTrue();
        }
    }
}
