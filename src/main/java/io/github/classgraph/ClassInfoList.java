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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.ClassInfo.ReachableAndDirectlyRelatedClasses;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.CollectionUtils;

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
 * {@link ClassInfo#getSuperclasses()}, which are in ascending order of the class hierarchy.
 */
public class ClassInfoList extends MappableInfoList<ClassInfo> {
    /** Directly related classes. */
    // N.B. this is marked transient to keep Scrutinizer happy, since thi class extends ArrayList, which is
    // Serializable, so all fields must be serializable (and Set is an interface, so is not Serializable).
    // Marking this transient will mean direct relationships will be lost on serialization, but the
    // Serializable interface is not widely used today anyway.
    private transient final Set<ClassInfo> directlyRelatedClasses;

    /** Whether to sort by name. */
    private final boolean sortByName;

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
     * Construct a modifiable list of {@link ClassInfo} objects, consisting of reachable classes (obtained through
     * the transitive closure) and directly related classes (one step away in the graph).
     *
     * @param reachableClasses
     *            reachable classes
     * @param directlyRelatedClasses
     *            directly related classes
     * @param sortByName
     *            whether to sort by name
     */
    ClassInfoList(final Set<ClassInfo> reachableClasses, final Set<ClassInfo> directlyRelatedClasses,
            final boolean sortByName) {
        super(reachableClasses);
        this.sortByName = sortByName;
        if (sortByName) {
            // It's a bit dicey calling CollectionUtils.sortIfNotEmpty(this) from within a constructor,
            // but the super-constructor has been called, so it should be fine :-)
            CollectionUtils.sortIfNotEmpty(this);
        }
        // If directlyRelatedClasses was not provided, then assume all reachable classes were directly related
        this.directlyRelatedClasses = directlyRelatedClasses == null ? reachableClasses : directlyRelatedClasses;
    }

    /**
     * Construct a modifiable list of {@link ClassInfo} objects.
     *
     * @param reachableAndDirectlyRelatedClasses
     *            reachable and directly related classes
     * @param sortByName
     *            whether to sort by name
     */
    ClassInfoList(final ReachableAndDirectlyRelatedClasses reachableAndDirectlyRelatedClasses,
            final boolean sortByName) {
        this(reachableAndDirectlyRelatedClasses.reachableClasses,
                reachableAndDirectlyRelatedClasses.directlyRelatedClasses, sortByName);
    }

    /**
     * Construct a modifiable list of {@link ClassInfo} objects, where each class is directly related.
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
     *            the size hint.
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
        this(classInfoCollection instanceof Set //
                ? (Set<ClassInfo>) classInfoCollection
                : new HashSet<ClassInfo>(classInfoCollection), //
                /* directlyRelatedClasses = */ null, /* sortByName = */ true);
    }

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
     * @param <T>
     *            The superclass or interface.
     * @param superclassOrInterfaceType
     *            The superclass or interface class reference to cast each loaded class to.
     * @param ignoreExceptions
     *            If true, ignore any exceptions or errors thrown during classloading, or when attempting to cast
     *            the resulting {@code Class<?>} reference to the requested type -- instead, skip the element (i.e.
     *            the returned list may contain fewer items than this input list). If false,
     *            {@link IllegalArgumentException} is thrown if the class could not be loaded or could not be cast
     *            to the requested type.
     * @return The loaded {@code Class<?>} objects corresponding to each {@link ClassInfo} object in this list.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and an exception or error was thrown while trying to load or cast
     *             any of the classes.
     */
    public <T> List<Class<T>> loadClasses(final Class<T> superclassOrInterfaceType,
            final boolean ignoreExceptions) {
        if (this.isEmpty()) {
            return Collections.emptyList();
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
     * @param <T>
     *            The superclass or interface.
     * @param superclassOrInterfaceType
     *            The superclass or interface class reference to cast each loaded class to.
     * @return The loaded {@code Class<?>} objects corresponding to each {@link ClassInfo} object in this list.
     * @throws IllegalArgumentException
     *             if an exception or error was thrown while trying to load or cast any of the classes.
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
     * @return The loaded {@code Class<?>} objects corresponding to each {@link ClassInfo} object in this list.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and an exception or error was thrown while trying to load any of the
     *             classes.
     */
    public List<Class<?>> loadClasses(final boolean ignoreExceptions) {
        if (this.isEmpty()) {
            return Collections.emptyList();
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
     * @return The loaded {@code Class<?>} objects corresponding to each {@link ClassInfo} object in this list.
     * @throws IllegalArgumentException
     *             if an exception or error was thrown while trying to load any of the classes.
     */
    public List<Class<?>> loadClasses() {
        return loadClasses(/* ignoreExceptions = */ false);
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
        // Put the first ClassInfoList that is not being sorted by name at the head of the list,
        // so that its order is preserved in the intersection (#238)
        final ArrayDeque<ClassInfoList> intersectionOrder = new ArrayDeque<>();
        intersectionOrder.add(this);
        boolean foundFirst = false;
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
        final ClassInfoList first = intersectionOrder.remove();
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
        final Set<ClassInfo> reachableClassesDifference = new LinkedHashSet<>(this);
        final Set<ClassInfo> directlyRelatedClassesDifference = new LinkedHashSet<>(directlyRelatedClasses);
        reachableClassesDifference.removeAll(other);
        directlyRelatedClassesDifference.removeAll(other.directlyRelatedClasses);
        return new ClassInfoList(reachableClassesDifference, directlyRelatedClassesDifference, sortByName);
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
        boolean accept(ClassInfo classInfo);
    }

    /**
     * Find the subset of this {@link ClassInfoList} for which the given filter predicate is true.
     *
     * @param filter
     *            The {@link ClassInfoFilter} to apply.
     * @return The subset of this {@link ClassInfoList} for which the given filter predicate is true.
     */
    public ClassInfoList filter(final ClassInfoFilter filter) {
        final Set<ClassInfo> reachableClassesFiltered = new LinkedHashSet<>(size());
        final Set<ClassInfo> directlyRelatedClassesFiltered = new LinkedHashSet<>(directlyRelatedClasses.size());
        for (final ClassInfo ci : this) {
            if (filter.accept(ci)) {
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
        return filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return ci.isStandardClass();
            }
        });
    }

    /**
     * Filter this {@link ClassInfoList} to include only interfaces that are not annotations. See also
     * {@link #getInterfacesAndAnnotations()}.
     * 
     * @return The filtered list, containing only interfaces.
     */
    public ClassInfoList getInterfaces() {
        return filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return ci.isInterface();
            }
        });
    }

    /**
     * Filter this {@link ClassInfoList} to include only interfaces and annotations (annotations are interfaces, and
     * can be implemented). See also {@link #getInterfaces()}.
     * 
     * @return The filtered list, containing only interfaces.
     */
    public ClassInfoList getInterfacesAndAnnotations() {
        return filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return ci.isInterfaceOrAnnotation();
            }
        });
    }

    /**
     * Filter this {@link ClassInfoList} to include only implemented interfaces, i.e. non-annotation interfaces, or
     * annotations that have been implemented by a class.
     * 
     * @return The filtered list, containing only implemented interfaces.
     */
    public ClassInfoList getImplementedInterfaces() {
        return filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return ci.isImplementedInterface();
            }
        });
    }

    /**
     * Filter this {@link ClassInfoList} to include only annotations.
     * 
     * @return The filtered list, containing only annotations.
     */
    public ClassInfoList getAnnotations() {
        return filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return ci.isAnnotation();
            }
        });
    }

    /**
     * Filter this {@link ClassInfoList} to include only {@link Enum} classes.
     * 
     * @return The filtered list, containing only enums.
     */
    public ClassInfoList getEnums() {
        return filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return ci.isEnum();
            }
        });
    }

    /**
     * Filter this {@link ClassInfoList} to include only {@code record} classes.
     * 
     * @return The filtered list, containing only {@code record} classes.
     */
    public ClassInfoList getRecords() {
        return filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return ci.isRecord();
            }
        });
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
     * @throws IllegalArgumentException
     *             if classInfo is null.
     */
    public ClassInfoList getAssignableTo(final ClassInfo superclassOrInterface) {
        if (superclassOrInterface == null) {
            throw new IllegalArgumentException("assignableToClass parameter cannot be null");
        }
        // Get subclasses and implementing classes for assignableFromClass
        final Set<ClassInfo> allAssignableFromClasses = new HashSet<>();
        if (superclassOrInterface.isStandardClass()) {
            allAssignableFromClasses.addAll(superclassOrInterface.getSubclasses());
        } else if (superclassOrInterface.isInterfaceOrAnnotation()) {
            allAssignableFromClasses.addAll(superclassOrInterface.getClassesImplementing());
        }
        // A class is its own superclass or interface
        allAssignableFromClasses.add(superclassOrInterface);

        return filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return allAssignableFromClasses.contains(ci);
            }
        });
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * returned graph shows inter-class dependencies only. The sizeX and sizeY parameters are the image output size
     * to use (in inches) when GraphViz is asked to render the .dot file. You must have called
     * {@link ClassGraph#enableInterClassDependencies()} before scanning to use this method.
     *
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout width in inches.
     * @param includeExternalClasses
     *            If true, and if {@link ClassGraph#enableExternalClasses()} was called before scanning, show
     *            "external classes" (non-whitelisted classes) within the dependency graph.
     * @return the GraphViz file contents.
     * @throws IllegalArgumentException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableInterClassDependencies()} was
     *             not called before scanning (since there would be nothing to graph).
     */
    public String generateGraphVizDotFileFromInterClassDependencies(final float sizeX, final float sizeY,
            final boolean includeExternalClasses) {
        if (isEmpty()) {
            throw new IllegalArgumentException("List is empty");
        }
        final ScanSpec scanSpec = get(0).scanResult.scanSpec;
        if (!scanSpec.enableInterClassDependencies) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableInterClassDependencies() before #scan()");
        }
        return GraphvizDotfileGenerator.generateGraphVizDotFileFromInterClassDependencies(this, sizeX, sizeY,
                includeExternalClasses);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * returned graph shows inter-class dependencies only. The sizeX and sizeY parameters are the image output size
     * to use (in inches) when GraphViz is asked to render the .dot file. You must have called
     * {@link ClassGraph#enableInterClassDependencies()} before scanning to use this method.
     * 
     * <p>
     * Equivalent to calling {@link #generateGraphVizDotFileFromInterClassDependencies(float, float, boolean)} with
     * parameters of (10.5f, 8f, scanSpec.enableExternalClasses), where scanSpec.enableExternalClasses is true if
     * {@link ClassGraph#enableExternalClasses()} was called before scanning.
     *
     * @return the GraphViz file contents.
     * @throws IllegalArgumentException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableInterClassDependencies()} was
     *             not called before scanning (since there would be nothing to graph).
     */
    public String generateGraphVizDotFileFromClassDependencies() {
        if (isEmpty()) {
            throw new IllegalArgumentException("List is empty");
        }
        final ScanSpec scanSpec = get(0).scanResult.scanSpec;
        if (!scanSpec.enableInterClassDependencies) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableInterClassDependencies() before #scan()");
        }
        return GraphvizDotfileGenerator.generateGraphVizDotFileFromInterClassDependencies(this, 10.5f, 8.0f,
                scanSpec.enableExternalClasses);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * sizeX and sizeY parameters are the image output size to use (in inches) when GraphViz is asked to render the
     * .dot file.
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
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout width in inches.
     * @param showFields
     *            If true, show fields within class nodes in the graph.
     * @param showFieldTypeDependencyEdges
     *            If true, show edges between classes and the types of their fields.
     * @param showMethods
     *            If true, show methods within class nodes in the graph.
     * @param showMethodTypeDependencyEdges
     *            If true, show edges between classes and the return types and/or parameter types of their methods.
     * @param showAnnotations
     *            If true, show annotations in the graph.
     * @param useSimpleNames
     *            whether to use simple names for classes in type signatures (if true, the package name is stripped
     *            from class names in method and field type signatures).
     * @return the GraphViz file contents.
     * @throws IllegalArgumentException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableClassInfo()} was not called
     *             before scanning (since there would be nothing to graph).
     */
    public String generateGraphVizDotFile(final float sizeX, final float sizeY, final boolean showFields,
            final boolean showFieldTypeDependencyEdges, final boolean showMethods,
            final boolean showMethodTypeDependencyEdges, final boolean showAnnotations,
            final boolean useSimpleNames) {
        if (isEmpty()) {
            throw new IllegalArgumentException("List is empty");
        }
        final ScanSpec scanSpec = get(0).scanResult.scanSpec;
        if (!scanSpec.enableClassInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableClassInfo() before #scan()");
        }
        return GraphvizDotfileGenerator.generateGraphVizDotFile(this, sizeX, sizeY, showFields,
                showFieldTypeDependencyEdges, showMethods, showMethodTypeDependencyEdges, showAnnotations,
                useSimpleNames, scanSpec);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * sizeX and sizeY parameters are the image output size to use (in inches) when GraphViz is asked to render the
     * .dot file.
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
     * <p>
     * This method uses simple names for class names in type signatures of fields and methods (package names are
     * stripped).
     *
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout width in inches.
     * @param showFields
     *            If true, show fields within class nodes in the graph.
     * @param showFieldTypeDependencyEdges
     *            If true, show edges between classes and the types of their fields.
     * @param showMethods
     *            If true, show methods within class nodes in the graph.
     * @param showMethodTypeDependencyEdges
     *            If true, show edges between classes and the return types and/or parameter types of their methods.
     * @param showAnnotations
     *            If true, show annotations in the graph.
     * @return the GraphViz file contents.
     * @throws IllegalArgumentException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableClassInfo()} was not called
     *             before scanning (since there would be nothing to graph).
     */
    public String generateGraphVizDotFile(final float sizeX, final float sizeY, final boolean showFields,
            final boolean showFieldTypeDependencyEdges, final boolean showMethods,
            final boolean showMethodTypeDependencyEdges, final boolean showAnnotations) {
        return generateGraphVizDotFile(sizeX, sizeY, showFields, showFieldTypeDependencyEdges, showMethods,
                showMethodTypeDependencyEdges, showAnnotations, true);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph.
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
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout width in inches.
     * @return the GraphViz file contents.
     * @throws IllegalArgumentException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableClassInfo()} was not called
     *             before scanning (since there would be nothing to graph).
     */
    public String generateGraphVizDotFile(final float sizeX, final float sizeY) {
        return generateGraphVizDotFile(sizeX, sizeY, /* showFields = */ true,
                /* showFieldTypeDependencyEdges = */ true, /* showMethods = */ true,
                /* showMethodTypeDependencyEdges = */ true, /* showAnnotations = */ true);
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph.
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
     * @throws IllegalArgumentException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableClassInfo()} was not called
     *             before scanning (since there would be nothing to graph).
     */
    public String generateGraphVizDotFile() {
        return generateGraphVizDotFile(/* sizeX = */ 10.5f, /* sizeY = */ 8.0f, /* showFields = */ true,
                /* showFieldTypeDependencyEdges = */ true, /* showMethods = */ true,
                /* showMethodTypeDependencyEdges = */ true, /* showAnnotations = */ true);
    }

    /**
     * Generate a and save a .dot file, which can be fed into GraphViz for layout and visualization of the class
     * graph.
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
     * @throws IOException
     *             if the file could not be saved.
     * @throws IllegalArgumentException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableClassInfo()} was not called
     *             before scanning (since there would be nothing to graph).
     */
    public void generateGraphVizDotFile(final File file) throws IOException {
        try (PrintWriter writer = new PrintWriter(file)) {
            writer.print(generateGraphVizDotFile());
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.util.ArrayList#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        } else if (!(obj instanceof ClassInfoList)) {
            return false;
        }
        final ClassInfoList other = (ClassInfoList) obj;
        if ((directlyRelatedClasses == null) != (other.directlyRelatedClasses == null)) {
            return false;
        }
        if (directlyRelatedClasses == null) {
            return super.equals(other);
        }
        return super.equals(other) && directlyRelatedClasses.equals(other.directlyRelatedClasses);
    }

    /* (non-Javadoc)
     * @see java.util.ArrayList#hashCode()
     */
    @Override
    public int hashCode() {
        return super.hashCode() ^ (directlyRelatedClasses == null ? 0 : directlyRelatedClasses.hashCode());
    }
}
