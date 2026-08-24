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
 * Copyright (c) 2026 Luke Hutchison
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
package io.github.classgraph.issues.issue209;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URLClassLoader;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;

public class Issue209Test {
    /**
     * The three parts of a Spring Boot jar -- the launcher's own classes at the root, the application's classes
     * under {@code BOOT-INF/classes}, and the application's dependencies under {@code BOOT-INF/lib} -- are all
     * scannable, and the classfile of each is readable, including the ones in a jar nested within the Spring Boot
     * jar. The Spring Boot launcher gives its classloader a URL for each of those parts; here they are named on an
     * overridden classpath instead, since a plain {@link URLClassLoader} handed the jar loads only from its root.
     */
    @Test
    public void testSpringBootJarWithLibJars() throws IOException {
        final var classNames = List.of(
                // Test reading from /
                "org.springframework.boot.loader.util.SystemPropertyUtils",
                // Test reading from /BOOT-INF/classes
                "com.foo.externalApp.ExternalAppApplication", "com.foo.externalApp.SomeClass",
                // Test reading from /BOOT-INF/lib/*.jar
                "issue209lib.Issue209Lib");
        final var jarURL = Issue209Test.class.getClassLoader().getResource("issue209.jar");
        try (var result = new ClassGraph()
                .acceptPackages("org.springframework.boot.loader.util", "com.foo", "issue209lib") //
                .enableClasspathEntries(List.of(jarURL.toString(), jarURL + "!/BOOT-INF/classes",
                        jarURL + "!/BOOT-INF/lib/issue209lib.jar"))
                .scan()) {
            assertThat(result.getAllClasses().getNames()).hasSameElementsAs(classNames);
            // The classfile of each class must be readable, including the classfile of the class that is in a jar
            // nested within the Spring Boot jar
            for (final var className : classNames) {
                final var classInfo = result.getClassInfo(className);
                assertThat(classInfo).isNotNull();
                final var classfileResource = classInfo.getResource();
                assertThat(classfileResource).isNotNull();
                assertThat(classfileResource.load()).isNotEmpty();
            }
        }
    }
}
