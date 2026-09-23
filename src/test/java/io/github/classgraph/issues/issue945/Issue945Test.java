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
package io.github.classgraph.issues.issue945;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.AnnotationParameterValueList;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.FieldInfoList;
import io.github.classgraph.InfoList;
import io.github.classgraph.MappableInfoList;
import io.github.classgraph.MethodInfoList;
import io.github.classgraph.ModuleInfoList;
import io.github.classgraph.PackageInfoList;
import io.github.classgraph.ResourceList;

/**
 * The public list classes extend a package-private class. JDK 8 javac does not emit public bridge methods in a
 * public subclass for the generic methods that the package-private class overrides, and mockk then fails to
 * mock the list class with "class redefinition failed: attempted to add a method".
 */
public class Issue945Test {
    /** Every public method of a public list class is declared in a public class. */
    @Test
    public void publicMethodsAreDeclaredInPublicClasses() {
        final List<String> methodsInNonPublicClasses = new ArrayList<>();
        for (final Class<?> listClass : new Class<?>[] { ResourceList.class, InfoList.class,
                MappableInfoList.class, ClassInfoList.class, MethodInfoList.class, FieldInfoList.class,
                AnnotationInfoList.class, AnnotationParameterValueList.class, ModuleInfoList.class,
                PackageInfoList.class }) {
            for (final Method method : listClass.getMethods()) {
                if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                    methodsInNonPublicClasses.add(listClass.getSimpleName() + ": " + method);
                }
            }
        }
        assertThat(methodsInNonPublicClasses).isEmpty();
    }
}
