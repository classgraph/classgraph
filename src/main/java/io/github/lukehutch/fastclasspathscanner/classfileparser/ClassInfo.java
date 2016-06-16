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
package io.github.lukehutch.fastclasspathscanner.classfileparser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class ClassInfo implements Comparable<ClassInfo> {
    /** Name of the class/interface/annotation. */
    public String className;

    /** True if the classfile indicated this is an interface. */
    private boolean isInterface;

    /** True if the classfile indicated this is an annotation. */
    private boolean isAnnotation;

    /**
     * True when a class has been scanned (i.e. its classfile contents read), as opposed to only being referenced by
     * another class' classfile as a superclass/superinterface/annotation. If classfileScanned is true, then this
     * also must be a whitelisted (and non-blacklisted) class in a whitelisted (and non-blacklisted) package.
     */
    public boolean classfileScanned;

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

    public enum RelType {
        /**
         * Superclasses of this class, if this is a regular class.
         * 
         * (Should consist of only one entry, or null if superclass is java.lang.Object or unknown).
         */
        SUPERCLASSES,

        /** All reachable superclasses of this class, if this is a regular class. */
        ALL_SUPERCLASSES,

        /** Subclasses of this class, if this is a regular class. */
        SUBCLASSES,

        /** All reachable subclasses of this class, if this is a regular class. */
        ALL_SUBCLASSES,

        /**
         * Interfaces that this class implements, if this is a regular class, or superinterfaces, if this is an
         * interface.
         * 
         * (May also include annotations, since annotations are interfaces, so you can implement an annotation.)
         */
        IMPLEMENTED_INTERFACES,

        /** All reachable interfaces that this class implements. */
        ALL_IMPLEMENTED_INTERFACES,

        /** Classes that implement this interface (including sub-interfaces), if this is an interface. */
        CLASSES_IMPLEMENTING,

        /** All reachable classes that implement this interface. */
        ALL_CLASSES_IMPLEMENTING,

        /** All reachable standard classes that implement this interface. */
        ALL_STANDARD_CLASSES_IMPLEMENTING,

        /** All reachable sub-interfaces that implement this interface. */
        ALL_SUBINTERFACES,

        /**
         * Annotations on this class, if this is a regular class, or meta-annotations on this annotation, if this is
         * an annotation.
         */
        ANNOTATIONS,

        /** All annotations and reachable meta-annotations on this class. */
        ALL_ANNOTATIONS,

        /** Classes annotated by this annotation, if this is an annotation. */
        ANNOTATED_CLASSES,

        /** All reachable classes annotated or meta-annotated by this annotation, if this is an annotation. */
        ALL_ANNOTATED_CLASSES,

        /**
         * All reachable classes or interfaces annotated or meta-annotated by this annotation, if this is an
         * annotation.
         */
        ALL_ANNOTATED_STANDARD_CLASSES_OR_INTERFACES,

        /** The types of fields, if this is a regular class. */
        FIELD_TYPES,

        /** Classes with fields of a given type. */
        CLASSES_WITH_FIELD_OF_TYPE,
    }

    /** The set of classes related to this one. */
    public HashMap<RelType, HashSet<ClassInfo>> relatedTypeToClassInfoSet = new HashMap<>();

    /**
     * The static constant initializer values of static final fields, if a StaticFinalFieldMatchProcessor matched a
     * field in this class.
     */
    public HashMap<String, Object> fieldValues;

    public ClassInfo(final String className) {
        this.className = className;
    }

    /** The class type to return. */
    public enum ClassType {
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
    public static List<ClassInfo> filterClassInfo(final Collection<ClassInfo> classInfoColl,
            final boolean removeExternalClasses, final ClassType classType) {
        if (classInfoColl == null) {
            return Collections.emptyList();
        } else {
            final ArrayList<ClassInfo> classInfoFiltered = new ArrayList<>(classInfoColl.size());
            for (final ClassInfo classInfo : classInfoColl) {
                // If needed, remove external classes (classes that were referenced but not scanned => not whitelisted)
                if ((!removeExternalClasses || classInfo.classfileScanned) &&
                // Return true only if this is the requested class type
                        (classType == ClassType.ALL
                                || classType == ClassType.STANDARD_CLASS && classInfo.isStandardClass()
                                || classType == ClassType.IMPLEMENTED_INTERFACE
                                        && classInfo.isImplementedInterface()
                                || classType == ClassType.ANNOTATION && classInfo.isAnnotation()
                                || classType == ClassType.INTERFACE_OR_ANNOTATION
                                        && (classInfo.isInterface || classInfo.isAnnotation))) {
                    classInfoFiltered.add(classInfo);
                }
            }
            Collections.sort(classInfoFiltered);
            return classInfoFiltered;
        }
    }

    /** Get the ClassInfo objects for the classes related to this one in the specified way. */
    public List<ClassInfo> getRelatedClasses(final RelType relType, final boolean removeExternalClasses,
            final ClassType classType) {
        final HashSet<ClassInfo> relatedClassClassInfo = relatedTypeToClassInfoSet.get(relType);
        if (relatedClassClassInfo == null) {
            return Collections.emptyList();
        } else {
            return filterClassInfo(relatedClassClassInfo, removeExternalClasses, classType);
        }
    }

    /**
     * Get the ClassInfo objects for the classes related to this one in the specified way.
     * 
     * Equivalent to getRelatedClasses(relType, false, ClassType.ALL)
     */
    public List<ClassInfo> getRelatedClasses(final RelType relType) {
        return getRelatedClasses(relType, /* removeExternalClasses = */ false, ClassType.ALL);
    }

    /**
     * Get the sorted list of the names of classes given a set of ClassInfo objects, optionally removing classes
     * that were referred to by whitelisted classes, but that were not themselves whitelisted (i.e. that were not
     * scanned).
     */
    public static List<String> getClassNamesFiltered(final Collection<ClassInfo> classInfoColl,
            final boolean removeExternalClasses, final ClassType classType) {
        final List<ClassInfo> filtered = filterClassInfo(classInfoColl, removeExternalClasses, classType);
        if (filtered.isEmpty()) {
            return Collections.emptyList();
        } else {
            final ArrayList<String> classNames = new ArrayList<>(filtered.size());
            for (final ClassInfo classInfo : filtered) {
                classNames.add(classInfo.className);
            }
            return classNames;
        }
    }

    /**
     * Get the sorted list of the names of classes that are related to this one in the specified way, optionally
     * removing classes that were referred to by whitelisted classes, but that were not themselves whitelisted (i.e.
     * that were not scanned).
     */
    public List<String> getRelatedClassNames(final RelType relType, final boolean removeExternalClasses,
            final ClassType classType) {
        return getClassNamesFiltered(relatedTypeToClassInfoSet.get(relType), removeExternalClasses, classType);
    }

    /**
     * Get the sorted list of the names of classes that are related to this one in the specified way.
     * 
     * Equivalent to getRelatedClassNames(relType, false, ClassType.ALL)
     */
    public List<String> getRelatedClassNames(final RelType relType) {
        return getRelatedClassNames(relType, /* removeExternalClasses = */ false, ClassType.ALL);
    }

    /**
     * Add a ClassInfo objects for the given relationship type. Returns true if the collection changed as a result
     * of the call.
     */
    public boolean addRelatedClass(final RelType relType, final ClassInfo classInfo) {
        HashSet<ClassInfo> classInfoSet = relatedTypeToClassInfoSet.get(relType);
        if (classInfoSet == null) {
            relatedTypeToClassInfoSet.put(relType, classInfoSet = new HashSet<>(4));
        }
        return classInfoSet.add(classInfo);
    }

    /**
     * Add a ClassInfo objects for the given relationship type. Returns true if the collection changed as a result
     * of the call.
     */
    public boolean addRelatedClasses(final RelType relType, final Collection<ClassInfo> classInfoSetToAdd) {
        if (classInfoSetToAdd.isEmpty()) {
            return false;
        } else {
            HashSet<ClassInfo> classInfoSet = relatedTypeToClassInfoSet.get(relType);
            if (classInfoSet == null) {
                relatedTypeToClassInfoSet.put(relType, classInfoSet = new HashSet<>(classInfoSetToAdd.size() + 4));
            }
            return classInfoSet.addAll(classInfoSetToAdd);
        }
    }

    /** Returns true if this ClassInfo corresponds to an annotation. */
    public boolean isAnnotation() {
        return isAnnotation;
    }

    /**
     * Returns true if this ClassInfo corresponds to an "implemented interface" (meaning a non-annotation interface,
     * or an annotation that has also been implemented as an interface by some class). Annotations are interfaces,
     * but you can also implement an annotation, so to we need to check if an interface (even an annotation) is
     * implemented by a class / extended by a subinterface (when classesImplementing contains at least one element),
     * or (failing that) if it is not an interface but not an annotation.
     * 
     * (This is named "implemented interface" rather than just "interface" to distinguish it from an annotation.)
     */
    public boolean isImplementedInterface() {
        return !getRelatedClasses(RelType.CLASSES_IMPLEMENTING).isEmpty() || isInterface && !isAnnotation;
    }

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

    private static ClassInfo getOrCreateClassInfo(final String className,
            final HashMap<String, ClassInfo> classNameToClassInfo) {
        ClassInfo classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            classNameToClassInfo.put(className, classInfo = new ClassInfo(className));
        }
        return classInfo;
    }

    public void addSuperclass(final String superclassName, final HashMap<String, ClassInfo> classNameToClassInfo) {
        if (superclassName != null && !superclassName.equals("java.lang.Object")) {
            final ClassInfo superclassClassInfo = getOrCreateClassInfo(scalaBaseClassName(superclassName),
                    classNameToClassInfo);
            this.addRelatedClass(RelType.SUPERCLASSES, superclassClassInfo);
            superclassClassInfo.addRelatedClass(RelType.SUBCLASSES, this);
        }
    }

    public void addAnnotation(final String annotationName, final HashMap<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo annotationClassInfo = getOrCreateClassInfo(scalaBaseClassName(annotationName),
                classNameToClassInfo);
        annotationClassInfo.isAnnotation = true;
        this.addRelatedClass(RelType.ANNOTATIONS, annotationClassInfo);
        annotationClassInfo.addRelatedClass(RelType.ANNOTATED_CLASSES, this);
    }

    public void addImplementedInterface(final String interfaceName,
            final HashMap<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo interfaceClassInfo = getOrCreateClassInfo(scalaBaseClassName(interfaceName),
                classNameToClassInfo);
        interfaceClassInfo.isInterface = true;
        this.addRelatedClass(RelType.IMPLEMENTED_INTERFACES, interfaceClassInfo);
        interfaceClassInfo.addRelatedClass(RelType.CLASSES_IMPLEMENTING, this);
    }

    public void addFieldType(final String fieldTypeName, final HashMap<String, ClassInfo> classNameToClassInfo) {
        final String fieldTypeBaseName = scalaBaseClassName(fieldTypeName);
        final ClassInfo fieldTypeClassInfo = getOrCreateClassInfo(fieldTypeBaseName, classNameToClassInfo);
        this.addRelatedClass(RelType.FIELD_TYPES, fieldTypeClassInfo);
    }

    public void addFieldConstantValue(final String fieldName, final Object constValue) {
        if (this.fieldValues == null) {
            this.fieldValues = new HashMap<>();
        }
        this.fieldValues.put(fieldName, constValue);
    }

    /** Add a class that has just been scanned (as opposed to just referenced by a scanned class). */
    public static ClassInfo addScannedClass(final String className, final boolean isInterface,
            final boolean isAnnotation, final HashMap<String, ClassInfo> classNameToClassInfo) {
        // Handle Scala auxiliary classes (companion objects ending in "$" and trait methods classes
        // ending in "$class")
        final boolean isCompanionObjectClass = className.endsWith("$");
        final boolean isTraitMethodClass = className.endsWith("$class");
        final boolean isNonAuxClass = !isCompanionObjectClass && !isTraitMethodClass;
        final String classBaseName = scalaBaseClassName(className);
        ClassInfo classInfo = classNameToClassInfo.get(classBaseName);
        if (classInfo == null) {
            // This is the first time this class name has been seen, create new ClassInfo object
            classNameToClassInfo.put(classBaseName, classInfo = new ClassInfo(classBaseName));
        } else {
            // Class name has been seen before
            if (isNonAuxClass && classInfo.classfileScanned
                    || isCompanionObjectClass && classInfo.companionObjectClassfileScanned
                    || isTraitMethodClass && classInfo.traitMethodClassfileScanned) {
                // This class was encountered more than once on the classpath -- ignore 2nd and subsequent defs 
                return null;
            }
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

    @Override
    public int compareTo(final ClassInfo o) {
        return this.className.compareTo(o.className);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (this.getClass() == obj.getClass()) {
            ClassInfo other = (ClassInfo) obj;
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
}
