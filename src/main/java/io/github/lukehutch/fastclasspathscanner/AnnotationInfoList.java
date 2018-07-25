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
import java.util.List;

/** A list of {@link AnnotationInfo} objects. */
public class AnnotationInfoList extends ArrayList<AnnotationInfo> {

    public AnnotationInfoList() {
        super();
    }

    public AnnotationInfoList(final int sizeHint) {
        super(sizeHint);
    }

    public AnnotationInfoList(final Collection<AnnotationInfo> annotationInfoCollection) {
        super(annotationInfoCollection);
    }

    static final AnnotationInfoList EMPTY_LIST = new AnnotationInfoList() {
        @Override
        public boolean add(final AnnotationInfo e) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public void add(final int index, final AnnotationInfo element) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public boolean remove(final Object o) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public AnnotationInfo remove(final int index) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public boolean addAll(final Collection<? extends AnnotationInfo> c) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public boolean addAll(final int index, final Collection<? extends AnnotationInfo> c) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public boolean removeAll(final Collection<?> c) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public boolean retainAll(final Collection<?> c) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public void clear() {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public AnnotationInfo set(final int index, final AnnotationInfo element) {
            throw new IllegalArgumentException("List  is immutable");
        }
    };

    // -------------------------------------------------------------------------------------------------------------

    /** Get the names of all annotations in this list. */
    public List<String> getNames() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> classNames = new ArrayList<>(this.size());
            for (final AnnotationInfo ai : this) {
                classNames.add(ai.getName());
            }
            return classNames;
        }
    }

    /**
     * Get the string representations of all annotations in this list (with meta-annotations, etc.), by calling
     * {@link AnnotationInfo#toString()} on each item in the list.
     */
    public List<String> getAsStrings() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> toStringVals = new ArrayList<>(this.size());
            for (final AnnotationInfo ai : this) {
                toStringVals.add(ai.toString());
            }
            return toStringVals;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Return true if this list contains the annotation with the given name. */
    public boolean containsName(final String annotationName) {
        for (final AnnotationInfo ai : this) {
            if (ai.getName().equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    /** Return the {@link AnnotationInfo} object in the list with the given name, or null if not found. */
    public AnnotationInfo get(final String annotationName) {
        for (final AnnotationInfo ai : this) {
            if (ai.getName().equals(annotationName)) {
                return ai;
            }
        }
        return null;
    }
}
