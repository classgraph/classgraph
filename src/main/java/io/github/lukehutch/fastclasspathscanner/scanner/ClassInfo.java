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

import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.scanner.AnnotationInfo.AnnotationParamValue;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult.InfoObject;
import io.github.lukehutch.fastclasspathscanner.utils.AdditionOrderedSet;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMapKeyToList;

/** Holds metadata about a class encountered during a scan. */
public class ClassInfo extends InfoObject implements Comparable<ClassInfo> {
    /** Name of the class/interface/annotation. */
    private final String className;

    /** Class modifier flags, e.g. Modifier.PUBLIC */
    private int classModifiers;

    /** True if the classfile indicated this is an interface. */
    private boolean isInterface;

    /** True if the classfile indicated this is an annotation. */
    private boolean isAnnotation;

    /** The fully-qualified containing method name, for anonymous inner classes. */
    private String fullyQualifiedContainingMethodName;

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

    /**
     * The classpath element URL(s) (classpath root dir or jar) that this class was found within. Generally this
     * will consist of exactly one entry, however it's possible for Scala that a class and its companion class will
     * be provided in different jars, so we need to be able to support multiple classpath roots per class, so that
     * classloading can find the class wherever it is, in order to provide MatchProcessors with a class reference.
     */
    private HashSet<URL> classpathElementURLs;

    /**
     * The classloaders to try to load this class with before calling a MatchProcessor.
     */
    private ClassLoader[] classLoaders;

    /** The scan spec. */
    private final ScanSpec scanSpec;

    /** Info on class annotations, including optional annotation param values. */
    List<AnnotationInfo> annotationInfo;

    /** Info on fields. */
    List<FieldInfo> fieldInfo;

    /** Reverse mapping from field name to FieldInfo. */
    private Map<String, FieldInfo> fieldNameToFieldInfo;

    /** Info on fields. */
    List<MethodInfo> methodInfo;

    /** Reverse mapping from method name to MethodInfo. */
    private MultiMapKeyToList<String, MethodInfo> methodNameToMethodInfo;

    /** For annotations, the default values of parameters. */
    List<AnnotationParamValue> annotationDefaultParamValues;

    /** Sets back-reference to scan result after scan is complete. */
    @Override
    void setScanResult(final ScanResult scanResult) {
        if (annotationInfo != null) {
            for (final AnnotationInfo ai : annotationInfo) {
                ai.setScanResult(scanResult);
            }
        }
        if (fieldInfo != null) {
            for (final FieldInfo fi : fieldInfo) {
                fi.setScanResult(scanResult);
            }
        }
        if (methodInfo != null) {
            for (final MethodInfo mi : methodInfo) {
                mi.setScanResult(scanResult);
            }
        }
    }

    private static final int ANNOTATION_CLASS_MODIFIER = 0x2000;

    // -------------------------------------------------------------------------------------------------------------

    /** Get the name of this class. */
    public String getClassName() {
        return className;
    }

    /** Get the class modifier flags, e.g. Modifier.PUBLIC */
    public int getClassModifiers() {
        return classModifiers;
    }

    /**
     * The classpath element URL(s) (classpath root dir or jar) that this class was found within. For Java, this
     * will consist of exactly one entry, so you should generally call the getClasspathElementURL() convenience
     * method instead.
     * 
     * For Scala, it is possible (though not likely) that a class and its companion class will be provided in
     * different jars or different directories, so to be safe, for Scala, you should probably call this method
     * instead.
     */
    public Set<URL> getClasspathElementURLs() {
        return classpathElementURLs;
    }

    /**
     * The classpath element URL (classpath root dir or jar) that this class was found within. This will consist of
     * exactly one entry for Java.
     * 
     * (If calling this from Scala, in the rare but possible case that a class and its companion class is split
     * between two jarfiles or directories, this will throw IllegalArgumentException.)
     */
    public URL getClasspathElementURL() {
        final Iterator<URL> iter = classpathElementURLs.iterator();
        if (!iter.hasNext()) {
            // Should not happen
            throw new IllegalArgumentException("classpathElementURLs set is empty");
        }
        final URL classpathElementURL = iter.next();
        if (iter.hasNext()) {
            throw new IllegalArgumentException("Class " + className
                    + " has multiple classpath URLs (need to call getClasspathElementURLs() instead): "
                    + classpathElementURLs);
        }
        return classpathElementURL;
    }

    /** Get the ClassLoader(s) to use when trying to load the class. */
    public ClassLoader[] getClassLoaders() {
        return classLoaders;
    }

    /** Compare based on class name. */
    @Override
    public int compareTo(final ClassInfo o) {
        return this.className.compareTo(o.className);
    }

    /** Use class name for equals(). */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (this.getClass() != obj.getClass()) {
            return false;
        }
        final ClassInfo other = (ClassInfo) obj;
        return className.equals(other.className);
    }

    /** Use hash code of class name. */
    @Override
    public int hashCode() {
        return className != null ? className.hashCode() : 33;
    }

    @Override
    public String toString() {
        return ((classModifiers & Modifier.PUBLIC) != 0 ? "public " : "") //
                + ((classModifiers & Modifier.PROTECTED) != 0 ? "protected " : "") //
                + ((classModifiers & Modifier.PRIVATE) != 0 ? "private " : "") //
                + ((classModifiers & 0x1000) != 0 ? "synthetic " : "") //
                + ((classModifiers & Modifier.ABSTRACT) != 0 ? "abstract " : "") //
                + ((classModifiers & Modifier.STATIC) != 0 ? "static " : "") //
                + ((classModifiers & Modifier.FINAL) != 0 ? "final " : "") //
                + ((classModifiers & Modifier.STRICT) != 0 ? "strict " : "") //
                + (isAnnotation ? "@interface " : isInterface ? "interface " : //
                        (classModifiers & 0x4000) != 0 ? "enum " : "class ") // 
                + className;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** How classes are related. */
    private enum RelType {

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

        /** Indicates that an inner class is contained within this one. */
        CONTAINS_INNER_CLASS,

        /** Indicates that an outer class contains this one. (Should only have zero or one entries.) */
        CONTAINED_WITHIN_OUTER_CLASS,

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

        // Class annotations:

        /**
         * Annotations on this class, if this is a regular class, or meta-annotations on this annotation, if this is
         * an annotation.
         */
        CLASS_ANNOTATIONS,

        /** Classes annotated with this annotation, if this is an annotation. */
        CLASSES_WITH_CLASS_ANNOTATION,

        // Method annotations:

        /** Annotations on one or more methods of this class. */
        METHOD_ANNOTATIONS,

        /** Classes that have one or more methods annotated with this annotation, if this is an annotation. */
        CLASSES_WITH_METHOD_ANNOTATION,

        // Field annotations:

        /** Annotations on one or more fields of this class. */
        FIELD_ANNOTATIONS,

        /** Classes that have one or more fields annotated with this annotation, if this is an annotation. */
        CLASSES_WITH_FIELD_ANNOTATION,
    }

    /** The set of classes related to this one. */
    private final Map<RelType, Set<ClassInfo>> relatedTypeToClassInfoSet = new HashMap<>();

    /**
     * The static constant initializer values of static final fields, if a StaticFinalFieldMatchProcessor matched a
     * field in this class.
     */
    private Map<String, Object> staticFinalFieldNameToConstantInitializerValue;

    private ClassInfo(final String className, final int classModifiers, final ScanSpec scanSpec) {
        this.className = className;
        this.classModifiers = classModifiers;
        this.scanSpec = scanSpec;
        if (className.endsWith(";")) {
            throw new RuntimeException("Bad class name");
        }
    }

    /** The class type to return. */
    enum ClassType {
        /** Get all class types. */
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

    /** Get the classes related to this one in the specified way. */
    static Set<ClassInfo> filterClassInfo(final Set<ClassInfo> classInfoSet,
            final boolean removeExternalClassesIfStrictWhitelist, final ScanSpec scanSpec,
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
        final Set<ClassInfo> classInfoSetFiltered = new HashSet<>(classInfoSet.size());
        for (final ClassInfo classInfo : classInfoSet) {
            // Check class type against requested type(s)
            if (includeAllTypes //
                    || includeStandardClasses && classInfo.isStandardClass()
                    || includeImplementedInterfaces && classInfo.isImplementedInterface()
                    || includeAnnotations && classInfo.isAnnotation()) {
                // Check whether class should be visible in results
                final boolean isExternal = !classInfo.classfileScanned;
                final boolean isBlacklisted = scanSpec.classIsBlacklisted(classInfo.className);
                final boolean isWhitelisted = !isExternal && !isBlacklisted;
                final boolean removeExternalClasses = removeExternalClassesIfStrictWhitelist
                        && scanSpec.strictWhitelist;
                final boolean isVisibleExternal = isExternal && !removeExternalClasses && !isBlacklisted;
                if (isWhitelisted || isVisibleExternal) {
                    // Class passed filter criteria
                    classInfoSetFiltered.add(classInfo);
                }
            }
        }
        return classInfoSetFiltered;
    }

    /**
     * Get the sorted list of the names of classes given a collection of ClassInfo objects. (Class names are not
     * deduplicated.)
     */
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

    /** Get the classes directly related to this ClassInfo object the specified way. */
    private Set<ClassInfo> getDirectlyRelatedClasses(final RelType relType) {
        final Set<ClassInfo> relatedClassClassInfo = relatedTypeToClassInfoSet.get(relType);
        return relatedClassClassInfo == null ? Collections.<ClassInfo> emptySet() : relatedClassClassInfo;
    }

    /**
     * Find all ClassInfo nodes reachable from this ClassInfo node over the given relationship type links (not
     * including this class itself).
     */
    private Set<ClassInfo> getReachableClasses(final RelType relType) {
        final Set<ClassInfo> directlyRelatedClasses = this.getDirectlyRelatedClasses(relType);
        if (directlyRelatedClasses.isEmpty()) {
            return directlyRelatedClasses;
        }
        final Set<ClassInfo> reachableClasses = new HashSet<>(directlyRelatedClasses);
        if (relType == RelType.METHOD_ANNOTATIONS || relType == RelType.FIELD_ANNOTATIONS) {
            // For method and field annotations, need to change the RelType when finding meta-annotations
            for (final ClassInfo annotation : directlyRelatedClasses) {
                reachableClasses.addAll(annotation.getReachableClasses(RelType.CLASS_ANNOTATIONS));
            }
        } else if (relType == RelType.CLASSES_WITH_METHOD_ANNOTATION
                || relType == RelType.CLASSES_WITH_FIELD_ANNOTATION) {
            // If looking for meta-annotated methods or fields, need to find all meta-annotated annotations,
            // then look for the methods or fields that they annotate
            for (final ClassInfo subAnnotation : filterClassInfo(
                    getReachableClasses(RelType.CLASSES_WITH_CLASS_ANNOTATION),
                    /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ANNOTATION)) {
                reachableClasses.addAll(subAnnotation.getDirectlyRelatedClasses(relType));
            }
        } else {
            // For other relationship types, the reachable type stays the same over the transitive closure.
            // Find the transitive closure, breaking cycles where necessary.
            final LinkedList<ClassInfo> queue = new LinkedList<>();
            queue.addAll(directlyRelatedClasses);
            while (!queue.isEmpty()) {
                final ClassInfo head = queue.removeFirst();
                for (final ClassInfo directlyReachableFromHead : head.getDirectlyRelatedClasses(relType)) {
                    // Don't get in cycle
                    if (reachableClasses.add(directlyReachableFromHead)) {
                        queue.add(directlyReachableFromHead);
                    }
                }
            }
        }
        return reachableClasses;
    }

    /**
     * Add a class with a given relationship type. Test whether the collection changed as a result of the call.
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

    /**
     * Get a ClassInfo object, or create it if it doesn't exist. N.B. not threadsafe, so ClassInfo objects should
     * only ever be constructed by a single thread.
     */
    private static ClassInfo getOrCreateClassInfo(final String className, final int classModifiers,
            final ScanSpec scanSpec, final Map<String, ClassInfo> classNameToClassInfo) {
        ClassInfo classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            classNameToClassInfo.put(className, classInfo = new ClassInfo(className, classModifiers, scanSpec));
        }
        return classInfo;
    }

    /** Add a superclass to this class. */
    void addSuperclass(final String superclassName, final Map<String, ClassInfo> classNameToClassInfo) {
        if (superclassName != null) {
            final ClassInfo superclassClassInfo = getOrCreateClassInfo(scalaBaseClassName(superclassName),
                    /* classModifiers = */ 0, scanSpec, classNameToClassInfo);
            this.addRelatedClass(RelType.SUPERCLASSES, superclassClassInfo);
            superclassClassInfo.addRelatedClass(RelType.SUBCLASSES, this);
        }
    }

    /** Add an annotation to this class. */
    void addClassAnnotation(final AnnotationInfo classAnnotationInfo,
            final Map<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo annotationClassInfo = getOrCreateClassInfo(
                scalaBaseClassName(classAnnotationInfo.annotationName), ANNOTATION_CLASS_MODIFIER, scanSpec,
                classNameToClassInfo);
        annotationClassInfo.isAnnotation = true;
        if (this.annotationInfo == null) {
            this.annotationInfo = new ArrayList<>();
        }
        this.annotationInfo.add(classAnnotationInfo);
        classAnnotationInfo.addDefaultValues(annotationClassInfo.annotationDefaultParamValues);
        this.addRelatedClass(RelType.CLASS_ANNOTATIONS, annotationClassInfo);
        annotationClassInfo.addRelatedClass(RelType.CLASSES_WITH_CLASS_ANNOTATION, this);
    }

    /** Add a method annotation to this class. */
    void addMethodAnnotation(final AnnotationInfo methodAnnotationInfo,
            final Map<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo annotationClassInfo = getOrCreateClassInfo(
                scalaBaseClassName(methodAnnotationInfo.annotationName), ANNOTATION_CLASS_MODIFIER, scanSpec,
                classNameToClassInfo);
        annotationClassInfo.isAnnotation = true;
        methodAnnotationInfo.addDefaultValues(annotationClassInfo.annotationDefaultParamValues);
        this.addRelatedClass(RelType.METHOD_ANNOTATIONS, annotationClassInfo);
        annotationClassInfo.addRelatedClass(RelType.CLASSES_WITH_METHOD_ANNOTATION, this);
    }

    /** Add a field annotation to this class. */
    void addFieldAnnotation(final AnnotationInfo fieldAnnotationInfo,
            final Map<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo annotationClassInfo = getOrCreateClassInfo(
                scalaBaseClassName(fieldAnnotationInfo.annotationName), ANNOTATION_CLASS_MODIFIER, scanSpec,
                classNameToClassInfo);
        annotationClassInfo.isAnnotation = true;
        fieldAnnotationInfo.addDefaultValues(annotationClassInfo.annotationDefaultParamValues);
        this.addRelatedClass(RelType.FIELD_ANNOTATIONS, annotationClassInfo);
        annotationClassInfo.addRelatedClass(RelType.CLASSES_WITH_FIELD_ANNOTATION, this);
    }

    /** Add an implemented interface to this class. */
    void addImplementedInterface(final String interfaceName, final Map<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo interfaceClassInfo = getOrCreateClassInfo(scalaBaseClassName(interfaceName),
                /* classModifiers = */ Modifier.INTERFACE, scanSpec, classNameToClassInfo);
        interfaceClassInfo.isInterface = true;
        this.addRelatedClass(RelType.IMPLEMENTED_INTERFACES, interfaceClassInfo);
        interfaceClassInfo.addRelatedClass(RelType.CLASSES_IMPLEMENTING, this);
    }

    /** Add class containment info */
    static void addClassContainment(final List<SimpleEntry<String, String>> classContainmentEntries,
            final ScanSpec scanSpec, final Map<String, ClassInfo> classNameToClassInfo) {
        for (final SimpleEntry<String, String> ent : classContainmentEntries) {
            final String innerClassName = ent.getKey();
            final ClassInfo innerClassInfo = ClassInfo.getOrCreateClassInfo(innerClassName,
                    /* classModifiers = */ 0, scanSpec, classNameToClassInfo);
            final String outerClassName = ent.getValue();
            final ClassInfo outerClassInfo = ClassInfo.getOrCreateClassInfo(outerClassName,
                    /* classModifiers = */ 0, scanSpec, classNameToClassInfo);
            innerClassInfo.addRelatedClass(RelType.CONTAINED_WITHIN_OUTER_CLASS, outerClassInfo);
            outerClassInfo.addRelatedClass(RelType.CONTAINS_INNER_CLASS, innerClassInfo);
        }
    }

    /** Add containing method name, for anonymous inner classes */
    void addFullyQualifiedContainingMethodName(final String fullyQualifiedContainingMethodName) {
        this.fullyQualifiedContainingMethodName = fullyQualifiedContainingMethodName;
    }

    /** Add a field type. */
    void addFieldType(final String fieldTypeName, final Map<String, ClassInfo> classNameToClassInfo) {
        final String fieldTypeBaseName = scalaBaseClassName(fieldTypeName);
        final ClassInfo fieldTypeClassInfo = getOrCreateClassInfo(fieldTypeBaseName, /* classModifiers = */ 0,
                scanSpec, classNameToClassInfo);
        this.addRelatedClass(RelType.FIELD_TYPES, fieldTypeClassInfo);
    }

    /** Add a static final field's constant initializer value. */
    void addStaticFinalFieldConstantInitializerValue(final String fieldName, final Object constValue) {
        if (this.staticFinalFieldNameToConstantInitializerValue == null) {
            this.staticFinalFieldNameToConstantInitializerValue = new HashMap<>();
        }
        this.staticFinalFieldNameToConstantInitializerValue.put(fieldName, constValue);
    }

    /** Add field info. */
    void addFieldInfo(final List<FieldInfo> fieldInfoList, final Map<String, ClassInfo> classNameToClassInfo) {
        for (final FieldInfo fieldInfo : fieldInfoList) {
            final List<AnnotationInfo> fieldAnnotationInfoList = fieldInfo.annotationInfo;
            if (fieldAnnotationInfoList != null) {
                for (final AnnotationInfo fieldAnnotationInfo : fieldAnnotationInfoList) {
                    final ClassInfo classInfo = getOrCreateClassInfo(fieldAnnotationInfo.annotationName,
                            ANNOTATION_CLASS_MODIFIER, scanSpec, classNameToClassInfo);
                    fieldAnnotationInfo.addDefaultValues(classInfo.annotationDefaultParamValues);
                }
            }
        }
        if (this.fieldInfo == null) {
            this.fieldInfo = fieldInfoList;
        } else {
            this.fieldInfo.addAll(fieldInfoList);
        }
    }

    /** Add method info. */
    void addMethodInfo(final List<MethodInfo> methodInfoList, final Map<String, ClassInfo> classNameToClassInfo) {
        for (final MethodInfo methodInfo : methodInfoList) {
            final List<AnnotationInfo> methodAnnotationInfoList = methodInfo.annotationInfo;
            if (methodAnnotationInfoList != null) {
                for (final AnnotationInfo methodAnnotationInfo : methodAnnotationInfoList) {
                    methodAnnotationInfo.addDefaultValues(
                            getOrCreateClassInfo(methodAnnotationInfo.annotationName, ANNOTATION_CLASS_MODIFIER,
                                    scanSpec, classNameToClassInfo).annotationDefaultParamValues);
                }
            }
            final AnnotationInfo[][] methodParamAnnotationInfoList = methodInfo.parameterAnnotationInfo;
            if (methodParamAnnotationInfoList != null) {
                for (int i = 0; i < methodParamAnnotationInfoList.length; i++) {
                    final AnnotationInfo[] paramAnnotationInfoArr = methodParamAnnotationInfoList[i];
                    if (paramAnnotationInfoArr != null) {
                        for (int j = 0; j < paramAnnotationInfoArr.length; j++) {
                            final AnnotationInfo paramAnnotationInfo = paramAnnotationInfoArr[j];
                            paramAnnotationInfo
                                    .addDefaultValues(getOrCreateClassInfo(paramAnnotationInfo.annotationName,
                                            ANNOTATION_CLASS_MODIFIER, scanSpec,
                                            classNameToClassInfo).annotationDefaultParamValues);
                        }
                    }
                }
            }
        }
        if (this.methodInfo == null) {
            this.methodInfo = methodInfoList;
        } else {
            this.methodInfo.addAll(methodInfoList);
        }
    }

    /** Add annotation default values. */
    void addAnnotationParamDefaultValues(final List<AnnotationParamValue> paramNamesAndValues) {
        if (this.annotationDefaultParamValues == null) {
            this.annotationDefaultParamValues = paramNamesAndValues;
        } else {
            this.annotationDefaultParamValues.addAll(paramNamesAndValues);
        }
    }

    /**
     * Add a class that has just been scanned (as opposed to just referenced by a scanned class). Not threadsafe,
     * should be run in single threaded context.
     */
    static ClassInfo addScannedClass(final String className, final int classModifiers, final boolean isInterface,
            final boolean isAnnotation, final ScanSpec scanSpec, final Map<String, ClassInfo> classNameToClassInfo,
            final ClasspathElement classpathElement, final LogNode log) {
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
            // Merge modifiers
            classInfo.classModifiers |= classModifiers;
        } else {
            // This is the first time this class has been seen, add it
            classNameToClassInfo.put(classBaseName,
                    classInfo = new ClassInfo(classBaseName, classModifiers, scanSpec));
        }

        // Remember which classpath element(s) the class was found in, for classloading
        if (classInfo.classpathElementURLs == null) {
            classInfo.classpathElementURLs = new HashSet<>();
        }
        classInfo.classpathElementURLs.add(classpathElement.getClasspathElementURL());

        // Remember which classpath element(s) the class was found in, for classloading
        final ClassLoader[] classLoaders = classpathElement.getClassLoaders();
        if (classInfo.classLoaders == null) {
            classInfo.classLoaders = classLoaders;
        } else if (classLoaders != null && !classInfo.classLoaders.equals(classLoaders)) {
            // Merge together ClassLoader list (concatenate and dedup)
            final AdditionOrderedSet<ClassLoader> allClassLoaders = new AdditionOrderedSet<>(
                    classInfo.classLoaders);
            for (final ClassLoader classLoader : classLoaders) {
                allClassLoaders.add(classLoader);
            }
            final List<ClassLoader> classLoaderOrder = allClassLoaders.toList();
            classInfo.classLoaders = classLoaderOrder.toArray(new ClassLoader[classLoaderOrder.size()]);
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
    // Standard classes

    /**
     * Get the names of all classes, interfaces and annotations found during the scan, or the empty list if none.
     * 
     * @return the sorted unique list of names of all classes, interfaces and annotations found during the scan, or
     *         the empty list if none.
     */
    static List<String> getNamesOfAllClasses(final ScanSpec scanSpec, final Set<ClassInfo> allClassInfo) {
        return getClassNames(filterClassInfo(allClassInfo, /* removeExternalClassesIfStrictWhitelist = */ true,
                scanSpec, ClassType.ALL));
    }

    /**
     * Get the names of all standard (non-interface/annotation) classes found during the scan, or the empty list if
     * none.
     * 
     * @return the sorted unique names of all standard (non-interface/annotation) classes found during the scan, or
     *         the empty list if none.
     */
    static List<String> getNamesOfAllStandardClasses(final ScanSpec scanSpec, final Set<ClassInfo> allClassInfo) {
        return getClassNames(filterClassInfo(allClassInfo, /* removeExternalClassesIfStrictWhitelist = */ true,
                scanSpec, ClassType.STANDARD_CLASS));
    }

    /**
     * Test whether this class is a standard class (not an annotation or interface).
     * 
     * @return true if this class is a standard class (not an annotation or interface).
     */
    public boolean isStandardClass() {
        return !(isAnnotation || isInterface);
    }

    // -------------

    /**
     * Get the subclasses of this class.
     * 
     * @return the set of subclasses of this class, or the empty set if none.
     */
    public Set<ClassInfo> getSubclasses() {
        return filterClassInfo(getReachableClasses(RelType.SUBCLASSES),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ALL);
    }

    /**
     * Get the names of subclasses of this class.
     * 
     * @return the sorted list of names of the subclasses of this class, or the empty list if none.
     */
    public List<String> getNamesOfSubclasses() {
        return getClassNames(getSubclasses());
    }

    /**
     * Test whether this class has the named class as a subclass.
     * 
     * @return true if this class has the named class as a subclass.
     */
    public boolean hasSubclass(final String subclassName) {
        return getNamesOfSubclasses().contains(subclassName);
    }

    // -------------

    /**
     * Get the direct subclasses of this class.
     * 
     * @return the set of direct subclasses of this class, or the empty set if none.
     */
    public Set<ClassInfo> getDirectSubclasses() {
        return filterClassInfo(getDirectlyRelatedClasses(RelType.SUBCLASSES),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ALL);
    }

    /**
     * Get the names of direct subclasses of this class.
     * 
     * @return the sorted list of names of direct subclasses of this class, or the empty list if none.
     */
    public List<String> getNamesOfDirectSubclasses() {
        return getClassNames(getDirectSubclasses());
    }

    /**
     * Test whether this class has the named direct subclass.
     * 
     * @return true if this class has the named direct subclass.
     */
    public boolean hasDirectSubclass(final String directSubclassName) {
        return getNamesOfDirectSubclasses().contains(directSubclassName);
    }

    // -------------

    /**
     * Get all direct and indirect superclasses of this class (i.e. the direct superclass(es) of this class, and
     * their superclass(es), all the way up to the top of the class hierarchy).
     * 
     * (Includes the union of all mixin superclass hierarchies in the case of Scala mixins.)
     * 
     * @return the set of all superclasses of this class, or the empty set if none.
     */
    public Set<ClassInfo> getSuperclasses() {
        return filterClassInfo(getReachableClasses(RelType.SUPERCLASSES),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ALL);
    }

    /**
     * Get the names of all direct and indirect superclasses of this class (i.e. the direct superclass(es) of this
     * class, and their superclass(es), all the way up to the top of the class hierarchy).
     * 
     * (Includes the union of all mixin superclass hierarchies in the case of Scala mixins.)
     * 
     * @return the sorted list of names of all superclasses of this class, or the empty list if none.
     */
    public List<String> getNamesOfSuperclasses() {
        return getClassNames(getSuperclasses());
    }

    /**
     * Test whether this class extends the named superclass, directly or indirectly.
     * 
     * @return true if this class has the named direct or indirect superclass.
     */
    public boolean hasSuperclass(final String superclassName) {
        return getNamesOfSuperclasses().contains(superclassName);
    }

    /**
     * Returns true if this is an inner class (call isAnonymousInnerClass() to test if this is an anonymous inner
     * class). If true, the containing class can be determined by calling getOuterClasses() or getOuterClassNames().
     */
    public boolean isInnerClass() {
        return !getOuterClasses().isEmpty();
    }

    /**
     * Returns the containing outer classes, for inner classes. Note that all containing outer classes are returned,
     * not just the innermost containing outer class. Returns the empty set if this is not an inner class.
     */
    public Set<ClassInfo> getOuterClasses() {
        return filterClassInfo(getReachableClasses(RelType.CONTAINED_WITHIN_OUTER_CLASS),
                /* removeExternalClassesIfStrictWhitelist = */ false, scanSpec, ClassType.ALL);
    }

    /**
     * Returns the names of the containing outer classes, for inner classes. Note that all containing outer classes
     * are returned, not just the innermost containing outer class. Returns the empty list if this is not an inner
     * class.
     */
    public List<String> getOuterClassName() {
        return getClassNames(getOuterClasses());
    }

    /**
     * Returns true if this class contains inner classes. If true, the inner classes can be determined by calling
     * getInnerClasses() or getInnerClassNames().
     */
    public boolean isOuterClass() {
        return !getInnerClasses().isEmpty();
    }

    /** Returns the inner classes contained within this class. Returns the empty set if none. */
    public Set<ClassInfo> getInnerClasses() {
        return filterClassInfo(getReachableClasses(RelType.CONTAINS_INNER_CLASS),
                /* removeExternalClassesIfStrictWhitelist = */ false, scanSpec, ClassType.ALL);
    }

    /** Returns the names of inner classes contained within this class. Returns the empty list if none. */
    public List<String> getInnerClassNames() {
        return getClassNames(getInnerClasses());
    }

    /**
     * Returns true if this is an anonymous inner class. If true, the name of the containing method can be obtained
     * by calling getFullyQualifiedContainingMethodName().
     */
    public boolean isAnonymousInnerClass() {
        return fullyQualifiedContainingMethodName != null;
    }

    /**
     * Get fully-qualified containing method name (i.e. fully qualified classname, followed by dot, followed by
     * method name, for the containing method that creates an anonymous inner class.
     */
    public String getFullyQualifiedContainingMethodName() {
        return fullyQualifiedContainingMethodName;
    }

    // -------------

    /**
     * Get the direct superclasses of this class.
     * 
     * Typically the returned set will contain zero or one direct superclass(es), but may contain more than one
     * direct superclass in the case of Scala mixins.
     * 
     * @return the direct superclasses of this class, or the empty set if none.
     */
    public Set<ClassInfo> getDirectSuperclasses() {
        return filterClassInfo(getDirectlyRelatedClasses(RelType.SUPERCLASSES),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ALL);
    }

    /**
     * Convenience method for getting the single direct superclass of this class. Returns null if the class does not
     * have a superclass (e.g. in the case of interfaces). Throws IllegalArgumentException if there are multiple
     * direct superclasses (e.g. in the case of Scala mixins) -- use getDirectSuperclasses() if you need to deal
     * with mixins.
     * 
     * @return the direct superclass of this class, or null if the class does not have a superclass.
     * @throws IllegalArgumentException
     *             if there are multiple direct superclasses of this class (in the case of Scala mixins).
     */
    public ClassInfo getDirectSuperclass() {
        final Set<ClassInfo> directSuperclasses = getDirectSuperclasses();
        final int numDirectSuperclasses = directSuperclasses.size();
        if (numDirectSuperclasses == 0) {
            return null;
        } else if (numDirectSuperclasses > 1) {
            throw new IllegalArgumentException("Class has multiple direct superclasses: "
                    + directSuperclasses.toString() + " -- need to call getDirectSuperclasses() instead");
        } else {
            return directSuperclasses.iterator().next();
        }
    }

    /**
     * Get the names of direct superclasses of this class.
     * 
     * Typically the returned list will contain zero or one direct superclass name(s), but may contain more than one
     * direct superclass name in the case of Scala mixins.
     * 
     * @return the direct superclasses of this class, or the empty set if none.
     */
    public List<String> getNamesOfDirectSuperclasses() {
        return getClassNames(getDirectSuperclasses());
    }

    /**
     * Convenience method for getting the name of the single direct superclass of this class. Returns null if the
     * class does not have a superclass (e.g. in the case of interfaces). Throws IllegalArgumentException if there
     * are multiple direct superclasses (e.g. in the case of Scala mixins) -- use getNamesOfDirectSuperclasses() if
     * you need to deal with mixins.
     * 
     * @return the name of the direct superclass of this class, or null if the class does not have a superclass.
     * @throws IllegalArgumentException
     *             if there are multiple direct superclasses of this class (in the case of Scala mixins).
     */
    public String getNameOfDirectSuperclass() {
        final List<String> namesOfDirectSuperclasses = getNamesOfDirectSuperclasses();
        final int numDirectSuperclasses = namesOfDirectSuperclasses.size();
        if (numDirectSuperclasses == 0) {
            return null;
        } else if (numDirectSuperclasses > 1) {
            throw new IllegalArgumentException(
                    "Class has multiple direct superclasses: " + namesOfDirectSuperclasses.toString()
                            + " -- need to call getNamesOfDirectSuperclasses() instead");
        } else {
            return namesOfDirectSuperclasses.get(0);
        }
    }

    /**
     * Test whether this class directly extends the named superclass.
     * 
     * If this class has multiple direct superclasses (in the case of Scala mixins), returns true if the named
     * superclass is one of the direct superclasses of this class.
     * 
     * @param directSuperclassName
     *            The direct superclass name to match. If null, matches classes without a direct superclass (e.g.
     *            interfaces). Note that standard classes that do not extend another class have java.lang.Object as
     *            their superclass.
     * @return true if this class has the named class as its direct superclass (or as one of its direct
     *         superclasses, in the case of Scala mixins).
     */
    public boolean hasDirectSuperclass(final String directSuperclassName) {
        final List<String> namesOfDirectSuperclasses = getNamesOfDirectSuperclasses();
        if (directSuperclassName == null && namesOfDirectSuperclasses.isEmpty()) {
            return true;
        } else if (directSuperclassName == null || namesOfDirectSuperclasses.isEmpty()) {
            return false;
        } else {
            return namesOfDirectSuperclasses.contains(directSuperclassName);
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /**
     * Get the names of interface classes found during the scan.
     *
     * @return the sorted list of names of interface classes found during the scan, or the empty list if none.
     */
    static List<String> getNamesOfAllInterfaceClasses(final ScanSpec scanSpec, final Set<ClassInfo> allClassInfo) {
        return getClassNames(filterClassInfo(allClassInfo, /* removeExternalClassesIfStrictWhitelist = */ true,
                scanSpec, ClassType.IMPLEMENTED_INTERFACE));
    }

    /**
     * Test whether this class is an "implemented interface" (meaning a standard, non-annotation interface, or an
     * annotation that has also been implemented as an interface by some class).
     * 
     * Annotations are interfaces, but you can also implement an annotation, so to we Test whether an interface
     * (even an annotation) is implemented by a class or extended by a subinterface, or (failing that) if it is not
     * an interface but not an annotation.
     * 
     * (This is named "implemented interface" rather than just "interface" to distinguish it from an annotation.)
     * 
     * @return true if this class is an "implemented interface".
     */
    public boolean isImplementedInterface() {
        return !getDirectlyRelatedClasses(RelType.CLASSES_IMPLEMENTING).isEmpty() || (isInterface && !isAnnotation);
    }

    // -------------

    /**
     * Get the subinterfaces of this interface.
     * 
     * @return the set of subinterfaces of this interface, or the empty set if none.
     */
    public Set<ClassInfo> getSubinterfaces() {
        return !isImplementedInterface() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getReachableClasses(RelType.CLASSES_IMPLEMENTING),
                        /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec,
                        ClassType.IMPLEMENTED_INTERFACE);
    }

    /**
     * Get the names of subinterfaces of this interface.
     * 
     * @return the sorted list of names of subinterfaces of this interface, or the empty list if none.
     */
    public List<String> getNamesOfSubinterfaces() {
        return getClassNames(getSubinterfaces());
    }

    /**
     * Test whether this class is has the named subinterface.
     * 
     * @return true if this class is an interface and has the named subinterface.
     */
    public boolean hasSubinterface(final String subinterfaceName) {
        return getNamesOfSubinterfaces().contains(subinterfaceName);
    }

    // -------------

    /**
     * Get the direct subinterfaces of this interface.
     * 
     * 
     * @return the set of direct subinterfaces of this interface, or the empty set if none.
     */
    public Set<ClassInfo> getDirectSubinterfaces() {
        return !isImplementedInterface() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getDirectlyRelatedClasses(RelType.CLASSES_IMPLEMENTING),
                        /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec,
                        ClassType.IMPLEMENTED_INTERFACE);
    }

    /**
     * Get the names of direct subinterfaces of this interface.
     * 
     * @return the sorted list of names of direct subinterfaces of this interface, or the empty list if none.
     */
    public List<String> getNamesOfDirectSubinterfaces() {
        return getClassNames(getDirectSubinterfaces());
    }

    /**
     * Test whether this class is and interface and has the named direct subinterface.
     * 
     * @return true if this class is and interface and has the named direct subinterface.
     */
    public boolean hasDirectSubinterface(final String directSubinterfaceName) {
        return getNamesOfDirectSubinterfaces().contains(directSubinterfaceName);
    }

    // -------------

    /**
     * Get the superinterfaces of this interface.
     * 
     * @return the set of superinterfaces of this interface, or the empty set if none.
     */
    public Set<ClassInfo> getSuperinterfaces() {
        return !isImplementedInterface() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getReachableClasses(RelType.IMPLEMENTED_INTERFACES),
                        /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec,
                        ClassType.IMPLEMENTED_INTERFACE);
    }

    /**
     * Get the names of superinterfaces of this interface.
     * 
     * @return the sorted list of names of superinterfaces of this interface, or the empty list if none.
     */
    public List<String> getNamesOfSuperinterfaces() {
        return getClassNames(getSuperinterfaces());
    }

    /**
     * Test whether this class is an interface and has the named superinterface.
     * 
     * @return true if this class is an interface and has the named superinterface.
     */
    public boolean hasSuperinterface(final String superinterfaceName) {
        return getNamesOfSuperinterfaces().contains(superinterfaceName);
    }

    // -------------

    /**
     * Get the direct superinterfaces of this interface.
     * 
     * @return the set of direct superinterfaces of this interface, or the empty set if none.
     */
    public Set<ClassInfo> getDirectSuperinterfaces() {
        return !isImplementedInterface() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getDirectlyRelatedClasses(RelType.IMPLEMENTED_INTERFACES),
                        /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec,
                        ClassType.IMPLEMENTED_INTERFACE);
    }

    /**
     * Get the names of direct superinterfaces of this interface.
     * 
     * @return the sorted list of names of direct superinterfaces of this interface, or the empty list if none.
     */
    public List<String> getNamesOfDirectSuperinterfaces() {
        return getClassNames(getDirectSuperinterfaces());
    }

    /**
     * Test whether this class is an interface and has the named direct superinterface.
     * 
     * @return true if this class is an interface and has the named direct superinterface.
     */
    public boolean hasDirectSuperinterface(final String directSuperinterfaceName) {
        return getNamesOfDirectSuperinterfaces().contains(directSuperinterfaceName);
    }

    // -------------

    /**
     * Get the interfaces implemented by this standard class, or by one of its superclasses.
     * 
     * @return the set of interfaces implemented by this standard class, or by one of its superclasses. Returns the
     *         empty set if none.
     */
    public Set<ClassInfo> getImplementedInterfaces() {
        if (!isStandardClass()) {
            return Collections.<ClassInfo> emptySet();
        } else {
            final Set<ClassInfo> superclasses = filterClassInfo(getReachableClasses(RelType.SUPERCLASSES),
                    /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.STANDARD_CLASS);
            // Subclasses of implementing classes also implement the interface
            final Set<ClassInfo> allInterfaces = new HashSet<>();
            allInterfaces.addAll(getReachableClasses(RelType.IMPLEMENTED_INTERFACES));
            for (final ClassInfo superClass : superclasses) {
                allInterfaces.addAll(superClass.getReachableClasses(RelType.IMPLEMENTED_INTERFACES));
            }
            return allInterfaces;
        }
    }

    /**
     * Get the interfaces implemented by this standard class, or by one of its superclasses.
     * 
     * @return the set of interfaces implemented by this standard class, or by one of its superclasses. Returns the
     *         empty list if none.
     */
    public List<String> getNamesOfImplementedInterfaces() {
        return getClassNames(getImplementedInterfaces());
    }

    /**
     * Test whether this standard class implements the named interface, or by one of its superclasses.
     * 
     * @return true this class is a standard class, and it (or one of its superclasses) implements the named
     *         interface.
     */
    public boolean implementsInterface(final String interfaceName) {
        return getNamesOfImplementedInterfaces().contains(interfaceName);
    }

    // -------------

    /**
     * Get the interfaces directly implemented by this standard class.
     * 
     * @return the set of interfaces directly implemented by this standard class. Returns the empty set if none.
     */
    public Set<ClassInfo> getDirectlyImplementedInterfaces() {
        return !isStandardClass() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getDirectlyRelatedClasses(RelType.IMPLEMENTED_INTERFACES),
                        /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec,
                        ClassType.IMPLEMENTED_INTERFACE);
    }

    /**
     * Get the interfaces directly implemented by this standard class, or by one of its superclasses.
     * 
     * @return the set of interfaces directly implemented by this standard class, or by one of its superclasses.
     *         Returns the empty list if none.
     */
    public List<String> getNamesOfDirectlyImplementedInterfaces() {
        return getClassNames(getDirectlyImplementedInterfaces());
    }

    /**
     * Test whether this standard class directly implements the named interface, or by one of its superclasses.
     * 
     * @return true this class is a standard class, and directly implements the named interface.
     */
    public boolean directlyImplementsInterface(final String interfaceName) {
        return getNamesOfDirectlyImplementedInterfaces().contains(interfaceName);
    }

    // -------------

    /**
     * Get the classes that implement this interface, and their subclasses.
     * 
     * @return the set of classes implementing this interface, or the empty set if none.
     */
    public Set<ClassInfo> getClassesImplementing() {
        if (!isImplementedInterface()) {
            return Collections.<ClassInfo> emptySet();
        } else {
            final Set<ClassInfo> implementingClasses = filterClassInfo(
                    getReachableClasses(RelType.CLASSES_IMPLEMENTING),
                    /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.STANDARD_CLASS);
            // Subclasses of implementing classes also implement the interface
            final Set<ClassInfo> allImplementingClasses = new HashSet<>();
            for (final ClassInfo implementingClass : implementingClasses) {
                allImplementingClasses.add(implementingClass);
                allImplementingClasses.addAll(implementingClass.getReachableClasses(RelType.SUBCLASSES));
            }
            return allImplementingClasses;
        }
    }

    /**
     * Get the names of classes that implement this interface, and the names of their subclasses.
     * 
     * @return the sorted list of names of classes implementing this interface, or the empty list if none.
     */
    public List<String> getNamesOfClassesImplementing() {
        return getClassNames(getClassesImplementing());
    }

    /**
     * Test whether this class is implemented by the named class, or by one of its superclasses.
     * 
     * @return true if this class is implemented by the named class, or by one of its superclasses.
     */
    public boolean isImplementedByClass(final String className) {
        return getNamesOfClassesImplementing().contains(className);
    }

    // -------------

    /**
     * Get the classes that directly implement this interface, and their subclasses.
     * 
     * @return the set of classes directly implementing this interface, or the empty set if none.
     */
    public Set<ClassInfo> getClassesDirectlyImplementing() {
        return !isImplementedInterface() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getDirectlyRelatedClasses(RelType.CLASSES_IMPLEMENTING),
                        /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.STANDARD_CLASS);
    }

    /**
     * Get the names of classes that directly implement this interface, and the names of their subclasses.
     * 
     * @return the sorted list of names of classes directly implementing this interface, or the empty list if none.
     */
    public List<String> getNamesOfClassesDirectlyImplementing() {
        return getClassNames(getClassesDirectlyImplementing());
    }

    /**
     * Test whether this class is directly implemented by the named class, or by one of its superclasses.
     * 
     * @return true if this class is directly implemented by the named class, or by one of its superclasses.
     */
    public boolean isDirectlyImplementedByClass(final String className) {
        return getNamesOfClassesDirectlyImplementing().contains(className);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /**
     * Get the names of all annotation classes found during the scan.
     *
     * @return the sorted list of names of annotation classes found during the scan, or the empty list if none.
     */
    static List<String> getNamesOfAllAnnotationClasses(final ScanSpec scanSpec, final Set<ClassInfo> allClassInfo) {
        return getClassNames(filterClassInfo(allClassInfo, /* removeExternalClassesIfStrictWhitelist = */ true,
                scanSpec, ClassType.ANNOTATION));
    }

    /**
     * Test whether this classInfo is an annotation.
     * 
     * @return true if this classInfo is an annotation.
     */
    public boolean isAnnotation() {
        return isAnnotation;
    }

    // -------------

    /**
     * Get the standard classes and non-annotation interfaces that are annotated by this annotation.
     * 
     * @param direct
     *            if true, return only directly-annotated classes.
     * @return the set of standard classes and non-annotation interfaces that are annotated by the annotation
     *         corresponding to this ClassInfo class, or the empty set if none.
     */
    private Set<ClassInfo> getClassesWithAnnotation(final boolean direct) {
        if (!isAnnotation()) {
            return Collections.<ClassInfo> emptySet();
        }
        final Set<ClassInfo> classesWithAnnotation = filterClassInfo(
                direct ? getDirectlyRelatedClasses(RelType.CLASSES_WITH_CLASS_ANNOTATION)
                        : getReachableClasses(RelType.CLASSES_WITH_CLASS_ANNOTATION),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, //
                ClassType.STANDARD_CLASS, ClassType.IMPLEMENTED_INTERFACE);
        boolean isInherited = false;
        for (final ClassInfo metaAnnotation : getDirectlyRelatedClasses(RelType.CLASS_ANNOTATIONS)) {
            if (metaAnnotation.className.equals("java.lang.annotation.Inherited")) {
                isInherited = true;
                break;
            }
        }
        if (isInherited) {
            final Set<ClassInfo> classesWithAnnotationAndTheirSubclasses = new HashSet<>(classesWithAnnotation);
            for (final ClassInfo classWithAnnotation : classesWithAnnotation) {
                classesWithAnnotationAndTheirSubclasses.addAll(classWithAnnotation.getSubclasses());
            }
            return classesWithAnnotationAndTheirSubclasses;
        } else {
            return classesWithAnnotation;
        }
    }

    /**
     * Get the standard classes and non-annotation interfaces that are annotated by this annotation.
     * 
     * @return the set of standard classes and non-annotation interfaces that are annotated by the annotation
     *         corresponding to this ClassInfo class, or the empty set if none.
     */
    public Set<ClassInfo> getClassesWithAnnotation() {
        return getClassesWithAnnotation(/* direct = */ false);
    }

    /**
     * Get the names of standard classes and non-annotation interfaces that are annotated by this annotation. .
     * 
     * @return the sorted list of names of ClassInfo objects for standard classes and non-annotation interfaces that
     *         are annotated by the annotation corresponding to this ClassInfo class, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithAnnotation() {
        return getClassNames(getClassesWithAnnotation());
    }

    /** Test whether this class annotates the named class. */
    public boolean annotatesClass(final String annotatedClassName) {
        return getNamesOfClassesWithAnnotation().contains(annotatedClassName);
    }

    // -------------

    /**
     * Get the standard classes or non-annotation interfaces that are directly annotated with this annotation.
     * 
     * @return the set of standard classes or non-annotation interfaces that are directly annotated with this
     *         annotation, or the empty set if none.
     */
    public Set<ClassInfo> getClassesWithDirectAnnotation() {
        return getClassesWithAnnotation(/* direct = */ true);
    }

    /**
     * Get the names of standard classes or non-annotation interfaces that are directly annotated with this
     * annotation.
     * 
     * @return the sorted list of names of standard classes or non-annotation interfaces that are directly annotated
     *         with this annotation, or the empty list if none.
     */
    public List<String> getNamesOfClassesWithDirectAnnotation() {
        return getClassNames(getClassesWithDirectAnnotation());
    }

    /**
     * Test whether this class annotates the named class.
     * 
     * @return true if this class annotates the named class.
     */
    public boolean directlyAnnotatesClass(final String directlyAnnotatedClassName) {
        return getNamesOfClassesWithDirectAnnotation().contains(directlyAnnotatedClassName);
    }

    // -------------

    /**
     * Get the annotations and meta-annotations on this class. This is equivalent to the reflection call
     * Class#getAnnotations(), except that it does not require calling the classloader, and it returns
     * meta-annotations as well as annotations.
     * 
     * @return the set of annotations and meta-annotations on this class or interface, or meta-annotations if this
     *         is an annotation. Returns the empty set if none.
     */
    public Set<ClassInfo> getAnnotations() {
        return filterClassInfo(getReachableClasses(RelType.CLASS_ANNOTATIONS),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ALL);
    }

    /**
     * Get the names of annotations and meta-annotations on this class. This is equivalent to the reflection call
     * Class#getAnnotations(), except that it does not require calling the classloader, and it returns
     * meta-annotations as well as annotations.
     * 
     * @return the sorted list of names of annotations and meta-annotations on this class or interface, or
     *         meta-annotations if this is an annotation. Returns the empty list if none.
     */
    public List<String> getNamesOfAnnotations() {
        return getClassNames(getAnnotations());
    }

    /**
     * Test whether this class, interface or annotation has the named class annotation or meta-annotation.
     * 
     * @return true if this class, interface or annotation has the named class annotation or meta-annotation.
     */
    public boolean hasAnnotation(final String annotationName) {
        return getNamesOfAnnotations().contains(annotationName);
    }

    /**
     * Get a list of annotations on this method, along with any annotation parameter values, wrapped in
     * AnnotationInfo objects, or the empty list if none.
     */
    public List<AnnotationInfo> getAnnotationInfo() {
        return annotationInfo == null ? Collections.<AnnotationInfo> emptyList() : annotationInfo;
    }

    /**
     * Get a list of the default parameter values, if this is an annotation, and it has default parameter values.
     * Otherwise returns the empty list.
     */
    public List<AnnotationParamValue> getAnnotationDefaultParamValues() {
        return annotationDefaultParamValues == null ? Collections.<AnnotationParamValue> emptyList()
                : annotationDefaultParamValues;
    }

    // -------------

    /**
     * Get the direct annotations and meta-annotations on this class. This is equivalent to the reflection call
     * Class#getAnnotations(), except that it does not require calling the classloader, and it returns
     * meta-annotations as well as annotations.
     * 
     * @return the set of direct annotations and meta-annotations on this class, or the empty set if none.
     */
    public Set<ClassInfo> getDirectAnnotations() {
        return filterClassInfo(getDirectlyRelatedClasses(RelType.CLASS_ANNOTATIONS),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ALL);
    }

    /**
     * Get the names of direct annotations and meta-annotations on this class. This is equivalent to the reflection
     * call Class#getAnnotations(), except that it does not require calling the classloader, and it returns
     * meta-annotations as well as annotations.
     * 
     * @return the sorted list of names of direct annotations and meta-annotations on this class, or the empty list
     *         if none.
     */
    public List<String> getNamesOfDirectAnnotations() {
        return getClassNames(getDirectAnnotations());
    }

    /**
     * Test whether this class has the named direct annotation or meta-annotation. (This is equivalent to the
     * reflection call Class#hasAnnotation(), except that it does not require calling the classloader, and it works
     * for meta-annotations as well as Annotatinons.)
     * 
     * @return true if this class has the named direct annotation or meta-annotation.
     */
    public boolean hasDirectAnnotation(final String directAnnotationName) {
        return getNamesOfDirectAnnotations().contains(directAnnotationName);
    }

    // -------------

    /**
     * Get the annotations and meta-annotations on this annotation class.
     * 
     * @return the set of annotations and meta-annotations, if this is an annotation class, or the empty set if none
     *         (or if this is not an annotation class).
     */
    public Set<ClassInfo> getMetaAnnotations() {
        return !isAnnotation() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getReachableClasses(RelType.CLASS_ANNOTATIONS),
                        /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ALL);
    }

    /**
     * Get the names of annotations and meta-annotations on this annotation class.
     * 
     * @return the set of annotations and meta-annotations, if this is an annotation class, or the empty list if
     *         none (or if this is not an annotation class).
     */
    public List<String> getNamesOfMetaAnnotations() {
        return getClassNames(getMetaAnnotations());
    }

    /**
     * Test whether this is an annotation class and it has the named meta-annotation.
     * 
     * @return true if this is an annotation class and it has the named meta-annotation.
     */
    public boolean hasMetaAnnotation(final String metaAnnotationName) {
        return getNamesOfMetaAnnotations().contains(metaAnnotationName);
    }

    // -------------

    /**
     * Get the annotations that have this meta-annotation.
     * 
     * @return the set of annotations that have this meta-annotation, or the empty set if none.
     */
    public Set<ClassInfo> getAnnotationsWithMetaAnnotation() {
        return !isAnnotation() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getReachableClasses(RelType.CLASSES_WITH_CLASS_ANNOTATION),
                        /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ANNOTATION);
    }

    /**
     * Get the names of annotations that have this meta-annotation.
     * 
     * @return the sorted list of names of annotations that have this meta-annotation, or the empty list if none.
     */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation() {
        return getClassNames(getAnnotationsWithMetaAnnotation());
    }

    /**
     * Test whether this annotation has the named meta-annotation.
     * 
     * @return true if this annotation has the named meta-annotation.
     */
    public boolean metaAnnotatesAnnotation(final String annotationName) {
        return getNamesOfAnnotationsWithMetaAnnotation().contains(annotationName);
    }

    // -------------

    /**
     * Get the annotations that have this direct meta-annotation.
     * 
     * @return the set of annotations that have this direct meta-annotation, or the empty set if none.
     */
    public Set<ClassInfo> getAnnotationsWithDirectMetaAnnotation() {
        return !isAnnotation() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getDirectlyRelatedClasses(RelType.CLASSES_WITH_CLASS_ANNOTATION),
                        /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ANNOTATION);
    }

    /**
     * Get the names of annotations that have this direct meta-annotation.
     * 
     * @return the sorted list of names of annotations that have this direct meta-annotation, or the empty list if
     *         none.
     */
    public List<String> getNamesOfAnnotationsWithDirectMetaAnnotation() {
        return getClassNames(getAnnotationsWithDirectMetaAnnotation());
    }

    /**
     * Test whether this annotation is directly meta-annotated with the named annotation.
     * 
     * @return true if this annotation is directly meta-annotated with the named annotation.
     */
    public boolean hasDirectMetaAnnotation(final String directMetaAnnotationName) {
        return getNamesOfAnnotationsWithDirectMetaAnnotation().contains(directMetaAnnotationName);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Methods

    /**
     * Returns information on visible methods of the class that are not constructors. There may be more than one
     * method of a given name with different type signatures, due to overloading.
     * 
     * <p>
     * Requires that FastClasspathScanner#enableMethodInfo() be called before scanning, otherwise throws
     * IllegalArgumentException.
     * 
     * <p>
     * By default only returns information for public methods, unless FastClasspathScanner#ignoreMethodVisibility()
     * was called before the scan. If method visibility is ignored, the result may include a reference to a private
     * static class initializer block, with a method name of {@code "<clinit>"}.
     * 
     * @return the list of MethodInfo objects for visible methods of this class, or the empty list if no methods
     *         were found or visible.
     * @throws IllegalArgumentException
     *             if FastClasspathScanner#enableMethodInfo() was not called prior to initiating the scan.
     */
    public List<MethodInfo> getMethodInfo() {
        if (!scanSpec.enableMethodInfo) {
            throw new IllegalArgumentException("Cannot get method info without calling "
                    + "FastClasspathScanner#enableMethodInfo() before starting the scan");
        }
        if (methodInfo == null) {
            return Collections.<MethodInfo> emptyList();
        } else {
            final List<MethodInfo> nonConstructorMethods = new ArrayList<>();
            for (final MethodInfo mi : methodInfo) {
                final String methodName = mi.getMethodName();
                if (!methodName.equals("<init>") && !methodName.equals("<clinit>")) {
                    nonConstructorMethods.add(mi);
                }
            }
            return nonConstructorMethods;
        }
    }

    /**
     * Returns information on visible constructors of the class. Constructors have the method name of
     * {@code "<init>"}. There may be more than one constructor of a given name with different type signatures, due
     * to overloading.
     * 
     * <p>
     * Requires that FastClasspathScanner#enableMethodInfo() be called before scanning, otherwise throws
     * IllegalArgumentException.
     * 
     * <p>
     * By default only returns information for public constructors, unless
     * FastClasspathScanner#ignoreMethodVisibility() was called before the scan.
     * 
     * @return the list of MethodInfo objects for visible constructors of this class, or the empty list if no
     *         constructors were found or visible.
     * @throws IllegalArgumentException
     *             if FastClasspathScanner#enableMethodInfo() was not called prior to initiating the scan.
     */
    public List<MethodInfo> getConstructorInfo() {
        if (!scanSpec.enableMethodInfo) {
            throw new IllegalArgumentException("Cannot get method info without calling "
                    + "FastClasspathScanner#enableMethodInfo() before starting the scan");
        }
        if (methodInfo == null) {
            return Collections.<MethodInfo> emptyList();
        } else {
            final List<MethodInfo> nonConstructorMethods = new ArrayList<>();
            for (final MethodInfo mi : methodInfo) {
                final String methodName = mi.getMethodName();
                if (methodName.equals("<init>")) {
                    nonConstructorMethods.add(mi);
                }
            }
            return nonConstructorMethods;
        }
    }

    /**
     * Returns information on visible methods and constructors of the class. There may be more than one method or
     * constructor or method of a given name with different type signatures, due to overloading. Constructors have
     * the method name of {@code "<init>"}.
     * 
     * <p>
     * Requires that FastClasspathScanner#enableMethodInfo() be called before scanning, otherwise throws
     * IllegalArgumentException.
     * 
     * <p>
     * By default only returns information for public methods and constructors, unless
     * FastClasspathScanner#ignoreMethodVisibility() was called before the scan. If method visibility is ignored,
     * the result may include a reference to a private static class initializer block, with a method name of
     * {@code "<clinit>"}.
     * 
     * @return the list of MethodInfo objects for visible methods and constructors of this class, or the empty list
     *         if no methods or constructors were found or visible.
     * @throws IllegalArgumentException
     *             if FastClasspathScanner#enableMethodInfo() was not called prior to initiating the scan.
     */
    public List<MethodInfo> getMethodAndConstructorInfo() {
        if (!scanSpec.enableMethodInfo) {
            throw new IllegalArgumentException("Cannot get method info without calling "
                    + "FastClasspathScanner#enableMethodInfo() before starting the scan");
        }
        return methodInfo == null ? Collections.<MethodInfo> emptyList() : methodInfo;
    }

    /**
     * Returns information on the method(s) of the class with the given method name. Constructors have the method
     * name of {@code "<init>"}.
     * 
     * <p>
     * Requires that FastClasspathScanner#enableMethodInfo() be called before scanning, otherwise throws
     * IllegalArgumentException.
     * 
     * <p>
     * By default only returns information for public methods, unless FastClasspathScanner#ignoreMethodVisibility()
     * was called before the scan.
     * 
     * <p>
     * May return info for multiple methods with the same name (with different type signatures).
     * 
     * @param methodName
     *            The method name to query.
     * @return a list of MethodInfo objects for the method(s) with the given name, or the empty list if the method
     *         was not found in this class (or is not visible).
     * @throws IllegalArgumentException
     *             if FastClasspathScanner#enableMethodInfo() was not called prior to initiating the scan.
     */
    public List<MethodInfo> getMethodInfo(final String methodName) {
        if (!scanSpec.enableMethodInfo) {
            throw new IllegalArgumentException("Cannot get method info without calling "
                    + "FastClasspathScanner#enableMethodInfo() before starting the scan");
        }
        if (methodInfo == null) {
            return null;
        }
        if (methodNameToMethodInfo == null) {
            // Lazily build reverse mapping cache
            methodNameToMethodInfo = new MultiMapKeyToList<>();
            for (final MethodInfo f : methodInfo) {
                methodNameToMethodInfo.put(f.getMethodName(), f);
            }
        }
        final List<MethodInfo> methodList = methodNameToMethodInfo.get(methodName);
        return methodList == null ? Collections.<MethodInfo> emptyList() : methodList;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Method annotations

    /**
     * Get the direct method direct annotations on this class.
     * 
     * @return the set of method direct annotations on this class, or the empty set if none.
     */
    public Set<ClassInfo> getMethodDirectAnnotations() {
        return filterClassInfo(getDirectlyRelatedClasses(RelType.METHOD_ANNOTATIONS),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ANNOTATION);
    }

    /**
     * Get the method annotations or meta-annotations on this class.
     * 
     * @return the set of method annotations or meta-annotations on this class, or the empty set if none.
     */
    public Set<ClassInfo> getMethodAnnotations() {
        return filterClassInfo(getReachableClasses(RelType.METHOD_ANNOTATIONS),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ANNOTATION);
    }

    /**
     * Get the names of method direct annotations on this class.
     * 
     * @return the sorted list of names of method direct annotations on this class, or the empty list if none.
     */
    public List<String> getNamesOfMethodDirectAnnotations() {
        return getClassNames(getMethodDirectAnnotations());
    }

    /**
     * Get the names of method annotations or meta-annotations on this class.
     * 
     * @return the sorted list of names of method annotations or meta-annotations on this class, or the empty list
     *         if none.
     */
    public List<String> getNamesOfMethodAnnotations() {
        return getClassNames(getMethodAnnotations());
    }

    /**
     * Test whether this class has a method with the named method direct annotation.
     * 
     * @return true if this class has a method with the named direct annotation.
     */
    public boolean hasMethodWithDirectAnnotation(final String annotationName) {
        return getNamesOfMethodDirectAnnotations().contains(annotationName);
    }

    /**
     * Test whether this class has a method with the named method annotation or meta-annotation.
     * 
     * @return true if this class has a method with the named annotation or meta-annotation.
     */
    public boolean hasMethodWithAnnotation(final String annotationName) {
        return getNamesOfMethodAnnotations().contains(annotationName);
    }

    // -------------

    /**
     * Get the classes that have a method with this direct annotation.
     * 
     * @return the set of classes that have a method with this direct annotation, or the empty set if none.
     */
    public Set<ClassInfo> getClassesWithDirectMethodAnnotation() {
        return filterClassInfo(getDirectlyRelatedClasses(RelType.CLASSES_WITH_METHOD_ANNOTATION),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ALL);
    }

    /**
     * Get the classes that have a method with this annotation or meta-annotation.
     * 
     * @return the set of classes that have a method with this annotation or meta-annotation, or the empty set if
     *         none.
     */
    public Set<ClassInfo> getClassesWithMethodAnnotation() {
        return filterClassInfo(getReachableClasses(RelType.CLASSES_WITH_METHOD_ANNOTATION),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ALL);
    }

    /**
     * Get the names of classes that have a method with this direct annotation.
     * 
     * @return the sorted list of names of classes that have a method with this direct annotation, or the empty list
     *         if none.
     */
    public List<String> getNamesOfClassesWithDirectMethodAnnotation() {
        return getClassNames(getClassesWithDirectMethodAnnotation());
    }

    /**
     * Get the names of classes that have a method with this annotation.
     * 
     * @return the sorted list of names of classes that have a method with this annotation, or the empty list if
     *         none.
     */
    public List<String> getNamesOfClassesWithMethodAnnotation() {
        return getClassNames(getClassesWithMethodAnnotation());
    }

    /**
     * Test whether this annotation annotates or meta-annotates a method of the named class.
     * 
     * @return true if this annotation annotates a method of the named class.
     */
    public boolean annotatesMethodOfClass(final String className) {
        return getNamesOfClassesWithMethodAnnotation().contains(className);
    }

    /**
     * Return a sorted list of classes that have a method directly annotated with the named annotation.
     * 
     * @return the sorted list of names of classes that have a method with the named direct annotation, or the empty
     *         list if none.
     */
    static List<String> getNamesOfClassesWithDirectMethodAnnotation(final String annotationName,
            final Set<ClassInfo> allClassInfo) {
        // This method will not likely be used for a large number of different annotation types, so perform a linear
        // search on each invocation, rather than building an index on classpath scan (so we don't slow down more
        // common methods).
        final ArrayList<String> namesOfClassesWithNamedMethodAnnotation = new ArrayList<>();
        for (final ClassInfo classInfo : allClassInfo) {
            for (final ClassInfo annotationType : classInfo.getDirectlyRelatedClasses(RelType.METHOD_ANNOTATIONS)) {
                if (annotationType.className.equals(annotationName)) {
                    namesOfClassesWithNamedMethodAnnotation.add(classInfo.className);
                    break;
                }
            }
        }
        if (!namesOfClassesWithNamedMethodAnnotation.isEmpty()) {
            Collections.sort(namesOfClassesWithNamedMethodAnnotation);
        }
        return namesOfClassesWithNamedMethodAnnotation;
    }

    /**
     * Return a sorted list of classes that have a method with the named annotation or meta-annotation.
     * 
     * @return the sorted list of names of classes that have a method with the named annotation or meta-annotation,
     *         or the empty list if none.
     */
    static List<String> getNamesOfClassesWithMethodAnnotation(final String annotationName,
            final Set<ClassInfo> allClassInfo) {
        // This method will not likely be used for a large number of different annotation types, so perform a linear
        // search on each invocation, rather than building an index on classpath scan (so we don't slow down more
        // common methods).
        final ArrayList<String> namesOfClassesWithNamedMethodAnnotation = new ArrayList<>();
        for (final ClassInfo classInfo : allClassInfo) {
            for (final ClassInfo annotationType : classInfo.getReachableClasses(RelType.METHOD_ANNOTATIONS)) {
                if (annotationType.className.equals(annotationName)) {
                    namesOfClassesWithNamedMethodAnnotation.add(classInfo.className);
                    break;
                }
            }
        }
        if (!namesOfClassesWithNamedMethodAnnotation.isEmpty()) {
            Collections.sort(namesOfClassesWithNamedMethodAnnotation);
        }
        return namesOfClassesWithNamedMethodAnnotation;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fields

    /**
     * Get the types of this class' fields. Requires FastClasspathScanner#indexFieldTypes() to have been called
     * before scanning.
     * 
     * @return the set of field types for this class, or the empty set if none.
     */
    public Set<ClassInfo> getFieldTypes() {
        return !isStandardClass() ? Collections.<ClassInfo> emptySet()
                : filterClassInfo(getDirectlyRelatedClasses(RelType.FIELD_TYPES),
                        /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ALL);
    }

    /**
     * Get the names of the types of this class' fields. Requires FastClasspathScanner#indexFieldTypes() to have
     * been called before scanning.
     * 
     * @return the sorted list of names of field types, or the empty list if none.
     */
    public List<String> getNamesOfFieldTypes() {
        return getClassNames(getFieldTypes());
    }

    /**
     * Get the list of classes that have a field of the named type.
     * 
     * @return the sorted list of names of classes that have a field of the named type.
     */
    static List<String> getNamesOfClassesWithFieldOfType(final String fieldTypeName,
            final Set<ClassInfo> allClassInfo) {
        // This method will not likely be used for a large number of different field types, so perform a linear
        // search on each invocation, rather than building an index on classpath scan (so we don't slow down more
        // common methods).
        final ArrayList<String> namesOfClassesWithFieldOfType = new ArrayList<>();
        for (final ClassInfo classInfo : allClassInfo) {
            for (final ClassInfo fieldType : classInfo.getDirectlyRelatedClasses(RelType.FIELD_TYPES)) {
                if (fieldType.className.equals(fieldTypeName)) {
                    namesOfClassesWithFieldOfType.add(classInfo.className);
                    break;
                }
            }
        }
        if (!namesOfClassesWithFieldOfType.isEmpty()) {
            Collections.sort(namesOfClassesWithFieldOfType);
        }
        return namesOfClassesWithFieldOfType;
    }

    /**
     * Get the constant initializer value for the named static final field, if present.
     * 
     * @return the constant initializer value for the named static final field, if present.
     */
    Object getStaticFinalFieldConstantInitializerValue(final String fieldName) {
        return staticFinalFieldNameToConstantInitializerValue == null ? null
                : staticFinalFieldNameToConstantInitializerValue.get(fieldName);
    }

    /**
     * Returns information on all visible fields of the class.
     * 
     * <p>
     * Requires that FastClasspathScanner#enableFieldInfo() be called before scanning, otherwise throws
     * IllegalArgumentException.
     * 
     * <p>
     * By default only returns information for public methods, unless FastClasspathScanner#ignoreFieldVisibility()
     * was called before the scan.
     * 
     * @return the list of FieldInfo objects for visible fields of this class, or the empty list if no fields were
     *         found or visible.
     * @throws IllegalArgumentException
     *             if FastClasspathScanner#enableFieldInfo() was not called prior to initiating the scan.
     */
    public List<FieldInfo> getFieldInfo() {
        if (!scanSpec.enableFieldInfo) {
            throw new IllegalArgumentException("Cannot get field info without calling "
                    + "FastClasspathScanner#enableFieldInfo() before starting the scan");
        }
        return fieldInfo == null ? Collections.<FieldInfo> emptyList() : fieldInfo;
    }

    /**
     * Returns information on a given visible field of the class.
     * 
     * <p>
     * Requires that FastClasspathScanner#enableFieldInfo() be called before scanning, otherwise throws
     * IllegalArgumentException.
     * 
     * <p>
     * By default only returns information for public fields, unless FastClasspathScanner#ignoreFieldVisibility()
     * was called before the scan.
     * 
     * @param fieldName
     *            The field name to query.
     * @return the FieldInfo object for the named field, or null if the field was not found in this class (or is not
     *         visible).
     * @throws IllegalArgumentException
     *             if FastClasspathScanner#enableFieldInfo() was not called prior to initiating the scan.
     */
    public FieldInfo getFieldInfo(final String fieldName) {
        if (!scanSpec.enableFieldInfo) {
            throw new IllegalArgumentException("Cannot get field info without calling "
                    + "FastClasspathScanner#enableFieldInfo() before starting the scan");
        }
        if (fieldInfo == null) {
            return null;
        }
        if (fieldNameToFieldInfo == null) {
            // Lazily build reverse mapping cache
            fieldNameToFieldInfo = new HashMap<>();
            for (final FieldInfo f : fieldInfo) {
                fieldNameToFieldInfo.put(f.getFieldName(), f);
            }
        }
        return fieldNameToFieldInfo.get(fieldName);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Field annotations

    /**
     * Get the field annotations on this class.
     * 
     * @return the set of field annotations on this class, or the empty set if none.
     */
    public Set<ClassInfo> getFieldAnnotations() {
        return filterClassInfo(getDirectlyRelatedClasses(RelType.FIELD_ANNOTATIONS),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ANNOTATION);
    }

    /**
     * Get the names of field annotations on this class.
     * 
     * @return the sorted list of names of field annotations on this class, or the empty list if none.
     */
    public List<String> getNamesOfFieldAnnotations() {
        return getClassNames(getFieldAnnotations());
    }

    /**
     * Test whether this class has a field with the named field annotation.
     * 
     * @return true if this class has a field with the named annotation.
     */
    public boolean hasFieldWithAnnotation(final String annotationName) {
        return getNamesOfFieldAnnotations().contains(annotationName);
    }

    // -------------

    /**
     * Get the classes that have a field with this annotation or meta-annotation.
     * 
     * @return the set of classes that have a field with this annotation or meta-annotation, or the empty set if
     *         none.
     */
    public Set<ClassInfo> getClassesWithFieldAnnotation() {
        return filterClassInfo(getReachableClasses(RelType.CLASSES_WITH_FIELD_ANNOTATION),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ALL);
    }

    /**
     * Get the names of classes that have a field with this annotation or meta-annotation.
     * 
     * @return the sorted list of names of classes that have a field with this annotation or meta-annotation, or the
     *         empty list if none.
     */
    public List<String> getNamesOfClassesWithFieldAnnotation() {
        return getClassNames(getClassesWithFieldAnnotation());
    }

    /**
     * Get the classes that have a field with this direct annotation.
     * 
     * @return the set of classes that have a field with this direct annotation, or the empty set if none.
     */
    public Set<ClassInfo> getClassesWithDirectFieldAnnotation() {
        return filterClassInfo(getDirectlyRelatedClasses(RelType.CLASSES_WITH_FIELD_ANNOTATION),
                /* removeExternalClassesIfStrictWhitelist = */ true, scanSpec, ClassType.ALL);
    }

    /**
     * Get the names of classes that have a field with this direct annotation.
     * 
     * @return the sorted list of names of classes that have a field with thisdirect annotation, or the empty list
     *         if none.
     */
    public List<String> getNamesOfClassesWithDirectFieldAnnotation() {
        return getClassNames(getClassesWithDirectFieldAnnotation());
    }

    /**
     * Test whether this annotation annotates a field of the named class.
     * 
     * @return true if this annotation annotates a field of the named class.
     */
    public boolean annotatesFieldOfClass(final String className) {
        return getNamesOfClassesWithFieldAnnotation().contains(className);
    }

    /**
     * Return a sorted list of classes that have a field with the named annotation or meta-annotation.
     * 
     * @return the sorted list of names of classes that have a field with the named annotation or meta-annotation,
     *         or the empty list if none.
     */
    static List<String> getNamesOfClassesWithFieldAnnotation(final String annotationName,
            final Set<ClassInfo> allClassInfo) {
        // This method will not likely be used for a large number of different annotation types, so perform a linear
        // search on each invocation, rather than building an index on classpath scan (so we don't slow down more
        // common methods).
        final ArrayList<String> namesOfClassesWithNamedFieldAnnotation = new ArrayList<>();
        for (final ClassInfo classInfo : allClassInfo) {
            for (final ClassInfo annotationType : classInfo.getReachableClasses(RelType.FIELD_ANNOTATIONS)) {
                if (annotationType.className.equals(annotationName)) {
                    namesOfClassesWithNamedFieldAnnotation.add(classInfo.className);
                    break;
                }
            }
        }
        if (!namesOfClassesWithNamedFieldAnnotation.isEmpty()) {
            Collections.sort(namesOfClassesWithNamedFieldAnnotation);
        }
        return namesOfClassesWithNamedFieldAnnotation;
    }

    /**
     * Return a sorted list of classes that have a field with the named annotation or direct annotation.
     * 
     * @return the sorted list of names of classes that have a field with the named direct annotation, or the empty
     *         list if none.
     */
    static List<String> getNamesOfClassesWithDirectFieldAnnotation(final String annotationName,
            final Set<ClassInfo> allClassInfo) {
        // This method will not likely be used for a large number of different annotation types, so perform a linear
        // search on each invocation, rather than building an index on classpath scan (so we don't slow down more
        // common methods).
        final ArrayList<String> namesOfClassesWithNamedFieldAnnotation = new ArrayList<>();
        for (final ClassInfo classInfo : allClassInfo) {
            for (final ClassInfo annotationType : classInfo.getDirectlyRelatedClasses(RelType.FIELD_ANNOTATIONS)) {
                if (annotationType.className.equals(annotationName)) {
                    namesOfClassesWithNamedFieldAnnotation.add(classInfo.className);
                    break;
                }
            }
        }
        if (!namesOfClassesWithNamedFieldAnnotation.isEmpty()) {
            Collections.sort(namesOfClassesWithNamedFieldAnnotation);
        }
        return namesOfClassesWithNamedFieldAnnotation;
    }
}
