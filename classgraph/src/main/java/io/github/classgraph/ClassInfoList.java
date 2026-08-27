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
package io.github.classgraph;

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
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.CollectionUtils;
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
 *
 * <p>
 * A list returned by {@link MethodInfo#getThrownExceptions()} preserves every entry in the declaration, so it can
 * contain the same {@link ClassInfo} more than once when distinct type variables resolve to the same class bound.
 *
 * <p>
 * Equality is {@link List} equality: two lists are equal if they hold equal classes in the same order. Which of the
 * classes are directly related is reported by {@link #directOnly()}, and is not part of the comparison, so two
 * lists that hold the same classes are equal even when they were reached by different relationships.
 */
public class ClassInfoList extends MappableInfoList<ClassInfo> {
    /** Directly related classes. */
    // Marked transient because this class extends ArrayList, which is Serializable, so javac requires every field
    // to be of a serializable type, and Set is not one. Nothing is lost by that: a non-empty list of this type
    // cannot be serialized anyway, since ClassInfo is not serializable either.
    private final transient Set<ClassInfo> directlyRelatedClasses;

    /** Whether to sort by name. */
    private final boolean sortByName;

    /** serialVersionUID. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** An unmodifiable empty {@link ClassInfoList}. */
    static final ClassInfoList EMPTY_LIST = new ClassInfoList(Set.of(), /* sortByName = */ false);

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
     */
    ClassInfoList(final Set<ClassInfo> reachableClasses, final @Nullable Set<ClassInfo> directlyRelatedClasses,
            final boolean sortByName) {
        // Sort a copy of the classes before handing them to the superclass constructor, rather than sorting this
        // list once it has been built, so that a partly-initialized instance is never passed to another method
        super(sortByName ? CollectionUtils.sortCopy(reachableClasses) : reachableClasses, /* modifiable = */ false);
        this.sortByName = sortByName;
        // If directlyRelatedClasses was not provided, then assume all reachable classes were directly related
        this.directlyRelatedClasses = new LinkedHashSet<>(
                directlyRelatedClasses == null ? reachableClasses : directlyRelatedClasses);
        this.directlyRelatedClasses.retainAll(reachableClasses);
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
     * Construct an unmodifiable list of {@link ClassInfo} objects from a completed list, preserving its order and
     * duplicates when sorting is disabled. Every class is treated as directly related. This is used for a throws
     * clause, where two declared types can resolve to the same {@link ClassInfo} through a type-variable bound.
     *
     * @param reachableClasses
     *            the completed list
     * @param sortByName
     *            whether to sort by name
     */
    ClassInfoList(final List<ClassInfo> reachableClasses, final boolean sortByName) {
        super(sortByName ? CollectionUtils.sortCopy(reachableClasses) : reachableClasses, /* modifiable = */ false);
        this.sortByName = sortByName;
        directlyRelatedClasses = new LinkedHashSet<>(reachableClasses);
    }

    /**
     * Construct a new unmodifiable {@link ClassInfoList} from a completed collection of {@link ClassInfo} objects.
     *
     * <p>
     * If the passed {@link Collection} is not a {@link Set}, then the {@link ClassInfo} objects will be uniquified
     * (by adding them to a set) before they are added to the returned list. {@link ClassInfo} objects in the
     * returned list will be sorted by name. The collection is copied, so later changes to it do not affect this
     * list. Every class in the constructed list is treated as directly related.
     *
     * @param classInfoCollection
     *            the initial collection of {@link ClassInfo} objects to add to the {@link ClassInfoList}.
     */
    public ClassInfoList(final Collection<ClassInfo> classInfoCollection) {
        this(Objects.requireNonNull(classInfoCollection,
                "classInfoCollection must not be null") instanceof final Set<ClassInfo> classInfoSet //
                        ? classInfoSet
                        : new HashSet<>(classInfoCollection), //
                /* directlyRelatedClasses = */ null, /* sortByName = */ true);
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
}
