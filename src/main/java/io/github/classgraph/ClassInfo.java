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
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import io.github.classgraph.Classfile.ClassContainment;
import nonapi.io.github.classgraph.json.Id;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.TypeUtils;
import nonapi.io.github.classgraph.types.TypeUtils.ModifierType;

/** Holds metadata about a class encountered during a scan. */
public class ClassInfo extends ScanResultObject implements Comparable<ClassInfo>, HasName {
    /** The name of the class. */
    @Id
    protected String name;

    /** Class modifier flags, e.g. Modifier.PUBLIC */
    private int modifiers;

    /** True if the class is a record. */
    private boolean isRecord;

    /**
     * This annotation has the {@link Inherited} meta-annotation, which means that any class that this annotation is
     * applied to also implicitly causes the annotation to annotate all subclasses too.
     */
    boolean isInherited;

    /** The class type signature string. */
    protected String typeSignatureStr;

    /** The class type signature, parsed. */
    private transient ClassTypeSignature typeSignature;

    /** The fully-qualified defining method name, for anonymous inner classes. */
    private String fullyQualifiedDefiningMethodName;

    /**
     * If true, this class is only being referenced by another class' classfile as a superclass / implemented
     * interface / annotation, but this class is not itself a whitelisted (non-blacklisted) class, or in a
     * whitelisted (non-blacklisted) package.
     * 
     * If false, this classfile was matched during scanning (i.e. its classfile contents read), i.e. this class is a
     * whitelisted (and non-blacklisted) class in a whitelisted (and non-blacklisted) package.
     */
    protected boolean isExternalClass = true;

    /**
     * Set to true when the class is actually scanned (as opposed to just referenced as a superclass, interface or
     * annotation of a scanned class).
     */
    protected boolean isScannedClass;

    /** The classpath element that this class was found within. */
    transient ClasspathElement classpathElement;

    /** The {@link Resource} for the classfile of this class. */
    protected transient Resource classfileResource;

    /** The classloader this class was obtained from. */
    transient ClassLoader classLoader;

    /** Info on the class module. */
    ModuleInfo moduleInfo;

    /** Info on the package containing the class. */
    PackageInfo packageInfo;

    /** Info on class annotations, including optional annotation param values. */
    AnnotationInfoList annotationInfo;

    /** Info on fields. */
    FieldInfoList fieldInfo;

    /** Info on fields. */
    MethodInfoList methodInfo;

    /** For annotations, the default values of parameters. */
    AnnotationParameterValueList annotationDefaultParamValues;

    /**
     * Names of classes referenced by this class in class refs and type signatures in the constant pool of the
     * classfile.
     */
    private Set<String> referencedClassNames;

    /** A list of ClassInfo objects for classes referenced by this class. */
    private ClassInfoList referencedClasses;

    /**
     * Set to true once any Object[] arrays of boxed types in annotationDefaultParamValues have been lazily
     * converted to primitive arrays.
     */
    transient boolean annotationDefaultParamValuesHasBeenConvertedToPrimitive;

    /** The set of classes related to this one. */
    private Map<RelType, Set<ClassInfo>> relatedClasses;

    /**
     * The override order for a class' fields or methods (base class, followed by interfaces, followed by
     * superclasses).
     */
    private transient List<ClassInfo> overrideOrder;

    // -------------------------------------------------------------------------------------------------------------

    /** The modifier bit for annotations. */
    private static final int ANNOTATION_CLASS_MODIFIER = 0x2000;

    /** The constant empty return value used when no classes are reachable. */
    private static final ReachableAndDirectlyRelatedClasses NO_REACHABLE_CLASSES = //
            new ReachableAndDirectlyRelatedClasses(Collections.<ClassInfo> emptySet(),
                    Collections.<ClassInfo> emptySet());

    // -------------------------------------------------------------------------------------------------------------

    /** Default constructor for deserialization. */
    ClassInfo() {
        super();
    }

    /**
     * Constructor.
     *
     * @param name
     *            the name
     * @param classModifiers
     *            the class modifiers
     * @param classfileResource
     *            the classfile resource
     */
    protected ClassInfo(final String name, final int classModifiers, final Resource classfileResource) {
        super();
        this.name = name;
        if (name.endsWith(";")) {
            // Spot check to make sure class names were parsed from descriptors
            throw new IllegalArgumentException("Bad class name");
        }
        setModifiers(classModifiers);
        this.classfileResource = classfileResource;
        this.relatedClasses = new EnumMap<>(RelType.class);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** How classes are related. */
    enum RelType {

        // Classes:

        /**
         * Superclasses of this class, if this is a regular class.
         *
         * <p>
         * (Should consist of only one entry, or null if superclass is java.lang.Object or unknown).
         */
        SUPERCLASSES,

        /** Subclasses of this class, if this is a regular class. */
        SUBCLASSES,

        /** Indicates that an inner class is contained within this one. */
        CONTAINS_INNER_CLASS,

        /** Indicates that an outer class contains this one. (Should only have zero or one entries.) */
        CONTAINED_WITHIN_OUTER_CLASS,

        // Interfaces:

        /**
         * Interfaces that this class implements, if this is a regular class, or superinterfaces, if this is an
         * interface.
         *
         * <p>
         * (May also include annotations, since annotations are interfaces, so you can implement an annotation.)
         */
        IMPLEMENTED_INTERFACES,

        /**
         * Classes that implement this interface (including sub-interfaces), if this is an interface.
         */
        CLASSES_IMPLEMENTING,

        // Class annotations:

        /**
         * Annotations on this class, if this is a regular class, or meta-annotations on this annotation, if this is
         * an annotation.
         */
        CLASS_ANNOTATIONS,

        /** Classes annotated with this annotation, if this is an annotation. */
        CLASSES_WITH_ANNOTATION,

        // Method annotations:

        /** Annotations on one or more methods of this class. */
        METHOD_ANNOTATIONS,

        /**
         * Classes that have one or more methods annotated with this annotation, if this is an annotation.
         */
        CLASSES_WITH_METHOD_ANNOTATION,

        /**
         * Classes that have one or more non-private (inherited) methods annotated with this annotation, if this is
         * an annotation.
         */
        CLASSES_WITH_NONPRIVATE_METHOD_ANNOTATION,

        /** Annotations on one or more parameters of methods of this class. */
        METHOD_PARAMETER_ANNOTATIONS,

        /**
         * Classes that have one or more methods that have one or more parameters annotated with this annotation, if
         * this is an annotation.
         */
        CLASSES_WITH_METHOD_PARAMETER_ANNOTATION,

        /**
         * Classes that have one or more non-private (inherited) methods that have one or more parameters annotated
         * with this annotation, if this is an annotation.
         */
        CLASSES_WITH_NONPRIVATE_METHOD_PARAMETER_ANNOTATION,

        // Field annotations:

        /** Annotations on one or more fields of this class. */
        FIELD_ANNOTATIONS,

        /**
         * Classes that have one or more fields annotated with this annotation, if this is an annotation.
         */
        CLASSES_WITH_FIELD_ANNOTATION,

        /**
         * Classes that have one or more non-private (inherited) fields annotated with this annotation, if this is
         * an annotation.
         */
        CLASSES_WITH_NONPRIVATE_FIELD_ANNOTATION,
    }

    /**
     * Add a class with a given relationship type. Return whether the collection changed as a result of the call.
     *
     * @param relType
     *            the {@link RelType}
     * @param classInfo
     *            the {@link ClassInfo}
     * @return true, if successful
     */
    boolean addRelatedClass(final RelType relType, final ClassInfo classInfo) {
        Set<ClassInfo> classInfoSet = relatedClasses.get(relType);
        if (classInfoSet == null) {
            relatedClasses.put(relType, classInfoSet = new LinkedHashSet<>(4));
        }
        return classInfoSet.add(classInfo);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a ClassInfo object, or create it if it doesn't exist. N.B. not threadsafe, so ClassInfo objects should
     * only ever be constructed by a single thread.
     *
     * @param className
     *            the class name
     * @param classNameToClassInfo
     *            the map from class name to class info
     * @return the {@link ClassInfo} object.
     */
    static ClassInfo getOrCreateClassInfo(final String className,
            final Map<String, ClassInfo> classNameToClassInfo) {
        // Look for array class names
        int numArrayDims = 0;
        String baseClassName = className;
        while (baseClassName.endsWith("[]")) {
            numArrayDims++;
            baseClassName = baseClassName.substring(0, baseClassName.length() - 2);
        }
        // Be resilient to the use of class descriptors rather than class names (should not be needed)
        while (baseClassName.startsWith("[")) {
            numArrayDims++;
            baseClassName = baseClassName.substring(1);
        }
        if (baseClassName.endsWith(";")) {
            baseClassName = baseClassName.substring(baseClassName.length() - 1);
        }
        baseClassName = baseClassName.replace('/', '.');

        ClassInfo classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            classNameToClassInfo.put(className, //
                    classInfo = numArrayDims == 0 //
                            ? new ClassInfo(baseClassName, /* classModifiers = */ 0, /* classfileResource = */ null)
                            : new ArrayClassInfo(new ArrayTypeSignature(baseClassName, numArrayDims)));
        }
        return classInfo;
    }

    /**
     * Set class modifiers.
     *
     * @param modifiers
     *            the class modifiers
     */
    void setModifiers(final int modifiers) {
        this.modifiers |= modifiers;
    }

    /**
     * Set isInterface status.
     *
     * @param isInterface
     *            true if this is an interface
     */
    void setIsInterface(final boolean isInterface) {
        if (isInterface) {
            this.modifiers |= Modifier.INTERFACE;
        }
    }

    /**
     * Set isAnnotation status.
     *
     * @param isAnnotation
     *            true if this is an annotation
     */
    void setIsAnnotation(final boolean isAnnotation) {
        if (isAnnotation) {
            this.modifiers |= ANNOTATION_CLASS_MODIFIER;
        }
    }

    /**
     * Set isRecord status.
     *
     * @param isRecord
     *            true if this is a record
     */
    void setIsRecord(final boolean isRecord) {
        if (isRecord) {
            this.isRecord = isRecord;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a superclass to this class.
     *
     * @param superclassName
     *            the superclass name
     * @param classNameToClassInfo
     *            the map from class name to class info
     */
    void addSuperclass(final String superclassName, final Map<String, ClassInfo> classNameToClassInfo) {
        if (superclassName != null && !superclassName.equals("java.lang.Object")) {
            final ClassInfo superclassClassInfo = getOrCreateClassInfo(superclassName, classNameToClassInfo);
            this.addRelatedClass(RelType.SUPERCLASSES, superclassClassInfo);
            superclassClassInfo.addRelatedClass(RelType.SUBCLASSES, this);
        }
    }

    /**
     * Add an implemented interface to this class.
     *
     * @param interfaceName
     *            the interface name
     * @param classNameToClassInfo
     *            the map from class name to class info
     */
    void addImplementedInterface(final String interfaceName, final Map<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo interfaceClassInfo = getOrCreateClassInfo(interfaceName, classNameToClassInfo);
        interfaceClassInfo.setIsInterface(true);
        this.addRelatedClass(RelType.IMPLEMENTED_INTERFACES, interfaceClassInfo);
        interfaceClassInfo.addRelatedClass(RelType.CLASSES_IMPLEMENTING, this);
    }

    /**
     * Add class containment info.
     *
     * @param classContainmentEntries
     *            the class containment entries
     * @param classNameToClassInfo
     *            the map from class name to class info
     */
    static void addClassContainment(final List<ClassContainment> classContainmentEntries,
            final Map<String, ClassInfo> classNameToClassInfo) {
        for (final ClassContainment classContainment : classContainmentEntries) {
            final ClassInfo innerClassInfo = ClassInfo.getOrCreateClassInfo(classContainment.innerClassName,
                    classNameToClassInfo);
            innerClassInfo.setModifiers(classContainment.innerClassModifierBits);
            final ClassInfo outerClassInfo = ClassInfo.getOrCreateClassInfo(classContainment.outerClassName,
                    classNameToClassInfo);
            innerClassInfo.addRelatedClass(RelType.CONTAINED_WITHIN_OUTER_CLASS, outerClassInfo);
            outerClassInfo.addRelatedClass(RelType.CONTAINS_INNER_CLASS, innerClassInfo);
        }
    }

    /**
     * Add containing method name, for anonymous inner classes.
     *
     * @param fullyQualifiedDefiningMethodName
     *            the fully qualified defining method name
     */
    void addFullyQualifiedDefiningMethodName(final String fullyQualifiedDefiningMethodName) {
        this.fullyQualifiedDefiningMethodName = fullyQualifiedDefiningMethodName;
    }

    /**
     * Add an annotation to this class.
     *
     * @param classAnnotationInfo
     *            the class annotation info
     * @param classNameToClassInfo
     *            the map from class name to class info
     */
    void addClassAnnotation(final AnnotationInfo classAnnotationInfo,
            final Map<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo annotationClassInfo = getOrCreateClassInfo(classAnnotationInfo.getName(),
                classNameToClassInfo);
        annotationClassInfo.setModifiers(ANNOTATION_CLASS_MODIFIER);
        if (this.annotationInfo == null) {
            this.annotationInfo = new AnnotationInfoList(2);
        }
        this.annotationInfo.add(classAnnotationInfo);

        this.addRelatedClass(RelType.CLASS_ANNOTATIONS, annotationClassInfo);
        annotationClassInfo.addRelatedClass(RelType.CLASSES_WITH_ANNOTATION, this);

        // Record use of @Inherited meta-annotation
        if (classAnnotationInfo.getName().equals(Inherited.class.getName())) {
            isInherited = true;
        }
    }

    /**
     * Add field or method annotation cross-links.
     *
     * @param annotationInfoList
     *            the annotation info list
     * @param isField
     *            the is field
     * @param modifiers
     *            the field or method modifiers
     * @param classNameToClassInfo
     *            the map from class name to class info
     */
    private void addFieldOrMethodAnnotationInfo(final AnnotationInfoList annotationInfoList, final boolean isField,
            final int modifiers, final Map<String, ClassInfo> classNameToClassInfo) {
        if (annotationInfoList != null) {
            for (final AnnotationInfo fieldAnnotationInfo : annotationInfoList) {
                final ClassInfo annotationClassInfo = getOrCreateClassInfo(fieldAnnotationInfo.getName(),
                        classNameToClassInfo);
                annotationClassInfo.setModifiers(ANNOTATION_CLASS_MODIFIER);
                // Mark this class as having a field or method with this annotation
                this.addRelatedClass(isField ? RelType.FIELD_ANNOTATIONS : RelType.METHOD_ANNOTATIONS,
                        annotationClassInfo);
                annotationClassInfo.addRelatedClass(
                        isField ? RelType.CLASSES_WITH_FIELD_ANNOTATION : RelType.CLASSES_WITH_METHOD_ANNOTATION,
                        this);
                // For non-private methods/fields, also add to nonprivate (inherited) mapping
                if (!Modifier.isPrivate(modifiers)) {
                    annotationClassInfo.addRelatedClass(isField ? RelType.CLASSES_WITH_NONPRIVATE_FIELD_ANNOTATION
                            : RelType.CLASSES_WITH_NONPRIVATE_METHOD_ANNOTATION, this);
                }
            }
        }
    }

    /**
     * Add field info.
     *
     * @param fieldInfoList
     *            the field info list
     * @param classNameToClassInfo
     *            the map from class name to class info
     */
    void addFieldInfo(final FieldInfoList fieldInfoList, final Map<String, ClassInfo> classNameToClassInfo) {
        for (final FieldInfo fi : fieldInfoList) {
            // Index field annotations
            addFieldOrMethodAnnotationInfo(fi.annotationInfo, /* isField = */ true, fi.getModifiers(),
                    classNameToClassInfo);
        }
        if (this.fieldInfo == null) {
            this.fieldInfo = fieldInfoList;
        } else {
            this.fieldInfo.addAll(fieldInfoList);
        }
    }

    /**
     * Add method info.
     *
     * @param methodInfoList
     *            the method info list
     * @param classNameToClassInfo
     *            the map from class name to class info
     */
    void addMethodInfo(final MethodInfoList methodInfoList, final Map<String, ClassInfo> classNameToClassInfo) {
        for (final MethodInfo mi : methodInfoList) {
            // Index method annotations
            addFieldOrMethodAnnotationInfo(mi.annotationInfo, /* isField = */ false, mi.getModifiers(),
                    classNameToClassInfo);

            // Index method parameter annotations
            if (mi.parameterAnnotationInfo != null) {
                for (int i = 0; i < mi.parameterAnnotationInfo.length; i++) {
                    final AnnotationInfo[] paramAnnotationInfoArr = mi.parameterAnnotationInfo[i];
                    if (paramAnnotationInfoArr != null) {
                        for (int j = 0; j < paramAnnotationInfoArr.length; j++) {
                            final AnnotationInfo methodParamAnnotationInfo = paramAnnotationInfoArr[j];
                            final ClassInfo annotationClassInfo = getOrCreateClassInfo(
                                    methodParamAnnotationInfo.getName(), classNameToClassInfo);
                            annotationClassInfo.setModifiers(ANNOTATION_CLASS_MODIFIER);
                            this.addRelatedClass(RelType.METHOD_PARAMETER_ANNOTATIONS, annotationClassInfo);
                            annotationClassInfo.addRelatedClass(RelType.CLASSES_WITH_METHOD_PARAMETER_ANNOTATION,
                                    this);
                            // For non-private methods/fields, also add to nonprivate (inherited) mapping
                            if (!Modifier.isPrivate(mi.getModifiers())) {
                                annotationClassInfo.addRelatedClass(
                                        RelType.CLASSES_WITH_NONPRIVATE_METHOD_PARAMETER_ANNOTATION, this);
                            }
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

    /**
     * Set the class type signature, including any type params.
     *
     * @param typeSignatureStr
     *            the type signature str
     */
    void setTypeSignature(final String typeSignatureStr) {
        this.typeSignatureStr = typeSignatureStr;
    }

    /**
     * Add annotation default values. (Only called in the case of annotation class definitions, when the annotation
     * has default parameter values.)
     *
     * @param paramNamesAndValues
     *            the default param names and values, if this is an annotation
     */
    void addAnnotationParamDefaultValues(final AnnotationParameterValueList paramNamesAndValues) {
        setIsAnnotation(true);
        if (this.annotationDefaultParamValues == null) {
            this.annotationDefaultParamValues = paramNamesAndValues;
        } else {
            this.annotationDefaultParamValues.addAll(paramNamesAndValues);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a class that has just been scanned (as opposed to just referenced by a scanned class). Not threadsafe,
     * should be run in single threaded context.
     *
     * @param className
     *            the class name
     * @param classModifiers
     *            the class modifiers
     * @param isExternalClass
     *            true if this is an external class
     * @param classNameToClassInfo
     *            the map from class name to class info
     * @param classpathElement
     *            the classpath element
     * @param classfileResource
     *            the classfile resource
     * @return the class info
     */
    static ClassInfo addScannedClass(final String className, final int classModifiers,
            final boolean isExternalClass, final Map<String, ClassInfo> classNameToClassInfo,
            final ClasspathElement classpathElement, final Resource classfileResource) {
        ClassInfo classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            // This is the first time this class has been seen, add it
            classNameToClassInfo.put(className,
                    classInfo = new ClassInfo(className, classModifiers, classfileResource));
        } else {
            // There was a previous placeholder ClassInfo class added, due to the class being referred
            // to as a superclass, interface or annotation. The isScannedClass field should be false
            // in this case, since the actual class definition wasn't reached before now.
            if (classInfo.isScannedClass) {
                // The class should not have been scanned more than once, because of classpath masking
                throw new IllegalArgumentException("Class " + className
                        + " should not have been encountered more than once due to classpath masking --"
                        + " please report this bug at: https://github.com/classgraph/classgraph/issues");
            }

            // Set the classfileResource for the placeholder class
            classInfo.classfileResource = classfileResource;

            // Add any additional modifier bits
            classInfo.modifiers |= classModifiers;
        }

        // Mark the class as scanned
        classInfo.isScannedClass = true;

        // Mark the class as non-external if it is a whitelisted class
        classInfo.isExternalClass = isExternalClass;

        // Remember which classpath element (zipfile / classpath root directory / module) the class was found in
        classInfo.classpathElement = classpathElement;

        // Remember which classloader is used to load the class
        classInfo.classLoader = classpathElement.getClassLoader();

        return classInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The class type to return. */
    private enum ClassType {
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
        /** An enum. */
        ENUM,
        /** A record type. */
        RECORD
    }

    /**
     * Filter classes according to scan spec and class type.
     *
     * @param classes
     *            the classes
     * @param scanSpec
     *            the scan spec
     * @param strictWhitelist
     *            If true, exclude class if it is is external, blacklisted, or a system class.
     * @param classTypes
     *            the class types
     * @return the filtered classes.
     */
    private static Set<ClassInfo> filterClassInfo(final Collection<ClassInfo> classes, final ScanSpec scanSpec,
            final boolean strictWhitelist, final ClassType... classTypes) {
        if (classes == null) {
            return Collections.<ClassInfo> emptySet();
        }
        boolean includeAllTypes = classTypes.length == 0;
        boolean includeStandardClasses = false;
        boolean includeImplementedInterfaces = false;
        boolean includeAnnotations = false;
        boolean includeEnums = false;
        boolean includeRecords = false;
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
            case ENUM:
                includeEnums = true;
                break;
            case RECORD:
                includeRecords = true;
                break;
            default:
                throw new IllegalArgumentException("Unknown ClassType: " + classType);
            }
        }
        if (includeStandardClasses && includeImplementedInterfaces && includeAnnotations) {
            includeAllTypes = true;
        }
        final Set<ClassInfo> classInfoSetFiltered = new LinkedHashSet<>(classes.size());
        for (final ClassInfo classInfo : classes) {
            // Check class type against requested type(s)
            if ((includeAllTypes //
                    || includeStandardClasses && classInfo.isStandardClass() //
                    || includeImplementedInterfaces && classInfo.isImplementedInterface() //
                    || includeAnnotations && classInfo.isAnnotation() //
                    || includeEnums && classInfo.isEnum() //
                    || includeRecords && classInfo.isRecord()) //
                    // Always check blacklist 
                    && !scanSpec.classOrPackageIsBlacklisted(classInfo.name) //
                    // Always return whitelisted classes, or external classes if enableExternalClasses is true
                    && (!classInfo.isExternalClass || scanSpec.enableExternalClasses
                    // Return external (non-whitelisted) classes if viewing class hierarchy "upwards" 
                            || !strictWhitelist)) {
                // Class passed strict whitelist criteria
                classInfoSetFiltered.add(classInfo);
            }
        }
        return classInfoSetFiltered;
    }

    /**
     * A set of classes that indirectly reachable through a directed path, for a given relationship type, and a set
     * of classes that is directly related (only one relationship step away).
     */
    static class ReachableAndDirectlyRelatedClasses {

        /** The reachable classes. */
        final Set<ClassInfo> reachableClasses;

        /** The directly related classes. */
        final Set<ClassInfo> directlyRelatedClasses;

        /**
         * Constructor.
         *
         * @param reachableClasses
         *            the reachable classes
         * @param directlyRelatedClasses
         *            the directly related classes
         */
        private ReachableAndDirectlyRelatedClasses(final Set<ClassInfo> reachableClasses,
                final Set<ClassInfo> directlyRelatedClasses) {
            this.reachableClasses = reachableClasses;
            this.directlyRelatedClasses = directlyRelatedClasses;
        }
    }

    /**
     * Get the classes related to this one (the transitive closure) for the given relationship type, and those
     * directly related.
     *
     * @param relType
     *            the rel type
     * @param strictWhitelist
     *            the strict whitelist
     * @param classTypes
     *            the class types
     * @return the reachable and directly related classes
     */
    private ReachableAndDirectlyRelatedClasses filterClassInfo(final RelType relType, final boolean strictWhitelist,
            final ClassType... classTypes) {
        Set<ClassInfo> directlyRelatedClasses = this.relatedClasses.get(relType);
        if (directlyRelatedClasses == null) {
            return NO_REACHABLE_CLASSES;
        } else {
            // Clone collection to prevent users modifying contents accidentally or intentionally
            directlyRelatedClasses = new LinkedHashSet<>(directlyRelatedClasses);
        }
        final Set<ClassInfo> reachableClasses = new LinkedHashSet<>(directlyRelatedClasses);
        if (relType == RelType.METHOD_ANNOTATIONS || relType == RelType.METHOD_PARAMETER_ANNOTATIONS
                || relType == RelType.FIELD_ANNOTATIONS) {
            // For method and field annotations, need to change the RelType when finding meta-annotations
            for (final ClassInfo annotation : directlyRelatedClasses) {
                reachableClasses.addAll(
                        annotation.filterClassInfo(RelType.CLASS_ANNOTATIONS, strictWhitelist).reachableClasses);
            }
        } else if (relType == RelType.CLASSES_WITH_METHOD_ANNOTATION
                || relType == RelType.CLASSES_WITH_NONPRIVATE_METHOD_ANNOTATION
                || relType == RelType.CLASSES_WITH_METHOD_PARAMETER_ANNOTATION
                || relType == RelType.CLASSES_WITH_NONPRIVATE_METHOD_PARAMETER_ANNOTATION
                || relType == RelType.CLASSES_WITH_FIELD_ANNOTATION
                || relType == RelType.CLASSES_WITH_NONPRIVATE_FIELD_ANNOTATION) {
            // If looking for meta-annotated methods or fields, need to find all meta-annotated annotations, then
            // look for the methods or fields that they annotate
            for (final ClassInfo subAnnotation : this.filterClassInfo(RelType.CLASSES_WITH_ANNOTATION,
                    strictWhitelist, ClassType.ANNOTATION).reachableClasses) {
                final Set<ClassInfo> annotatedClasses = subAnnotation.relatedClasses.get(relType);
                if (annotatedClasses != null) {
                    reachableClasses.addAll(annotatedClasses);
                }
            }
        } else {
            // For other relationship types, the reachable type stays the same over the transitive closure. Find the
            // transitive closure, breaking cycles where necessary.
            final LinkedList<ClassInfo> queue = new LinkedList<>(directlyRelatedClasses);
            while (!queue.isEmpty()) {
                final ClassInfo head = queue.removeFirst();
                final Set<ClassInfo> headRelatedClasses = head.relatedClasses.get(relType);
                if (headRelatedClasses != null) {
                    for (final ClassInfo directlyReachableFromHead : headRelatedClasses) {
                        // Don't get in cycle
                        if (reachableClasses.add(directlyReachableFromHead)) {
                            queue.add(directlyReachableFromHead);
                        }
                    }
                }
            }
        }
        if (reachableClasses.isEmpty()) {
            return NO_REACHABLE_CLASSES;
        }

        if (relType == RelType.CLASS_ANNOTATIONS || relType == RelType.METHOD_ANNOTATIONS
                || relType == RelType.METHOD_PARAMETER_ANNOTATIONS || relType == RelType.FIELD_ANNOTATIONS) {
            // Special case -- don't inherit java.lang.annotation.* meta-annotations as related meta-annotations
            // (but still return them as direct meta-annotations on annotation classes).
            Set<ClassInfo> reachableClassesToRemove = null;
            for (final ClassInfo reachableClassInfo : reachableClasses) {
                // Remove all java.lang.annotation annotations that are not directly related to this class
                if (reachableClassInfo.getName().startsWith("java.lang.annotation.")
                        && !directlyRelatedClasses.contains(reachableClassInfo)) {
                    if (reachableClassesToRemove == null) {
                        reachableClassesToRemove = new LinkedHashSet<>();
                    }
                    reachableClassesToRemove.add(reachableClassInfo);
                }
            }
            if (reachableClassesToRemove != null) {
                reachableClasses.removeAll(reachableClassesToRemove);
            }
        }

        return new ReachableAndDirectlyRelatedClasses(
                filterClassInfo(reachableClasses, scanResult.scanSpec, strictWhitelist, classTypes),
                filterClassInfo(directlyRelatedClasses, scanResult.scanSpec, strictWhitelist, classTypes));

    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get all classes found during the scan.
     *
     * @param classes
     *            the classes
     * @param scanSpec
     *            the scan spec
     * @return A list of all classes found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllClasses(final Collection<ClassInfo> classes, final ScanSpec scanSpec) {
        return new ClassInfoList(
                ClassInfo.filterClassInfo(classes, scanSpec, /* strictWhitelist = */ true, ClassType.ALL),
                /* sortByName = */ true);
    }

    /**
     * Get all {@link Enum} classes found during the scan.
     *
     * @param classes
     *            the classes
     * @param scanSpec
     *            the scan spec
     * @return A list of all {@link Enum} classes found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllEnums(final Collection<ClassInfo> classes, final ScanSpec scanSpec) {
        return new ClassInfoList(
                ClassInfo.filterClassInfo(classes, scanSpec, /* strictWhitelist = */ true, ClassType.ENUM),
                /* sortByName = */ true);
    }

    /**
     * Get all {@code record} classes found during the scan.
     *
     * @param classes
     *            the classes
     * @param scanSpec
     *            the scan spec
     * @return A list of all {@code record} classes found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllRecords(final Collection<ClassInfo> classes, final ScanSpec scanSpec) {
        return new ClassInfoList(
                ClassInfo.filterClassInfo(classes, scanSpec, /* strictWhitelist = */ true, ClassType.RECORD),
                /* sortByName = */ true);
    }

    /**
     * Get all standard classes found during the scan.
     *
     * @param classes
     *            the classes
     * @param scanSpec
     *            the scan spec
     * @return A list of all standard classes found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllStandardClasses(final Collection<ClassInfo> classes, final ScanSpec scanSpec) {
        return new ClassInfoList(ClassInfo.filterClassInfo(classes, scanSpec, /* strictWhitelist = */ true,
                ClassType.STANDARD_CLASS), /* sortByName = */ true);
    }

    /**
     * Get all implemented interface (non-annotation interface) classes found during the scan.
     *
     * @param classes
     *            the classes
     * @param scanSpec
     *            the scan spec
     * @return A list of all annotation classes found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllImplementedInterfaceClasses(final Collection<ClassInfo> classes,
            final ScanSpec scanSpec) {
        return new ClassInfoList(ClassInfo.filterClassInfo(classes, scanSpec, /* strictWhitelist = */ true,
                ClassType.IMPLEMENTED_INTERFACE), /* sortByName = */ true);
    }

    /**
     * Get all annotation classes found during the scan. See also
     * {@link #getAllInterfacesOrAnnotationClasses(Collection, ScanSpec, ScanResult)} ()}.
     *
     * @param classes
     *            the classes
     * @param scanSpec
     *            the scan spec
     * @return A list of all annotation classes found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllAnnotationClasses(final Collection<ClassInfo> classes, final ScanSpec scanSpec) {
        return new ClassInfoList(
                ClassInfo.filterClassInfo(classes, scanSpec, /* strictWhitelist = */ true, ClassType.ANNOTATION),
                /* sortByName = */ true);
    }

    /**
     * Get all interface or annotation classes found during the scan. (Annotations are technically interfaces, and
     * they can be implemented.)
     *
     * @param classes
     *            the classes
     * @param scanSpec
     *            the scan spec
     * @return A list of all whitelisted interfaces found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllInterfacesOrAnnotationClasses(final Collection<ClassInfo> classes,
            final ScanSpec scanSpec) {
        return new ClassInfoList(ClassInfo.filterClassInfo(classes, scanSpec, /* strictWhitelist = */ true,
                ClassType.INTERFACE_OR_ANNOTATION), /* sortByName = */ true);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Predicates

    /**
     * Get the name of the class.
     *
     * @return The name of the class.
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Get simple name from fully-qualified class name. Returns everything after the last '.' in the class name, or
     * the whole string if the class is in the root package. (Note that this is not the same as the result of
     * {@link Class#getSimpleName()}, which returns "" for anonymous classes.)
     *
     * @param className
     *            the class name
     * @return The simple name of the class.
     */
    static String getSimpleName(final String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /**
     * Get the simple name of the class. Returns everything after the last '.' in the class name, or the whole
     * string if the class is in the root package. (Note that this is not the same as the result of
     * {@link Class#getSimpleName()}, which returns "" for anonymous classes.)
     *
     * @return The simple name of the class.
     */
    public String getSimpleName() {
        return getSimpleName(name);
    }

    /**
     * Get the {@link ModuleInfo} object for the class.
     *
     * @return the {@link ModuleInfo} object for the class, or null if the class is not part of a named module.
     */
    public ModuleInfo getModuleInfo() {
        return moduleInfo;
    }

    /**
     * Get the {@link PackageInfo} object for the class.
     *
     * @return the {@link PackageInfo} object for the package that contains the class.
     */
    public PackageInfo getPackageInfo() {
        return packageInfo;
    }

    /**
     * Get the name of the class' package.
     *
     * @return The name of the class' package.
     */
    public String getPackageName() {
        return PackageInfo.getParentPackageName(name);
    }

    /**
     * Checks if this is an external class.
     *
     * @return true if this class is an external class, i.e. was referenced by a whitelisted class as a superclass,
     *         interface, or annotation, but is not itself a whitelisted class.
     */
    public boolean isExternalClass() {
        return isExternalClass;
    }

    /**
     * Get the class modifier bits.
     *
     * @return The class modifier bits, e.g. {@link Modifier#PUBLIC}.
     */
    public int getModifiers() {
        return modifiers;
    }

    /**
     * Get the class modifiers as a String.
     *
     * @return The field modifiers as a string, e.g. "public static final". For the modifier bits, call
     *         {@link #getModifiers()}.
     */
    public String getModifiersStr() {
        final StringBuilder buf = new StringBuilder();
        TypeUtils.modifiersToString(modifiers, ModifierType.CLASS, /* ignored */ false, buf);
        return buf.toString();
    }

    /**
     * Checks if the class is public.
     *
     * @return true if this class is a public class.
     */
    public boolean isPublic() {
        return (modifiers & Modifier.PUBLIC) != 0;
    }

    /**
     * Checks if the class is abstract.
     *
     * @return true if this class is an abstract class.
     */
    public boolean isAbstract() {
        return (modifiers & 0x400) != 0;
    }

    /**
     * Checks if the class is synthetic.
     *
     * @return true if this class is a synthetic class.
     */
    public boolean isSynthetic() {
        return (modifiers & 0x1000) != 0;
    }

    /**
     * Checks if the class is final.
     *
     * @return true if this class is a final class.
     */
    public boolean isFinal() {
        return (modifiers & Modifier.FINAL) != 0;
    }

    /**
     * Checks if the class is static.
     *
     * @return true if this class is static.
     */
    public boolean isStatic() {
        return Modifier.isStatic(modifiers);
    }

    /**
     * Checks if the class is an annotation.
     *
     * @return true if this class is an annotation class.
     */
    public boolean isAnnotation() {
        return (modifiers & ANNOTATION_CLASS_MODIFIER) != 0;
    }

    /**
     * Checks if is the class an interface and is not an annotation.
     *
     * @return true if this class is an interface and is not an annotation (annotations are interfaces, and can be
     *         implemented).
     */
    public boolean isInterface() {
        return isInterfaceOrAnnotation() && !isAnnotation();
    }

    /**
     * Checks if is an interface or an annotation.
     *
     * @return true if this class is an interface or an annotation (annotations are interfaces, and can be
     *         implemented).
     */
    public boolean isInterfaceOrAnnotation() {
        return (modifiers & Modifier.INTERFACE) != 0;
    }

    /**
     * Checks if is the class is an {@link Enum}.
     *
     * @return true if this class is an {@link Enum}.
     */
    public boolean isEnum() {
        return (modifiers & 0x4000) != 0;
    }

    /**
     * Checks if is the class is a record (JDK 14+).
     *
     * @return true if this class is a record.
     */
    public boolean isRecord() {
        return isRecord;
    }

    /**
     * Checks if this class is a standard class.
     *
     * @return true if this class is a standard class (i.e. is not an annotation or interface).
     */
    public boolean isStandardClass() {
        return !(isAnnotation() || isInterface());
    }

    /**
     * Checks if this class is an array class. Returns false unless this {@link ClassInfo} is an instance of
     * {@link ArrayClassInfo}.
     *
     * @return true if this is an array class.
     */
    public boolean isArrayClass() {
        return this instanceof ArrayClassInfo;
    }

    /**
     * Checks if this class extends the named superclass.
     *
     * @param superclassName
     *            The name of a superclass.
     * @return true if this class extends the named superclass.
     */
    public boolean extendsSuperclass(final String superclassName) {
        return getSuperclasses().containsName(superclassName);
    }

    /**
     * Checks if this class is an inner class.
     *
     * @return true if this is an inner class (call {@link #isAnonymousInnerClass()} to test if this is an anonymous
     *         inner class). If true, the containing class can be determined by calling {@link #getOuterClasses()}.
     */
    public boolean isInnerClass() {
        return !getOuterClasses().isEmpty();
    }

    /**
     * Checks if this class is an outer class.
     *
     * @return true if this class contains inner classes. If true, the inner classes can be determined by calling
     *         {@link #getInnerClasses()}.
     */
    public boolean isOuterClass() {
        return !getInnerClasses().isEmpty();
    }

    /**
     * Checks if this class is an anonymous inner class.
     *
     * @return true if this is an anonymous inner class. If true, the name of the containing method can be obtained
     *         by calling {@link #getFullyQualifiedDefiningMethodName()}.
     */
    public boolean isAnonymousInnerClass() {
        return fullyQualifiedDefiningMethodName != null;
    }

    /**
     * Checks whether this class is an implemented interface (meaning a standard, non-annotation interface, or an
     * annotation that has also been implemented as an interface by some class).
     *
     * <p>
     * Annotations are interfaces, but you can also implement an annotation, so to we return whether an interface
     * (even an annotation) is implemented by a class or extended by a subinterface, or (failing that) if it is not
     * an interface but not an annotation.
     *
     * @return true if this class is an implemented interface.
     */
    public boolean isImplementedInterface() {
        return relatedClasses.get(RelType.CLASSES_IMPLEMENTING) != null || isInterface();
    }

    /**
     * Checks whether this class implements the named interface.
     *
     * @param interfaceName
     *            The name of an interface.
     * @return true if this class implements the named interface.
     */
    public boolean implementsInterface(final String interfaceName) {
        return getInterfaces().containsName(interfaceName);
    }

    /**
     * Checks whether this class has the named annotation.
     *
     * @param annotationName
     *            The name of an annotation.
     * @return true if this class has the named annotation.
     */
    public boolean hasAnnotation(final String annotationName) {
        return getAnnotations().containsName(annotationName);
    }

    /**
     * Checks whether this class has the named declared field.
     *
     * @param fieldName
     *            The name of a field.
     * @return true if this class declares a field of the given name.
     */
    public boolean hasDeclaredField(final String fieldName) {
        return getDeclaredFieldInfo().containsName(fieldName);
    }

    /**
     * Checks whether this class or one of its superclasses has the named field.
     *
     * @param fieldName
     *            The name of a field.
     * @return true if this class or one of its superclasses declares a field of the given name.
     */
    public boolean hasField(final String fieldName) {
        for (final ClassInfo ci : getOverrideOrder()) {
            if (ci.hasDeclaredField(fieldName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class declares a field with the named annotation.
     *
     * @param fieldAnnotationName
     *            The name of a field annotation.
     * @return true if this class declares a field with the named annotation.
     */
    public boolean hasDeclaredFieldAnnotation(final String fieldAnnotationName) {
        for (final FieldInfo fi : getDeclaredFieldInfo()) {
            if (fi.hasAnnotation(fieldAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class or one of its superclasses declares a field with the named annotation.
     *
     * @param fieldAnnotationName
     *            The name of a field annotation.
     * @return true if this class or one of its superclasses declares a field with the named annotation.
     */
    public boolean hasFieldAnnotation(final String fieldAnnotationName) {
        for (final ClassInfo ci : getOverrideOrder()) {
            if (ci.hasDeclaredFieldAnnotation(fieldAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class declares a field of the given name.
     *
     * @param methodName
     *            The name of a method.
     * @return true if this class declares a field of the given name.
     */
    public boolean hasDeclaredMethod(final String methodName) {
        return getDeclaredMethodInfo().containsName(methodName);
    }

    /**
     * Checks whether this class or one of its superclasses or interfaces declares a method of the given name.
     *
     * @param methodName
     *            The name of a method.
     * @return true if this class or one of its superclasses or interfaces declares a method of the given name.
     */
    public boolean hasMethod(final String methodName) {
        for (final ClassInfo ci : getOverrideOrder()) {
            if (ci.hasDeclaredMethod(methodName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class declares a method with the named annotation.
     *
     * @param methodAnnotationName
     *            The name of a method annotation.
     * @return true if this class declares a method with the named annotation.
     */
    public boolean hasDeclaredMethodAnnotation(final String methodAnnotationName) {
        for (final MethodInfo mi : getDeclaredMethodInfo()) {
            if (mi.hasAnnotation(methodAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class or one of its superclasses or interfaces declares a method with the named
     * annotation.
     *
     * @param methodAnnotationName
     *            The name of a method annotation.
     * @return true if this class or one of its superclasses or interfaces declares a method with the named
     *         annotation.
     */
    public boolean hasMethodAnnotation(final String methodAnnotationName) {
        for (final ClassInfo ci : getOverrideOrder()) {
            if (ci.hasDeclaredMethodAnnotation(methodAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class declares a method with the named annotation.
     *
     * @param methodParameterAnnotationName
     *            The name of a method annotation.
     * @return true if this class declares a method with the named annotation.
     */
    public boolean hasDeclaredMethodParameterAnnotation(final String methodParameterAnnotationName) {
        for (final MethodInfo mi : getDeclaredMethodInfo()) {
            if (mi.hasParameterAnnotation(methodParameterAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class or one of its superclasses or interfaces has a method with the named annotation.
     *
     * @param methodParameterAnnotationName
     *            The name of a method annotation.
     * @return true if this class or one of its superclasses or interfaces has a method with the named annotation.
     */
    public boolean hasMethodParameterAnnotation(final String methodParameterAnnotationName) {
        for (final ClassInfo ci : getOverrideOrder()) {
            if (ci.hasDeclaredMethodParameterAnnotation(methodParameterAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recurse to interfaces and superclasses to get the order that fields and methods are overridden in.
     *
     * @param visited
     *            visited
     * @param overrideOrderOut
     *            the override order
     * @return the override order
     */
    private List<ClassInfo> getOverrideOrder(final Set<ClassInfo> visited, final List<ClassInfo> overrideOrderOut) {
        if (visited.add(this)) {
            overrideOrderOut.add(this);
            for (final ClassInfo iface : getInterfaces()) {
                iface.getOverrideOrder(visited, overrideOrderOut);
            }
            final ClassInfo superclass = getSuperclass();
            if (superclass != null) {
                superclass.getOverrideOrder(visited, overrideOrderOut);
            }
        }
        return overrideOrderOut;
    }

    /**
     * Get the order that fields and methods are overridden in (base class first).
     *
     * @return the override order
     */
    private List<ClassInfo> getOverrideOrder() {
        if (overrideOrder == null) {
            overrideOrder = getOverrideOrder(new HashSet<ClassInfo>(), new ArrayList<ClassInfo>());
        }
        return overrideOrder;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Standard classes

    /**
     * Get the subclasses of this class, sorted in order of name. Call {@link ClassInfoList#directOnly()} to get
     * direct subclasses.
     *
     * @return the list of subclasses of this class, or the empty list if none.
     */
    public ClassInfoList getSubclasses() {
        if (getName().equals("java.lang.Object")) {
            // Make an exception for querying all subclasses of java.lang.Object
            return scanResult.getAllClasses();
        } else {
            return new ClassInfoList(
                    this.filterClassInfo(RelType.SUBCLASSES, /* strictWhitelist = */ !isExternalClass),
                    /* sortByName = */ true);
        }
    }

    /**
     * Get all superclasses of this class, in ascending order in the class hierarchy. Does not include
     * superinterfaces, if this is an interface (use {@link #getInterfaces()} to get superinterfaces of an
     * interface.}
     *
     * @return the list of all superclasses of this class, or the empty list if none.
     */
    public ClassInfoList getSuperclasses() {
        return new ClassInfoList(this.filterClassInfo(RelType.SUPERCLASSES, /* strictWhitelist = */ false),
                /* sortByName = */ false);
    }

    /**
     * Get the single direct superclass of this class, or null if none. Does not return the superinterfaces, if this
     * is an interface (use {@link #getInterfaces()} to get superinterfaces of an interface.}
     *
     * @return the superclass of this class, or null if none.
     */
    public ClassInfo getSuperclass() {
        final Set<ClassInfo> superClasses = relatedClasses.get(RelType.SUPERCLASSES);
        if (superClasses == null || superClasses.isEmpty()) {
            return null;
        } else if (superClasses.size() > 2) {
            throw new IllegalArgumentException("More than one superclass: " + superClasses);
        } else {
            final ClassInfo superclass = superClasses.iterator().next();
            if (superclass.getName().equals("java.lang.Object")) {
                return null;
            } else {
                return superclass;
            }
        }
    }

    /**
     * Get the containing outer classes, if this is an inner class.
     *
     * @return A list of the containing outer classes, if this is an inner class, otherwise the empty list. Note
     *         that all containing outer classes are returned, not just the innermost of the containing outer
     *         classes.
     */
    public ClassInfoList getOuterClasses() {
        return new ClassInfoList(
                this.filterClassInfo(RelType.CONTAINED_WITHIN_OUTER_CLASS, /* strictWhitelist = */ false),
                /* sortByName = */ false);
    }

    /**
     * Get the inner classes contained within this class, if this is an outer class.
     *
     * @return A list of the inner classes contained within this class, or the empty list if none.
     */
    public ClassInfoList getInnerClasses() {
        return new ClassInfoList(this.filterClassInfo(RelType.CONTAINS_INNER_CLASS, /* strictWhitelist = */ false),
                /* sortByName = */ true);
    }

    /**
     * Gets fully-qualified method name (i.e. fully qualified classname, followed by dot, followed by method name)
     * for the defining method, if this is an anonymous inner class.
     *
     * @return The fully-qualified method name (i.e. fully qualified classname, followed by dot, followed by method
     *         name) for the defining method, if this is an anonymous inner class, or null if not.
     */
    public String getFullyQualifiedDefiningMethodName() {
        return fullyQualifiedDefiningMethodName;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /**
     * Get the interfaces implemented by this class or by one of its superclasses, if this is a standard class, or
     * the superinterfaces extended by this interface, if this is an interface.
     *
     * @return The list of interfaces implemented by this class or by one of its superclasses, if this is a standard
     *         class, or the superinterfaces extended by this interface, if this is an interface. Returns the empty
     *         list if none.
     */
    public ClassInfoList getInterfaces() {
        // Classes also implement the interfaces of their superclasses
        final ReachableAndDirectlyRelatedClasses implementedInterfaces = this
                .filterClassInfo(RelType.IMPLEMENTED_INTERFACES, /* strictWhitelist = */ false);
        final Set<ClassInfo> allInterfaces = new LinkedHashSet<>(implementedInterfaces.reachableClasses);
        for (final ClassInfo superclass : this.filterClassInfo(RelType.SUPERCLASSES,
                /* strictWhitelist = */ false).reachableClasses) {
            final Set<ClassInfo> superclassImplementedInterfaces = superclass.filterClassInfo(
                    RelType.IMPLEMENTED_INTERFACES, /* strictWhitelist = */ false).reachableClasses;
            allInterfaces.addAll(superclassImplementedInterfaces);
        }
        return new ClassInfoList(allInterfaces, implementedInterfaces.directlyRelatedClasses,
                /* sortByName = */ true);
    }

    /**
     * Get the classes (and their subclasses) that implement this interface, if this is an interface.
     *
     * @return the list of the classes (and their subclasses) that implement this interface, if this is an
     *         interface, otherwise returns the empty list.
     */
    public ClassInfoList getClassesImplementing() {
        if (!isInterface()) {
            throw new IllegalArgumentException("Class is not an interface: " + getName());
        }
        // Subclasses of implementing classes also implement the interface
        final ReachableAndDirectlyRelatedClasses implementingClasses = this
                .filterClassInfo(RelType.CLASSES_IMPLEMENTING, /* strictWhitelist = */ !isExternalClass);
        final Set<ClassInfo> allImplementingClasses = new LinkedHashSet<>(implementingClasses.reachableClasses);
        for (final ClassInfo implementingClass : implementingClasses.reachableClasses) {
            final Set<ClassInfo> implementingSubclasses = implementingClass.filterClassInfo(RelType.SUBCLASSES,
                    /* strictWhitelist = */ !implementingClass.isExternalClass).reachableClasses;
            allImplementingClasses.addAll(implementingSubclasses);
        }
        return new ClassInfoList(allImplementingClasses, implementingClasses.directlyRelatedClasses,
                /* sortByName = */ true);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /**
     * Get the annotations and meta-annotations on this class. (Call {@link #getAnnotationInfo()} instead, if you
     * need the parameter values of annotations, rather than just the annotation classes.)
     * 
     * <p>
     * Also handles the {@link Inherited} meta-annotation, which causes an annotation to annotate a class and all of
     * its subclasses.
     * 
     * <p>
     * Filters out meta-annotations in the {@code java.lang.annotation} package.
     *
     * @return the list of annotations and meta-annotations on this class.
     */
    public ClassInfoList getAnnotations() {
        if (!scanResult.scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableAnnotationInfo() before #scan()");
        }

        // Get all annotations on this class
        final ReachableAndDirectlyRelatedClasses annotationClasses = this.filterClassInfo(RelType.CLASS_ANNOTATIONS,
                /* strictWhitelist = */ false);
        // Check for any @Inherited annotations on superclasses
        Set<ClassInfo> inheritedSuperclassAnnotations = null;
        for (final ClassInfo superclass : getSuperclasses()) {
            for (final ClassInfo superclassAnnotation : superclass.filterClassInfo(RelType.CLASS_ANNOTATIONS,
                    /* strictWhitelist = */ false).reachableClasses) {
                // Check if any of the meta-annotations on this annotation are @Inherited,
                // which causes an annotation to annotate a class and all of its subclasses.
                if (superclassAnnotation != null && superclassAnnotation.isInherited) {
                    // superclassAnnotation has an @Inherited meta-annotation
                    if (inheritedSuperclassAnnotations == null) {
                        inheritedSuperclassAnnotations = new LinkedHashSet<>();
                    }
                    inheritedSuperclassAnnotations.add(superclassAnnotation);
                }
            }
        }

        if (inheritedSuperclassAnnotations == null) {
            // No inherited superclass annotations
            return new ClassInfoList(annotationClasses, /* sortByName = */ true);
        } else {
            // Merge inherited superclass annotations and annotations on this class
            inheritedSuperclassAnnotations.addAll(annotationClasses.reachableClasses);
            return new ClassInfoList(inheritedSuperclassAnnotations, annotationClasses.directlyRelatedClasses,
                    /* sortByName = */ true);
        }
    }

    /**
     * Get the annotations or meta-annotations on fields, methods or method parametres declared by the class, (not
     * including fields, methods or method parameters declared by the interfaces or superclasses of this class).
     *
     * @param relType
     *            One of {@link RelType#FIELD_ANNOTATIONS}, {@link RelType#METHOD_ANNOTATIONS} or
     *            {@link RelType#METHOD_PARAMETER_ANNOTATIONS}.
     * @return A list of annotations or meta-annotations on fields or methods declared by the class, (not including
     *         fields or methods declared by the interfaces or superclasses of this class), as a list of
     *         {@link ClassInfo} objects, or the empty list if none.
     */
    private ClassInfoList getFieldOrMethodAnnotations(final RelType relType) {
        final boolean isField = relType == RelType.FIELD_ANNOTATIONS;
        if (!(isField ? scanResult.scanSpec.enableFieldInfo : scanResult.scanSpec.enableMethodInfo)
                || !scanResult.scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enable" + (isField ? "Field" : "Method")
                    + "Info() and " + "#enableAnnotationInfo() before #scan()");
        }
        final ReachableAndDirectlyRelatedClasses fieldOrMethodAnnotations = this.filterClassInfo(relType,
                /* strictWhitelist = */ false, ClassType.ANNOTATION);
        final Set<ClassInfo> fieldOrMethodAnnotationsAndMetaAnnotations = new LinkedHashSet<>(
                fieldOrMethodAnnotations.reachableClasses);
        return new ClassInfoList(fieldOrMethodAnnotationsAndMetaAnnotations,
                fieldOrMethodAnnotations.directlyRelatedClasses, /* sortByName = */ true);
    }

    /**
     * Get the classes that have this class as a field, method or method parameter annotation.
     *
     * @param relType
     *            One of {@link RelType#CLASSES_WITH_FIELD_ANNOTATION},
     *            {@link RelType#CLASSES_WITH_NONPRIVATE_FIELD_ANNOTATION},
     *            {@link RelType#CLASSES_WITH_METHOD_ANNOTATION},
     *            {@link RelType#CLASSES_WITH_NONPRIVATE_METHOD_ANNOTATION},
     *            {@link RelType#CLASSES_WITH_METHOD_PARAMETER_ANNOTATION}, or
     *            {@link RelType#CLASSES_WITH_NONPRIVATE_METHOD_PARAMETER_ANNOTATION}.
     * @return A list of classes that have a declared method with this annotation or meta-annotation, or the empty
     *         list if none.
     */
    private ClassInfoList getClassesWithFieldOrMethodAnnotation(final RelType relType) {
        final boolean isField = relType == RelType.CLASSES_WITH_FIELD_ANNOTATION
                || relType == RelType.CLASSES_WITH_NONPRIVATE_FIELD_ANNOTATION;
        if (!(isField ? scanResult.scanSpec.enableFieldInfo : scanResult.scanSpec.enableMethodInfo)
                || !scanResult.scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enable" + (isField ? "Field" : "Method")
                    + "Info() and " + "#enableAnnotationInfo() before #scan()");
        }
        final ReachableAndDirectlyRelatedClasses classesWithDirectlyAnnotatedFieldsOrMethods = this
                .filterClassInfo(relType, /* strictWhitelist = */ !isExternalClass);
        final ReachableAndDirectlyRelatedClasses annotationsWithThisMetaAnnotation = this.filterClassInfo(
                RelType.CLASSES_WITH_ANNOTATION, /* strictWhitelist = */ !isExternalClass, ClassType.ANNOTATION);
        if (annotationsWithThisMetaAnnotation.reachableClasses.isEmpty()) {
            // This annotation does not meta-annotate another annotation that annotates a method
            return new ClassInfoList(classesWithDirectlyAnnotatedFieldsOrMethods, /* sortByName = */ true);
        } else {
            // Take the union of all classes with fields or methods directly annotated by this annotation,
            // and classes with fields or methods meta-annotated by this annotation
            final Set<ClassInfo> allClassesWithAnnotatedOrMetaAnnotatedFieldsOrMethods = new LinkedHashSet<>(
                    classesWithDirectlyAnnotatedFieldsOrMethods.reachableClasses);
            for (final ClassInfo metaAnnotatedAnnotation : annotationsWithThisMetaAnnotation.reachableClasses) {
                allClassesWithAnnotatedOrMetaAnnotatedFieldsOrMethods
                        .addAll(metaAnnotatedAnnotation.filterClassInfo(relType,
                                /* strictWhitelist = */ !metaAnnotatedAnnotation.isExternalClass).reachableClasses);
            }
            return new ClassInfoList(allClassesWithAnnotatedOrMetaAnnotatedFieldsOrMethods,
                    classesWithDirectlyAnnotatedFieldsOrMethods.directlyRelatedClasses, /* sortByName = */ true);
        }
    }

    /**
     * Get a list of the annotations on this class, or the empty list if none.
     * 
     * <p>
     * Also handles the {@link Inherited} meta-annotation, which causes an annotation to annotate a class and all of
     * its subclasses.
     * 
     * @return A list of {@link AnnotationInfo} objects for the annotations on this class, or the empty list if
     *         none.
     */
    public AnnotationInfoList getAnnotationInfo() {
        if (!scanResult.scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableAnnotationInfo() before #scan()");
        }
        return AnnotationInfoList.getIndirectAnnotations(annotationInfo, this);
    }

    /**
     * Get a the named non-{@link Repeatable} annotation on this class, or null if the class does not have the named
     * annotation. (Use {@link #getAnnotationInfoRepeatable(String)} for {@link Repeatable} annotations.)
     * 
     * <p>
     * Also handles the {@link Inherited} meta-annotation, which causes an annotation to annotate a class and all of
     * its subclasses.
     * 
     * <p>
     * Note that if you need to get multiple named annotations, it is faster to call {@link #getAnnotationInfo()},
     * and then get the named annotations from the returned {@link AnnotationInfoList}, so that the returned list
     * doesn't have to be built multiple times.
     * 
     * @param annotationName
     *            The annotation name.
     * @return An {@link AnnotationInfo} object representing the named annotation on this class, or null if the
     *         class does not have the named annotation.
     */
    public AnnotationInfo getAnnotationInfo(final String annotationName) {
        return getAnnotationInfo().get(annotationName);
    }

    /**
     * Get a the named {@link Repeatable} annotation on this class, or the empty list if the class does not have the
     * named annotation.
     * 
     * <p>
     * Also handles the {@link Inherited} meta-annotation, which causes an annotation to annotate a class and all of
     * its subclasses.
     * 
     * <p>
     * Note that if you need to get multiple named annotations, it is faster to call {@link #getAnnotationInfo()},
     * and then get the named annotations from the returned {@link AnnotationInfoList}, so that the returned list
     * doesn't have to be built multiple times.
     * 
     * @param annotationName
     *            The annotation name.
     * @return An {@link AnnotationInfoList} of all instances of the named annotation on this class, or the empty
     *         list if the class does not have the named annotation.
     */
    public AnnotationInfoList getAnnotationInfoRepeatable(final String annotationName) {
        return getAnnotationInfo().getRepeatable(annotationName);
    }

    /**
     * Get the default parameter values for this annotation, if this is an annotation class.
     *
     * @return A list of {@link AnnotationParameterValue} objects for each of the default parameter values for this
     *         annotation, if this is an annotation class with default parameter values, otherwise the empty list.
     */
    public AnnotationParameterValueList getAnnotationDefaultParameterValues() {
        if (!scanResult.scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableAnnotationInfo() before #scan()");
        }
        if (!isAnnotation()) {
            throw new IllegalArgumentException("Class is not an annotation: " + getName());
        }
        if (annotationDefaultParamValues == null) {
            return AnnotationParameterValueList.EMPTY_LIST;
        }
        if (!annotationDefaultParamValuesHasBeenConvertedToPrimitive) {
            annotationDefaultParamValues.convertWrapperArraysToPrimitiveArrays(this);
            annotationDefaultParamValuesHasBeenConvertedToPrimitive = true;
        }
        return annotationDefaultParamValues;
    }

    /**
     * Get the classes that have this class as an annotation.
     *
     * @return A list of standard classes and non-annotation interfaces that are annotated by this class, if this is
     *         an annotation class, or the empty list if none. Also handles the {@link Inherited} meta-annotation,
     *         which causes an annotation on a class to be inherited by all of its subclasses.
     */
    public ClassInfoList getClassesWithAnnotation() {
        if (!scanResult.scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableAnnotationInfo() before #scan()");
        }
        if (!isAnnotation()) {
            throw new IllegalArgumentException("Class is not an annotation: " + getName());
        }

        // Get classes that have this annotation
        final ReachableAndDirectlyRelatedClasses classesWithAnnotation = this
                .filterClassInfo(RelType.CLASSES_WITH_ANNOTATION, /* strictWhitelist = */ !isExternalClass);

        if (isInherited) {
            // If this is an inherited annotation, add into the result all subclasses of the annotated classes. 
            final Set<ClassInfo> classesWithAnnotationAndTheirSubclasses = new LinkedHashSet<>(
                    classesWithAnnotation.reachableClasses);
            for (final ClassInfo classWithAnnotation : classesWithAnnotation.reachableClasses) {
                classesWithAnnotationAndTheirSubclasses.addAll(classWithAnnotation.getSubclasses());
            }
            return new ClassInfoList(classesWithAnnotationAndTheirSubclasses,
                    classesWithAnnotation.directlyRelatedClasses, /* sortByName = */ true);
        } else {
            // If not inherited, only return the annotated classes
            return new ClassInfoList(classesWithAnnotation, /* sortByName = */ true);
        }
    }

    /**
     * Get the classes that have this class as a direct annotation.
     *
     * @return The list of classes that are directly (i.e. are not meta-annotated) annotated with the requested
     *         annotation, or the empty list if none.
     */
    ClassInfoList getClassesWithAnnotationDirectOnly() {
        return new ClassInfoList(
                this.filterClassInfo(RelType.CLASSES_WITH_ANNOTATION, /* strictWhitelist = */ !isExternalClass),
                /* sortByName = */ true);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Methods

    /**
     * Get the declared methods, constructors, and/or static initializer methods of the class.
     *
     * @param methodName
     *            the method name
     * @param getNormalMethods
     *            whether to get normal methods
     * @param getConstructorMethods
     *            whether to get constructor methods
     * @param getStaticInitializerMethods
     *            whether to get static initializer methods
     * @return the declared method info
     */
    private MethodInfoList getDeclaredMethodInfo(final String methodName, final boolean getNormalMethods,
            final boolean getConstructorMethods, final boolean getStaticInitializerMethods) {
        if (!scanResult.scanSpec.enableMethodInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableMethodInfo() before #scan()");
        }
        if (methodInfo == null) {
            return MethodInfoList.EMPTY_LIST;
        }
        if (methodName == null) {
            // If no method name is provided, filter for methods with the right type (normal method / constructor /
            // static initializer)
            final MethodInfoList methodInfoList = new MethodInfoList();
            for (final MethodInfo mi : methodInfo) {
                final String miName = mi.getName();
                final boolean isConstructor = "<init>".equals(miName);
                // (Currently static initializer methods are never returned by public methods)
                final boolean isStaticInitializer = "<clinit>".equals(miName);
                if ((isConstructor && getConstructorMethods) || (isStaticInitializer && getStaticInitializerMethods)
                        || (!isConstructor && !isStaticInitializer && getNormalMethods)) {
                    methodInfoList.add(mi);
                }
            }
            return methodInfoList;
        } else {
            // If method name is provided, filter for methods whose name matches, and ignore method type
            boolean hasMethodWithName = false;
            for (final MethodInfo f : methodInfo) {
                if (f.getName().equals(methodName)) {
                    hasMethodWithName = true;
                    break;
                }
            }
            if (!hasMethodWithName) {
                return MethodInfoList.EMPTY_LIST;
            }
            final MethodInfoList methodInfoList = new MethodInfoList();
            for (final MethodInfo mi : methodInfo) {
                if (mi.getName().equals(methodName)) {
                    methodInfoList.add(mi);
                }
            }
            return methodInfoList;
        }
    }

    /**
     * Get the methods, constructors, and/or static initializer methods of the class.
     *
     * @param methodName
     *            the method name
     * @param getNormalMethods
     *            whether to get normal methods
     * @param getConstructorMethods
     *            whether to get constructor methods
     * @param getStaticInitializerMethods
     *            whether to get static initializer methods
     * @return the method info
     */
    private MethodInfoList getMethodInfo(final String methodName, final boolean getNormalMethods,
            final boolean getConstructorMethods, final boolean getStaticInitializerMethods) {
        if (!scanResult.scanSpec.enableMethodInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableMethodInfo() before #scan()");
        }
        // Implement method/constructor overriding
        final MethodInfoList methodInfoList = new MethodInfoList();
        final Set<Entry<String, String>> nameAndTypeDescriptorSet = new HashSet<>();
        for (final ClassInfo ci : getOverrideOrder()) {
            for (final MethodInfo mi : ci.getDeclaredMethodInfo(methodName, getNormalMethods, getConstructorMethods,
                    getStaticInitializerMethods)) {
                // If method/constructor has not been overridden by method of same name and type descriptor 
                if (nameAndTypeDescriptorSet
                        .add(new SimpleEntry<>(mi.getName(), mi.getTypeDescriptor().toString()))) {
                    // Add method/constructor to output order
                    methodInfoList.add(mi);
                }
            }
        }
        return methodInfoList;
    }

    /**
     * Returns information on visible methods declared by this class, but not by its interfaces or superclasses,
     * that are not constructors. See also:
     * 
     * <ul>
     * <li>{@link #getMethodInfo(String)}
     * <li>{@link #getDeclaredMethodInfo(String)}
     * <li>{@link #getMethodInfo()}
     * <li>{@link #getConstructorInfo()}
     * <li>{@link #getDeclaredConstructorInfo()}
     * <li>{@link #getMethodAndConstructorInfo()}
     * <li>{@link #getDeclaredMethodAndConstructorInfo()}
     * </ul>
     * 
     * <p>
     * There may be more than one method of a given name with different type signatures, due to overloading.
     *
     * <p>
     * Requires that {@link ClassGraph#enableMethodInfo()} be called before scanning, otherwise throws
     * {@link IllegalArgumentException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * @return the list of {@link MethodInfo} objects for visible methods declared by this class, or the empty list
     *         if no methods were found.
     * @throws IllegalArgumentException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getDeclaredMethodInfo() {
        return getDeclaredMethodInfo(/* methodName = */ null, /* getNormalMethods = */ true,
                /* getConstructorMethods = */ false, /* getStaticInitializerMethods = */ false);
    }

    /**
     * Returns information on visible methods declared by this class, or by its interfaces or superclasses, that are
     * not constructors. See also:
     * 
     * <ul>
     * <li>{@link #getMethodInfo(String)}
     * <li>{@link #getDeclaredMethodInfo(String)}
     * <li>{@link #getDeclaredMethodInfo()}
     * <li>{@link #getConstructorInfo()}
     * <li>{@link #getDeclaredConstructorInfo()}
     * <li>{@link #getMethodAndConstructorInfo()}
     * <li>{@link #getDeclaredMethodAndConstructorInfo()}
     * </ul>
     * 
     * <p>
     * There may be more than one method of a given name with different type signatures, due to overloading.
     *
     * <p>
     * Requires that {@link ClassGraph#enableMethodInfo()} be called before scanning, otherwise throws
     * {@link IllegalArgumentException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * @return the list of {@link MethodInfo} objects for visible methods of this class, its interfaces and
     *         superclasses, or the empty list if no methods were found.
     * @throws IllegalArgumentException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getMethodInfo() {
        return getMethodInfo(/* methodName = */ null, /* getNormalMethods = */ true,
                /* getConstructorMethods = */ false, /* getStaticInitializerMethods = */ false);
    }

    /**
     * Returns information on visible constructors declared by this class, but not by its interfaces or
     * superclasses. Constructors have the method name of {@code "<init>"}. See also:
     * 
     * <ul>
     * <li>{@link #getMethodInfo(String)}
     * <li>{@link #getDeclaredMethodInfo(String)}
     * <li>{@link #getMethodInfo()}
     * <li>{@link #getDeclaredMethodInfo()}
     * <li>{@link #getConstructorInfo()}
     * <li>{@link #getMethodAndConstructorInfo()}
     * <li>{@link #getDeclaredMethodAndConstructorInfo()}
     * </ul>
     * 
     * <p>
     * There may be more than one constructor of a given name with different type signatures, due to overloading.
     *
     * <p>
     * Requires that {@link ClassGraph#enableMethodInfo()} be called before scanning, otherwise throws
     * {@link IllegalArgumentException}.
     *
     * <p>
     * By default only returns information for public constructors, unless
     * {@link ClassGraph#ignoreMethodVisibility()} was called before the scan.
     *
     * @return the list of {@link MethodInfo} objects for visible constructors declared by this class, or the empty
     *         list if no constructors were found or visible.
     * @throws IllegalArgumentException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getDeclaredConstructorInfo() {
        return getDeclaredMethodInfo(/* methodName = */ null, /* getNormalMethods = */ false,
                /* getConstructorMethods = */ true, /* getStaticInitializerMethods = */ false);
    }

    /**
     * Returns information on visible constructors declared by this class, or by its interfaces or superclasses.
     * Constructors have the method name of {@code "<init>"}. See also:
     * 
     * <ul>
     * <li>{@link #getMethodInfo(String)}
     * <li>{@link #getDeclaredMethodInfo(String)}
     * <li>{@link #getMethodInfo()}
     * <li>{@link #getDeclaredMethodInfo()}
     * <li>{@link #getDeclaredConstructorInfo()}
     * <li>{@link #getMethodAndConstructorInfo()}
     * <li>{@link #getDeclaredMethodAndConstructorInfo()}
     * </ul>
     * 
     * <p>
     * There may be more than one method of a given name with different type signatures, due to overloading.
     *
     * <p>
     * Requires that {@link ClassGraph#enableMethodInfo()} be called before scanning, otherwise throws
     * {@link IllegalArgumentException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * @return the list of {@link MethodInfo} objects for visible constructors of this class and its superclasses,
     *         or the empty list if no methods were found.
     * @throws IllegalArgumentException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getConstructorInfo() {
        return getMethodInfo(/* methodName = */ null, /* getNormalMethods = */ false,
                /* getConstructorMethods = */ true, /* getStaticInitializerMethods = */ false);
    }

    /**
     * Returns information on visible methods and constructors declared by this class, but not by its interfaces or
     * superclasses. Constructors have the method name of {@code "<init>"} and static initializer blocks have the
     * name of {@code "<clinit>"}. See also:
     * 
     * <ul>
     * <li>{@link #getMethodInfo(String)}
     * <li>{@link #getDeclaredMethodInfo(String)}
     * <li>{@link #getMethodInfo()}
     * <li>{@link #getDeclaredMethodInfo()}
     * <li>{@link #getConstructorInfo()}
     * <li>{@link #getDeclaredConstructorInfo()}
     * <li>{@link #getMethodAndConstructorInfo()}
     * </ul>
     * 
     * <p>
     * There may be more than one method or constructor or method of a given name with different type signatures,
     * due to overloading.
     *
     * <p>
     * Requires that {@link ClassGraph#enableMethodInfo()} be called before scanning, otherwise throws
     * {@link IllegalArgumentException}.
     *
     * <p>
     * By default only returns information for public methods and constructors, unless
     * {@link ClassGraph#ignoreMethodVisibility()} was called before the scan. If method visibility is ignored, the
     * result may include a reference to a private static class initializer block, with a method name of
     * {@code "<clinit>"}.
     *
     * @return the list of {@link MethodInfo} objects for visible methods and constructors of this class, or the
     *         empty list if no methods or constructors were found or visible.
     * @throws IllegalArgumentException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getDeclaredMethodAndConstructorInfo() {
        return getDeclaredMethodInfo(/* methodName = */ null, /* getNormalMethods = */ true,
                /* getConstructorMethods = */ true, /* getStaticInitializerMethods = */ false);
    }

    /**
     * Returns information on visible constructors declared by this class, or by its interfaces or superclasses.
     * Constructors have the method name of {@code "<init>"} and static initializer blocks have the name of
     * {@code "<clinit>"}. See also:
     * 
     * <ul>
     * <li>{@link #getMethodInfo(String)}
     * <li>{@link #getDeclaredMethodInfo(String)}
     * <li>{@link #getMethodInfo()}
     * <li>{@link #getDeclaredMethodInfo()}
     * <li>{@link #getConstructorInfo()}
     * <li>{@link #getDeclaredConstructorInfo()}
     * <li>{@link #getDeclaredMethodAndConstructorInfo()}
     * </ul>
     * 
     * <p>
     * There may be more than one method of a given name with different type signatures, due to overloading.
     *
     * <p>
     * Requires that {@link ClassGraph#enableMethodInfo()} be called before scanning, otherwise throws
     * {@link IllegalArgumentException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * @return the list of {@link MethodInfo} objects for visible methods and constructors of this class, its
     *         interfaces and superclasses, or the empty list if no methods were found.
     * @throws IllegalArgumentException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getMethodAndConstructorInfo() {
        return getMethodInfo(/* methodName = */ null, /* getNormalMethods = */ true,
                /* getConstructorMethods = */ true, /* getStaticInitializerMethods = */ false);
    }

    /**
     * Returns information on the method(s) or constructor(s) of the given name declared by this class, but not by
     * its interfaces or superclasses. Constructors have the method name of {@code "<init>"}. See also:
     * 
     * <ul>
     * <li>{@link #getMethodInfo(String)}
     * <li>{@link #getMethodInfo()}
     * <li>{@link #getDeclaredMethodInfo()}
     * <li>{@link #getConstructorInfo()}
     * <li>{@link #getDeclaredConstructorInfo()}
     * <li>{@link #getMethodAndConstructorInfo()}
     * <li>{@link #getDeclaredMethodAndConstructorInfo()}
     * </ul>
     *
     * <p>
     * Requires that {@link ClassGraph#enableMethodInfo()} be called before scanning, otherwise throws
     * {@link IllegalArgumentException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * <p>
     * May return info for multiple methods with the same name (with different type signatures).
     *
     * @param methodName
     *            The method name to query.
     * @return a list of {@link MethodInfo} objects for the method(s) with the given name, or the empty list if the
     *         method was not found in this class (or is not visible).
     * @throws IllegalArgumentException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getDeclaredMethodInfo(final String methodName) {
        return getDeclaredMethodInfo(methodName, /* ignored */ false, /* ignored */ false, /* ignored */ false);
    }

    /**
     * Returns information on the method(s) or constructor(s) of the given name declared by this class, but not by
     * its interfaces or superclasses. Constructors have the method name of {@code "<init>"}. See also:
     * 
     * <ul>
     * <li>{@link #getDeclaredMethodInfo(String)}
     * <li>{@link #getMethodInfo()}
     * <li>{@link #getDeclaredMethodInfo()}
     * <li>{@link #getConstructorInfo()}
     * <li>{@link #getDeclaredConstructorInfo()}
     * <li>{@link #getMethodAndConstructorInfo()}
     * <li>{@link #getDeclaredMethodAndConstructorInfo()}
     * </ul>
     *
     * <p>
     * Requires that {@link ClassGraph#enableMethodInfo()} be called before scanning, otherwise throws
     * {@link IllegalArgumentException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * <p>
     * May return info for multiple methods with the same name (with different type signatures).
     *
     * @param methodName
     *            The method name to query.
     * @return a list of {@link MethodInfo} objects for the method(s) with the given name, or the empty list if the
     *         method was not found in this class (or is not visible).
     * @throws IllegalArgumentException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getMethodInfo(final String methodName) {
        return getMethodInfo(methodName, /* ignored */ false, /* ignored */ false, /* ignored */ false);
    }

    /**
     * Get all method annotations.
     *
     * @return A list of all annotations or meta-annotations on methods declared by the class, (not including
     *         methods declared by the interfaces or superclasses of this class), as a list of {@link ClassInfo}
     *         objects, or the empty list if none. N.B. these annotations do not contain specific annotation
     *         parameters -- call {@link MethodInfo#getAnnotationInfo()} to get details on specific method
     *         annotation instances.
     */
    public ClassInfoList getMethodAnnotations() {
        return getFieldOrMethodAnnotations(RelType.METHOD_ANNOTATIONS);
    }

    /**
     * Get all method parameter annotations.
     *
     * @return A list of all annotations or meta-annotations on methods declared by the class, (not including
     *         methods declared by the interfaces or superclasses of this class), as a list of {@link ClassInfo}
     *         objects, or the empty list if none. N.B. these annotations do not contain specific annotation
     *         parameters -- call {@link MethodInfo#getAnnotationInfo()} to get details on specific method
     *         annotation instances.
     */
    public ClassInfoList getMethodParameterAnnotations() {
        return getFieldOrMethodAnnotations(RelType.METHOD_PARAMETER_ANNOTATIONS);
    }

    /**
     * Get all classes that have this class as a method annotation, and their subclasses, if the method is
     * non-private.
     *
     * @return A list of classes that have a declared method with this annotation or meta-annotation, or the empty
     *         list if none.
     */
    public ClassInfoList getClassesWithMethodAnnotation() {
        // Get all classes that have a method annotated or meta-annotated with this annotation
        final Set<ClassInfo> classesWithMethodAnnotation = new HashSet<>(
                getClassesWithFieldOrMethodAnnotation(RelType.CLASSES_WITH_METHOD_ANNOTATION));
        // Add subclasses of all classes with a method that is non-privately annotated or meta-annotated with
        // this annotation (non-private methods are inherited)
        for (final ClassInfo classWithNonprivateMethodAnnotationOrMetaAnnotation : //
        getClassesWithFieldOrMethodAnnotation(RelType.CLASSES_WITH_NONPRIVATE_METHOD_ANNOTATION)) {
            classesWithMethodAnnotation.addAll(classWithNonprivateMethodAnnotationOrMetaAnnotation.getSubclasses());
        }
        return new ClassInfoList(classesWithMethodAnnotation,
                new HashSet<>(getClassesWithMethodAnnotationDirectOnly()), /* sortByName = */ true);
    }

    /**
     * Get all classes that have this class as a method parameter annotation, and their subclasses, if the method is
     * non-private.
     *
     * @return A list of classes that have a declared method with a parameter that is annotated with this annotation
     *         or meta-annotation, or the empty list if none.
     */
    public ClassInfoList getClassesWithMethodParameterAnnotation() {
        // Get all classes that have a method annotated or meta-annotated with this annotation
        final Set<ClassInfo> classesWithMethodParameterAnnotation = new HashSet<>(
                getClassesWithFieldOrMethodAnnotation(RelType.CLASSES_WITH_METHOD_PARAMETER_ANNOTATION));
        // Add subclasses of all classes with a method that is non-privately annotated or meta-annotated with
        // this annotation (non-private methods are inherited)
        for (final ClassInfo classWithNonprivateMethodParameterAnnotationOrMetaAnnotation : //
        getClassesWithFieldOrMethodAnnotation(RelType.CLASSES_WITH_NONPRIVATE_METHOD_PARAMETER_ANNOTATION)) {
            classesWithMethodParameterAnnotation
                    .addAll(classWithNonprivateMethodParameterAnnotationOrMetaAnnotation.getSubclasses());
        }
        return new ClassInfoList(classesWithMethodParameterAnnotation,
                new HashSet<>(getClassesWithMethodParameterAnnotationDirectOnly()), /* sortByName = */ true);
    }

    /**
     * Get the classes that have this class as a direct method annotation.
     *
     * @return A list of classes that declare methods that are directly annotated (i.e. are not meta-annotated) with
     *         the requested method annotation, or the empty list if none.
     */
    ClassInfoList getClassesWithMethodAnnotationDirectOnly() {
        return new ClassInfoList(this.filterClassInfo(RelType.CLASSES_WITH_METHOD_ANNOTATION,
                /* strictWhitelist = */ !isExternalClass), /* sortByName = */ true);
    }

    /**
     * Get the classes that have this class as a direct method parameter annotation.
     *
     * @return A list of classes that declare methods with parameters that are directly annotated (i.e. are not
     *         meta-annotated) with the requested method annotation, or the empty list if none.
     */
    ClassInfoList getClassesWithMethodParameterAnnotationDirectOnly() {
        return new ClassInfoList(this.filterClassInfo(RelType.CLASSES_WITH_METHOD_PARAMETER_ANNOTATION,
                /* strictWhitelist = */ !isExternalClass), /* sortByName = */ true);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fields

    /**
     * Returns information on all visible fields declared by this class, but not by its superclasses. See also:
     * 
     * <ul>
     * <li>{@link #getFieldInfo(String)}
     * <li>{@link #getDeclaredFieldInfo(String)}
     * <li>{@link #getFieldInfo()}
     * </ul>
     *
     * <p>
     * Requires that {@link ClassGraph#enableFieldInfo()} be called before scanning, otherwise throws
     * {@link IllegalArgumentException}.
     *
     * <p>
     * By default only returns information for public fields, unless {@link ClassGraph#ignoreFieldVisibility()} was
     * called before the scan.
     *
     * @return the list of FieldInfo objects for visible fields declared by this class, or the empty list if no
     *         fields were found or visible.
     * @throws IllegalArgumentException
     *             if {@link ClassGraph#enableFieldInfo()} was not called prior to initiating the scan.
     */
    public FieldInfoList getDeclaredFieldInfo() {
        if (!scanResult.scanSpec.enableFieldInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableFieldInfo() before #scan()");
        }
        return fieldInfo == null ? FieldInfoList.EMPTY_LIST : fieldInfo;
    }

    /**
     * Returns information on all visible fields declared by this class, or by its superclasses. See also:
     * 
     * <ul>
     * <li>{@link #getFieldInfo(String)}
     * <li>{@link #getDeclaredFieldInfo(String)}
     * <li>{@link #getDeclaredFieldInfo()}
     * </ul>
     *
     * <p>
     * Requires that {@link ClassGraph#enableFieldInfo()} be called before scanning, otherwise throws
     * {@link IllegalArgumentException}.
     *
     * <p>
     * By default only returns information for public fields, unless {@link ClassGraph#ignoreFieldVisibility()} was
     * called before the scan.
     *
     * @return the list of FieldInfo objects for visible fields of this class or its superclases, or the empty list
     *         if no fields were found or visible.
     * @throws IllegalArgumentException
     *             if {@link ClassGraph#enableFieldInfo()} was not called prior to initiating the scan.
     */
    public FieldInfoList getFieldInfo() {
        if (!scanResult.scanSpec.enableFieldInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableFieldInfo() before #scan()");
        }
        // Implement field overriding
        final FieldInfoList fieldInfoList = new FieldInfoList();
        final Set<String> fieldNameSet = new HashSet<>();
        for (final ClassInfo ci : getOverrideOrder()) {
            for (final FieldInfo fi : ci.getDeclaredFieldInfo()) {
                // If field has not been overridden by field of same name 
                if (fieldNameSet.add(fi.getName())) {
                    // Add field to output order
                    fieldInfoList.add(fi);
                }
            }
        }
        return fieldInfoList;
    }

    /**
     * Returns information on the named field declared by the class, but not by its superclasses. See also:
     * 
     * <ul>
     * <li>{@link #getFieldInfo(String)}
     * <li>{@link #getFieldInfo()}
     * <li>{@link #getDeclaredFieldInfo()}
     * </ul>
     *
     * <p>
     * Requires that {@link ClassGraph#enableFieldInfo()} be called before scanning, otherwise throws
     * {@link IllegalArgumentException}.
     *
     * <p>
     * By default only returns information for public fields, unless {@link ClassGraph#ignoreFieldVisibility()} was
     * called before the scan.
     *
     * @param fieldName
     *            The field name.
     * @return the {@link FieldInfo} object for the named field declared by this class, or null if the field was not
     *         found in this class (or is not visible).
     * @throws IllegalArgumentException
     *             if {@link ClassGraph#enableFieldInfo()} was not called prior to initiating the scan.
     */
    public FieldInfo getDeclaredFieldInfo(final String fieldName) {
        if (!scanResult.scanSpec.enableFieldInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableFieldInfo() before #scan()");
        }
        if (fieldInfo == null) {
            return null;
        }
        for (final FieldInfo fi : fieldInfo) {
            if (fi.getName().equals(fieldName)) {
                return fi;
            }
        }
        return null;
    }

    /**
     * Returns information on the named filed declared by this class, or by its superclasses. See also:
     * 
     * <ul>
     * <li>{@link #getDeclaredFieldInfo(String)}
     * <li>{@link #getFieldInfo()}
     * <li>{@link #getDeclaredFieldInfo()}
     * </ul>
     *
     * <p>
     * Requires that {@link ClassGraph#enableFieldInfo()} be called before scanning, otherwise throws
     * {@link IllegalArgumentException}.
     *
     * <p>
     * By default only returns information for public fields, unless {@link ClassGraph#ignoreFieldVisibility()} was
     * called before the scan.
     *
     * @param fieldName
     *            The field name.
     * @return the {@link FieldInfo} object for the named field of this class or its superclases, or the empty list
     *         if no fields were found or visible.
     * @throws IllegalArgumentException
     *             if {@link ClassGraph#enableFieldInfo()} was not called prior to initiating the scan.
     */
    public FieldInfo getFieldInfo(final String fieldName) {
        if (!scanResult.scanSpec.enableFieldInfo) {
            throw new IllegalArgumentException("Please call ClassGraph#enableFieldInfo() before #scan()");
        }
        // Implement field overriding
        for (final ClassInfo ci : getOverrideOrder()) {
            final FieldInfo fi = ci.getDeclaredFieldInfo(fieldName);
            if (fi != null) {
                return fi;
            }
        }
        return null;
    }

    /**
     * Get all field annotations.
     *
     * @return A list of all annotations on fields of this class, or the empty list if none. N.B. these annotations
     *         do not contain specific annotation parameters -- call {@link FieldInfo#getAnnotationInfo()} to get
     *         details on specific field annotation instances.
     */
    public ClassInfoList getFieldAnnotations() {
        return getFieldOrMethodAnnotations(RelType.FIELD_ANNOTATIONS);
    }

    /**
     * Get the classes that have this class as a field annotation or meta-annotation.
     *
     * @return A list of classes that have a field with this annotation or meta-annotation, or the empty list if
     *         none.
     */
    public ClassInfoList getClassesWithFieldAnnotation() {
        // Get all classes that have a field annotated or meta-annotated with this annotation
        final Set<ClassInfo> classesWithMethodAnnotation = new HashSet<>(
                getClassesWithFieldOrMethodAnnotation(RelType.CLASSES_WITH_FIELD_ANNOTATION));
        // Add subclasses of all classes with a field that is non-privately annotated or meta-annotated with
        // this annotation (non-private fields are inherited)
        for (final ClassInfo classWithNonprivateMethodAnnotationOrMetaAnnotation : //
        getClassesWithFieldOrMethodAnnotation(RelType.CLASSES_WITH_NONPRIVATE_FIELD_ANNOTATION)) {
            classesWithMethodAnnotation.addAll(classWithNonprivateMethodAnnotationOrMetaAnnotation.getSubclasses());
        }
        return new ClassInfoList(classesWithMethodAnnotation,
                new HashSet<>(getClassesWithMethodAnnotationDirectOnly()), /* sortByName = */ true);
    }

    /**
     * Get the classes that have this class as a direct field annotation.
     *
     * @return A list of classes that declare fields that are directly annotated (i.e. are not meta-annotated) with
     *         the requested method annotation, or the empty list if none.
     */
    ClassInfoList getClassesWithFieldAnnotationDirectOnly() {
        return new ClassInfoList(this.filterClassInfo(RelType.CLASSES_WITH_FIELD_ANNOTATION,
                /* strictWhitelist = */ !isExternalClass), /* sortByName = */ true);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the parsed type signature for the class.
     *
     * @return The parsed type signature for the class, including any generic type parameters, or null if not
     *         available (probably indicating the class is not generic).
     */
    public ClassTypeSignature getTypeSignature() {
        if (typeSignatureStr == null) {
            return null;
        }
        if (typeSignature == null) {
            try {
                typeSignature = ClassTypeSignature.parse(typeSignatureStr, this);
                typeSignature.setScanResult(scanResult);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeSignature;
    }

    /**
     * Get the type signature string for the class.
     *
     * @return The type signature string for the class, including any generic type parameters, or null if not
     *         available (probably indicating the class is not generic).
     */
    public String getTypeSignatureStr() {
        return typeSignatureStr;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the {@link URI} of the classpath element that this class was found within.
     *
     * @return The {@link URI} of the classpath element that this class was found within.
     * @throws IllegalArgumentException
     *             if the classpath element does not have a valid URI (e.g. for modules whose location URI is null).
     */
    public URI getClasspathElementURI() {
        if (classpathElement == null) {
            throw new IllegalArgumentException("Classpath element is not known for this classpath element");
        }
        return classpathElement.getURI();
    }

    /**
     * Get the {@link URL} of the classpath element or module that this class was found within. Use
     * {@link #getClasspathElementURI()} instead if the resource may have come from a system module, or if this is a
     * jlink'd runtime image, since "jrt:" URI schemes used by system modules and jlink'd runtime images are not
     * suppored by {@link URL}, and this will cause {@link IllegalArgumentException} to be thrown.
     *
     * @return The {@link URL} of the classpath element that this class was found within.
     * @throws IllegalArgumentException
     *             if the classpath element URI cannot be converted to a {@link URL} (in particular, if the URI has
     *             a {@code jrt:/} scheme).
     */
    public URL getClasspathElementURL() {
        if (classpathElement == null) {
            throw new IllegalArgumentException("Classpath element is not known for this classpath element");
        }
        try {
            return classpathElement.getURI().toURL();
        } catch (final MalformedURLException e) {
            throw new IllegalArgumentException("Could not get classpath element URL", e);
        }
    }

    /**
     * Get the {@link File} for the classpath element package root dir or jar that this class was found within, or
     * null if this class was found in a module. (See also {@link #getModuleRef}.)
     *
     * @return The {@link File} for the classpath element package root dir or jar that this class was found within,
     *         or null if this class was found in a module (see {@link #getModuleRef}). May also return null if the
     *         classpath element was an http/https URL, and the jar was downloaded directly to RAM, rather than to a
     *         temp file on disk (e.g. if the temp dir is not writeable).
     */
    public File getClasspathElementFile() {
        if (classpathElement == null) {
            throw new IllegalArgumentException("Classpath element is not known for this classpath element");
        }
        return classpathElement.getFile();
    }

    /**
     * Get the module that this class was found within, as a {@link ModuleRef}, or null if this class was found in a
     * directory or jar in the classpath. (See also {@link #getClasspathElementFile()}.)
     *
     * @return The module that this class was found within, as a {@link ModuleRef}, or null if this class was found
     *         in a directory or jar in the classpath. (See also {@link #getClasspathElementFile()}.)
     */
    public ModuleRef getModuleRef() {
        if (classpathElement == null) {
            throw new IllegalArgumentException("Classpath element is not known for this classpath element");
        }
        return classpathElement instanceof ClasspathElementModule
                ? ((ClasspathElementModule) classpathElement).getModuleRef()
                : null;
    }

    /**
     * The {@link Resource} for the classfile of this class.
     *
     * @return The {@link Resource} for the classfile of this class. Returns null if the classfile for this class
     *         was not actually read during the scan, e.g. because this class was not itself whitelisted, but was
     *         referenced by a whitelisted class.
     */
    public Resource getResource() {
        return classfileResource;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Obtain a {@code Class<?>} reference for the class named by this {@link ClassInfo} object, casting it to the
     * requested interface or superclass type. Causes the ClassLoader to load the class, if it is not already
     * loaded.
     * 
     * <p>
     * <b>Important note:</b> since {@code superclassOrInterfaceType} is a class reference for an already-loaded
     * class, it is critical that {@code superclassOrInterfaceType} is loaded by the same classloader as the class
     * referred to by this {@code ClassInfo} object, otherwise the class cast will fail.
     *
     * @param <T>
     *            the superclass or interface type
     * @param superclassOrInterfaceType
     *            The {@link Class} reference for the type to cast the loaded class to.
     * @param ignoreExceptions
     *            If true, return null if any exceptions or errors thrown during classloading, or if attempting to
     *            cast the resulting {@code Class<?>} reference to the requested superclass or interface type fails.
     *            If false, {@link IllegalArgumentException} is thrown if the class could not be loaded or could not
     *            be cast to the requested type.
     * @return The class reference, or null, if ignoreExceptions is true and there was an exception or error loading
     *         the class.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and there were problems loading the class, or casting it to the
     *             requested type.
     */
    @Override
    public <T> Class<T> loadClass(final Class<T> superclassOrInterfaceType, final boolean ignoreExceptions) {
        return super.loadClass(superclassOrInterfaceType, ignoreExceptions);
    }

    /**
     * Obtain a {@code Class<?>} reference for the class named by this {@link ClassInfo} object, casting it to the
     * requested interface or superclass type. Causes the ClassLoader to load the class, if it is not already
     * loaded.
     * 
     * <p>
     * <b>Important note:</b> since {@code superclassOrInterfaceType} is a class reference for an already-loaded
     * class, it is critical that {@code superclassOrInterfaceType} is loaded by the same classloader as the class
     * referred to by this {@code ClassInfo} object, otherwise the class cast will fail.
     *
     * @param <T>
     *            The superclass or interface type
     * @param superclassOrInterfaceType
     *            The type to cast the loaded class to.
     * @return The class reference.
     * @throws IllegalArgumentException
     *             if there were problems loading the class or casting it to the requested type.
     */
    @Override
    public <T> Class<T> loadClass(final Class<T> superclassOrInterfaceType) {
        return super.loadClass(superclassOrInterfaceType, /* ignoreExceptions = */ false);
    }

    /**
     * Obtain a {@code Class<?>} reference for the class named by this {@link ClassInfo} object. Causes the
     * ClassLoader to load the class, if it is not already loaded.
     *
     * @param ignoreExceptions
     *            Whether or not to ignore exceptions
     * @return The class reference, or null, if ignoreExceptions is true and there was an exception or error loading
     *         the class.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and there were problems loading the class.
     */
    @Override
    public Class<?> loadClass(final boolean ignoreExceptions) {
        return super.loadClass(ignoreExceptions);
    }

    /**
     * Obtain a {@code Class<?>} reference for the class named by this {@link ClassInfo} object. Causes the
     * ClassLoader to load the class, if it is not already loaded.
     * 
     * @return The class reference.
     * @throws IllegalArgumentException
     *             if there were problems loading the class.
     */
    @Override
    public Class<?> loadClass() {
        return super.loadClass(/* ignoreExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassName()
     */
    @Override
    protected String getClassName() {
        return name;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#getClassInfo()
     */
    @Override
    protected ClassInfo getClassInfo() {
        return this;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (this.typeSignature != null) {
            this.typeSignature.setScanResult(scanResult);
        }
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
        if (annotationDefaultParamValues != null) {
            for (final AnnotationParameterValue apv : annotationDefaultParamValues) {
                apv.setScanResult(scanResult);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Handle {@link Repeatable} annotations.
     *
     * @param allRepeatableAnnotationNames
     *            the names of all repeatable annotations
     */
    void handleRepeatableAnnotations(final Set<String> allRepeatableAnnotationNames) {
        if (annotationInfo != null) {
            annotationInfo.handleRepeatableAnnotations(allRepeatableAnnotationNames, this,
                    RelType.CLASS_ANNOTATIONS, RelType.CLASSES_WITH_ANNOTATION, null);
        }
        if (fieldInfo != null) {
            for (final FieldInfo fi : fieldInfo) {
                fi.handleRepeatableAnnotations(allRepeatableAnnotationNames);
            }
        }
        if (methodInfo != null) {
            for (final MethodInfo mi : methodInfo) {
                mi.handleRepeatableAnnotations(allRepeatableAnnotationNames);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add names of classes referenced by this class.
     *
     * @param refdClassNames
     *            the referenced class names
     */
    void addReferencedClassNames(final Set<String> refdClassNames) {
        if (this.referencedClassNames == null) {
            this.referencedClassNames = refdClassNames;
        } else {
            this.referencedClassNames.addAll(refdClassNames);
        }
    }

    /**
     * Get {@link ClassInfo} objects for any classes referenced in this class' type descriptor, or the type
     * descriptors of fields, methods or annotations.
     *
     * @param classNameToClassInfo
     *            the map from class name to {@link ClassInfo}.
     * @param refdClassInfo
     *            the referenced class info
     */
    @Override
    protected void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo) {
        // Add this class to the set of references
        super.findReferencedClassInfo(classNameToClassInfo, refdClassInfo);

        if (this.referencedClassNames != null) {
            for (final String refdClassName : this.referencedClassNames) {
                final ClassInfo classInfo = ClassInfo.getOrCreateClassInfo(refdClassName, classNameToClassInfo);
                classInfo.setScanResult(scanResult);
                refdClassInfo.add(classInfo);
            }
        }
        getMethodInfo().findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
        getFieldInfo().findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
        getAnnotationInfo().findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
        if (annotationDefaultParamValues != null) {
            annotationDefaultParamValues.findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
        }
        final ClassTypeSignature classSig = getTypeSignature();
        if (classSig != null) {
            classSig.findReferencedClassInfo(classNameToClassInfo, refdClassInfo);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Set the list of ClassInfo objects for classes referenced by this class.
     *
     * @param refdClasses
     *            the referenced classes
     */
    void setReferencedClasses(final ClassInfoList refdClasses) {
        this.referencedClasses = refdClasses;
    }

    /**
     * Get the class dependencies.
     *
     * @return A {@link ClassInfoList} of {@link ClassInfo} objects for all classes referenced by this class. Note
     *         that you need to call {@link ClassGraph#enableInterClassDependencies()} before
     *         {@link ClassGraph#scan()} for this method to work. You should also call
     *         {@link ClassGraph#enableExternalClasses()} before {@link ClassGraph#scan()} if you want
     *         non-whitelisted classes to appear in the result.
     */
    public ClassInfoList getClassDependencies() {
        if (!scanResult.scanSpec.enableInterClassDependencies) {
            throw new IllegalArgumentException(
                    "Please call ClassGraph#enableInterClassDependencies() before #scan()");
        }
        return referencedClasses == null ? ClassInfoList.EMPTY_LIST : referencedClasses;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Compare based on class name.
     *
     * @param o
     *            the other object
     * @return the comparison result
     */
    @Override
    public int compareTo(final ClassInfo o) {
        return this.name.compareTo(o.name);
    }

    /**
     * Use class name for equals().
     *
     * @param obj
     *            the other object
     * @return Whether the objects were equal.
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ClassInfo)) {
            return false;
        }
        final ClassInfo other = (ClassInfo) obj;
        return name.equals(other.name);
    }

    /**
     * Use hash code of class name.
     *
     * @return the hashcode
     */
    @Override
    public int hashCode() {
        return name == null ? 0 : name.hashCode();
    }

    /**
     * To string.
     *
     * @param typeNameOnly
     *            if true, convert type name to string only.
     * @return the string
     */
    protected String toString(final boolean typeNameOnly) {
        final ClassTypeSignature typeSig = getTypeSignature();
        if (typeSig != null) {
            // Generic classes
            return typeSig.toString(name, typeNameOnly, modifiers, isAnnotation(), isInterface());
        } else {
            // Non-generic classes
            final StringBuilder buf = new StringBuilder();
            if (typeNameOnly) {
                buf.append(name);
            } else {
                TypeUtils.modifiersToString(modifiers, ModifierType.CLASS, /* ignored */ false, buf);
                if (buf.length() > 0) {
                    buf.append(' ');
                }
                buf.append(isRecord() ? "record " //
                        : isEnum() ? "enum " //
                                : isAnnotation() ? "@interface " //
                                        : isInterface() ? "interface " //
                                                : "class ");
                buf.append(name);
                final ClassInfo superclass = getSuperclass();
                if (superclass != null && !superclass.getName().equals("java.lang.Object")) {
                    buf.append(" extends ").append(superclass.toString(/* typeNameOnly = */ true));
                }
                final Set<ClassInfo> interfaces = this.filterClassInfo(RelType.IMPLEMENTED_INTERFACES,
                        /* strictWhitelist = */ false).directlyRelatedClasses;
                if (!interfaces.isEmpty()) {
                    buf.append(isInterface() ? " extends " : " implements ");
                    boolean first = true;
                    for (final ClassInfo iface : interfaces) {
                        if (first) {
                            first = false;
                        } else {
                            buf.append(", ");
                        }
                        buf.append(iface.toString(/* typeNameOnly = */ true));
                    }
                }
            }
            return buf.toString();
        }
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return toString(false);
    }
}
