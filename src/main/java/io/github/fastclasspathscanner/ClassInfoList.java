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
package io.github.fastclasspathscanner;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.fastclasspathscanner.ClassInfo.ReachableAndDirectlyRelatedClasses;

/**
 * A list of {@link ClassInfo} objects, which stores both reachable classes (obtained through a given class
 * relationship, either by direct relationship or through an indirect path), and directly related classes (classes
 * reachable through a direct relationship only).
 * 
 * <p>
 * By default, this list returns reachable classes. By calling {@link #directOnly()}, you can get the directly
 * related classes.
 */
public class ClassInfoList extends ArrayList<ClassInfo> {

    private final Set<ClassInfo> directlyRelatedClasses;

    /**
     * A list of {@link ClassInfo} objects, consisting of reachable classes (obtained through the transitive
     * closure) and directly related classes (one step away in the graph).
     */
    ClassInfoList(final Set<ClassInfo> reachableClasses, final Set<ClassInfo> directlyRelatedClasses) {
        super(reachableClasses);
        // It's a bit dicey calling Collections.sort(this) from within a constructor, but the super-constructor
        // has been called, so it should be fine :-)
        Collections.sort(this);
        // If directlyRelatedClasses was not provided, then assume all reachable classes were directly related
        this.directlyRelatedClasses = directlyRelatedClasses == null ? reachableClasses : directlyRelatedClasses;
    }

    /** A list of {@link ClassInfo} objects. */
    ClassInfoList(final ReachableAndDirectlyRelatedClasses reachableAndDirectlyRelatedClasses) {
        this(reachableAndDirectlyRelatedClasses.reachableClasses,
                reachableAndDirectlyRelatedClasses.directlyRelatedClasses);
    }

    /** A list of {@link ClassInfo} objects, where each class is directly related. */
    ClassInfoList(final Set<ClassInfo> reachableClasses) {
        this(reachableClasses, null);
    }

    private ClassInfoList() {
        super(1);
        directlyRelatedClasses = Collections.<ClassInfo> emptySet();
    }

    /** Unmodifiable empty ClassInfoList. */
    static final ClassInfoList EMPTY_LIST = new ClassInfoList() {
        @Override
        public boolean add(final ClassInfo e) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public void add(final int index, final ClassInfo element) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public boolean remove(final Object o) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public ClassInfo remove(final int index) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public boolean addAll(final Collection<? extends ClassInfo> c) {
            throw new IllegalArgumentException("List  is immutable");
        }

        @Override
        public boolean addAll(final int index, final Collection<? extends ClassInfo> c) {
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
        public ClassInfo set(final int index, final ClassInfo element) {
            throw new IllegalArgumentException("List  is immutable");
        }
    };

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Convert this list of {@link ClassInfo} objects to a list of {@code Class<?>} objects, casting each item in
     * the list to the requested superclass or interface type. Causes the classloader to load the class named by
     * each {@link ClassInfo} object, if it is not already loaded.
     * 
     * <p>
     * <b>Important note:</b> since {@code superclassOrInterfaceType} is a class reference for an already-loaded
     * class, it is critical that {@code superclassOrInterfaceType} is loaded by the same classloader as the class
     * referred to by this {@code ClassInfo} object, otherwise the class cast will fail.
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
    public <T> List<Class<T>> loadClasses(final Class<T> superclassOrInterfaceType,
            final boolean ignoreExceptions) {
        if (this.isEmpty()) {
            return Collections.<Class<T>> emptyList();
        } else {
            final List<Class<T>> classRefs = new ArrayList<>();
            for (final ClassInfo classInfo : this) {
                final Class<T> classRef = classInfo.loadClass(superclassOrInterfaceType, ignoreExceptions);
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
     * <b>Important note:</b> since {@code superclassOrInterfaceType} is a class reference for an already-loaded
     * class, it is critical that {@code superclassOrInterfaceType} is loaded by the same classloader as the class
     * referred to by this {@code ClassInfo} object, otherwise the class cast will fail.
     * 
     * @param superclassOrInterfaceType
     *            The type to cast each loaded class to.
     * @throws IllegalArgumentException
     *             if an exception or error was thrown while trying to load or cast any of the classes.
     * @return The loaded {@code Class<?>} objects corresponding to each {@link ClassInfo} object in this list.
     */
    public <T> List<Class<T>> loadClasses(final Class<T> superclassOrInterfaceType) {
        return loadClasses(superclassOrInterfaceType, /* ignoreExceptions = */ false);
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
    public List<Class<?>> loadClasses(final boolean ignoreExceptions) {
        if (this.isEmpty()) {
            return Collections.<Class<?>> emptyList();
        } else {
            final List<Class<?>> classRefs = new ArrayList<>();
            // Try loading each class
            for (final ClassInfo classInfo : this) {
                final Class<?> classRef = classInfo.loadClass(ignoreExceptions);
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
    public List<Class<?>> loadClasses() {
        return loadClasses(/* ignoreExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Get the names of all classes in this list. */
    public List<String> getNames() {
        if (this.isEmpty()) {
            return Collections.<String> emptyList();
        } else {
            final List<String> names = new ArrayList<>(this.size());
            for (final ClassInfo ci : this) {
                names.add(ci.getName());
            }
            return names;
        }
    }

    /**
     * Get the string representations of all classes in this list (with annotations, modifiers, etc.), by calling
     * {@link ClassInfo#toString()} on each item in the list.
     */
    public List<String> getAsStrings() {
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
    public boolean containsName(final String className) {
        for (final ClassInfo ci : this) {
            if (ci.getName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    /** Return the {@link ClassInfo} object in the list with the given name, or null if not found. */
    public ClassInfo get(final String name) {
        for (final ClassInfo ci : this) {
            if (ci.getName().equals(name)) {
                return ci;
            }
        }
        return null;
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
        return filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo classInfo) {
                return directlyRelatedClasses.contains(classInfo);
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the union of this {@link ClassInfoList} with one or more others.
     *
     * @param others
     *            The other {@link ClassInfoList}s to union with this one.
     * @return The union of this {@link ClassInfoList} with the others.
     */
    public ClassInfoList union(final ClassInfoList... others) {
        final Set<ClassInfo> reachableClassesUnion = new HashSet<>(this);
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
     * Find the intersection of this {@link ClassInfoList} with one or more others.
     *
     * @param others
     *            The other {@link ClassInfoList}s to intersect with this one.
     * @return The intersection of this {@link ClassInfoList} with the others.
     */
    public ClassInfoList intersect(final ClassInfoList... others) {
        final Set<ClassInfo> reachableClassesIntersection = new HashSet<>(this);
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
     * Find the set difference between this {@link ClassInfoList} and another {@link ClassInfoList}, i.e. (this \
     * other).
     *
     * @param other
     *            The other {@link ClassInfoList} to subtract from this one.
     * @return The set difference of this {@link ClassInfoList} and other, i.e. (this \ other).
     */
    public ClassInfoList exclude(final ClassInfoList other) {
        final Set<ClassInfo> reachableClassesDifference = new HashSet<>(this);
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
     * Filter a {@link ClassInfoList} using a predicate mapping a {@link ClassInfo} object to a boolean, producing
     * another {@link ClassInfoList} for all items in the list for which the predicate is true.
     */
    @FunctionalInterface
    public interface ClassInfoFilter {
        /**
         * Whether or not to allow a {@link ClassInfo} list item through the filter.
         *
         * @param classInfo
         *            The {@link ClassInfo} item to filter.
         * @return Whether or not to allow the item through the filter. If true, the item is copied to the output
         *         list; if false, it is excluded.
         */
        public boolean accept(ClassInfo classInfo);
    }

    /**
     * Find the subset of this {@link ClassInfoList} for which the given filter predicate is true.
     *
     * @param filter
     *            The {@link ClassInfoFilter} to apply.
     * @return The subset of this {@link ClassInfoList} for which the given filter predicate is true.
     */
    public ClassInfoList filter(final ClassInfoFilter filter) {
        final Set<ClassInfo> reachableClassesFiltered = new HashSet<>(size());
        for (final ClassInfo ci : this) {
            if (filter.accept(ci)) {
                reachableClassesFiltered.add(ci);
            }
        }
        final Set<ClassInfo> directlyRelatedClassesFiltered = new HashSet<>();
        for (final ClassInfo ci : directlyRelatedClasses) {
            if (filter.accept(ci)) {
                directlyRelatedClassesFiltered.add(ci);
            }
        }
        return new ClassInfoList(reachableClassesFiltered, directlyRelatedClassesFiltered);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Filter this {@link ClassInfoList} to include only standard classes (classes that are not interfaces or
     * annotations).
     * 
     * @return The filtered list, containing only standard classes.
     */
    public ClassInfoList getStandardClasses() {
        final Set<ClassInfo> reachableClassesFiltered = new HashSet<>(size());
        for (final ClassInfo classInfo : this) {
            if (classInfo.isStandardClass()) {
                reachableClassesFiltered.add(classInfo);
            }
        }
        final Set<ClassInfo> directlyRelatedClassesFiltered = new HashSet<>(directlyRelatedClasses.size());
        for (final ClassInfo classInfo : directlyRelatedClasses) {
            if (classInfo.isStandardClass()) {
                directlyRelatedClassesFiltered.add(classInfo);
            }
        }
        return new ClassInfoList(reachableClassesFiltered, directlyRelatedClassesFiltered);
    }

    /**
     * Filter this {@link ClassInfoList} to include only interfaces that are not annotations. See also
     * {@link #getInterfacesAndAnnotations()}.
     * 
     * @return The filtered list, containing only interfaces.
     */
    public ClassInfoList getInterfaces() {
        final Set<ClassInfo> reachableClassesFiltered = new HashSet<>(size());
        for (final ClassInfo classInfo : this) {
            if (classInfo.isInterface()) {
                reachableClassesFiltered.add(classInfo);
            }
        }
        final Set<ClassInfo> directlyRelatedClassesFiltered = new HashSet<>(directlyRelatedClasses.size());
        for (final ClassInfo classInfo : directlyRelatedClasses) {
            if (classInfo.isInterface()) {
                directlyRelatedClassesFiltered.add(classInfo);
            }
        }
        return new ClassInfoList(reachableClassesFiltered, directlyRelatedClassesFiltered);
    }

    /**
     * Filter this {@link ClassInfoList} to include only interfaces and annotations (annotations are interfaces, and
     * can be implemented). See also {@link #getInterfaces()}.
     * 
     * @return The filtered list, containing only interfaces.
     */
    public ClassInfoList getInterfacesAndAnnotations() {
        final Set<ClassInfo> reachableClassesFiltered = new HashSet<>(size());
        for (final ClassInfo classInfo : this) {
            if (classInfo.isInterfaceOrAnnotation()) {
                reachableClassesFiltered.add(classInfo);
            }
        }
        final Set<ClassInfo> directlyRelatedClassesFiltered = new HashSet<>(directlyRelatedClasses.size());
        for (final ClassInfo classInfo : directlyRelatedClasses) {
            if (classInfo.isInterfaceOrAnnotation()) {
                directlyRelatedClassesFiltered.add(classInfo);
            }
        }
        return new ClassInfoList(reachableClassesFiltered, directlyRelatedClassesFiltered);
    }

    /**
     * Filter this {@link ClassInfoList} to include only implemented interfaces, i.e. non-annotation interfaces, or
     * annotations that have been implemented by a class.
     * 
     * @return The filtered list, containing only implemented interfaces.
     */
    public ClassInfoList getImplementedInterfaces() {
        final Set<ClassInfo> reachableClassesFiltered = new HashSet<>(size());
        for (final ClassInfo classInfo : this) {
            if (classInfo.isImplementedInterface()) {
                reachableClassesFiltered.add(classInfo);
            }
        }
        final Set<ClassInfo> directlyRelatedClassesFiltered = new HashSet<>(directlyRelatedClasses.size());
        for (final ClassInfo classInfo : directlyRelatedClasses) {
            if (classInfo.isImplementedInterface()) {
                directlyRelatedClassesFiltered.add(classInfo);
            }
        }
        return new ClassInfoList(reachableClassesFiltered, directlyRelatedClassesFiltered);
    }

    /**
     * Filter this {@link ClassInfoList} to include only annotations.
     * 
     * @return The filtered list, containing only annotations.
     */
    public ClassInfoList getAnnotations() {
        final Set<ClassInfo> reachableClassesFiltered = new HashSet<>(size());
        for (final ClassInfo classInfo : this) {
            if (classInfo.isAnnotation()) {
                reachableClassesFiltered.add(classInfo);
            }
        }
        final Set<ClassInfo> directlyRelatedClassesFiltered = new HashSet<>(directlyRelatedClasses.size());
        for (final ClassInfo classInfo : directlyRelatedClasses) {
            if (classInfo.isAnnotation()) {
                directlyRelatedClassesFiltered.add(classInfo);
            }
        }
        return new ClassInfoList(reachableClassesFiltered, directlyRelatedClassesFiltered);
    }

    /**
     * Filter this {@link ClassInfoList} to include only {@link Enum} classes.
     * 
     * @return The filtered list, containing only enums.
     */
    public ClassInfoList getEnums() {
        final Set<ClassInfo> reachableClassesFiltered = new HashSet<>(size());
        for (final ClassInfo classInfo : this) {
            if (classInfo.isEnum()) {
                reachableClassesFiltered.add(classInfo);
            }
        }
        final Set<ClassInfo> directlyRelatedClassesFiltered = new HashSet<>(directlyRelatedClasses.size());
        for (final ClassInfo classInfo : directlyRelatedClasses) {
            if (classInfo.isEnum()) {
                directlyRelatedClassesFiltered.add(classInfo);
            }
        }
        return new ClassInfoList(reachableClassesFiltered, directlyRelatedClassesFiltered);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * sizeX and sizeY parameters are the image output size to use (in inches) when GraphViz is asked to render the
     * .dot file.
     * 
     * <p>
     * Note that if you call this with showFields or showMethods set to false, but with method and/or field info
     * enabled during scanning, then arrows will still be added between classes even if the field or method that
     * created that dependency is not shown.
     *
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout width in inches.
     * @param showFields
     *            If true, show fields within class nodes in the graph. To show fields,
     *            {@link FastClasspathScanner#enableFieldInfo()} must be called before scanning. You may also want
     *            to call {@link FastClasspathScanner#ignoreFieldVisibility()} before scanning, to show non-public
     *            fields.
     * @param showMethods
     *            If true, show methods within class nodes in the graph. To show methods,
     *            {@link FastClasspathScanner#enableMethodInfo()} must be called before scanning. You may also want
     *            to call {@link FastClasspathScanner#ignoreMethodVisibility()} before scanning, to show non-public
     *            methods.
     * @param showAnnotations
     *            If true, show annotations in the graph. To show annotations,
     *            {@link FastClasspathScanner#enableAnnotationInfo()} must be called before scanning. You may also
     *            want to call {@link FastClasspathScanner#ignoreClassVisibility()} before scanning, to show
     *            non-public classes (including non-public annotation classes).
     * @return the GraphViz file contents.
     */
    public String generateGraphVizDotFile(final float sizeX, final float sizeY, final boolean showFields,
            final boolean showMethods, final boolean showAnnotations) {
        final ScanSpec scanSpec = size() == 0 ? null : get(0).scanResult.scanSpec;
        if (scanSpec == null || !scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call FastClasspathScanner#enableClassInfo() before #scan()");
        }
        return GraphvizDotfileGenerator.generateClassGraphDotFile(this, sizeX, sizeY, showFields, showMethods,
                showAnnotations, scanSpec);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph.
     * 
     * <p>
     * Methods, fields and annotations are shown if enabled, via {@link FastClasspathScanner#enableMethodInfo()},
     * {@link FastClasspathScanner#enableFieldInfo()} and {@link FastClasspathScanner#enableAnnotationInfo()}.
     * 
     * <p>
     * Only public classes, methods, and fields are shown, unless
     * {@link FastClasspathScanner#ignoreClassVisibility()}, {@link FastClasspathScanner#ignoreMethodVisibility()},
     * and/or {@link FastClasspathScanner#ignoreFieldVisibility()} has/have been called.
     *
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout width in inches.
     * @return the GraphViz file contents.
     */
    public String generateGraphVizDotFile(final float sizeX, final float sizeY) {
        return generateGraphVizDotFile(sizeX, sizeY, /* showFields = */ true, /* showMethods = */ true,
                /* showAnnotations = */ true);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph.
     * 
     * <p>
     * Methods, fields and annotations are shown if enabled, via {@link FastClasspathScanner#enableMethodInfo()},
     * {@link FastClasspathScanner#enableFieldInfo()} and {@link FastClasspathScanner#enableAnnotationInfo()}.
     * 
     * <p>
     * Only public classes, methods, and fields are shown, unless
     * {@link FastClasspathScanner#ignoreClassVisibility()}, {@link FastClasspathScanner#ignoreMethodVisibility()},
     * and/or {@link FastClasspathScanner#ignoreFieldVisibility()} has/have been called.
     *
     * @return the GraphViz file contents.
     */
    public String generateGraphVizDotFile() {
        return generateGraphVizDotFile(/* sizeX = */ 10.5f, /* sizeY = */ 8f, /* showFields = */ true,
                /* showMethods = */ true, /* showAnnotations = */ true);
    }

    /**
     * Generate a and save a .dot file, which can be fed into GraphViz for layout and visualization of the class
     * graph.
     * 
     * <p>
     * Methods, fields and annotations are shown if enabled, via {@link FastClasspathScanner#enableMethodInfo()},
     * {@link FastClasspathScanner#enableFieldInfo()} and {@link FastClasspathScanner#enableAnnotationInfo()}.
     * 
     * <p>
     * Only public classes, methods, and fields are shown, unless
     * {@link FastClasspathScanner#ignoreClassVisibility()}, {@link FastClasspathScanner#ignoreMethodVisibility()},
     * and/or {@link FastClasspathScanner#ignoreFieldVisibility()} has/have been called.
     *
     * @param file
     *            the file to save the GraphViz .dot file to.
     * @throws IOException
     *             if the file could not be saved.
     */
    public void generateGraphVizDotFile(final File file) throws IOException {
        try (final PrintWriter writer = new PrintWriter(file)) {
            writer.print(generateGraphVizDotFile());
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append('[');
        for (int i = 0, n = size(); i < n; i++) {
            if (i > 0) {
                buf.append(", ");
            }
            buf.append(get(i));
        }
        buf.append(']');
        return buf.toString();
    }
}
