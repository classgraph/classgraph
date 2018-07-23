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
package io.github.lukehutch.fastclasspathscanner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A list of {@link MethodInfo} objects. */
public class MethodInfoList extends ArrayList<MethodInfo> {

    public MethodInfoList() {
        super();
    }

    public MethodInfoList(final int sizeHint) {
        super(sizeHint);
    }

    public MethodInfoList(final Collection<MethodInfo> methodInfoCollection) {
        super(methodInfoCollection);
    }

    static final MethodInfoList EMPTY_LIST = new MethodInfoList() {
        @Override
        public boolean add(final MethodInfo e) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public void add(final int index, final MethodInfo element) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean remove(final Object o) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public MethodInfo remove(final int index) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final Collection<? extends MethodInfo> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final int index, final Collection<? extends MethodInfo> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean removeAll(final Collection<?> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean retainAll(final Collection<?> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public void clear() {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public MethodInfo set(final int index, final MethodInfo element) {
            throw new IllegalArgumentException("List is immutable");
        }
    };

    /** Get the names of all methods in this list. */
    public List<String> getMethodNames() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> classNames = new ArrayList<>(this.size());
            for (final MethodInfo mi : this) {
                classNames.add(mi.getMethodName());
            }
            return classNames;
        }
    }

    /** Return true if this list contains a method with the given name. */
    public boolean containsMethodNamed(final String methodName) {
        for (final MethodInfo mi : this) {
            if (mi.getMethodName().equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a list of all unique annotation types on the methods in this list, as a list of {@link ClassInfo}
     * objects.
     */
    public ClassInfoList getUniqueMethodAnnotationTypes(final String methodName) {
        final Set<ClassInfo> allUniqueAnnotations = new HashSet<>();
        for (final MethodInfo mi : this) {
            for (final AnnotationInfo ai : mi.getAnnotationInfo()) {
                allUniqueAnnotations.add(ai.getClassInfo());
            }
        }
        return new ClassInfoList(allUniqueAnnotations, allUniqueAnnotations);
    }

    /**
     * Returns a list of all unique annotation types on the parameters of methods in this list, as a list of
     * {@link ClassInfo} objects.
     */
    public ClassInfoList getUniqueMethodParameterAnnotationTypes(final String methodName) {
        final Set<ClassInfo> allUniqueAnnotations = new HashSet<>();
        for (final MethodInfo mi : this) {
            final AnnotationInfo[][] pi = mi.getParameterAnnotationInfo();
            if (pi != null) {
                for (int i = 0; i < pi.length; i++) {
                    final AnnotationInfo[] pai = pi[i];
                    for (int j = 0; j < pai.length; j++) {
                        final AnnotationInfo parameterAnnotationInfo = pai[j];
                        allUniqueAnnotations.add(parameterAnnotationInfo.getClassInfo());
                    }
                }
            }
        }
        return new ClassInfoList(allUniqueAnnotations, allUniqueAnnotations);
    }
}
