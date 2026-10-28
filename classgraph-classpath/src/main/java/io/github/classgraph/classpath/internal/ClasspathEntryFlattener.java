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
package io.github.classgraph.classpath.internal;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Flattens nested arrays and {@link Iterable} instances containing classpath entries.
 */
final class ClasspathEntryFlattener {
    /**
     * Stack frame tracking an active array or {@link Iterable} expansion.
     *
     * @param container
     *            the array or iterable being expanded, or null for the root frame.
     * @param elements
     *            the iterator over the container's elements.
     */
    private record Expansion(@Nullable Object container, Iterator<?> elements) {
    }

    /** Constructor. */
    private ClasspathEntryFlattener() {
        // Cannot be constructed
    }

    /**
     * Applies {@code action} to each flattened classpath entry in {@code object}, expanding nested arrays and
     * {@link Iterable} instances (except {@link Path} and self-yielding non-{@link Collection} iterables such as
     * Apache Ant {@code Resource} instances) while guarding against cycles.
     *
     * @param object
     *            the classpath entry, or a nested array or {@link Iterable} of classpath entries.
     * @param action
     *            predicate applied to each leaf classpath entry.
     * @return true if {@code action} returned true for at least one entry.
     */
    static boolean forEachEntry(final @Nullable Object object, final Predicate<@Nullable Object> action) {
        // The arrays and Iterables that enclose the element being expanded, compared by identity, since the
        // hashCode() of a collection that contains itself overflows the stack
        final var expanding = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        final var stack = new ArrayDeque<Expansion>();
        stack.push(new Expansion(null, Collections.singletonList(object).iterator()));
        var accepted = false;
        while (!stack.isEmpty()) {
            final var frame = stack.peek();
            if (!frame.elements.hasNext()) {
                stack.pop();
                if (frame.container != null) {
                    expanding.remove(frame.container);
                }
                continue;
            }
            final var element = frame.elements.next();
            // A Path is tested for before Iterable is, since a Path is an Iterable of its own name elements
            if (element instanceof Path || !(element instanceof Iterable<?> || element instanceof Object[])) {
                accepted |= action.test(element);
            } else if (!expanding.add(element)) {
                // The string form of an array or Collection names no file, and the string form of a Collection that
                // holds itself through another Collection recurses until the stack overflows, so it is skipped. Any
                // other Iterable that is met again, such as an Ant Resource, which yields only itself, is one entry
                if (!(element instanceof Collection<?> || element instanceof Object[])) {
                    accepted |= action.test(element);
                }
            } else {
                final Iterable<?> elements = element instanceof Object[] array ? Arrays.asList(array)
                        : (Iterable<?>) element;
                stack.push(new Expansion(element, elements.iterator()));
            }
        }
        return accepted;
    }
}
