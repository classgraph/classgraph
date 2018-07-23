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
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

/**
 * A list of {@link ClassInfo} objects, which stores both reachable classes (obtained through a given class
 * relationship, either by direct relationship or through an indirect path), and directly related classes (classes
 * reachable through a direct relationship only).
 * 
 * <p>
 * By default, this list returns reachable classes. By calling {@link #directOnly()}, you can get the directly
 * related classes.
 */
public class ClassInfoList implements List<ClassInfo> {

    private final List<ClassInfo> reachableClasses;
    private final ClassInfoList directlyRelatedClasses;

    /** A list of {@link ClassInfo} objects. */
    public ClassInfoList(final List<ClassInfo> reachableClasses, final List<ClassInfo> directlyRelatedClasses) {
        this.reachableClasses = reachableClasses == null ? Collections.<ClassInfo> emptyList() : reachableClasses;
        Collections.sort(reachableClasses);
        // Make directlyRelatedClasses idempotent
        final List<ClassInfo> directlyRelatedClassesNotNull = directlyRelatedClasses == null
                ? Collections.<ClassInfo> emptyList()
                : directlyRelatedClasses;
        this.directlyRelatedClasses = (reachableClasses == directlyRelatedClasses) ? this
                : new ClassInfoList(directlyRelatedClassesNotNull, directlyRelatedClassesNotNull);
    }

    /** A list of {@link ClassInfo} objects. */
    public ClassInfoList(final Collection<ClassInfo> reachableClasses,
            final Collection<ClassInfo> directlyRelatedClasses) {
        this(reachableClasses == null ? null : new ArrayList<>(reachableClasses),
                directlyRelatedClasses == null ? null : new ArrayList<>(directlyRelatedClasses));
    }

    /** A list of {@link ClassInfo} objects. */
    public ClassInfoList(final Collection<ClassInfo> reachableClasses) {
        this(reachableClasses, reachableClasses);
    }

    /** Unmodifiable empty ClassInfoList. */
    static final ClassInfoList EMPTY_LIST = new ClassInfoList(Collections.<ClassInfo> emptyList(), null);

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int size() {
        return reachableClasses.size();
    }

    @Override
    public boolean isEmpty() {
        return reachableClasses.isEmpty();
    }

    @Override
    public ClassInfo get(final int index) {
        return reachableClasses.get(index);
    }

    @Override
    public boolean contains(final Object o) {
        return reachableClasses.contains(o);
    }

    @Override
    public boolean containsAll(final Collection<?> c) {
        return reachableClasses.containsAll(c);
    }

    @Override
    public Object[] toArray() {
        return reachableClasses.toArray();
    }

    @Override
    public <T> T[] toArray(final T[] a) {
        return reachableClasses.toArray(a);
    }

    @Override
    public int indexOf(final Object o) {
        return reachableClasses.indexOf(o);
    }

    @Override
    public int lastIndexOf(final Object o) {
        return reachableClasses.lastIndexOf(o);
    }

    @Override
    public Iterator<ClassInfo> iterator() {
        return reachableClasses.iterator();
    }

    @Override
    public ListIterator<ClassInfo> listIterator() {
        return reachableClasses.listIterator();
    }

    @Override
    public ListIterator<ClassInfo> listIterator(final int index) {
        return reachableClasses.listIterator(index);
    }

    @Override
    public List<ClassInfo> subList(final int fromIndex, final int toIndex) {
        return reachableClasses.subList(fromIndex, toIndex);
    }

    @Override
    public boolean add(final ClassInfo e) {
        throw new IllegalArgumentException(ClassInfoList.class.getSimpleName() + " is immutable");
    }

    @Override
    public void add(final int index, final ClassInfo element) {
        throw new IllegalArgumentException(ClassInfoList.class.getSimpleName() + " is immutable");
    }

    @Override
    public boolean remove(final Object o) {
        throw new IllegalArgumentException(ClassInfoList.class.getSimpleName() + " is immutable");
    }

    @Override
    public ClassInfo remove(final int index) {
        throw new IllegalArgumentException(ClassInfoList.class.getSimpleName() + " is immutable");
    }

    @Override
    public boolean addAll(final Collection<? extends ClassInfo> c) {
        throw new IllegalArgumentException(ClassInfoList.class.getSimpleName() + " is immutable");
    }

    @Override
    public boolean addAll(final int index, final Collection<? extends ClassInfo> c) {
        throw new IllegalArgumentException(ClassInfoList.class.getSimpleName() + " is immutable");
    }

    @Override
    public boolean removeAll(final Collection<?> c) {
        throw new IllegalArgumentException(ClassInfoList.class.getSimpleName() + " is immutable");
    }

    @Override
    public boolean retainAll(final Collection<?> c) {
        throw new IllegalArgumentException(ClassInfoList.class.getSimpleName() + " is immutable");
    }

    @Override
    public void clear() {
        throw new IllegalArgumentException(ClassInfoList.class.getSimpleName() + " is immutable");
    }

    @Override
    public ClassInfo set(final int index, final ClassInfo element) {
        throw new IllegalArgumentException(ClassInfoList.class.getSimpleName() + " is immutable");
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Convert this list of {@link ClassInfo} objects to a list of {@code Class<?>} objects, casting each item in
     * the list to the requested superclass or interface type. Causes the classloader to load the class named by
     * each {@link ClassInfo} object, if it is not already loaded.
     * 
     * <p>
     * Important note: since {@code superclassOrInterfaceType} is a class reference for an already-loaded class, it
     * is critical that {@code superclassOrInterfaceType} is loaded by the same classloader as the class referred to
     * by each {@code ClassInfo} object, otherwise the class cast will fail.
     * 
     * @param superclassOrInterfaceType
     *            The type to cast each loaded class to.
     * @param ignoreExceptions
     *            If true, ignore any exceptions or errors thrown during classloading, or when attempting to cast
     *            the resulting {@code Class<?>} reference to the requested type. If an exception or error is
     *            thrown, no {@code Class<?>} reference is added to the output class for the corresponding
     *            {@link ClassInfo} object, so the returned list may contain fewer items than this input list. If
     *            false, {@link IllegalArgumentException} is thrown if the class could not be loaded or cast to the
     *            requested type.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and an exception or error was thrown while trying to load or cast
     *             any of the classes.
     * @return The loaded {@code Class<?>} objects corresponding to each {@link ClassInfo} object in this list.
     */
    public <T> List<Class<T>> getClassRefs(final Class<T> superclassOrInterfaceType,
            final boolean ignoreExceptions) {
        if (this.isEmpty()) {
            return Collections.<Class<T>> emptyList();
        } else {
            final List<Class<T>> classRefs = new ArrayList<>();
            for (final ClassInfo classInfo : this) {
                final Class<T> classRef = classInfo.scanResult.loadClass(classInfo.getClassName(),
                        superclassOrInterfaceType, ignoreExceptions);
                if (classRef != null) {
                    classRefs.add(classRef);
                }
            }
            return classRefs.isEmpty() ? Collections.<Class<T>> emptyList() : classRefs;
        }
    }

    /**
     * Convert this list of {@link ClassInfo} objects to a list of {@code Class<?>} objects, casting each item in
     * the list to the requested superclass or interface type. Causes the classloader to load the class named by
     * each {@link ClassInfo} object, if it is not already loaded.
     * 
     * <p>
     * Important note: since {@code superclassOrInterfaceType} is a class reference for an already-loaded class, it
     * is critical that {@code superclassOrInterfaceType} is loaded by the same classloader as the class referred to
     * by each {@code ClassInfo} object, otherwise the class cast will fail.
     * 
     * @param superclassOrInterfaceType
     *            The type to cast each loaded class to.
     * @throws IllegalArgumentException
     *             if an exception or error was thrown while trying to load or cast any of the classes.
     * @return The loaded {@code Class<?>} objects corresponding to each {@link ClassInfo} object in this list.
     */
    public <T> List<Class<T>> getClassRefs(final Class<T> superclassOrInterfaceType) {
        return getClassRefs(superclassOrInterfaceType, /* ignoreExceptions = */ false);
    }

    /**
     * Convert this list of {@link ClassInfo} objects to a list of {@code Class<?>} objects. Causes the classloader
     * to load the class named by each {@link ClassInfo} object, if it is not already loaded.
     * 
     * @param ignoreExceptions
     *            If true, ignore any exceptions or errors thrown during classloading. If an exception or error is
     *            thrown during classloading, no {@code Class<?>} reference is added to the output class for the
     *            corresponding {@link ClassInfo} object, so the returned list may contain fewer items than this
     *            input list. If false, {@link IllegalArgumentException} is thrown if the class could not be loaded.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and an exception or error was thrown while trying to load any of the
     *             classes.
     * @return The loaded {@code Class<?>} objects corresponding to each {@link ClassInfo} object in this list.
     */
    public List<Class<?>> getClassRefs(final boolean ignoreExceptions) {
        if (this.isEmpty()) {
            return Collections.<Class<?>> emptyList();
        } else {
            final List<Class<?>> classRefs = new ArrayList<>();
            // Try loading each class
            for (final ClassInfo classInfo : this) {
                final Class<?> classRef = classInfo.scanResult.loadClass(classInfo.getClassName(),
                        ignoreExceptions);
                if (classRef != null) {
                    classRefs.add(classRef);
                }
            }
            return classRefs.isEmpty() ? Collections.<Class<?>> emptyList() : classRefs;
        }
    }

    /**
     * Convert this list of {@link ClassInfo} objects to a list of {@code Class<?>} objects. Causes the classloader
     * to load the class named by each {@link ClassInfo} object, if it is not already loaded.
     * 
     * @throws IllegalArgumentException
     *             if an exception or error was thrown while trying to load any of the classes.
     * @return The loaded {@code Class<?>} objects corresponding to each {@link ClassInfo} object in this list.
     */
    public List<Class<?>> getClassRefs() {
        return getClassRefs(/* ignoreExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Get the names of all classes in this list. */
    public List<String> getClassNames() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> classNames = new ArrayList<>(this.size());
            for (final ClassInfo ci : this) {
                classNames.add(ci.getClassName());
            }
            return classNames;
        }
    }

    /**
     * Get the string representations of all classes in this list (with annotations, modifiers, etc.), obtained by
     * calling {@link ClassInfo#toString()}.
     */
    public List<String> getClassStrs() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> toStringVals = new ArrayList<>(this.size());
            for (final ClassInfo ci : this) {
                toStringVals.add(ci.toString());
            }
            return toStringVals;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Return true if this {@link ClassInfo} list contains a class with the given name. */
    public boolean containsClassNamed(final String className) {
        for (final ClassInfo ci : this) {
            if (ci.getClassName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the list of classes that were directly related, as opposed to reachable through multiple steps. For
     * example, if this {@link ClassInfoList} was produced by querying for all superclasses of a given class, then
     * {@link #directOnly()} will return only the direct superclass of this class.
     * 
     * @return The list of directly-related classes.
     */
    public ClassInfoList directOnly() {
        // If directlyRelatedClasses is not set, just return this, so that directlyRelatedClasses() is idempotent.
        return directlyRelatedClasses == null ? this : directlyRelatedClasses;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the union of this ClassInfoList with one or more others.
     *
     * @param others
     *            The other {@link ClassInfoList}s to union with this one.
     * @return The union of this {@link ClassInfoList} with the others.
     */
    public ClassInfoList union(final ClassInfoList... others) {
        final Set<ClassInfo> reachableClassesUnion = new HashSet<>(reachableClasses);
        final Set<ClassInfo> directlyRelatedClassesUnion = new HashSet<>();
        if (directlyRelatedClasses != null) {
            directlyRelatedClassesUnion.addAll(directlyRelatedClasses);
        }
        for (final ClassInfoList other : others) {
            reachableClassesUnion.addAll(other);
            if (other.directlyRelatedClasses != null) {
                directlyRelatedClassesUnion.addAll(other.directlyRelatedClasses);
            }
        }
        return new ClassInfoList(reachableClassesUnion, directlyRelatedClassesUnion);
    }

    /**
     * Find the intersection of this ClassInfoList with one or more others.
     *
     * @param others
     *            The other {@link ClassInfoList}s to intersect with this one.
     * @return The intersection of this {@link ClassInfoList} with the others.
     */
    public ClassInfoList intersect(final ClassInfoList... others) {
        final Set<ClassInfo> reachableClassesIntersection = new HashSet<>(reachableClasses);
        final Set<ClassInfo> directlyRelatedClassesIntersection = new HashSet<>();
        if (directlyRelatedClasses != null) {
            directlyRelatedClassesIntersection.addAll(directlyRelatedClasses);
        }
        for (final ClassInfoList other : others) {
            reachableClassesIntersection.retainAll(other);
            if (other.directlyRelatedClasses != null) {
                directlyRelatedClassesIntersection.retainAll(other.directlyRelatedClasses);
            }
        }
        return new ClassInfoList(reachableClassesIntersection, directlyRelatedClassesIntersection);
    }

    /**
     * Find the set difference between this ClassInfoList and another ClassInfoList, i.e. (this \ other).
     *
     * @param other
     *            The other {@link ClassInfoList} to subtract from this one.
     * @return The set difference of this {@link ClassInfoList} and other, i.e. (this \ other).
     */
    public ClassInfoList exclude(final ClassInfoList other) {
        final Set<ClassInfo> reachableClassesDifference = new HashSet<>(reachableClasses);
        final Set<ClassInfo> directlyRelatedClassesDifference = new HashSet<>();
        if (directlyRelatedClasses != null) {
            directlyRelatedClassesDifference.addAll(directlyRelatedClasses);
        }
        reachableClassesDifference.removeAll(other);
        if (other.directlyRelatedClasses != null) {
            directlyRelatedClassesDifference.removeAll(other.directlyRelatedClasses);
        }
        return new ClassInfoList(reachableClassesDifference, directlyRelatedClassesDifference);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Filter this {@link ClassInfoList} to include only standard classes (classes that are not interfaces or
     * annotations).
     * 
     * @return The filtered list, containing only standard classes.
     */
    public ClassInfoList getStandardClasses() {
        final List<ClassInfo> reachableClassesFiltered = new ArrayList<>(reachableClasses.size());
        for (final ClassInfo classInfo : reachableClasses) {
            if (classInfo.isStandardClass()) {
                reachableClassesFiltered.add(classInfo);
            }
        }
        if (directlyRelatedClasses == reachableClasses) {
            // Avoid duplicating work
            return new ClassInfoList(reachableClassesFiltered, reachableClassesFiltered);
        } else {
            final List<ClassInfo> directlyRelatedClassesFiltered = new ArrayList<>(directlyRelatedClasses.size());
            for (final ClassInfo classInfo : directlyRelatedClasses) {
                if (classInfo.isStandardClass()) {
                    directlyRelatedClassesFiltered.add(classInfo);
                }
            }
            return new ClassInfoList(reachableClassesFiltered, directlyRelatedClassesFiltered);
        }
    }

    /**
     * Filter this {@link ClassInfoList} to include only interfaces (N.B. this includes annotations, since they are
     * technically interfaces, and can be implemented). See also {@link #getImplementedInterfaces()}.
     * 
     * @return The filtered list, containing only interfaces.
     */
    public ClassInfoList getInterfaces() {
        final List<ClassInfo> reachableClassesFiltered = new ArrayList<>(reachableClasses.size());
        for (final ClassInfo classInfo : reachableClasses) {
            if (classInfo.isInterface()) {
                reachableClassesFiltered.add(classInfo);
            }
        }
        if (directlyRelatedClasses == reachableClasses) {
            // Avoid duplicating work
            return new ClassInfoList(reachableClassesFiltered, reachableClassesFiltered);
        } else {
            final List<ClassInfo> directlyRelatedClassesFiltered = new ArrayList<>(directlyRelatedClasses.size());
            for (final ClassInfo classInfo : directlyRelatedClasses) {
                if (classInfo.isInterface()) {
                    directlyRelatedClassesFiltered.add(classInfo);
                }
            }
            return new ClassInfoList(reachableClassesFiltered, directlyRelatedClassesFiltered);
        }
    }

    /**
     * Filter this {@link ClassInfoList} to include only implemented interfaces, i.e. non-annotation interfaces, or
     * annotations that have been implemented by a class.
     * 
     * @return The filtered list, containing only implemented interfaces.
     */
    public ClassInfoList getImplementedInterfaces() {
        final List<ClassInfo> reachableClassesFiltered = new ArrayList<>(reachableClasses.size());
        for (final ClassInfo classInfo : reachableClasses) {
            if (classInfo.isInterface()) {
                reachableClassesFiltered.add(classInfo);
            }
        }
        if (directlyRelatedClasses == reachableClasses) {
            // Avoid duplicating work
            return new ClassInfoList(reachableClassesFiltered, reachableClassesFiltered);
        } else {
            final List<ClassInfo> directlyRelatedClassesFiltered = new ArrayList<>(directlyRelatedClasses.size());
            for (final ClassInfo classInfo : directlyRelatedClasses) {
                if (classInfo.isInterface()) {
                    directlyRelatedClassesFiltered.add(classInfo);
                }
            }
            return new ClassInfoList(reachableClassesFiltered, directlyRelatedClassesFiltered);
        }
    }

    /**
     * Filter this {@link ClassInfoList} to include only annotations.
     * 
     * @return The filtered list, containing only annotations.
     */
    public ClassInfoList getAnnotations() {
        final List<ClassInfo> reachableClassesFiltered = new ArrayList<>(reachableClasses.size());
        for (final ClassInfo classInfo : reachableClasses) {
            if (classInfo.isInterface()) {
                reachableClassesFiltered.add(classInfo);
            }
        }
        if (directlyRelatedClasses == reachableClasses) {
            // Avoid duplicating work
            return new ClassInfoList(reachableClassesFiltered, reachableClassesFiltered);
        } else {
            final List<ClassInfo> directlyRelatedClassesFiltered = new ArrayList<>(directlyRelatedClasses.size());
            for (final ClassInfo classInfo : directlyRelatedClasses) {
                if (classInfo.isInterface()) {
                    directlyRelatedClassesFiltered.add(classInfo);
                }
            }
            return new ClassInfoList(reachableClassesFiltered, directlyRelatedClassesFiltered);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append('[');
        for (int i = 0, n = reachableClasses.size(); i < n; i++) {
            if (i > 0) {
                buf.append(", ");
            }
            buf.append(reachableClasses.get(i));
        }
        buf.append(']');
        return buf.toString();
    }
}
