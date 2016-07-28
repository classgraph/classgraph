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
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

public class ClassInfo implements Comparable<ClassInfo> {

    /** Name of the class/interface/annotation. */
    String className;

    /** True if the classfile indicated this is an interface. */
    private boolean isInterface;

    /** True if the classfile indicated this is an annotation. */
    private boolean isAnnotation;

    /**
     * True when a class has been scanned (i.e. its classfile contents read), as opposed to only being referenced by
     * another class' classfile as a superclass/superinterface/annotation. If classfileScanned is true, then this
     * also must be a whitelisted (and non-blacklisted) class in a whitelisted (and non-blacklisted) package.
     */
    private boolean classfileScanned;

    /**
     * Used to keep track of whether the classfile of a Scala companion object class (with name ending in "$") has
     * been read, since these classes need to be merged into the base class.
     */
    private boolean companionObjectClassfileScanned;

    /**
     * Used to keep track of whether the classfile of a Scala trait method class (with name ending in "$class") has
     * been read, since these classes need to be merged into the base class.
     */
    private boolean traitMethodClassfileScanned;

    enum RelType {

        // Classes:

        /**
         * Superclasses of this class, if this is a regular class.
         * 
         * (Should consist of only one entry, or null if superclass is java.lang.Object or unknown).
         */
        SUPERCLASSES,

        /** Subclasses of this class, if this is a regular class. */
        SUBCLASSES,

        /** The types of fields of regular classes, if this is a regular class. */
        FIELD_TYPES,

        // Interfaces:

        /**
         * Interfaces that this class implements, if this is a regular class, or superinterfaces, if this is an
         * interface.
         * 
         * (May also include annotations, since annotations are interfaces, so you can implement an annotation.)
         */
        IMPLEMENTED_INTERFACES,

        /** Classes that implement this interface (including sub-interfaces), if this is an interface. */
        CLASSES_IMPLEMENTING,

        // Annotations:

        /**
         * Annotations on this class, if this is a regular class, or meta-annotations on this annotation, if this is
         * an annotation.
         */
        ANNOTATIONS,

        /** Classes annotated by this annotation, if this is an annotation. */
        ANNOTATED_CLASSES,
    }

    /** The set of classes related to this one. */
    private final Map<RelType, Set<ClassInfo>> relatedTypeToClassInfoSet = new HashMap<>();

    /**
     * The static constant initializer values of static final fields, if a StaticFinalFieldMatchProcessor matched a
     * field in this class.
     */
    Map<String, Object> staticFinalFieldNameToConstantInitializerValue;

    private ClassInfo(final String className) {
        this.className = className;
    }

    /** The class type to return. */
    enum ClassType {
        /** Return all class types. */
        ALL,
        /** A standard class (not an interface or annotation). */
        STANDARD_CLASS,
        /**
         * An interface (this is named "implemented interface" rather than just "interface" to distinguish it from
         * an annotation.)
         */
        IMPLEMENTED_INTERFACE,
        /** An annotation. */
        ANNOTATION,
        /** An interface or annotation (used since you can actually implement an annotation). */
        INTERFACE_OR_ANNOTATION,
    }

    /** Get the ClassInfo objects for the classes related to this one in the specified way. */
    static Set<ClassInfo> filterClassInfo(final Set<ClassInfo> classInfoSet, final boolean removeExternalClasses,
            final ClassType... classTypes) {
        if (classInfoSet == null) {
            return Collections.emptySet();
        }
        boolean includeAllTypes = classTypes.length == 0;
        boolean includeStandardClasses = false;
        boolean includeImplementedInterfaces = false;
        boolean includeAnnotations = false;
        for (final ClassType classType : classTypes) {
            switch (classType) {
            case ALL:
                includeAllTypes = true;
                break;
            case STANDARD_CLASS:
                includeStandardClasses = true;
                break;
            case IMPLEMENTED_INTERFACE:
                includeImplementedInterfaces = true;
                break;
            case ANNOTATION:
                includeAnnotations = true;
                break;
            case INTERFACE_OR_ANNOTATION:
                includeImplementedInterfaces = includeAnnotations = true;
                break;
            default:
                throw new RuntimeException("Unknown ClassType: " + classType);
            }
        }
        if (includeStandardClasses && includeImplementedInterfaces && includeAnnotations) {
            includeAllTypes = true;
        }
        // Do two passes with the same filter logic to avoid copying the set if nothing is filtered out
        boolean hasFilteredOutClass = false;
        for (final ClassInfo classInfo : classInfoSet) {
            if ((!removeExternalClasses || classInfo.classfileScanned) && //
                    (includeAllTypes //
                            || includeStandardClasses && classInfo.isStandardClass()
                            || includeImplementedInterfaces && classInfo.isImplementedInterface()
                            || includeAnnotations && classInfo.isAnnotation())) {
                // Do nothing
            } else {
                hasFilteredOutClass = true;
                break;
            }
        }
        if (!hasFilteredOutClass) {
            // Nothing to filter out
            return classInfoSet;
        } else {
            // Need to filter out one or more classes from set
            final Set<ClassInfo> classInfoSetFiltered = new HashSet<>(classInfoSet.size());
            for (final ClassInfo classInfo : classInfoSet) {
                if ((!removeExternalClasses || classInfo.classfileScanned) && //
                        (includeAllTypes //
                                || includeStandardClasses && classInfo.isStandardClass()
                                || includeImplementedInterfaces && classInfo.isImplementedInterface()
                                || includeAnnotations && classInfo.isAnnotation())) {
                    classInfoSetFiltered.add(classInfo);
                }
            }
            return classInfoSetFiltered;
        }
    }

    /** Get the sorted list of the names of classes given a set of ClassInfo objects. */
    public static List<String> getClassNames(final Collection<ClassInfo> classInfoColl) {
        if (classInfoColl.isEmpty()) {
            return Collections.emptyList();
        } else {
            final ArrayList<String> classNames = new ArrayList<>(classInfoColl.size());
            for (final ClassInfo classInfo : classInfoColl) {
                classNames.add(classInfo.className);
            }
            Collections.sort(classNames);
            return classNames;
        }
    }

    /** Get the ClassInfo objects for the classes related to this one in the specified way. */
    Set<ClassInfo> getRelatedClasses(final RelType relType) {
        final Set<ClassInfo> relatedClassClassInfo = relatedTypeToClassInfoSet.get(relType);
        return relatedClassClassInfo == null ? Collections.<ClassInfo> emptySet() : relatedClassClassInfo;
    }

    /**
     * Find all ClassInfo nodes reachable from this ClassInfo node over the given relationship type links (not
     * including this class itself).
     */
    Set<ClassInfo> getReachableClasses(final RelType relType) {
        final Set<ClassInfo> directlyRelatedClasses = this.getRelatedClasses(relType);
        if (directlyRelatedClasses.isEmpty()) {
            return directlyRelatedClasses;
        }
        final Set<ClassInfo> reachableClasses = new HashSet<>(directlyRelatedClasses);
        final LinkedList<ClassInfo> queue = new LinkedList<>();
        queue.addAll(directlyRelatedClasses);
        while (!queue.isEmpty()) {
            final ClassInfo head = queue.removeFirst();
            for (final ClassInfo directlyReachableFromHead : head.getRelatedClasses(relType)) {
                // Don't get in cycle
                if (reachableClasses.add(directlyReachableFromHead)) {
                    queue.add(directlyReachableFromHead);
                }
            }
        }
        return reachableClasses;
    }

    /**
     * Add a ClassInfo objects for the given relationship type. Returns true if the collection changed as a result
     * of the call.
     */
    private boolean addRelatedClass(final RelType relType, final ClassInfo classInfo) {
        Set<ClassInfo> classInfoSet = relatedTypeToClassInfoSet.get(relType);
        if (classInfoSet == null) {
            relatedTypeToClassInfoSet.put(relType, classInfoSet = new HashSet<>(4));
        }
        return classInfoSet.add(classInfo);
    }

    /** Strip Scala auxiliary class suffixes from class name. */
    private static String scalaBaseClassName(final String className) {
        if (className != null && className.endsWith("$")) {
            return className.substring(0, className.length() - 1);
        } else if (className != null && className.endsWith("$class")) {
            return className.substring(0, className.length() - 6);
        } else {
            return className;
        }
    }

    // N.B. not threadsafe, but this class should only ever be called by a single thread.
    private static ClassInfo getOrCreateClassInfo(final String className,
            final Map<String, ClassInfo> classNameToClassInfo) {
        ClassInfo classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            classNameToClassInfo.put(className, classInfo = new ClassInfo(className));
        }
        return classInfo;
    }

    void addSuperclass(final String superclassName, final Map<String, ClassInfo> classNameToClassInfo) {
        if (superclassName != null) {
            final ClassInfo superclassClassInfo = getOrCreateClassInfo(scalaBaseClassName(superclassName),
                    classNameToClassInfo);
            this.addRelatedClass(RelType.SUPERCLASSES, superclassClassInfo);
            superclassClassInfo.addRelatedClass(RelType.SUBCLASSES, this);
        }
    }

    void addAnnotation(final String annotationName, final Map<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo annotationClassInfo = getOrCreateClassInfo(scalaBaseClassName(annotationName),
                classNameToClassInfo);
        annotationClassInfo.isAnnotation = true;
        this.addRelatedClass(RelType.ANNOTATIONS, annotationClassInfo);
        annotationClassInfo.addRelatedClass(RelType.ANNOTATED_CLASSES, this);
    }

    void addImplementedInterface(final String interfaceName, final Map<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo interfaceClassInfo = getOrCreateClassInfo(scalaBaseClassName(interfaceName),
                classNameToClassInfo);
        interfaceClassInfo.isInterface = true;
        this.addRelatedClass(RelType.IMPLEMENTED_INTERFACES, interfaceClassInfo);
        interfaceClassInfo.addRelatedClass(RelType.CLASSES_IMPLEMENTING, this);
    }

    void addFieldType(final String fieldTypeName, final Map<String, ClassInfo> classNameToClassInfo) {
        final String fieldTypeBaseName = scalaBaseClassName(fieldTypeName);
        final ClassInfo fieldTypeClassInfo = getOrCreateClassInfo(fieldTypeBaseName, classNameToClassInfo);
        this.addRelatedClass(RelType.FIELD_TYPES, fieldTypeClassInfo);
    }

    void addStaticFinalFieldConstantInitializerValue(final String fieldName, final Object constValue) {
        if (this.staticFinalFieldNameToConstantInitializerValue == null) {
            this.staticFinalFieldNameToConstantInitializerValue = new HashMap<>();
        }
        this.staticFinalFieldNameToConstantInitializerValue.put(fieldName, constValue);
    }

    /** Add a class that has just been scanned (as opposed to just referenced by a scanned class). */
    static ClassInfo addScannedClass(final String className, final boolean isInterface, final boolean isAnnotation,
            final Map<String, ClassInfo> classNameToClassInfo, final LogNode log) {
        // Handle Scala auxiliary classes (companion objects ending in "$" and trait methods classes
        // ending in "$class")
        final boolean isCompanionObjectClass = className.endsWith("$");
        final boolean isTraitMethodClass = className.endsWith("$class");
        final boolean isNonAuxClass = !isCompanionObjectClass && !isTraitMethodClass;
        final String classBaseName = scalaBaseClassName(className);
        ClassInfo classInfo;
        if (classNameToClassInfo.containsKey(classBaseName)) {
            // Merge into base ClassInfo object that was already allocated, rather than the new one
            classInfo = classNameToClassInfo.get(classBaseName);
            // Class base name has been seen before, check if we're just merging scala aux classes together 
            if (isNonAuxClass && classInfo.classfileScanned
                    || isCompanionObjectClass && classInfo.companionObjectClassfileScanned
                    || isTraitMethodClass && classInfo.traitMethodClassfileScanned) {
                // The same class was encountered more than once in a single jarfile -- should not happen.
                // However, actually there is no restriction for paths within a zipfile to be unique (!!),
                // and in fact zipfiles in the wild do contain the same classfiles multiple times with the
                // same exact path, e.g.: xmlbeans-2.6.0.jar!org/apache/xmlbeans/xml/stream/Location.class
                if (log != null) {
                    log.log("Encountered class with same exact path more than once in the same jarfile: "
                            + className + " (merging info from all copies of the classfile)");
                }
            }
        } else {
            classNameToClassInfo.put(classBaseName, classInfo = new ClassInfo(classBaseName));
        }
        // Mark the appropriate class type as scanned (aux classes all need to be merged into a single
        // ClassInfo object for Scala, but we only want to use the first instance of a given class on the
        // classpath).
        if (isTraitMethodClass) {
            classInfo.traitMethodClassfileScanned = true;
        } else if (isCompanionObjectClass) {
            classInfo.companionObjectClassfileScanned = true;
        } else {
            classInfo.classfileScanned = true;
        }
        classInfo.isInterface |= isInterface;
        classInfo.isAnnotation |= isAnnotation;
        return classInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Get the name of this class. */
    public String getClassName() {
        return className;
    }

    @Override
    public int compareTo(final ClassInfo o) {
        return this.className.compareTo(o.className);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (this.getClass() == obj.getClass()) {
            final ClassInfo other = (ClassInfo) obj;
            return className != null ? className.equals(other.className) : other.className == null;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return className != null ? className.hashCode() : 33;
    }

    @Override
    public String toString() {
        return className;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Standard classes

    /**
     * Regular classes are not annotations, and should not be interfaces, but to be robust, even if it's an
     * interface, check if there are super- or sub-classes (if the classpath can contain two versions of a class,
     * once with it defined as a class, and once with it defined as an interface -- classpath masking should fix
     * this, but this is to fix problems like issue #38 in future).
     */
    public boolean isStandardClass() {
        return !isAnnotation && //
                (!getRelatedClasses(RelType.SUBCLASSES).isEmpty()
                        || !getRelatedClasses(RelType.SUPERCLASSES).isEmpty() || !isImplementedInterface());
    }

    // -------------

    /** Return the set of all subclasses. */
    public Set<ClassInfo> getSubclasses() {
        return filterClassInfo(getReachableClasses(RelType.SUBCLASSES), /* removeExternalClasses = */ true,
                ClassType.ALL);
    }

    /** Return the sorted list of names of all subclasses. */
    public List<String> getNamesOfSubclasses() {
        return getClassNames(getSubclasses());
    }

    /** Returns true if this class has the named subclass. */
    public boolean hasSubclass(final String subclassName) {
        return getNamesOfSubclasses().contains(subclassName);
    }

    // -------------

    /** Return the set of all direct subclasses. */
    public Set<ClassInfo> getDirectSubclasses() {
        return filterClassInfo(getRelatedClasses(RelType.SUBCLASSES), /* removeExternalClasses = */ true,
                ClassType.ALL);
    }

    /** Return the sorted list of names of all direct subclasses. */
    public List<String> getNamesOfDirectSubclasses() {
        return getClassNames(getDirectSubclasses());
    }

    /** Returns true if this class has the named direct subclass. */
    public boolean hasDirectSubclass(final String directSubclassName) {
        return getNamesOfDirectSubclasses().contains(directSubclassName);
    }

    // -------------

    /** Return the set of all superclasses. */
    public Set<ClassInfo> getSuperclasses() {
        return filterClassInfo(getReachableClasses(RelType.SUPERCLASSES), /* removeExternalClasses = */ true,
                ClassType.ALL);
    }

    /** Return the sorted list of names of all superclasses. */
    public List<String> getNamesOfSuperclasses() {
        return getClassNames(getSuperclasses());
    }

    /** Returns true if this class has the named superclass. */
    public boolean hasSuperclass(final String superclassName) {
        return getNamesOfSuperclasses().contains(superclassName);
    }

    // -------------

    /** Return the set of all direct superclasses. */
    public Set<ClassInfo> getDirectSuperclasses() {
        return filterClassInfo(getRelatedClasses(RelType.SUPERCLASSES), /* removeExternalClasses = */ true,
                ClassType.ALL);
    }

    /** Return the sorted list of names of all direct superclasses. */
    public List<String> getNamesOfDirectSuperclasses() {
        return getClassNames(getDirectSuperclasses());
    }

    /** Returns true if this class has the named direct subclass. */
    public boolean hasDirectSuperclass(final String directSuperclassName) {
        return getNamesOfDirectSuperclasses().contains(directSuperclassName);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /**
     * Returns true if this ClassInfo corresponds to an "implemented interface" (meaning a standard, non-annotation
     * interface, or an annotation that has also been implemented as an interface by some class).
     * 
     * Annotations are interfaces, but you can also implement an annotation, so to we return true if an interface
     * (even an annotation) is implemented by a class or extended by a subinterface, or (failing that) if it is not
     * an interface but not an annotation.
     * 
     * (This is named "implemented interface" rather than just "interface" to distinguish it from an annotation.)
     */
    public boolean isImplementedInterface() {
        return !getRelatedClasses(RelType.CLASSES_IMPLEMENTING).isEmpty() || (isInterface && !isAnnotation);
    }

    // -------------

    /** Return the set of all subinterfaces of an interface. */
    public Set<ClassInfo> getSubinterfaces() {
        return !isImplementedInterface() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getReachableClasses(RelType.CLASSES_IMPLEMENTING),
                        /* removeExternalClasses = */ true, ClassType.IMPLEMENTED_INTERFACE);
    }

    /** Return the sorted list of names of all subinterfaces of an interface. */
    public List<String> getNamesOfSubinterfaces() {
        return getClassNames(getSubinterfaces());
    }

    /** Returns true if this class is an interface and has the named subinterface. */
    public boolean hasSubinterface(final String subinterfaceName) {
        return getNamesOfSubinterfaces().contains(subinterfaceName);
    }

    // -------------

    /** Return the set of all direct subinterfaces of an interface. */
    public Set<ClassInfo> getDirectSubinterfaces() {
        return !isImplementedInterface() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getRelatedClasses(RelType.CLASSES_IMPLEMENTING),
                        /* removeExternalClasses = */ true, ClassType.IMPLEMENTED_INTERFACE);
    }

    /** Return the sorted list of names of all direct subinterfaces of an interface. */
    public List<String> getNamesOfDirectSubinterfaces() {
        return getClassNames(getDirectSubinterfaces());
    }

    /** Returns true if this class is and interface and has the named direct subinterface. */
    public boolean hasDirectSubinterface(final String directSubinterfaceName) {
        return getNamesOfDirectSubinterfaces().contains(directSubinterfaceName);
    }

    // -------------

    /** Return the set of all superinterfaces of an interface. */
    public Set<ClassInfo> getSuperinterfaces() {
        return !isImplementedInterface() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getReachableClasses(RelType.IMPLEMENTED_INTERFACES),
                        /* removeExternalClasses = */ true, ClassType.IMPLEMENTED_INTERFACE);
    }

    /** Return the sorted list of names of all superinterfaces of an interface. */
    public List<String> getNamesOfSuperinterfaces() {
        return getClassNames(getSuperinterfaces());
    }

    /** Returns true if this class is an interface and has the named superinterface. */
    public boolean hasSuperinterface(final String superinterfaceName) {
        return getNamesOfSuperinterfaces().contains(superinterfaceName);
    }

    // -------------

    /** Return the set of all direct superinterfaces of an interface. */
    public Set<ClassInfo> getDirectSuperinterfaces() {
        return !isImplementedInterface() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getRelatedClasses(RelType.IMPLEMENTED_INTERFACES),
                        /* removeExternalClasses = */ true, ClassType.IMPLEMENTED_INTERFACE);
    }

    /** Return the names of all direct superinterfaces of an interface. */
    public List<String> getNamesOfDirectSuperinterfaces() {
        return getClassNames(getDirectSuperinterfaces());
    }

    /** Returns true if this class is an interface and has the named direct superinterface. */
    public boolean hasDirectSuperinterface(final String directSuperinterfaceName) {
        return getNamesOfDirectSuperinterfaces().contains(directSuperinterfaceName);
    }

    // -------------

    /** Return the set of all interfaces implemented by this standard class, or by one of its superclasses. */
    public Set<ClassInfo> getImplementedInterfaces() {
        if (!isStandardClass()) {
            return Collections.<ClassInfo> emptySet();
        } else {
            final Set<ClassInfo> superclasses = ClassInfo.filterClassInfo(getReachableClasses(RelType.SUPERCLASSES),
                    /* removeExternalClasses = */ true, ClassType.STANDARD_CLASS);
            // Subclasses of implementing classes also implement the interface
            final Set<ClassInfo> allInterfaces = new HashSet<>();
            allInterfaces.addAll(getReachableClasses(RelType.IMPLEMENTED_INTERFACES));
            for (final ClassInfo superClass : superclasses) {
                allInterfaces.addAll(superClass.getReachableClasses(RelType.IMPLEMENTED_INTERFACES));
            }
            return allInterfaces;
        }
    }

    /** Return the set of all interfaces implemented by this standard class, or by one of its superclasses. */
    public List<String> getNamesOfImplementedInterfaces() {
        return getClassNames(getImplementedInterfaces());
    }

    /** Returns true if this standard class implements the named interface, or by one of its superclasses. */
    public boolean implementsInterface(final String interfaceName) {
        return getNamesOfImplementedInterfaces().contains(interfaceName);
    }

    // -------------

    /**
     * Return the set of all interfaces directly implemented by this standard class, or by one of its superclasses.
     */
    public Set<ClassInfo> getDirectlyImplementedInterfaces() {
        if (!isStandardClass()) {
            return Collections.<ClassInfo> emptySet();
        } else {
            final Set<ClassInfo> superclasses = ClassInfo.filterClassInfo(getReachableClasses(RelType.SUPERCLASSES),
                    /* removeExternalClasses = */ true, ClassType.STANDARD_CLASS);
            // Subclasses of implementing classes also implement the interface
            final Set<ClassInfo> allInterfaces = new HashSet<>();
            allInterfaces.addAll(getRelatedClasses(RelType.IMPLEMENTED_INTERFACES));
            for (final ClassInfo superClass : superclasses) {
                allInterfaces.addAll(superClass.getRelatedClasses(RelType.IMPLEMENTED_INTERFACES));
            }
            return allInterfaces;
        }
    }

    /**
     * Return the set of all interfaces directly implemented by this standard class, or by one of its superclasses.
     */
    public List<String> getNamesOfDirectlyImplementedInterfaces() {
        return getClassNames(getDirectlyImplementedInterfaces());
    }

    /**
     * Returns true if this standard class directly implements the named interface, or by one of its superclasses.
     */
    public boolean directlyImplementsInterface(final String interfaceName) {
        return getNamesOfDirectlyImplementedInterfaces().contains(interfaceName);
    }

    // -------------

    /** Return the set of all class implementing this interface, and all their subclasses. */
    public Set<ClassInfo> getClassesImplementing() {
        if (!isImplementedInterface()) {
            return Collections.<ClassInfo> emptySet();
        } else {
            final Set<ClassInfo> implementingClasses = ClassInfo.filterClassInfo(
                    getReachableClasses(RelType.CLASSES_IMPLEMENTING), /* removeExternalClasses = */ true,
                    ClassType.STANDARD_CLASS);
            // Subclasses of implementing classes also implement the interface
            final Set<ClassInfo> allImplementingClasses = new HashSet<>();
            for (final ClassInfo implementingClass : implementingClasses) {
                allImplementingClasses.add(implementingClass);
                allImplementingClasses.addAll(implementingClass.getReachableClasses(RelType.SUBCLASSES));
            }
            return allImplementingClasses;
        }
    }

    /** Return the names of all classes implementing this interface, and all their subclasses. */
    public List<String> getNamesOfClassesImplementing() {
        return getClassNames(getClassesImplementing());
    }

    /** Returns true if this class is implemented by the named class, or by one of its superclasses. */
    public boolean isImplementedByClass(final String className) {
        return getNamesOfClassesImplementing().contains(className);
    }

    // -------------

    /** Return the set of all class directly implementing this interface. */
    public Set<ClassInfo> getClassesDirectlyImplementing() {
        return !isImplementedInterface() ? Collections.<ClassInfo> emptySet()
                : ClassInfo.filterClassInfo(getRelatedClasses(RelType.CLASSES_IMPLEMENTING),
                        /* removeExternalClasses = */ true, ClassType.STANDARD_CLASS);
    }

    /** Return the names of all classes directly implementing this interface. */
    public List<String> getNamesOfClassesDirectlyImplementing() {
        return getClassNames(getClassesDirectlyImplementing());
    }

    /** Returns true if this class is directly implemented by the named class. */
    public boolean isDirectlyImplementedByClass(final String className) {
        return getNamesOfClassesDirectlyImplementing().contains(className);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /** Returns true if this ClassInfo corresponds to an annotation. */
    public boolean isAnnotation() {
        return isAnnotation;
    }

    // -------------

    /** Return the set of all standard classes or non-annotation interfaces that this annotation class annotates. */
    public Set<ClassInfo> getClassesWithAnnotation() {
        return !isAnnotation() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getReachableClasses(RelType.ANNOTATED_CLASSES),
                        /* removeExternalClasses = */ true, ClassType.STANDARD_CLASS,
                        ClassType.IMPLEMENTED_INTERFACE);
    }

    /**
     * Return the sorted list of names of all standard classes or non-annotation interfaces with this class as a
     * class annotation or meta-annotation.
     */
    public List<String> getNamesOfClassesWithAnnotation() {
        return getClassNames(getClassesWithAnnotation());
    }

    /** Returns true if this class annotates the named class. */
    public boolean annotatesClass(final String annotatedClassName) {
        return getNamesOfClassesWithAnnotation().contains(annotatedClassName);
    }

    // -------------

    /**
     * Return the set of all standard classes or non-annotation interfaces that are directly annotated with this
     * annotation class.
     */
    public Set<ClassInfo> getDirectlyAnnotatedClasses() {
        return !isAnnotation() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getRelatedClasses(RelType.ANNOTATED_CLASSES), /* removeExternalClasses = */ true,
                        ClassType.STANDARD_CLASS, ClassType.IMPLEMENTED_INTERFACE);
    }

    /**
     * Return the sorted list of names of all standard classes or non-annotation interfaces that are directly
     * annotated with this annotation class.
     */
    public List<String> getNamesOfDirectlyAnnotatedClasses() {
        return getClassNames(getDirectlyAnnotatedClasses());
    }

    /** Returns true if this class annotates the named class. */
    public boolean directlyAnnotatesClass(final String directlyAnnotatedClassName) {
        return getNamesOfDirectlyAnnotatedClasses().contains(directlyAnnotatedClassName);
    }

    // -------------

    /**
     * Return the set of all annotations and meta-annotations on this class or interface, or meta-annotations if
     * this is an annotation.
     */
    public Set<ClassInfo> getAnnotations() {
        return filterClassInfo(getReachableClasses(RelType.ANNOTATIONS), /* removeExternalClasses = */ true,
                ClassType.ALL);
    }

    /**
     * Return the sorted list of names of all annotations and meta-annotations on this class or interface, or
     * meta-annotations if this is an annotation.
     */
    public List<String> getNamesOfAnnotations() {
        return getClassNames(getAnnotations());
    }

    /** Returns true if this class, interface or annotation has the named class annotation or meta-annotation. */
    public boolean hasAnnotation(final String annotationName) {
        return getNamesOfAnnotations().contains(annotationName);
    }

    // -------------

    /** Return the set of all annotations and meta-annotations, if this is an annotation class. */
    public Set<ClassInfo> getMetaAnnotations() {
        return !isAnnotation() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getReachableClasses(RelType.ANNOTATIONS), /* removeExternalClasses = */ true,
                        ClassType.ALL);
    }

    /** Return the sorted list of names of all annotations and meta-annotations, if this is an annotation class. */
    public List<String> getNamesOfMetaAnnotations() {
        return getClassNames(getMetaAnnotations());
    }

    /** Returns true if this is an annotation class and it has the named meta-annotation. */
    public boolean hasMetaAnnotation(final String metaAnnotationName) {
        return getNamesOfMetaAnnotations().contains(metaAnnotationName);
    }

    // -------------

    /**
     * Return the set of all direct annotations and meta-annotations on this class or interface, or of direct
     * meta-annotations if this is an annotation. (This is equivalent to the reflection call Class#getAnnotations(),
     * except that it does not require calling the classloader.)
     */
    public Set<ClassInfo> getDirectAnnotations() {
        return filterClassInfo(getRelatedClasses(RelType.ANNOTATIONS), /* removeExternalClasses = */ true,
                ClassType.ALL);
    }

    /**
     * Return the sorted list of names of all direct annotations and meta-annotations on this class or interface, or
     * of direct meta-annotations if this is an annotation. (This is equivalent to the reflection call
     * Class#getAnnotations(), except that it does not require calling the classloader.)
     */
    public List<String> getNamesOfDirectAnnotations() {
        return getClassNames(getDirectAnnotations());
    }

    /**
     * Returns true if this class has the named direct annotation or meta-annotation. (This is equivalent to the
     * reflection call Class#getAnnotations(), except that it does not require calling the classloader.)
     */
    public boolean hasDirectAnnotation(final String directAnnotationName) {
        return getNamesOfDirectAnnotations().contains(directAnnotationName);
    }

    // -------------

    /** Return the set of all annotations that have this meta-annotation. */
    public Set<ClassInfo> getAnnotationsWithMetaAnnotation() {
        return !isAnnotation() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getReachableClasses(RelType.ANNOTATED_CLASSES),
                        /* removeExternalClasses = */ true, ClassType.ANNOTATION);
    }

    /** Return the sorted list of names of all annotations that have this meta-annotation. */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation() {
        return getClassNames(getAnnotationsWithMetaAnnotation());
    }

    /** Returns true if this annotation has the named meta-annotation. */
    public boolean metaAnnotatesAnnotation(final String annotationName) {
        return getNamesOfAnnotationsWithMetaAnnotation().contains(annotationName);
    }

    // -------------

    /** Return the set of all annotations that have this direct meta-annotation. */
    public Set<ClassInfo> getAnnotationsWithDirectMetaAnnotation() {
        return !isAnnotation() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getRelatedClasses(RelType.ANNOTATED_CLASSES), /* removeExternalClasses = */ true,
                        ClassType.ANNOTATION);
    }

    /** Return the sorted list of names of all annotations that have this direct meta-annotation. */
    public List<String> getNamesOfAnnotationsWithDirectMetaAnnotation() {
        return getClassNames(getAnnotationsWithDirectMetaAnnotation());
    }

    /** Returns true if this annotation is directly meta-annotated with the named annotation. */
    public boolean hasDirectMetaAnnotation(final String directMetaAnnotationName) {
        return getNamesOfAnnotationsWithDirectMetaAnnotation().contains(directMetaAnnotationName);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fields

    /**
     * Return the sorted list of names of field types. Requires FastClasspathScanner#indexFieldTypes() to have been
     * called before scanning.
     */
    public List<String> getFieldTypes() {
        return !isStandardClass() ? Collections.<String> emptyList()
                : getClassNames(filterClassInfo(getRelatedClasses(RelType.FIELD_TYPES),
                        /* removeExternalClasses = */ true, ClassType.ALL));
    }
}
