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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Expands the arrays and {@link Iterable}s that hold classpath entries, nested to any depth. The classpath entries
 * that a classloader handler adds and the ones passed to
 * {@link ScanSourceSpec#enableClasspathEntries(java.util.List)} are expanded by this one rule, so that the same
 * object names the same classpath entries on both.
 */
final class ClasspathEntryFlattener {
    /** Constructor. */
    private ClasspathEntryFlattener() {
        // Cannot be constructed
    }

    /**
     * Pass each classpath entry held by an object to an action, in order. An array of objects, or an
     * {@link Iterable} other than a {@link Path} (which is an {@link Iterable} of its own name elements), is
     * replaced by its own elements, to any depth. Any other object, including null and an array of primitives, is
     * one classpath entry.
     *
     * <p>
     * An array or {@link Iterable} that is met again while its own elements are being passed on is not expanded
     * again, which would never end: an array or {@link Collection} is skipped, since every entry it holds is
     * already being passed on by the call that is expanding it, and any other {@link Iterable} is passed on as one
     * classpath entry. (An Apache Ant {@code FileResource} is an {@link Iterable} that yields only itself, and its
     * string form is the path of its file.)
     *
     * @param object
     *            the classpath entry, or the array or {@link Iterable} of classpath entries.
     * @param action
     *            the action to apply to each classpath entry, which returns true if it added the entry.
     * @return true if the action returned true for at least one classpath entry.
     */
    static boolean forEachEntry(final @Nullable Object object, final Predicate<@Nullable Object> action) {
        // Compared by identity, since the hashCode() of a collection that contains itself overflows the stack
        return forEachEntry(object, action, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Pass each classpath entry held by an object to an action.
     *
     * @param object
     *            the classpath entry, or the array or {@link Iterable} of classpath entries.
     * @param action
     *            the action to apply to each classpath entry.
     * @param expanding
     *            the arrays and {@link Iterable}s that are being expanded by the callers of this call, compared by
     *            identity.
     * @return true if the action returned true for at least one classpath entry.
     */
    private static boolean forEachEntry(final @Nullable Object object, final Predicate<@Nullable Object> action,
            final Set<Object> expanding) {
        // A Path is tested for before Iterable is, since a Path is an Iterable of its own name elements
        if (object instanceof Path || !(object instanceof Iterable<?> || object instanceof Object[])) {
            return action.test(object);
        }
        if (!expanding.add(object)) {
            // The string form of an array or Collection names no file, and the string form of a Collection that
            // holds itself through another Collection recurses until the stack overflows, so it is skipped. Any
            // other Iterable that is met again, such as an Ant Resource, which yields only itself, is one entry
            return !(object instanceof Collection<?> || object instanceof Object[]) && action.test(object);
        }
        var accepted = false;
        try {
            final Iterable<?> elements = object instanceof final Object[] array ? Arrays.asList(array)
                    : (Iterable<?>) object;
            for (final @Nullable Object element : elements) {
                accepted |= forEachEntry(element, action, expanding);
            }
        } finally {
            expanding.remove(object);
        }
        return accepted;
    }
}
