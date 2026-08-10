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
package io.github.classgraph;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serial;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import io.github.classgraph.ClassInfo.ReachableAndDirectlyRelatedClasses;
import nonapi.io.github.classgraph.utils.Assert;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import org.jspecify.annotations.Nullable;

/**
 * A <i>uniquified</i> (deduplicated) list of {@link ClassInfo} objects, which stores both reachable classes
 * (obtained through a given class relationship, either by direct relationship or through an indirect path), and
 * directly related classes (classes reachable through a direct relationship only). (By default, accessing a
 * {@link ClassInfoList} as a {@link List} returns only reachable classes; by calling {@link #directOnly()}, you can
 * get the directly related classes.)
 *
 * <p>
 * Most {@link ClassInfoList} objects returned by ClassGraph are sorted into lexicographical order by the value of
 * {@link ClassInfo#getName()}. One exception to this is the classes returned by
 * {@link ClassInfo#getAllSuperclasses()}, which are in ascending order of the class hierarchy.
 */
public class ClassInfoList extends MappableInfoList<ClassInfo> {
    /** Directly related classes. */
    // N.B. this is marked transient to keep Scrutinizer happy, since this class extends ArrayList, which is
    // Serializable, so all fields must be serializable (and Set is an interface, so is not Serializable). Marking
    // this transient will mean direct relationships will be lost on serialization, but the Serializable interface
    // is not widely used today anyway.
    private final transient Set<ClassInfo> directlyRelatedClasses;

    /** Whether to sort by name. */
    private final boolean sortByName;

    /** serialVersionUID */
    @Serial
    private static final long serialVersionUID = 1L;

    /** An unmodifiable empty {@link ClassInfoList}. */
    static final ClassInfoList EMPTY_LIST = new ClassInfoList();
    static {
        EMPTY_LIST.makeUnmodifiable();
    }

    /**
     * Return an unmodifiable empty {@link ClassInfoList}.
     *
     * @return the unmodifiable empty {@link ClassInfoList}.
     */
    public static ClassInfoList emptyList() {
        return EMPTY_LIST;
    }

    /**
     * Constructor.
     *
     * @param reachableClasses
     *            reachable classes
     * @param directlyRelatedClasses
     *            directly related classes
     * @param sortByName
     *            whether to sort by name
     * @param modifiable
     *            whether the list may be modified after construction
     */
    private ClassInfoList(final Set<ClassInfo> reachableClasses,
            final @Nullable Set<ClassInfo> directlyRelatedClasses, final boolean sortByName,
            final boolean modifiable) {
        // Sort a copy of the classes before handing them to the superclass constructor, rather than sorting this
        // list once it has been built, so that a partly-initialized instance is never passed to another method
        super(sortByName ? CollectionUtils.sortCopy(reachableClasses) : reachableClasses);
        this.sortByName = sortByName;
        // If directlyRelatedClasses was not provided, then assume all reachable classes were directly related
        this.directlyRelatedClasses = directlyRelatedClasses == null ? reachableClasses : directlyRelatedClasses;
        this.modifiable = modifiable;
    }

    /**
     * Construct an unmodifiable list of {@link ClassInfo} objects, consisting of reachable classes (obtained
     * through the transitive closure) and directly related classes (one step away in the graph). This is the
     * constructor used to build the result lists returned by the public API, which are all unmodifiable.
     *
     * @param reachableClasses
     *            reachable classes
     * @param directlyRelatedClasses
     *            directly related classes
     * @param sortByName
     *            whether to sort by name
     */
    ClassInfoList(final Set<ClassInfo> reachableClasses, final @Nullable Set<ClassInfo> directlyRelatedClasses,
            final boolean sortByName) {
        this(reachableClasses, directlyRelatedClasses, sortByName, /* modifiable = */ false);
    }

    /**
     * Construct an unmodifiable list of {@link ClassInfo} objects.
     *
     * @param reachableAndDirectlyRelatedClasses
     *            reachable and directly related classes
     * @param sortByName
     *            whether to sort by name
     */
    ClassInfoList(final ReachableAndDirectlyRelatedClasses reachableAndDirectlyRelatedClasses,
            final boolean sortByName) {
        this(reachableAndDirectlyRelatedClasses.reachableClasses(),
                reachableAndDirectlyRelatedClasses.directlyRelatedClasses(), sortByName);
    }

    /**
     * Construct an unmodifiable list of {@link ClassInfo} objects, where each class is directly related.
     *
     * @param reachableClasses
     *            reachable classes
     * @param sortByName
     *            whether to sort by name
     */
    ClassInfoList(final Set<ClassInfo> reachableClasses, final boolean sortByName) {
        this(reachableClasses, /* directlyRelatedClasses = */ null, sortByName);
    }

    /**
     * Construct a new empty modifiable list of {@link ClassInfo} objects.
     */
    public ClassInfoList() {
        super(1);
        this.sortByName = false;
        directlyRelatedClasses = new HashSet<>(2);
    }

    /**
     * Construct a new empty modifiable list of {@link ClassInfo} objects, given a size hint.
     *
     * @param sizeHint
     *            the expected number of elements
     */
    public ClassInfoList(final int sizeHint) {
        super(sizeHint);
        this.sortByName = false;
        directlyRelatedClasses = new HashSet<>(2);
    }

    /**
     * Construct a new modifiable empty {@link ClassInfoList}, given an initial list of {@link ClassInfo} objects.
     *
     * <p>
     * If the passed {@link Collection} is not a {@link Set}, then the {@link ClassInfo} objects will be uniquified
     * (by adding them to a set) before they are added to the returned list. {@link ClassInfo} objects in the
     * returned list will be sorted by name.
     *
     * @param classInfoCollection
     *            the initial collection of {@link ClassInfo} objects to add to the {@link ClassInfoList}.
     */
    public ClassInfoList(final Collection<ClassInfo> classInfoCollection) {
        this(Objects.requireNonNull(classInfoCollection,
                "classInfoCollection must not be null") instanceof final Set<ClassInfo> classInfoSet //
                        ? classInfoSet
                        : new HashSet<>(classInfoCollection), //
                /* directlyRelatedClasses = */ null, /* sortByName = */ true, /* modifiable = */ true);
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
        return new ClassInfoList(directlyRelatedClasses, directlyRelatedClasses, sortByName);
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
        Assert.notNullElements(others, "others");
        final Set<ClassInfo> reachableClassesUnion = new LinkedHashSet<>(this);
        final Set<ClassInfo> directlyRelatedClassesUnion = new LinkedHashSet<>(directlyRelatedClasses);
        for (final ClassInfoList other : others) {
            reachableClassesUnion.addAll(other);
            directlyRelatedClassesUnion.addAll(other.directlyRelatedClasses);
        }
        return new ClassInfoList(reachableClassesUnion, directlyRelatedClassesUnion, sortByName);
    }

    /**
     * Find the intersection of this {@link ClassInfoList} with one or more others.
     *
     * @param others
     *            The other {@link ClassInfoList}s to intersect with this one.
     * @return The intersection of this {@link ClassInfoList} with the others.
     */
    public ClassInfoList intersect(final ClassInfoList... others) {
        Assert.notNullElements(others, "others");
        // Put the first ClassInfoList that is not being sorted by name at the head of the list, so that its order
        // is preserved in the intersection (#238)
        final ArrayDeque<ClassInfoList> intersectionOrder = new ArrayDeque<>();
        intersectionOrder.add(this);
        var foundFirst = false;
        for (final ClassInfoList other : others) {
            if (other.sortByName) {
                intersectionOrder.add(other);
            } else if (!foundFirst) {
                foundFirst = true;
                intersectionOrder.push(other);
            } else {
                intersectionOrder.add(other);
            }
        }
        final var first = intersectionOrder.remove();
        final Set<ClassInfo> reachableClassesIntersection = new LinkedHashSet<>(first);
        while (!intersectionOrder.isEmpty()) {
            reachableClassesIntersection.retainAll(intersectionOrder.remove());
        }
        final Set<ClassInfo> directlyRelatedClassesIntersection = new LinkedHashSet<>(directlyRelatedClasses);
        for (final ClassInfoList other : others) {
            directlyRelatedClassesIntersection.retainAll(other.directlyRelatedClasses);
        }
        return new ClassInfoList(reachableClassesIntersection, directlyRelatedClassesIntersection,
                first.sortByName);
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
        Assert.notNull(other, "other");
        final Set<ClassInfo> reachableClassesDifference = new LinkedHashSet<>(this);
        final Set<ClassInfo> directlyRelatedClassesDifference = new LinkedHashSet<>(directlyRelatedClasses);
        reachableClassesDifference.removeAll(other);
        directlyRelatedClassesDifference.removeAll(other.directlyRelatedClasses);
        return new ClassInfoList(reachableClassesDifference, directlyRelatedClassesDifference, sortByName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the subset of this {@link ClassInfoList} for which the given filter predicate is true.
     *
     * @param filter
     *            The filter to apply. Only the {@link ClassInfo} objects for which the filter returns true are
     *            copied to the returned list.
     * @return The subset of this {@link ClassInfoList} for which the given filter predicate is true.
     */
    public ClassInfoList filter(final Predicate<ClassInfo> filter) {
        Assert.notNull(filter, "filter");
        final Set<ClassInfo> reachableClassesFiltered = new LinkedHashSet<>(size());
        final Set<ClassInfo> directlyRelatedClassesFiltered = new LinkedHashSet<>(directlyRelatedClasses.size());
        for (final ClassInfo ci : this) {
            if (filter.test(ci)) {
                reachableClassesFiltered.add(ci);
                if (directlyRelatedClasses.contains(ci)) {
                    directlyRelatedClassesFiltered.add(ci);
                }
            }
        }
        return new ClassInfoList(reachableClassesFiltered, directlyRelatedClassesFiltered, sortByName);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Filter this {@link ClassInfoList} to include only standard classes (classes that are not interfaces or
     * annotations).
     *
     * @return The filtered list, containing only standard classes.
     */
    public ClassInfoList getStandardClasses() {
        return filter(ClassInfo::isStandardClass);
    }

    /**
     * Filter this {@link ClassInfoList} to include only interfaces that are not annotations. See also
     * {@link #getInterfacesAndAnnotations()}.
     *
     * @return The filtered list, containing only interfaces.
     */
    public ClassInfoList getInterfaces() {
        return filter(ClassInfo::isInterface);
    }

    /**
     * Filter this {@link ClassInfoList} to include only interfaces and annotations (annotations are interfaces, and
     * can be implemented). See also {@link #getInterfaces()}.
     *
     * @return The filtered list, containing only interfaces.
     */
    public ClassInfoList getInterfacesAndAnnotations() {
        return filter(ClassInfo::isInterfaceOrAnnotation);
    }

    /**
     * Filter this {@link ClassInfoList} to include only implemented interfaces, i.e. non-annotation interfaces, or
     * annotations that have been implemented by a class.
     *
     * @return The filtered list, containing only implemented interfaces.
     */
    public ClassInfoList getImplementedInterfaces() {
        return filter(ClassInfo::isImplementedInterface);
    }

    /**
     * Filter this {@link ClassInfoList} to include only annotations.
     *
     * @return The filtered list, containing only annotations.
     */
    public ClassInfoList getAnnotations() {
        return filter(ClassInfo::isAnnotation);
    }

    /**
     * Filter this {@link ClassInfoList} to include only {@link Enum} classes.
     *
     * @return The filtered list, containing only enums.
     */
    public ClassInfoList getEnums() {
        return filter(ClassInfo::isEnum);
    }

    /**
     * Filter this {@link ClassInfoList} to include only {@code record} classes.
     *
     * @return The filtered list, containing only {@code record} classes.
     */
    public ClassInfoList getRecords() {
        return filter(ClassInfo::isRecord);
    }

    /**
     * Filter this {@link ClassInfoList} to include only classes that are assignable to the requested class,
     * assignableToClass (i.e. where assignableToClass is a superclass or implemented interface of the list
     * element).
     *
     * @param superclassOrInterface
     *            the superclass or interface to filter for.
     * @return The filtered list, containing only classes for which
     *         {@code assignableToClassRef.isAssignableFrom(listItemClassRef)} is true for the corresponding
     *         {@code Class<?>} references for assignableToClass and the list items. Returns the empty list if no
     *         classes were assignable to the requested class.
     * @throws NullPointerException
     *             if superclassOrInterface is null.
     */
    public ClassInfoList getAssignableTo(final ClassInfo superclassOrInterface) {
        Assert.notNull(superclassOrInterface, "superclassOrInterface");
        // Get subclasses and implementing classes for assignableFromClass
        final Set<ClassInfo> allAssignableFromClasses = new HashSet<>();
        if (superclassOrInterface.isStandardClass()) {
            allAssignableFromClasses.addAll(superclassOrInterface.getAllSubclasses());
        } else if (superclassOrInterface.isInterfaceOrAnnotation()) {
            allAssignableFromClasses.addAll(superclassOrInterface.getAllClassesImplementing());
        }
        // A class is its own superclass or interface
        allAssignableFromClasses.add(superclassOrInterface);

        return filter(allAssignableFromClasses::contains);
    }

    /**
     * Filter this {@link ClassInfoList} to include only classes that are assignable to the named class or interface
     * (i.e. where the named class or interface is a superclass or implemented interface of the list element).
     *
     * @param superclassOrInterfaceName
     *            the name of the superclass or interface to filter for.
     * @return The filtered list, or the empty list if no classes were assignable to the named class or interface,
     *         or if the named class or interface was not found during the scan.
     * @throws NullPointerException
     *             if superclassOrInterfaceName is null.
     */
    public ClassInfoList getAssignableTo(final String superclassOrInterfaceName) {
        Assert.notNull(superclassOrInterfaceName, "superclassOrInterfaceName");
        if (isEmpty()) {
            return EMPTY_LIST;
        }
        // Any element of this list can be used to reach the ScanResult
        final ClassInfo superclassOrInterface = get(0).scanResult().getClassInfo(superclassOrInterfaceName);
        return superclassOrInterface == null ? EMPTY_LIST : getAssignableTo(superclassOrInterface);
    }

    /**
     * Filter this {@link ClassInfoList} to include only classes that are assignable to the requested class or
     * interface (i.e. where the requested class or interface is a superclass or implemented interface of the list
     * element).
     *
     * @param superclassOrInterface
     *            the superclass or interface to filter for.
     * @return The filtered list, or the empty list if no classes were assignable to the requested class or
     *         interface, or if the requested class or interface was not found during the scan.
     * @throws NullPointerException
     *             if superclassOrInterface is null.
     */
    public ClassInfoList getAssignableTo(final Class<?> superclassOrInterface) {
        Assert.notNull(superclassOrInterface, "superclassOrInterface");
        return getAssignableTo(superclassOrInterface.getName());
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * returned graph shows inter-class dependencies only. You must have called
     * {@link ClassGraph#enableInterClassDependencies()} before scanning to use this method.
     *
     * @param options
     *            the graph options. Only the layout size and the external-class setting have any effect on this
     *            graph.
     * @return the GraphViz file contents.
     * @throws IllegalStateException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableInterClassDependencies()} was
     *             not called before scanning (since there would be nothing to graph).
     */
    public String generateGraphVizDotFileFromInterClassDependencies(final GraphVizDotFileOptions options) {
        Assert.notNull(options, "options");
        if (isEmpty()) {
            throw new IllegalStateException("List is empty");
        }
        final var scanSpec = get(0).scanResult().scanSpec;
        if (!scanSpec.enableInterClassDependencies) {
            throw new IllegalStateException("Please call ClassGraph#enableInterClassDependencies() before #scan()");
        }
        return GraphvizDotfileGenerator.generateGraphVizDotFileFromInterClassDependencies(this, options.sizeX,
                options.sizeY, options.includeExternalClasses != null ? options.includeExternalClasses
                        : scanSpec.enableExternalClasses);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, using
     * the default options. The returned graph shows inter-class dependencies only. You must have called
     * {@link ClassGraph#enableInterClassDependencies()} before scanning to use this method.
     *
     * @return the GraphViz file contents.
     * @throws IllegalStateException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableInterClassDependencies()} was
     *             not called before scanning (since there would be nothing to graph).
     */
    public String generateGraphVizDotFileFromInterClassDependencies() {
        return generateGraphVizDotFileFromInterClassDependencies(new GraphVizDotFileOptions());
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, and save
     * it to a file. The saved graph shows inter-class dependencies only. You must have called
     * {@link ClassGraph#enableInterClassDependencies()} before scanning to use this method.
     *
     * @param file
     *            the file to save the GraphViz .dot file to.
     * @param options
     *            the graph options. Only the layout size and the external-class setting have any effect on this
     *            graph.
     * @return this (for method chaining).
     * @throws IOException
     *             if the file could not be saved.
     * @throws IllegalStateException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableInterClassDependencies()} was
     *             not called before scanning (since there would be nothing to graph).
     */
    public ClassInfoList writeGraphVizDotFileFromInterClassDependencies(final File file,
            final GraphVizDotFileOptions options) throws IOException {
        Assert.notNull(file, "file");
        try (var writer = new PrintWriter(file)) {
            writer.print(generateGraphVizDotFileFromInterClassDependencies(options));
        }
        return this;
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, using
     * the default options, and save it to a file. The saved graph shows inter-class dependencies only. You must
     * have called {@link ClassGraph#enableInterClassDependencies()} before scanning to use this method.
     *
     * @param file
     *            the file to save the GraphViz .dot file to.
     * @return this (for method chaining).
     * @throws IOException
     *             if the file could not be saved.
     * @throws IllegalStateException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableInterClassDependencies()} was
     *             not called before scanning (since there would be nothing to graph).
     */
    public ClassInfoList writeGraphVizDotFileFromInterClassDependencies(final File file) throws IOException {
        return writeGraphVizDotFileFromInterClassDependencies(file, new GraphVizDotFileOptions());
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph.
     *
     * <p>
     * To show non-public classes, call {@link ClassGraph#ignoreClassVisibility()} before scanning.
     *
     * <p>
     * To show fields, call {@link ClassGraph#enableFieldInfo()} before scanning. To show non-public fields, also
     * call {@link ClassGraph#ignoreFieldVisibility()} before scanning.
     *
     * <p>
     * To show methods, call {@link ClassGraph#enableMethodInfo()} before scanning. To show non-public methods, also
     * call {@link ClassGraph#ignoreMethodVisibility()} before scanning.
     *
     * <p>
     * To show annotations, call {@link ClassGraph#enableAnnotationInfo()} before scanning. To show non-public
     * annotations, also call {@link ClassGraph#ignoreFieldVisibility()} before scanning (there is no separate
     * visibility modifier for annotations).
     *
     * @param options
     *            the graph options.
     * @return the GraphViz file contents.
     * @throws IllegalStateException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableClassInfo()} was not called
     *             before scanning (since there would be nothing to graph).
     */
    public String generateGraphVizDotFile(final GraphVizDotFileOptions options) {
        Assert.notNull(options, "options");
        if (isEmpty()) {
            throw new IllegalStateException("List is empty");
        }
        final var scanSpec = get(0).scanResult().scanSpec;
        if (!scanSpec.enableClassInfo) {
            throw new IllegalStateException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return GraphvizDotfileGenerator.generateGraphVizDotFile(this, options.sizeX, options.sizeY,
                options.showFields, options.showFieldTypeDependencyEdges, options.showMethods,
                options.showMethodTypeDependencyEdges, options.showAnnotations, options.useSimpleNames, scanSpec);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, using
     * the default options.
     *
     * <p>
     * Methods, fields and annotations are shown if enabled, via {@link ClassGraph#enableMethodInfo()},
     * {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()}.
     *
     * <p>
     * Only public classes, methods, and fields are shown, unless {@link ClassGraph#ignoreClassVisibility()},
     * {@link ClassGraph#ignoreMethodVisibility()}, and/or {@link ClassGraph#ignoreFieldVisibility()} has/have been
     * called.
     *
     * @return the GraphViz file contents.
     * @throws IllegalStateException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableClassInfo()} was not called
     *             before scanning (since there would be nothing to graph).
     */
    public String generateGraphVizDotFile() {
        return generateGraphVizDotFile(new GraphVizDotFileOptions());
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, and save
     * it to a file.
     *
     * @param file
     *            the file to save the GraphViz .dot file to.
     * @param options
     *            the graph options.
     * @return this (for method chaining).
     * @throws IOException
     *             if the file could not be saved.
     * @throws IllegalStateException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableClassInfo()} was not called
     *             before scanning (since there would be nothing to graph).
     */
    public ClassInfoList writeGraphVizDotFile(final File file, final GraphVizDotFileOptions options)
            throws IOException {
        Assert.notNull(file, "file");
        try (var writer = new PrintWriter(file)) {
            writer.print(generateGraphVizDotFile(options));
        }
        return this;
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, using
     * the default options, and save it to a file.
     *
     * <p>
     * Methods, fields and annotations are shown if enabled, via {@link ClassGraph#enableMethodInfo()},
     * {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()}.
     *
     * <p>
     * Only public classes, methods, and fields are shown, unless {@link ClassGraph#ignoreClassVisibility()},
     * {@link ClassGraph#ignoreMethodVisibility()}, and/or {@link ClassGraph#ignoreFieldVisibility()} has/have been
     * called.
     *
     * @param file
     *            the file to save the GraphViz .dot file to.
     * @return this (for method chaining).
     * @throws IOException
     *             if the file could not be saved.
     * @throws IllegalStateException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableClassInfo()} was not called
     *             before scanning (since there would be nothing to graph).
     */
    public ClassInfoList writeGraphVizDotFile(final File file) throws IOException {
        return writeGraphVizDotFile(file, new GraphVizDotFileOptions());
    }

    // -------------------------------------------------------------------------------------------------------------

    /*
     * (non-Javadoc)
     *
     * @see java.util.ArrayList#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        // directlyRelatedClasses is final, and every constructor assigns it a non-null value
        return obj instanceof final ClassInfoList other && super.equals(other)
                && directlyRelatedClasses.equals(other.directlyRelatedClasses);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.util.ArrayList#hashCode()
     */
    @Override
    public int hashCode() {
        return super.hashCode() ^ directlyRelatedClasses.hashCode();
    }
}
