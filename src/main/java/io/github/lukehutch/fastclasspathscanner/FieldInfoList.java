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

/** A list of {@link FieldInfo} objects. */
public class FieldInfoList extends ArrayList<FieldInfo> {

    public FieldInfoList() {
        super();
    }

    public FieldInfoList(final int sizeHint) {
        super(sizeHint);
    }

    public FieldInfoList(final Collection<FieldInfo> fieldInfoCollection) {
        super(fieldInfoCollection);
    }

    static final FieldInfoList EMPTY_LIST = new FieldInfoList() {
        @Override
        public boolean add(final FieldInfo e) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public void add(final int index, final FieldInfo element) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean remove(final Object o) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public FieldInfo remove(final int index) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final Collection<? extends FieldInfo> c) {
            throw new IllegalArgumentException("List is immutable");
        }

        @Override
        public boolean addAll(final int index, final Collection<? extends FieldInfo> c) {
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
        public FieldInfo set(final int index, final FieldInfo element) {
            throw new IllegalArgumentException("List is immutable");
        }
    };

    /** Get the names of all fields in this list. */
    public List<String> getFieldNames() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> classNames = new ArrayList<>(this.size());
            for (final FieldInfo fi : this) {
                classNames.add(fi.getFieldName());
            }
            return classNames;
        }
    }

    /** Return true if this list contains a field with the given name. */
    public boolean containsFieldNamed(final String fieldName) {
        for (final FieldInfo fi : this) {
            if (fi.getFieldName().equals(fieldName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a list of all unique annotation types on the fields in this list, as a list of {@link ClassInfo}
     * objects.
     */
    public ClassInfoList getUniqueFieldAnnotationTypes(final String fieldName) {
        final Set<ClassInfo> allUniqueAnnotations = new HashSet<>();
        for (final FieldInfo fi : this) {
            for (final AnnotationInfo ai : fi.getAnnotationInfo()) {
                allUniqueAnnotations.add(ai.getClassInfo());
            }
        }
        return new ClassInfoList(allUniqueAnnotations, allUniqueAnnotations);
    }
}
