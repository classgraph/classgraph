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

import static io.github.classgraph.PotentiallyUnmodifiableList.unmodifiable;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.module.ModuleReference;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import io.github.classgraph.TypeUtils.ModifierType;
import io.github.classgraph.Classfile.ClassContainment;
import io.github.classgraph.Classfile.ClassTypeAnnotationDecorator;
import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * Holds metadata about a class encountered during a scan.
 *
 * <p>
 * A {@link ClassInfo} object is also created for each class that was merely referred to by a scanned class as a
 * superclass, implemented interface or annotation, but whose own classfile was never read during the scan, because
 * the class was not on the scanned classpath -- {@link Object} is the most common example, when only the user's own
 * packages were accepted. These placeholder objects hold only what could be inferred from the classes that referred
 * to them: {@link #getResource()} returns null for them, accessors that depend on the classfile having been read
 * return empty results, and the accessors that report where the class was found
 * ({@link #getClasspathElementFile()}, {@link #getClasspathElementURI()}, {@link #getClasspathElementURL()} and
 * {@link #getModuleReference()}) throw {@link IllegalStateException}.
 *
 * <p>
 * Separately, {@link #isExternalClass()} reports whether a class lies outside the accepted packages. An external
 * class may still have been scanned, if its classfile happened to be reachable on the scanned classpath, in which
 * case none of the above applies to it. External classes are only returned by queries that report all scanned
 * classes, such as {@link ScanResult#getAllClasses()}, if {@link ClassGraph#enableExternalClasses()} was called
 * before scanning.
 */
public class ClassInfo extends ScanResultObject implements Comparable<ClassInfo>, HasName, HasAnnotations {
    /** The name of the class. */
    String name;

    /** Class modifier flags, e.g. Modifier.PUBLIC */
    private int modifiers;

    /** True if the class is a record. */
    private boolean isRecord;

    /**
     * This annotation has the {@link Inherited} meta-annotation, which means that any class that this annotation is
     * applied to also implicitly causes the annotation to annotate all subclasses too.
     */
    boolean isInherited;

    /** The minor version of the classfile format for this class' classfile. */
    private int classfileMinorVersion;

    /** The major version of the classfile format for this class' classfile. */
    private int classfileMajorVersion;

    /** The class type signature string. */
    @Nullable
    String typeSignatureStr;

    /** The class type signature, parsed. */
    private @Nullable ClassTypeSignature typeSignature;

    /** The synthetic class type descriptor. */
    private @Nullable ClassTypeSignature typeDescriptor;

    /** The name of the source file this class has been compiled from */
    private @Nullable String sourceFile;

    /** The fully-qualified defining method name, for anonymous inner classes. */
    private @Nullable String fullyQualifiedDefiningMethodName;

    /**
     * If true, this class is only being referenced by another class' classfile as a superclass / implemented
     * interface / annotation, but this class is not itself an accepted (non-rejected) class, or in a accepted
     * (non-rejected) package.
     *
     * If false, this classfile was matched during scanning (i.e. its classfile contents read), i.e. this class is a
     * accepted (and non-rejected) class in an accepted (and non-rejected) package.
     */
    boolean isExternalClass = true;

    /**
     * Set to true when the class is actually scanned (as opposed to just referenced as a superclass, interface or
     * annotation of a scanned class).
     */
    boolean isScannedClass;

    /** The classpath element that this class was found within. */
    @Nullable
    ClasspathElement classpathElement;

    /** The {@link Resource} for the classfile of this class. */
    @Nullable
    Resource classfileResource;

    // N.B. the classloader this class was found under is deliberately not held here -- it is read on demand from
    // classpathElement, which references it weakly, so that a ScanResult (or a ClassInfo object that outlives it)
    // cannot keep a classloader alive

    /** Info on the class module. */
    @Nullable
    ModuleInfo moduleInfo;

    /** Info on the package containing the class. */
    @Nullable
    PackageInfo packageInfo;

    /** Info on class annotations, including optional annotation param values. */
    @Nullable
    AnnotationInfoList annotationInfo;

    /** Info on fields. */
    @Nullable
    FieldInfoList fieldInfo;

    /** Info on fields. */
    @Nullable
    MethodInfoList methodInfo;

    /** For annotations, the default values of parameters. */
    @Nullable
    AnnotationParameterValueList annotationDefaultParamValues;

    /**
     * The type annotation decorators for the {@link ClassTypeSignature} instance.
     */
    @Nullable
    List<ClassTypeAnnotationDecorator> typeAnnotationDecorators;

    /**
     * Names of classes referenced by this class in class refs and type signatures in the constant pool of the
     * classfile.
     */
    private @Nullable Set<String> referencedClassNames;

    /**
     * A list of ClassInfo objects for classes referenced by this class. Derived from {@link #referencedClassNames}
     * when the relevant {@link ClassInfo} objects are created.
     */
    private @Nullable ClassInfoList referencedClasses;

    /**
     * Set to true once any Object[] arrays of boxed types in annotationDefaultParamValues have been lazily
     * converted to primitive arrays.
     */
    boolean annotationDefaultParamValuesHasBeenConvertedToPrimitive;

    /** The set of classes related to this one. */
    private Map<RelType, Set<ClassInfo>> relatedClasses;

    /**
     * The override order for a class' fields or methods (base class, followed by interfaces, followed by
     * superclasses).
     */
    private @Nullable List<ClassInfo> overrideOrder;

    /**
     * The override order for a class' methods (base class, followed by superclasses, followed by interfaces).
     */
    private @Nullable List<ClassInfo> methodOverrideOrder;

    /** The annotations, once they are loaded */
    private @Nullable ClassInfoList annotationsRef;

    /** The annotation infos, once they are loaded */
    private @Nullable AnnotationInfoList annotationInfoRef;

    // -------------------------------------------------------------------------------------------------------------

    /** The modifier bit for annotations. */
    private static final int ANNOTATION_CLASS_MODIFIER = 0x2000;

    /**
     * The {@code ACC_SUPER} bit of a classfile's {@code access_flags} field. This selects the JVM's treatment of
     * the {@code invokespecial} instruction, and has no counterpart in {@link Modifier} -- the same bit value is
     * {@link Modifier#SYNCHRONIZED}, which is not a legal class modifier. It is masked out of the value returned by
     * {@link #getModifiers()}.
     */
    // #791
    private static final int ACC_SUPER = 0x0020;

    /** The constant empty return value used when no classes are reachable. */
    private static final ReachableAndDirectlyRelatedClasses NO_REACHABLE_CLASSES = //
            new ReachableAndDirectlyRelatedClasses(Set.of(), Set.of());

    // -------------------------------------------------------------------------------------------------------------

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
    ClassInfo(final String name, final int classModifiers, final @Nullable Resource classfileResource) {
        super();
        this.name = name;
        if (name.endsWith(";")) {
            // Spot check to make sure class names were parsed from descriptors
            throw new IllegalArgumentException("Bad class name");
        }
        // Assign the field directly rather than calling setModifiers(int), which is overridable, and so would let a
        // subclass see a partly-initialized instance. The field is still zero here, so the "|=" in
        // setModifiers(int) would have the same effect as this assignment.
        this.modifiers = classModifiers;
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
         * (Should consist of only one entry, or be empty if this is an interface, or is {@code java.lang.Object}
         * itself.)
         */
        SUPERCLASSES,

        /** Subclasses of this class, if this is a regular class. */
        SUBCLASSES,

        /** Indicates that an inner class is contained within this one. */
        CONTAINS_INNER_CLASS,

        /**
         * Indicates that an outer class contains this one. (Should only have zero or one entries.)
         */
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
        return relatedClasses.computeIfAbsent(relType, k -> new LinkedHashSet<>(4)).add(classInfo);
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
        var numArrayDims = 0;
        var baseClassName = className;
        while (baseClassName.endsWith("[]")) {
            numArrayDims++;
            baseClassName = baseClassName.substring(0, baseClassName.length() - 2);
        }
        // Be resilient to the use of class descriptors rather than class names (should not be needed)
        while (baseClassName.startsWith("[")) {
            numArrayDims++;
            baseClassName = baseClassName.substring(1);
        }
        if (baseClassName.length() > 1 && baseClassName.charAt(0) == 'L' && baseClassName.endsWith(";")) {
            // Strip the 'L' and the ';' from an object type descriptor, e.g. "Ljava/lang/String;"
            baseClassName = baseClassName.substring(1, baseClassName.length() - 1);
        }
        baseClassName = baseClassName.replace('/', '.');

        var classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            if (numArrayDims == 0) {
                classInfo = new ClassInfo(baseClassName, /* classModifiers = */ 0, /* classfileResource = */ null);
            } else {
                final StringBuilder arrayTypeSigStrBuf = new StringBuilder();
                for (var i = 0; i < numArrayDims; i++) {
                    arrayTypeSigStrBuf.append('[');
                }
                TypeSignature elementTypeSignature;
                final var baseTypeChar = BaseTypeSignature.getTypeChar(baseClassName);
                if (baseTypeChar != '\0') {
                    // Element type is a base (primitive) type
                    arrayTypeSigStrBuf.append(baseTypeChar);
                    elementTypeSignature = new BaseTypeSignature(baseTypeChar);
                } else {
                    // Element type is not a base (primitive) type -- create a type signature for element type
                    final var eltTypeSigStr = "L" + baseClassName.replace('.', '/') + ";";
                    arrayTypeSigStrBuf.append(eltTypeSigStr);
                    try {
                        elementTypeSignature = ClassRefTypeSignature.parse(new TypeSignatureParser(eltTypeSigStr),
                                // No type variables to resolve for generic types
                                /* definingClassName = */ null);
                        if (elementTypeSignature == null) {
                            throw new IllegalArgumentException(
                                    "Could not form array base type signature for class " + baseClassName);
                        }
                    } catch (final TypeSignatureParseException e) {
                        throw new IllegalArgumentException(
                                "Could not form array base type signature for class " + baseClassName);
                    }
                }
                classInfo = new ArrayClassInfo(
                        new ArrayTypeSignature(elementTypeSignature, numArrayDims, arrayTypeSigStrBuf.toString()));
            }
            classNameToClassInfo.put(className, classInfo);
        }
        return classInfo;
    }

    /**
     * Set classfile version.
     *
     * @param minorVersion
     *            the minor version of the classfile format for this class' classfile.
     * @param majorVersion
     *            the major version of the classfile format for this class' classfile.
     */
    void setClassfileVersion(final int minorVersion, final int majorVersion) {
        this.classfileMinorVersion = minorVersion;
        this.classfileMajorVersion = majorVersion;
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
     * The access level modifier bits: {@code public}, {@code private} and {@code protected}.
     */
    private static final int ACCESS_LEVEL_MODIFIERS = Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED;

    /**
     * Set the modifiers of a nested class from the {@code InnerClasses} attribute of its enclosing class.
     *
     * <p>
     * For a nested class, the {@code access_flags} field in the class's own classfile cannot express the
     * source-level access level: the JVM requires a nested class to be reachable from its enclosing class, so javac
     * emits {@code ACC_PUBLIC} (or package-private) there, and records the real access level only in the
     * {@code InnerClasses} attribute of the enclosing class. The {@code InnerClasses} bits are therefore
     * authoritative for the access level, so they replace the access level bits read from the classfile rather than
     * being OR'd into them -- OR-ing left a {@code protected} nested class with both {@code ACC_PUBLIC} and
     * {@code ACC_PROTECTED} set, so that both {@link #isPublic()} and {@link #isProtected()} returned true. The
     * remaining bits (e.g. {@code ACC_STATIC}, which only appears in the {@code InnerClasses} attribute) are OR'd
     * in as before.
     *
     * @param innerClassModifierBits
     *            the modifier bits from the {@code InnerClasses} attribute entry for this class
     */
    // #791
    void setNestedClassModifiers(final int innerClassModifierBits) {
        this.modifiers = (this.modifiers & ~ACCESS_LEVEL_MODIFIERS) | innerClassModifierBits;
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

    /**
     * Set source file.
     *
     * @param sourceFile
     *            the source file, or null if the classfile has no {@code SourceFile} attribute
     */
    void setSourceFile(final @Nullable String sourceFile) {
        this.sourceFile = sourceFile;
    }

    /**
     * Add {@link ClassTypeAnnotationDecorator} instances.
     *
     * @param classTypeAnnotationDecorators
     *            {@link ClassTypeAnnotationDecorator} instances.
     */
    void addTypeDecorators(final List<ClassTypeAnnotationDecorator> classTypeAnnotationDecorators) {
        if (typeAnnotationDecorators == null) {
            typeAnnotationDecorators = new ArrayList<>();
        }
        typeAnnotationDecorators.addAll(classTypeAnnotationDecorators);
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
        final var superclassClassInfo = getOrCreateClassInfo(superclassName, classNameToClassInfo);
        this.addRelatedClass(RelType.SUPERCLASSES, superclassClassInfo);
        superclassClassInfo.addRelatedClass(RelType.SUBCLASSES, this);
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
        final var interfaceClassInfo = getOrCreateClassInfo(interfaceName, classNameToClassInfo);
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
            final var innerClassInfo = ClassInfo.getOrCreateClassInfo(classContainment.innerClassName(),
                    classNameToClassInfo);
            innerClassInfo.setNestedClassModifiers(classContainment.innerClassModifierBits());
            final var outerClassInfo = ClassInfo.getOrCreateClassInfo(classContainment.outerClassName(),
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
        final var annotationClassInfo = getOrCreateClassInfo(classAnnotationInfo.getName(), classNameToClassInfo);
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
    private void addFieldOrMethodAnnotationInfo(final @Nullable AnnotationInfoList annotationInfoList,
            final boolean isField, final int modifiers, final Map<String, ClassInfo> classNameToClassInfo) {
        if (annotationInfoList != null) {
            for (final AnnotationInfo fieldAnnotationInfo : annotationInfoList) {
                final var annotationClassInfo = getOrCreateClassInfo(fieldAnnotationInfo.getName(),
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
                for (final AnnotationInfo[] paramAnnotationInfoArr : mi.parameterAnnotationInfo) {
                    if (paramAnnotationInfoArr != null) {
                        for (final AnnotationInfo methodParamAnnotationInfo : paramAnnotationInfoArr) {
                            final var annotationClassInfo = getOrCreateClassInfo(
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
        var classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            // This is the first time this class has been seen, add it
            classNameToClassInfo.put(className,
                    classInfo = new ClassInfo(className, classModifiers, classfileResource));
        } else {
            // There was a previous placeholder ClassInfo class added, due to the class being referred to as a
            // superclass, interface or annotation. The isScannedClass field should be false in this case, since the
            // actual class definition wasn't reached before now.
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

        // Mark the class as non-external if it is an accepted class
        classInfo.isExternalClass = isExternalClass;

        // Remember which classpath element (zipfile / classpath root directory / module) the class was found in
        classInfo.classpathElement = classpathElement;

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
        /**
         * An interface or annotation (used since you can actually implement an annotation).
         */
        INTERFACE_OR_ANNOTATION,
        /** An enum. */
        ENUM,
        /** A record type. */
        RECORD
    }

    /**
     * Filter classes according to scan spec and class type.
     *
     * <p>
     * The rule for {@code strictAccept}: a query that looks "upwards" in the class hierarchy -- for the
     * superclasses, interfaces, annotations or outer classes of a class -- passes false, since it is reporting what
     * an accepted classfile itself declares, and the answer would be misleading if part of it were left out. A
     * query that looks "downwards" -- for the subclasses of a class, the classes implementing an interface, or the
     * classes annotated with an annotation -- passes true, since it can only ever report what was scanned.
     *
     * @param classes
     *            the classes
     * @param scanSpec
     *            the scan spec
     * @param strictAccept
     *            If true, exclude class if it is external, if external classes are not enabled
     * @param classTypes
     *            the class types
     * @return the filtered classes.
     */
    private static Set<ClassInfo> filterClassInfo(final Collection<ClassInfo> classes, final ScanSpec scanSpec,
            final boolean strictAccept, final ClassType... classTypes) {
        var includeAllTypes = classTypes.length == 0;
        var includeStandardClasses = false;
        var includeImplementedInterfaces = false;
        var includeAnnotations = false;
        var includeEnums = false;
        var includeRecords = false;
        for (final ClassType classType : classTypes) {
            switch (classType) {
            case ALL -> includeAllTypes = true;
            case STANDARD_CLASS -> includeStandardClasses = true;
            case IMPLEMENTED_INTERFACE -> includeImplementedInterfaces = true;
            case ANNOTATION -> includeAnnotations = true;
            case INTERFACE_OR_ANNOTATION -> includeImplementedInterfaces = includeAnnotations = true;
            case ENUM -> includeEnums = true;
            case RECORD -> includeRecords = true;
            default -> throw new IllegalArgumentException("Unknown ClassType: " + classType);
            }
        }
        if (includeStandardClasses && includeImplementedInterfaces && includeAnnotations) {
            includeAllTypes = true;
        }
        final Set<ClassInfo> classInfoSetFiltered = new LinkedHashSet<>(classes.size());
        for (final ClassInfo classInfo : classes) {
            // Check class type against requested type(s)
            final var includeType = includeAllTypes //
                    || includeStandardClasses && classInfo.isStandardClass() //
                    || includeImplementedInterfaces && classInfo.isImplementedInterface() //
                    || includeAnnotations && classInfo.isAnnotation() //
                    || includeEnums && classInfo.isEnum() //
                    || includeRecords && classInfo.isRecord();
            // External (non-accepted) classes are returned only by "upwards" queries, or if external classes were
            // enabled
            final var acceptClass = !classInfo.isExternalClass || scanSpec.enableExternalClasses || !strictAccept;
            // If class is of correct type, and class is accepted, and class/package are not explicitly rejected
            if (includeType && acceptClass && !scanSpec.classOrPackageIsRejected(classInfo.name)) {
                // Class passed accept criteria
                classInfoSetFiltered.add(classInfo);
            }
        }
        return classInfoSetFiltered;
    }

    /**
     * A set of classes that indirectly reachable through a directed path, for a given relationship type, and a set
     * of classes that is directly related (only one relationship step away).
     *
     * @param reachableClasses
     *            the reachable classes
     * @param directlyRelatedClasses
     *            the directly related classes
     */
    record ReachableAndDirectlyRelatedClasses(Set<ClassInfo> reachableClasses,
            Set<ClassInfo> directlyRelatedClasses) {
    }

    /**
     * Get the classes related to this one (the transitive closure) for the given relationship type, and those
     * directly related.
     *
     * @param relType
     *            the relationship type
     * @param strictAccept
     *            If true, exclude class if it is external, if external classes are not enabled
     * @param classTypes
     *            the class types to accept
     * @return the reachable and directly related classes
     */
    private ReachableAndDirectlyRelatedClasses filterClassInfo(final RelType relType, final boolean strictAccept,
            final ClassType... classTypes) {
        var directlyRelatedClasses = this.relatedClasses.get(relType);
        if (directlyRelatedClasses == null) {
            return NO_REACHABLE_CLASSES;
        } else {
            // Clone collection to prevent users modifying contents accidentally or intentionally
            directlyRelatedClasses = new LinkedHashSet<>(directlyRelatedClasses);
        }
        final var reachableClasses = findReachableClasses(relType, directlyRelatedClasses);
        if (reachableClasses.isEmpty()) {
            return NO_REACHABLE_CLASSES;
        }

        if (relType == RelType.CLASS_ANNOTATIONS || relType == RelType.METHOD_ANNOTATIONS
                || relType == RelType.METHOD_PARAMETER_ANNOTATIONS || relType == RelType.FIELD_ANNOTATIONS) {
            removeInheritedJavaLangAnnotations(reachableClasses, directlyRelatedClasses);
        }

        return new ReachableAndDirectlyRelatedClasses(
                filterClassInfo(reachableClasses, scanResult().scanSpec, strictAccept, classTypes),
                filterClassInfo(directlyRelatedClasses, scanResult().scanSpec, strictAccept, classTypes));

    }

    /**
     * Find the transitive closure of the classes reachable from this one for the given relationship type.
     *
     * @param relType
     *            the relationship type
     * @param directlyRelatedClasses
     *            the classes that are one relationship step away from this one
     * @return the reachable classes, including the directly related classes
     */
    private Set<ClassInfo> findReachableClasses(final RelType relType,
            final Set<ClassInfo> directlyRelatedClasses) {
        final Set<ClassInfo> reachableClasses = new LinkedHashSet<>(directlyRelatedClasses);
        if (relType == RelType.METHOD_ANNOTATIONS || relType == RelType.METHOD_PARAMETER_ANNOTATIONS
                || relType == RelType.FIELD_ANNOTATIONS) {
            // For method and field annotations, need to change the RelType when finding meta-annotations
            for (final ClassInfo annotation : directlyRelatedClasses) {
                // Don't filter this intermediate traversal -- the result is filtered by the caller
                reachableClasses.addAll(annotation
                        .filterClassInfo(RelType.CLASS_ANNOTATIONS, /* strictAccept = */ false).reachableClasses());
            }
        } else if (relType == RelType.CLASSES_WITH_METHOD_ANNOTATION
                || relType == RelType.CLASSES_WITH_NONPRIVATE_METHOD_ANNOTATION
                || relType == RelType.CLASSES_WITH_METHOD_PARAMETER_ANNOTATION
                || relType == RelType.CLASSES_WITH_NONPRIVATE_METHOD_PARAMETER_ANNOTATION
                || relType == RelType.CLASSES_WITH_FIELD_ANNOTATION
                || relType == RelType.CLASSES_WITH_NONPRIVATE_FIELD_ANNOTATION) {
            // If looking for meta-annotated methods or fields, need to find all meta-annotated annotations, then
            // look for the methods or fields that they annotate. Don't filter this intermediate traversal -- an
            // accepted class can be annotated by an external annotation that is itself meta-annotated by this one.
            // The result is filtered by the caller.
            for (final ClassInfo subAnnotation : this.filterClassInfo(RelType.CLASSES_WITH_ANNOTATION,
                    /* strictAccept = */ false, ClassType.ANNOTATION).reachableClasses()) {
                final var annotatedClasses = subAnnotation.relatedClasses.get(relType);
                if (annotatedClasses != null) {
                    reachableClasses.addAll(annotatedClasses);
                }
            }
        } else {
            // For other relationship types, the reachable type stays the same over the transitive closure. Find the
            // transitive closure, breaking cycles where necessary.
            final LinkedList<ClassInfo> queue = new LinkedList<>(directlyRelatedClasses);
            while (!queue.isEmpty()) {
                final var head = queue.removeFirst();
                final var headRelatedClasses = head.relatedClasses.get(relType);
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
        return reachableClasses;
    }

    /**
     * Remove from the reachable classes any {@code java.lang.annotation.*} meta-annotation that was reached
     * indirectly, since meta-annotations of meta-annotations should not be inherited. Directly related
     * {@code java.lang.annotation.*} annotations are still returned as direct meta-annotations of annotation
     * classes.
     *
     * @param reachableClasses
     *            the reachable classes, which are modified in place
     * @param directlyRelatedClasses
     *            the classes that are one relationship step away from this one
     */
    private static void removeInheritedJavaLangAnnotations(final Set<ClassInfo> reachableClasses,
            final Set<ClassInfo> directlyRelatedClasses) {
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
                ClassInfo.filterClassInfo(classes, scanSpec, /* strictAccept = */ true, ClassType.ALL),
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
                ClassInfo.filterClassInfo(classes, scanSpec, /* strictAccept = */ true, ClassType.ENUM),
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
                ClassInfo.filterClassInfo(classes, scanSpec, /* strictAccept = */ true, ClassType.RECORD),
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
        return new ClassInfoList(
                ClassInfo.filterClassInfo(classes, scanSpec, /* strictAccept = */ true, ClassType.STANDARD_CLASS),
                /* sortByName = */ true);
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
        return new ClassInfoList(ClassInfo.filterClassInfo(classes, scanSpec, /* strictAccept = */ true,
                ClassType.IMPLEMENTED_INTERFACE), /* sortByName = */ true);
    }

    /**
     * Get all annotation classes found during the scan. See also
     * {@link #getAllInterfacesOrAnnotationClasses(Collection, ScanSpec)}.
     *
     * @param classes
     *            the classes
     * @param scanSpec
     *            the scan spec
     * @return A list of all annotation classes found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllAnnotationClasses(final Collection<ClassInfo> classes, final ScanSpec scanSpec) {
        return new ClassInfoList(
                ClassInfo.filterClassInfo(classes, scanSpec, /* strictAccept = */ true, ClassType.ANNOTATION),
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
     * @return A list of all accepted interfaces found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllInterfacesOrAnnotationClasses(final Collection<ClassInfo> classes,
            final ScanSpec scanSpec) {
        return new ClassInfoList(ClassInfo.filterClassInfo(classes, scanSpec, /* strictAccept = */ true,
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
     * Get simple name from fully-qualified class name. Returns everything after the last '.' or the last '$' in the
     * class name, or the whole string if the class is in the root package. (Note that this is not the same as the
     * result of {@link Class#getSimpleName()}, which returns "" for anonymous classes.)
     *
     * @param className
     *            the class name
     * @return The simple name of the class.
     */
    static String getSimpleName(final String className) {
        return className.substring(Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1);
    }

    /**
     * Get the simple name of the class. Returns everything after the last '.' or the last '$' in the class name, so
     * that the package name and any enclosing class names are stripped, or the whole string if the class is in the
     * root package and is not nested. (Note that this is not the same as the result of
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
    public @Nullable ModuleInfo getModuleInfo() {
        return moduleInfo;
    }

    /**
     * Get the {@link PackageInfo} object for the class.
     *
     * @return the {@link PackageInfo} object for the package that contains the class.
     */
    public @Nullable PackageInfo getPackageInfo() {
        return packageInfo;
    }

    /**
     * Get the name of the class' package.
     *
     * @return The name of the class' package.
     */
    public String getPackageName() {
        // A class name is never empty, so getParentPackageName cannot return null
        return Objects.requireNonNull(PackageInfo.getParentPackageName(name));
    }

    /**
     * Checks if this is an external class.
     *
     * @return true if this class is an external class, i.e. was referenced by an accepted class as a superclass,
     *         interface, or annotation, but is not itself an accepted class.
     */
    public boolean isExternalClass() {
        return isExternalClass;
    }

    /**
     * Get the minor version of the classfile format for this class' classfile.
     *
     * @return The minor version of the classfile format for this class' classfile, or 0 if this {@link ClassInfo}
     *         object is a placeholder for a referenced class that was not found or not accepted during the scan.
     */
    public int getClassfileMinorVersion() {
        return classfileMinorVersion;
    }

    /**
     * Get the major version of the classfile format for this class' classfile.
     *
     * @return The major version of the classfile format for this class' classfile, or 0 if this {@link ClassInfo}
     *         object is a placeholder for a referenced class that was not found or not accepted during the scan.
     */
    public int getClassfileMajorVersion() {
        return classfileMajorVersion;
    }

    /**
     * Get the class modifier bits, in the same form as {@link Class#getModifiers()}.
     *
     * <p>
     * The {@code ACC_SUPER} bit (0x0020) of the classfile's {@code access_flags} field is masked out, because it is
     * not a modifier: it selects the JVM's treatment of the {@code invokespecial} instruction, javac sets it on
     * almost every class, and the same bit value in {@link Modifier} is {@link Modifier#SYNCHRONIZED}, which is not
     * a legal class modifier. {@link Class#getModifiers()} masks it out for the same reason.
     *
     * <p>
     * <b>Note:</b> comparing the returned value against a hardcoded integer is fragile, and the value of that bit
     * changed in version 4.8.186: a {@code protected static} nested class previously returned 0x002C, and now
     * returns 0x000C. Prefer the named accessors ({@link #isPublic()}, {@link #isProtected()},
     * {@link #isPrivate()}, {@link #isStatic()}, {@link #isFinal()}, {@link #isAbstract()}, {@link #isInterface()},
     * {@link #isAnnotation()}, {@link #isEnum()}, {@link #isSynthetic()}), or test individual bits with the
     * {@link Modifier} predicates, rather than comparing the whole value.
     *
     * @return The class modifier bits, e.g. {@link Modifier#PUBLIC}.
     */
    // #791
    public int getModifiers() {
        return modifiers & ~ACC_SUPER;
    }

    /**
     * Get the class modifiers as a String.
     *
     * @return The field modifiers as a string, e.g. "public static final". For the modifier bits, call
     *         {@link #getModifiers()}.
     */
    public String getModifiersString() {
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
        return Modifier.isPublic(modifiers);
    }

    /**
     * Checks if the class is private.
     *
     * @return true if this class is a private class.
     */
    public boolean isPrivate() {
        return Modifier.isPrivate(modifiers);
    }

    /**
     * Checks if the class is protected.
     *
     * @return true if this class is a protected class.
     */
    public boolean isProtected() {
        return Modifier.isProtected(modifiers);
    }

    /**
     * Checks if the class has default (package) visibility.
     *
     * @return true if this class is only visible within its package.
     */
    public boolean isPackageVisible() {
        return !isPublic() && !isPrivate() && !isProtected();
    }

    /**
     * Checks if the class is abstract.
     *
     * @return true if this class is an abstract class.
     */
    public boolean isAbstract() {
        return Modifier.isAbstract(modifiers);
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
        return Modifier.isFinal(modifiers);
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
     * Checks if this class extends the superclass.
     *
     * @param superclass
     *            The superclass. Only the name of this class is used for the lookup, so the class itself does not
     *            need to have been on the scanned classpath.
     * @return true if this class extends the superclass.
     */
    public boolean extendsSuperclass(final Class<?> superclass) {
        Assert.notNull(superclass, "superclass");
        return extendsSuperclass(superclass.getName());
    }

    /**
     * Checks if this class extends the named superclass.
     *
     * @param superclassName
     *            The name of a superclass.
     * @return true if this class extends the named superclass.
     */
    public boolean extendsSuperclass(final String superclassName) {
        Assert.notNull(superclassName, "superclassName");
        return ("java.lang.Object".equals(superclassName) && isStandardClass())
                || getAllSuperclasses().containsName(superclassName);
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
     * Checks whether this class implements the interface.
     *
     * @param interfaceClazz
     *            An interface.
     * @return true if this class implements the interface.
     * @throws IllegalArgumentException
     *             if {@code interfaceClazz} is not an interface.
     */
    public boolean implementsInterface(final Class<?> interfaceClazz) {
        Assert.notNull(interfaceClazz, "interfaceClazz");
        Assert.isInterface(interfaceClazz);
        return implementsInterface(interfaceClazz.getName());
    }

    /**
     * Checks whether this class implements the named interface.
     *
     * @param interfaceName
     *            The name of an interface.
     * @return true if this class implements the named interface.
     */
    public boolean implementsInterface(final String interfaceName) {
        Assert.notNull(interfaceName, "interfaceName");
        return getAllSuperinterfaces().containsName(interfaceName);
    }

    /**
     * Checks whether this class has the named annotation.
     *
     * @param annotationName
     *            the name of the annotation class
     * @return true if this class has the named annotation.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    @Override
    public boolean hasAnnotation(final String annotationName) {
        Assert.notNull(annotationName, "annotationName");
        return getAllAnnotations().containsName(annotationName);
    }

    /**
     * Checks whether this class has the named declared field.
     *
     * @param fieldName
     *            The name of a field.
     * @return true if this class declares a field of the given name.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} was not called before scanning.
     */
    public boolean hasDeclaredField(final String fieldName) {
        Assert.notNull(fieldName, "fieldName");
        return getDeclaredFieldInfo().containsName(fieldName);
    }

    /**
     * Checks whether this class or one of its superclasses has the named field.
     *
     * @param fieldName
     *            The name of a field.
     * @return true if this class or one of its superclasses declares a field of the given name.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} was not called before scanning.
     */
    public boolean hasField(final String fieldName) {
        Assert.notNull(fieldName, "fieldName");
        for (final ClassInfo ci : getFieldOverrideOrder()) {
            if (ci.hasDeclaredField(fieldName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class declares a field with the annotation.
     *
     * @param annotation
     *            A field annotation.
     * @return true if this class declares a field with the annotation.
     * @throws IllegalArgumentException
     *             if {@code annotation} is not an annotation type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public boolean hasDeclaredFieldAnnotation(final Class<? extends Annotation> annotation) {
        Assert.notNull(annotation, "annotation");
        Assert.isAnnotation(annotation);
        return hasDeclaredFieldAnnotation(annotation.getName());
    }

    /**
     * Checks whether this class declares a field with the named annotation.
     *
     * @param fieldAnnotationName
     *            The name of a field annotation.
     * @return true if this class declares a field with the named annotation.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public boolean hasDeclaredFieldAnnotation(final String fieldAnnotationName) {
        Assert.notNull(fieldAnnotationName, "fieldAnnotationName");
        for (final FieldInfo fi : getDeclaredFieldInfo()) {
            if (fi.hasAnnotation(fieldAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class or one of its superclasses declares a field with the annotation.
     *
     * @param fieldAnnotation
     *            The field annotation. Only the name of this class is used for the lookup, so the class itself does
     *            not need to have been on the scanned classpath.
     * @return true if this class or one of its superclasses declares a field with the annotation.
     * @throws IllegalArgumentException
     *             if {@code fieldAnnotation} is not an annotation type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public boolean hasFieldAnnotation(final Class<? extends Annotation> fieldAnnotation) {
        Assert.notNull(fieldAnnotation, "fieldAnnotation");
        Assert.isAnnotation(fieldAnnotation);
        return hasFieldAnnotation(fieldAnnotation.getName());
    }

    /**
     * Checks whether this class or one of its superclasses declares a field with the named annotation.
     *
     * @param fieldAnnotationName
     *            The name of a field annotation.
     * @return true if this class or one of its superclasses declares a field with the named annotation.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public boolean hasFieldAnnotation(final String fieldAnnotationName) {
        Assert.notNull(fieldAnnotationName, "fieldAnnotationName");
        for (final ClassInfo ci : getFieldOverrideOrder()) {
            if (ci.hasDeclaredFieldAnnotation(fieldAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class declares a method of the given name.
     *
     * @param methodName
     *            The name of a method.
     * @return true if this class declares a method of the given name.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} was not called before scanning.
     */
    public boolean hasDeclaredMethod(final String methodName) {
        Assert.notNull(methodName, "methodName");
        return getDeclaredMethodInfo().containsName(methodName);
    }

    /**
     * Checks whether this class or one of its superclasses or interfaces declares a method of the given name.
     *
     * @param methodName
     *            The name of a method.
     * @return true if this class or one of its superclasses or interfaces declares a method of the given name.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} was not called before scanning.
     */
    public boolean hasMethod(final String methodName) {
        Assert.notNull(methodName, "methodName");
        for (final ClassInfo ci : getMethodOverrideOrder()) {
            if (ci.hasDeclaredMethod(methodName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class declares a method with the annotation.
     *
     * @param methodAnnotation
     *            The method annotation. Only the name of this class is used for the lookup, so the class itself
     *            does not need to have been on the scanned classpath.
     * @return true if this class declares a method with the annotation.
     * @throws IllegalArgumentException
     *             if {@code methodAnnotation} is not an annotation type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public boolean hasDeclaredMethodAnnotation(final Class<? extends Annotation> methodAnnotation) {
        Assert.notNull(methodAnnotation, "methodAnnotation");
        Assert.isAnnotation(methodAnnotation);
        return hasDeclaredMethodAnnotation(methodAnnotation.getName());
    }

    /**
     * Checks whether this class declares a method with the named annotation.
     *
     * @param methodAnnotationName
     *            The name of a method annotation.
     * @return true if this class declares a method with the named annotation.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public boolean hasDeclaredMethodAnnotation(final String methodAnnotationName) {
        Assert.notNull(methodAnnotationName, "methodAnnotationName");
        for (final MethodInfo mi : getDeclaredMethodInfo()) {
            if (mi.hasAnnotation(methodAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class or one of its superclasses or interfaces declares a method with the annotation.
     *
     * @param methodAnnotation
     *            The method annotation. Only the name of this class is used for the lookup, so the class itself
     *            does not need to have been on the scanned classpath.
     * @return true if this class or one of its superclasses or interfaces declares a method with the annotation.
     * @throws IllegalArgumentException
     *             if {@code methodAnnotation} is not an annotation type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public boolean hasMethodAnnotation(final Class<? extends Annotation> methodAnnotation) {
        Assert.notNull(methodAnnotation, "methodAnnotation");
        Assert.isAnnotation(methodAnnotation);
        return hasMethodAnnotation(methodAnnotation.getName());
    }

    /**
     * Checks whether this class or one of its superclasses or interfaces declares a method with the named
     * annotation.
     *
     * @param methodAnnotationName
     *            The name of a method annotation.
     * @return true if this class or one of its superclasses or interfaces declares a method with the named
     *         annotation.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public boolean hasMethodAnnotation(final String methodAnnotationName) {
        Assert.notNull(methodAnnotationName, "methodAnnotationName");
        for (final ClassInfo ci : getMethodOverrideOrder()) {
            if (ci.hasDeclaredMethodAnnotation(methodAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class declares a method with the annotation.
     *
     * @param methodParameterAnnotation
     *            A method annotation.
     * @return true if this class declares a method with the annotation.
     * @throws IllegalArgumentException
     *             if {@code methodParameterAnnotation} is not an annotation type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public boolean hasDeclaredMethodParameterAnnotation(
            final Class<? extends Annotation> methodParameterAnnotation) {
        Assert.notNull(methodParameterAnnotation, "methodParameterAnnotation");
        Assert.isAnnotation(methodParameterAnnotation);
        return hasDeclaredMethodParameterAnnotation(methodParameterAnnotation.getName());
    }

    /**
     * Checks whether this class declares a method with the named annotation.
     *
     * @param methodParameterAnnotationName
     *            The name of a method annotation.
     * @return true if this class declares a method with the named annotation.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public boolean hasDeclaredMethodParameterAnnotation(final String methodParameterAnnotationName) {
        Assert.notNull(methodParameterAnnotationName, "methodParameterAnnotationName");
        for (final MethodInfo mi : getDeclaredMethodInfo()) {
            if (mi.hasParameterAnnotation(methodParameterAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether this class or one of its superclasses or interfaces has a method with the annotation.
     *
     * @param methodParameterAnnotation
     *            A method annotation.
     * @return true if this class or one of its superclasses or interfaces has a method with the annotation.
     * @throws IllegalArgumentException
     *             if {@code methodParameterAnnotation} is not an annotation type.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public boolean hasMethodParameterAnnotation(final Class<? extends Annotation> methodParameterAnnotation) {
        Assert.notNull(methodParameterAnnotation, "methodParameterAnnotation");
        Assert.isAnnotation(methodParameterAnnotation);
        return hasMethodParameterAnnotation(methodParameterAnnotation.getName());
    }

    /**
     * Checks whether this class or one of its superclasses or interfaces has a method with the named annotation.
     *
     * @param methodParameterAnnotationName
     *            The name of a method annotation.
     * @return true if this class or one of its superclasses or interfaces has a method with the named annotation.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public boolean hasMethodParameterAnnotation(final String methodParameterAnnotationName) {
        Assert.notNull(methodParameterAnnotationName, "methodParameterAnnotationName");
        for (final ClassInfo ci : getMethodOverrideOrder()) {
            if (ci.hasDeclaredMethodParameterAnnotation(methodParameterAnnotationName)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recurse to interfaces and superclasses to get the order that fields are overridden in.
     *
     * @param visited
     *            visited
     * @param overrideOrderOut
     *            the override order
     * @return the override order
     */
    private List<ClassInfo> getFieldOverrideOrder(final Set<ClassInfo> visited,
            final List<ClassInfo> overrideOrderOut) {
        if (visited.add(this)) {
            overrideOrderOut.add(this);
            for (final ClassInfo iface : getAllSuperinterfaces()) {
                iface.getFieldOverrideOrder(visited, overrideOrderOut);
            }
            final var superclass = getSuperclass();
            if (superclass != null) {
                superclass.getFieldOverrideOrder(visited, overrideOrderOut);
            }
        }
        return overrideOrderOut;
    }

    /**
     * Get the order that fields are overridden in (base class first).
     *
     * @return the override order
     */
    private List<ClassInfo> getFieldOverrideOrder() {
        if (overrideOrder == null) {
            overrideOrder = getFieldOverrideOrder(new HashSet<>(), new ArrayList<>());
        }
        return overrideOrder;
    }

    /**
     * Recurse to collect classes and interfaces in the order of overridden methods, in descending priority.
     * <p>
     * First collects all direct super classes, as their methods always have a higher priority than any method
     * declared by an interface. Iterates over interfaces and inserts those extending already found interfaces
     * before them in the output. The order of unrelated interfaces is unspecified.
     * <p>
     * See Java Language Specification 8.4.8 for details.
     *
     * @param visited
     *            non-null set of already visited ClassInfos
     * @param overrideOrderOut
     *            non-null outgoing list of ClassInfos in descending override order.
     * @return the overrideOrderOut instance
     */
    private List<ClassInfo> getMethodOverrideOrder(final Set<ClassInfo> visited,
            final List<ClassInfo> overrideOrderOut) {
        if (!visited.add(this)) {
            return overrideOrderOut;
        }
        // collect concrete super classes first, simply add to overrideOrder
        if (!isInterfaceOrAnnotation()) {
            overrideOrderOut.add(this);
            // iterate over direct super classes first, they have the highest priority regarding method overrides
            final var superclass = getSuperclass();
            if (superclass != null) {
                superclass.getMethodOverrideOrder(visited, overrideOrderOut);
            }
            for (final ClassInfo iface : getAllSuperinterfaces()) {
                iface.getMethodOverrideOrder(visited, overrideOrderOut);
            }
            return overrideOrderOut;
        }
        // overrideOrderOut already contains all concrete classes now. This is an interface. If one of the extended
        // interfaces is already in the output, then this needs to be added before it. Otherwise, this is unrelated
        // to all collected ClassInfo so far and can simply be added to the result. The compiler should've prevented
        // inheriting unrelated interfaces with methods having the same signature. Can still happen thanks to
        // dynamically linking a different interface during runtime, for which the returned order is undefined.
        final var interfaces = getAllSuperinterfaces();
        var minIndex = Integer.MAX_VALUE;
        for (final ClassInfo iface : interfaces) {
            if (!visited.contains(iface)) {
                continue;
            }
            final var currIdx = overrideOrderOut.indexOf(iface);
            minIndex = currIdx >= 0 && currIdx < minIndex ? currIdx : minIndex;
        }
        if (minIndex == Integer.MAX_VALUE) {
            overrideOrderOut.add(this);
        } else {
            overrideOrderOut.add(minIndex, this);
        }
        // Add interfaces to end of override order
        for (final ClassInfo iface : interfaces) {
            iface.getMethodOverrideOrder(visited, overrideOrderOut);
        }
        return overrideOrderOut;
    }

    /**
     * Get the order that methods are overridden in.
     *
     * @return the override order
     */
    private List<ClassInfo> getMethodOverrideOrder() {
        if (methodOverrideOrder == null) {
            methodOverrideOrder = getMethodOverrideOrder(new HashSet<>(), new ArrayList<>());
        }
        return methodOverrideOrder;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Standard classes

    /**
     * Get all subclasses of this class, i.e. the classes that extend this class, and the classes that extend those,
     * transitively, sorted in order of name.
     *
     * If this class represents {@link Object}, then returns every standard class in the scan result, but no
     * interfaces, since interfaces don't extend {@link Object}.
     *
     * @return the list of all subclasses of this class, or the empty list if none.
     */
    public ClassInfoList getAllSubclasses() {
        if ("java.lang.Object".equals(getName())) {
            // Every standard class is a subclass of Object by the rules of the language, whether or not its whole
            // superclass chain was scanned, so answer from the list of standard classes rather than from the
            // recorded superclass links
            return scanResult().getAllStandardClasses().filter(classInfo -> classInfo != this);
        } else {
            return new ClassInfoList(this.filterClassInfo(RelType.SUBCLASSES, /* strictAccept = */ true),
                    /* sortByName = */ true);
        }
    }

    /**
     * Get the direct subclasses of this class, i.e. only the classes that name this class as their superclass,
     * sorted in order of name.
     *
     * @return the list of direct subclasses of this class, or the empty list if none.
     */
    public ClassInfoList getDirectSubclasses() {
        return new ClassInfoList(this.filterClassInfo(RelType.SUBCLASSES, /* strictAccept = */ true),
                /* sortByName = */ true).directOnly();
    }

    /**
     * Get all superclasses of this class, in ascending order in the class hierarchy, ending with {@link Object} if
     * the whole superclass chain was scanned. Call {@link #getSuperclass()} to get only the direct superclass.
     *
     * Also does not include superinterfaces, if this is an interface (use {@link #getAllSuperinterfaces()} to get
     * superinterfaces of an interface).
     *
     * @return the list of all superclasses of this class, or the empty list if none.
     */
    public ClassInfoList getAllSuperclasses() {
        return new ClassInfoList(this.filterClassInfo(RelType.SUPERCLASSES, /* strictAccept = */ false),
                /* sortByName = */ false);
    }

    /**
     * Get the single direct superclass of this class, or null if none. Does not return the superinterfaces, if this
     * is an interface (use {@link #getDirectSuperinterfaces()} to get the direct superinterfaces of an interface).
     *
     * <p>
     * As with {@link Class#getSuperclass()}, the superclass of a class that extends no other class is
     * {@link Object}, and null is returned only for {@link Object} itself and for interfaces.
     *
     * @return the superclass of this class, or null if none.
     */
    public @Nullable ClassInfo getSuperclass() {
        final var superClasses = relatedClasses.get(RelType.SUPERCLASSES);
        if (superClasses == null || superClasses.isEmpty()) {
            return null;
        } else if (superClasses.size() > 1) {
            throw new IllegalStateException("More than one superclass: " + superClasses);
        } else {
            return superClasses.iterator().next();
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
                this.filterClassInfo(RelType.CONTAINED_WITHIN_OUTER_CLASS, /* strictAccept = */ false),
                /* sortByName = */ false);
    }

    /**
     * Get the inner classes contained within this class, if this is an outer class.
     *
     * @return A list of the inner classes contained within this class, or the empty list if none.
     */
    public ClassInfoList getInnerClasses() {
        return new ClassInfoList(this.filterClassInfo(RelType.CONTAINS_INNER_CLASS, /* strictAccept = */ false),
                /* sortByName = */ true);
    }

    /**
     * Gets fully-qualified method name (i.e. fully qualified classname, followed by dot, followed by method name)
     * for the defining method, if this is an anonymous inner class.
     *
     * @return The fully-qualified method name (i.e. fully qualified classname, followed by dot, followed by method
     *         name) for the defining method, if this is an anonymous inner class, or null if not.
     */
    public @Nullable String getFullyQualifiedDefiningMethodName() {
        return fullyQualifiedDefiningMethodName;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /**
     * Get all superinterfaces of this class or interface: all interfaces implemented by this class or by one of its
     * superclasses, if this is a standard class, or all interfaces extended by this interface, directly or
     * indirectly, if this is an interface.
     *
     * @return The list of all superinterfaces of this class or interface, or the empty list if none.
     */
    public ClassInfoList getAllSuperinterfaces() {
        // Classes also implement the interfaces of their superclasses
        final var implementedInterfaces = this.filterClassInfo(RelType.IMPLEMENTED_INTERFACES,
                /* strictAccept = */ false);
        final Set<ClassInfo> allInterfaces = new LinkedHashSet<>(implementedInterfaces.reachableClasses());
        for (final ClassInfo superclass : this.filterClassInfo(RelType.SUPERCLASSES, /* strictAccept = */ false)
                .reachableClasses()) {
            final var superclassImplementedInterfaces = superclass
                    .filterClassInfo(RelType.IMPLEMENTED_INTERFACES, /* strictAccept = */ false).reachableClasses();
            allInterfaces.addAll(superclassImplementedInterfaces);
        }
        // Can't sort interfaces by name, since their order is significant in the definition of inheritance
        return new ClassInfoList(allInterfaces, implementedInterfaces.directlyRelatedClasses(),
                /* sortByName = */ false);
    }

    /**
     * Get the direct superinterfaces of this class or interface: the interfaces directly implemented by this class,
     * if this is a standard class, or the interfaces directly extended by this interface, if this is an interface.
     * Does not include the interfaces implemented by superclasses, or the superinterfaces of the returned
     * interfaces.
     *
     * @return The list of direct superinterfaces of this class or interface, or the empty list if none.
     */
    public ClassInfoList getDirectSuperinterfaces() {
        return getAllSuperinterfaces().directOnly();
    }

    /**
     * Get all the classes (and their subclasses) that implement this interface, if this is an interface.
     *
     * <p>
     * The returned list also contains the transitive subinterfaces of this interface, since an interface that
     * extends this interface is a subtype of it. To separate the two, call {@link ClassInfoList#getInterfaces()}
     * for just the subinterfaces, or {@link ClassInfoList#getStandardClasses()} for just the implementing classes.
     * (Note that {@link #getAllSubclasses()} does not traverse the interface hierarchy -- use this method instead
     * to find the subinterfaces of an interface.)
     *
     * @return the list of all the classes (and their subclasses) that implement this interface, and the transitive
     *         subinterfaces of this interface, if this is an interface, otherwise returns the empty list.
     */
    public ClassInfoList getAllClassesImplementing() {
        // Subclasses of implementing classes also implement the interface. Don't filter the two traversals, since
        // an accepted class can be reachable only through an external class (e.g. an accepted subclass of an
        // external class that implements this interface) -- filter the union at the end instead.
        final var implementingClasses = this.filterClassInfo(RelType.CLASSES_IMPLEMENTING,
                /* strictAccept = */ false);
        final Set<ClassInfo> allImplementingClasses = new LinkedHashSet<>(implementingClasses.reachableClasses());
        for (final ClassInfo implementingClass : implementingClasses.reachableClasses()) {
            final var implementingSubclasses = implementingClass
                    .filterClassInfo(RelType.SUBCLASSES, /* strictAccept = */ false).reachableClasses();
            allImplementingClasses.addAll(implementingSubclasses);
        }
        final var scanSpec = scanResult().scanSpec;
        return new ClassInfoList(
                ClassInfo.filterClassInfo(allImplementingClasses, scanSpec, /* strictAccept = */ true),
                ClassInfo.filterClassInfo(implementingClasses.directlyRelatedClasses(), scanSpec,
                        /* strictAccept = */ true),
                /* sortByName = */ true);
    }

    /**
     * Get the classes that directly implement this interface, i.e. that name this interface in their
     * {@code implements} clause, and the interfaces that name it in their {@code extends} clause, if this is an
     * interface.
     *
     * @return the list of the classes and interfaces that directly implement or extend this interface, if this is
     *         an interface, otherwise returns the empty list.
     */
    public ClassInfoList getDirectClassesImplementing() {
        return getAllClassesImplementing().directOnly();
    }

    /**
     * Get all subinterfaces of this interface, i.e. the interfaces that extend this interface, and the interfaces
     * that extend those, if this is an interface.
     *
     * <p>
     * This is the interface-hierarchy equivalent of {@link #getAllSubclasses()}, which only traverses the
     * superclass hierarchy. (It is equivalent to filtering {@link #getAllClassesImplementing()} down to just the
     * interfaces.)
     *
     * @return the list of all subinterfaces of this interface, if this is an interface, otherwise returns the empty
     *         list.
     */
    public ClassInfoList getAllSubinterfaces() {
        if (!isInterfaceOrAnnotation()) {
            return ClassInfoList.EMPTY_LIST;
        }
        return getAllClassesImplementing().filter(ClassInfo::isInterfaceOrAnnotation);
    }

    /**
     * Get the direct subinterfaces of this interface, i.e. only the interfaces that name this interface in their
     * {@code extends} clause, if this is an interface.
     *
     * @return the list of the direct subinterfaces of this interface, if this is an interface, otherwise returns
     *         the empty list.
     */
    public ClassInfoList getDirectSubinterfaces() {
        if (!isInterfaceOrAnnotation()) {
            return ClassInfoList.EMPTY_LIST;
        }
        return getDirectClassesImplementing().filter(ClassInfo::isInterfaceOrAnnotation);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /**
     * Get the annotations and meta-annotations on this class. (Call {@link #getAllAnnotationInfo()} instead, if you
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
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    public ClassInfoList getAllAnnotations() {
        synchronized (this) {
            if (annotationsRef != null) {
                return annotationsRef;
            }

            scanResult().scanSpec.checkAnnotationInfoEnabled();

            // Get all annotations on this class
            final var annotationClasses = this.filterClassInfo(RelType.CLASS_ANNOTATIONS,
                    /* strictAccept = */ false);
            // Check for any @Inherited annotations on superclasses
            Set<ClassInfo> inheritedSuperclassAnnotations = null;
            for (final ClassInfo superclass : getAllSuperclasses()) {
                for (final ClassInfo superclassAnnotation : superclass
                        .filterClassInfo(RelType.CLASS_ANNOTATIONS, /* strictAccept = */ false)
                        .reachableClasses()) {
                    // Check if any of the meta-annotations on this annotation are @Inherited, which causes an
                    // annotation to annotate a class and all of its subclasses.
                    if (superclassAnnotation.isInherited) {
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
                annotationsRef = new ClassInfoList(annotationClasses, /* sortByName = */ true);
            } else {
                // Merge inherited superclass annotations and annotations on this class
                inheritedSuperclassAnnotations.addAll(annotationClasses.reachableClasses());
                annotationsRef = new ClassInfoList(inheritedSuperclassAnnotations,
                        annotationClasses.directlyRelatedClasses(), /* sortByName = */ true);
            }
            return annotationsRef;
        }
    }

    /**
     * Get only the annotations directly present on this class, not the meta-annotations on those annotations, and
     * not the {@link Inherited} annotations of superclasses. (Call {@link #getDirectAnnotationInfo()} instead, if
     * you need the parameter values of annotations, rather than just the annotation classes.)
     *
     * @return the list of annotations directly present on this class.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    public ClassInfoList getDirectAnnotations() {
        return getAllAnnotations().directOnly();
    }

    /**
     * Throw {@link IllegalStateException} if the annotations on fields or methods were not recorded during the
     * scan, which needs both the field or method info and the annotation info to have been enabled.
     *
     * @param isField
     *            true to check for field annotations, false to check for method or method parameter annotations.
     * @throws IllegalStateException
     *             if the required scan options were not enabled before the scan.
     */
    private void checkFieldOrMethodAnnotationInfoEnabled(final boolean isField) {
        final var scanSpec = scanResult().scanSpec;
        if (!(isField ? scanSpec.enableFieldInfo : scanSpec.enableMethodInfo) || !scanSpec.enableAnnotationInfo) {
            throw new IllegalStateException("Please call ClassGraph#enable" + (isField ? "Field" : "Method")
                    + "Info() and #enableAnnotationInfo() before #scan()");
        }
    }

    /**
     * Get the annotations or meta-annotations on fields, methods or method parameters declared by the class, (not
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
        final var isField = relType == RelType.FIELD_ANNOTATIONS;
        checkFieldOrMethodAnnotationInfoEnabled(isField);
        final var fieldOrMethodAnnotations = this.filterClassInfo(relType, /* strictAccept = */ false,
                ClassType.ANNOTATION);
        final Set<ClassInfo> fieldOrMethodAnnotationsAndMetaAnnotations = new LinkedHashSet<>(
                fieldOrMethodAnnotations.reachableClasses());
        return new ClassInfoList(fieldOrMethodAnnotationsAndMetaAnnotations,
                fieldOrMethodAnnotations.directlyRelatedClasses(), /* sortByName = */ true);
    }

    /**
     * Get the classes that have this class as a direct field, method or method parameter annotation.
     *
     * @param relType
     *            One of {@link RelType#CLASSES_WITH_FIELD_ANNOTATION},
     *            {@link RelType#CLASSES_WITH_METHOD_ANNOTATION}, or
     *            {@link RelType#CLASSES_WITH_METHOD_PARAMETER_ANNOTATION}.
     * @return A list of classes that declare a field, method or method parameter that is directly annotated (i.e.
     *         is not meta-annotated) with this annotation, or the empty list if none.
     */
    private ClassInfoList getClassesWithFieldOrMethodAnnotationDirectOnly(final RelType relType) {
        return new ClassInfoList(this.filterClassInfo(relType, /* strictAccept = */ true), /* sortByName = */ true);
    }

    /**
     * Get the classes that have this class as a field, method or method parameter annotation, plus the subclasses
     * of any class whose annotated field or method is non-private, since non-private fields and methods are
     * inherited.
     *
     * @param relType
     *            One of {@link RelType#CLASSES_WITH_FIELD_ANNOTATION},
     *            {@link RelType#CLASSES_WITH_METHOD_ANNOTATION}, or
     *            {@link RelType#CLASSES_WITH_METHOD_PARAMETER_ANNOTATION}.
     * @param nonprivateRelType
     *            the corresponding {@code CLASSES_WITH_NONPRIVATE_*} relation type.
     * @return A list of classes that have a field, method or method parameter with this annotation or
     *         meta-annotation, or the empty list if none.
     */
    private ClassInfoList getClassesWithFieldOrMethodAnnotationAndSubclasses(final RelType relType,
            final RelType nonprivateRelType) {
        // Get all classes that have a field or method annotated or meta-annotated with this annotation
        final Set<ClassInfo> classesWithAnnotation = new HashSet<>(getClassesWithFieldOrMethodAnnotation(relType));
        // Add subclasses of all classes with a field or method that is non-privately annotated or meta-annotated
        // with this annotation, since non-private fields and methods are inherited
        for (final ClassInfo classWithNonprivateAnnotation : getClassesWithFieldOrMethodAnnotation(
                nonprivateRelType)) {
            classesWithAnnotation.addAll(classWithNonprivateAnnotation.getAllSubclasses());
        }
        return new ClassInfoList(classesWithAnnotation,
                new HashSet<>(getClassesWithFieldOrMethodAnnotationDirectOnly(relType)), /* sortByName = */ true);
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
        final var isField = relType == RelType.CLASSES_WITH_FIELD_ANNOTATION
                || relType == RelType.CLASSES_WITH_NONPRIVATE_FIELD_ANNOTATION;
        checkFieldOrMethodAnnotationInfoEnabled(isField);
        final var classesWithDirectlyAnnotatedFieldsOrMethods = this.filterClassInfo(relType,
                /* strictAccept = */ true);
        // Don't filter the meta-annotated annotations -- they are only traversed through, and an accepted class can
        // have a field or method annotated by an external annotation that is meta-annotated by this one
        final var annotationsWithThisMetaAnnotation = this.filterClassInfo(RelType.CLASSES_WITH_ANNOTATION,
                /* strictAccept = */ false, ClassType.ANNOTATION);
        if (annotationsWithThisMetaAnnotation.reachableClasses().isEmpty()) {
            // This annotation does not meta-annotate another annotation that annotates a method
            return new ClassInfoList(classesWithDirectlyAnnotatedFieldsOrMethods, /* sortByName = */ true);
        } else {
            // Take the union of all classes with fields or methods directly annotated by this annotation, and
            // classes with fields or methods meta-annotated by this annotation
            final Set<ClassInfo> allClassesWithAnnotatedOrMetaAnnotatedFieldsOrMethods = new LinkedHashSet<>(
                    classesWithDirectlyAnnotatedFieldsOrMethods.reachableClasses());
            for (final ClassInfo metaAnnotatedAnnotation : annotationsWithThisMetaAnnotation.reachableClasses()) {
                allClassesWithAnnotatedOrMetaAnnotatedFieldsOrMethods.addAll(metaAnnotatedAnnotation
                        .filterClassInfo(relType, /* strictAccept = */ true).reachableClasses());
            }
            return new ClassInfoList(allClassesWithAnnotatedOrMetaAnnotatedFieldsOrMethods,
                    classesWithDirectlyAnnotatedFieldsOrMethods.directlyRelatedClasses(), /* sortByName = */ true);
        }
    }

    /**
     * Get a list of the annotations and meta-annotations on this class, or the empty list if none.
     *
     * <p>
     * Also handles the {@link Inherited} meta-annotation, which causes an annotation to annotate a class and all of
     * its subclasses.
     *
     * @return A list of {@link AnnotationInfo} objects for the annotations and meta-annotations on this class, or
     *         the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    @Override
    public AnnotationInfoList getAllAnnotationInfo() {
        synchronized (this) {
            if (annotationInfoRef != null) {
                return annotationInfoRef;
            }

            scanResult().scanSpec.checkAnnotationInfoEnabled();

            annotationInfoRef = unmodifiable(AnnotationInfoList.getIndirectAnnotations(annotationInfo, this));
            return annotationInfoRef;
        }
    }

    /**
     * Get the default parameter values for this annotation, if this is an annotation class.
     *
     * @return A list of {@link AnnotationParameterValue} objects for each of the default parameter values for this
     *         annotation, if this is an annotation class with default parameter values, otherwise the empty list.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning, or if this class is
     *             not an annotation.
     */
    public AnnotationParameterValueList getAnnotationDefaultParameterValues() {
        scanResult().scanSpec.checkAnnotationInfoEnabled();
        if (!isAnnotation()) {
            throw new IllegalStateException("Class is not an annotation: " + getName());
        }
        synchronized (this) {
            if (annotationDefaultParamValues == null) {
                return AnnotationParameterValueList.EMPTY_LIST;
            }
            if (!annotationDefaultParamValuesHasBeenConvertedToPrimitive) {
                annotationDefaultParamValues.convertWrapperArraysToPrimitiveArrays(this);
                annotationDefaultParamValuesHasBeenConvertedToPrimitive = true;
            }
            return unmodifiable(annotationDefaultParamValues);
        }
    }

    /**
     * Get the classes that have this class as an annotation.
     *
     * @return A list of standard classes and non-annotation interfaces that are annotated by this class, if this is
     *         an annotation class, or the empty list if none. Also handles the {@link Inherited} meta-annotation,
     *         which causes an annotation on a class to be inherited by all of its subclasses.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     */
    public ClassInfoList getClassesWithAnnotation() {
        scanResult().scanSpec.checkAnnotationInfoEnabled();

        if (isInherited) {
            // If this is an inherited annotation, add into the result all subclasses of the annotated classes.
            // Don't filter the two traversals, since an accepted class can inherit the annotation from an external
            // superclass -- filter the union at the end instead.
            final var classesWithAnnotation = this.filterClassInfo(RelType.CLASSES_WITH_ANNOTATION,
                    /* strictAccept = */ false);
            final Set<ClassInfo> classesWithAnnotationAndTheirSubclasses = new LinkedHashSet<>(
                    classesWithAnnotation.reachableClasses());
            for (final ClassInfo classWithAnnotation : classesWithAnnotation.reachableClasses()) {
                classesWithAnnotationAndTheirSubclasses.addAll(classWithAnnotation
                        .filterClassInfo(RelType.SUBCLASSES, /* strictAccept = */ false).reachableClasses());
            }
            final var scanSpec = scanResult().scanSpec;
            return new ClassInfoList(
                    ClassInfo.filterClassInfo(classesWithAnnotationAndTheirSubclasses, scanSpec,
                            /* strictAccept = */ true),
                    ClassInfo.filterClassInfo(classesWithAnnotation.directlyRelatedClasses(), scanSpec,
                            /* strictAccept = */ true),
                    /* sortByName = */ true);
        } else {
            // If not inherited, only return the annotated classes
            return new ClassInfoList(
                    this.filterClassInfo(RelType.CLASSES_WITH_ANNOTATION, /* strictAccept = */ true),
                    /* sortByName = */ true);
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Methods

    /**
     * Get the methods and/or constructors declared by the class. Static initializer blocks are never returned --
     * they can only be reached by name, via {@link #getDeclaredMethodInfo(String)}.
     *
     * @param getNormalMethods
     *            whether to get methods that are not constructors
     * @param getConstructorMethods
     *            whether to get constructors
     * @return the declared method info
     */
    private MethodInfoList getDeclaredMethodInfoOfKind(final boolean getNormalMethods,
            final boolean getConstructorMethods) {
        scanResult().scanSpec.checkMethodInfoEnabled();
        if (methodInfo == null) {
            return MethodInfoList.EMPTY_LIST;
        }
        final var methodInfoList = new MethodInfoList();
        for (final MethodInfo mi : methodInfo) {
            final var miName = mi.getName();
            final var isConstructor = "<init>".equals(miName);
            final var isStaticInitializer = "<clinit>".equals(miName);
            if (isConstructor ? getConstructorMethods : !isStaticInitializer && getNormalMethods) {
                methodInfoList.add(mi);
            }
        }
        return unmodifiable(methodInfoList);
    }

    /**
     * Get the methods declared by the class that have the given name. Unlike
     * {@link #getDeclaredMethodInfoOfKind(boolean, boolean)}, this ignores the kind of the method, so it can return
     * constructors ({@code "<init>"}) and static initializer blocks ({@code "<clinit>"}).
     *
     * @param methodName
     *            the method name
     * @return the declared method info
     */
    private MethodInfoList getDeclaredMethodInfoWithName(final String methodName) {
        scanResult().scanSpec.checkMethodInfoEnabled();
        if (methodInfo == null) {
            return MethodInfoList.EMPTY_LIST;
        }
        final var methodInfoList = new MethodInfoList();
        for (final MethodInfo mi : methodInfo) {
            if (mi.getName().equals(methodName)) {
                methodInfoList.add(mi);
            }
        }
        return methodInfoList.isEmpty() ? MethodInfoList.EMPTY_LIST : unmodifiable(methodInfoList);
    }

    /**
     * Merge the declared methods of this class and of its superclasses and interfaces, in method override order,
     * dropping any method that was already overridden by a method of the same name and type descriptor.
     *
     * @param declaredMethodsOf
     *            returns the declared methods to merge, for one class in the override order
     * @return the merged method info
     */
    private MethodInfoList mergeDeclaredMethodsInOverrideOrder(
            final Function<ClassInfo, MethodInfoList> declaredMethodsOf) {
        scanResult().scanSpec.checkMethodInfoEnabled();
        final var methodInfoList = new MethodInfoList();
        final Set<Entry<String, String>> nameAndTypeDescriptorSet = new HashSet<>();
        for (final ClassInfo ci : getMethodOverrideOrder()) {
            for (final MethodInfo mi : declaredMethodsOf.apply(ci)) {
                // If method has not been overridden by method of same name and type descriptor
                if (nameAndTypeDescriptorSet.add(new SimpleEntry<>(mi.getName(), mi.getTypeDescriptorString()))) {
                    // Add method to output order
                    methodInfoList.add(mi);
                }
            }
        }
        return unmodifiable(methodInfoList);
    }

    /**
     * Get the methods and/or constructors of the class, its superclasses and its interfaces. Static initializer
     * blocks are never returned -- they can only be reached by name, via {@link #getMethodInfo(String)}.
     *
     * @param getNormalMethods
     *            whether to get methods that are not constructors
     * @param getConstructorMethods
     *            whether to get constructors
     * @return the method info
     */
    private MethodInfoList getMethodInfoOfKind(final boolean getNormalMethods,
            final boolean getConstructorMethods) {
        // Constructors are not inherited from superclasses
        return mergeDeclaredMethodsInOverrideOrder(
                ci -> ci.getDeclaredMethodInfoOfKind(getNormalMethods, ci == this && getConstructorMethods));
    }

    /**
     * Get the methods of the class, its superclasses and its interfaces that have the given name. Unlike
     * {@link #getMethodInfoOfKind(boolean, boolean)}, this ignores the kind of the method, so it can return
     * constructors ({@code "<init>"}) and static initializer blocks ({@code "<clinit>"}).
     *
     * @param methodName
     *            the method name
     * @return the method info
     */
    private MethodInfoList getMethodInfoWithName(final String methodName) {
        return mergeDeclaredMethodsInOverrideOrder(ci -> ci.getDeclaredMethodInfoWithName(methodName));
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
     * {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * @return the list of {@link MethodInfo} objects for visible methods declared by this class, or the empty list
     *         if no methods were found.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getDeclaredMethodInfo() {
        return getDeclaredMethodInfoOfKind(/* getNormalMethods = */ true, /* getConstructorMethods = */ false);
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
     * {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * @return the list of {@link MethodInfo} objects for visible methods of this class, its interfaces and
     *         superclasses, or the empty list if no methods were found.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getMethodInfo() {
        return getMethodInfoOfKind(/* getNormalMethods = */ true, /* getConstructorMethods = */ false);
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
     * {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public constructors, unless
     * {@link ClassGraph#ignoreMethodVisibility()} was called before the scan.
     *
     * @return the list of {@link MethodInfo} objects for visible constructors declared by this class, or the empty
     *         list if no constructors were found or visible.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getDeclaredConstructorInfo() {
        return getDeclaredMethodInfoOfKind(/* getNormalMethods = */ false, /* getConstructorMethods = */ true);
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
     * {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * @return the list of {@link MethodInfo} objects for visible constructors of this class and its superclasses,
     *         or the empty list if no methods were found.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getConstructorInfo() {
        return getMethodInfoOfKind(/* getNormalMethods = */ false, /* getConstructorMethods = */ true);
    }

    /**
     * Returns information on visible methods and constructors declared by this class, but not by its interfaces or
     * superclasses. Constructors have the method name of {@code "<init>"}. See also:
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
     * {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public methods and constructors, unless
     * {@link ClassGraph#ignoreMethodVisibility()} was called before the scan.
     *
     * <p>
     * Static initializer blocks are not returned. They can only be looked up by name, using
     * {@link #getDeclaredMethodInfo(String)} with a method name of {@code "<clinit>"}, and only if
     * {@link ClassGraph#ignoreMethodVisibility()} was called before the scan.
     *
     * @return the list of {@link MethodInfo} objects for visible methods and constructors of this class, or the
     *         empty list if no methods or constructors were found or visible.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getDeclaredMethodAndConstructorInfo() {
        return getDeclaredMethodInfoOfKind(/* getNormalMethods = */ true, /* getConstructorMethods = */ true);
    }

    /**
     * Returns information on visible methods and constructors declared by this class, or by its interfaces or
     * superclasses. Constructors have the method name of {@code "<init>"}. See also:
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
     * {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * <p>
     * Static initializer blocks are not returned. They can only be looked up by name, using
     * {@link #getMethodInfo(String)} with a method name of {@code "<clinit>"}, and only if
     * {@link ClassGraph#ignoreMethodVisibility()} was called before the scan.
     *
     * @return the list of {@link MethodInfo} objects for visible methods and constructors of this class, its
     *         interfaces and superclasses, or the empty list if no methods were found.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getMethodAndConstructorInfo() {
        return getMethodInfoOfKind(/* getNormalMethods = */ true, /* getConstructorMethods = */ true);
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
     * {@link IllegalStateException}.
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
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getDeclaredMethodInfo(final String methodName) {
        Assert.notNull(methodName, "methodName");
        return getDeclaredMethodInfoWithName(methodName);
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
     * {@link IllegalStateException}.
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
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} was not called prior to initiating the scan.
     */
    public MethodInfoList getMethodInfo(final String methodName) {
        Assert.notNull(methodName, "methodName");
        return getMethodInfoWithName(methodName);
    }

    /**
     * Returns information on visible methods declared by this class that are not constructors, and that have the
     * named annotation or meta-annotation. See also:
     *
     * <ul>
     * <li>{@link #getMethodInfoWithAnnotation(String)}
     * <li>{@link #getDeclaredMethodInfo()}
     * </ul>
     *
     * <p>
     * Constructors are not included -- to find annotated constructors, filter
     * {@link #getDeclaredMethodAndConstructorInfo()} using
     * {@link MethodInfoList#filter(java.util.function.Predicate)}.
     *
     * <p>
     * Requires that {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} be called
     * before scanning, otherwise throws {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * @param methodAnnotationName
     *            The name of the method annotation.
     * @return the list of {@link MethodInfo} objects for visible methods declared by this class that have the named
     *         annotation or meta-annotation, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} or {@link ClassGraph#enableAnnotationInfo()} was not
     *             called prior to initiating the scan.
     */
    public MethodInfoList getDeclaredMethodInfoWithAnnotation(final String methodAnnotationName) {
        Assert.notNull(methodAnnotationName, "methodAnnotationName");
        return filterByAnnotation(getDeclaredMethodInfo(), methodAnnotationName);
    }

    /**
     * Returns information on visible methods declared by this class that are not constructors, and that have the
     * given annotation or meta-annotation. See also:
     *
     * <ul>
     * <li>{@link #getMethodInfoWithAnnotation(Class)}
     * <li>{@link #getDeclaredMethodInfo()}
     * </ul>
     *
     * <p>
     * Constructors are not included -- to find annotated constructors, filter
     * {@link #getDeclaredMethodAndConstructorInfo()} using
     * {@link MethodInfoList#filter(java.util.function.Predicate)}.
     *
     * <p>
     * Requires that {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} be called
     * before scanning, otherwise throws {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * @param methodAnnotation
     *            The method annotation. Only the name of this class is used for the lookup, so the class itself
     *            does not need to have been on the scanned classpath.
     * @return the list of {@link MethodInfo} objects for visible methods declared by this class that have the given
     *         annotation or meta-annotation, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} or {@link ClassGraph#enableAnnotationInfo()} was not
     *             called prior to initiating the scan.
     * @throws IllegalArgumentException
     *             if {@code methodAnnotation} is not an annotation type.
     */
    public MethodInfoList getDeclaredMethodInfoWithAnnotation(final Class<? extends Annotation> methodAnnotation) {
        Assert.notNull(methodAnnotation, "methodAnnotation");
        Assert.isAnnotation(methodAnnotation);
        return getDeclaredMethodInfoWithAnnotation(methodAnnotation.getName());
    }

    /**
     * Returns information on visible methods declared by this class, or by its interfaces or superclasses, that are
     * not constructors, and that have the named annotation or meta-annotation. See also:
     *
     * <ul>
     * <li>{@link #getDeclaredMethodInfoWithAnnotation(String)}
     * <li>{@link #getMethodInfo()}
     * </ul>
     *
     * <p>
     * Constructors are not included -- to find annotated constructors, filter
     * {@link #getMethodAndConstructorInfo()} using {@link MethodInfoList#filter(java.util.function.Predicate)}.
     *
     * <p>
     * Requires that {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} be called
     * before scanning, otherwise throws {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * @param methodAnnotationName
     *            The name of the method annotation.
     * @return the list of {@link MethodInfo} objects for visible methods of this class, its interfaces and
     *         superclasses that have the named annotation or meta-annotation, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} or {@link ClassGraph#enableAnnotationInfo()} was not
     *             called prior to initiating the scan.
     */
    public MethodInfoList getMethodInfoWithAnnotation(final String methodAnnotationName) {
        Assert.notNull(methodAnnotationName, "methodAnnotationName");
        return filterByAnnotation(getMethodInfo(), methodAnnotationName);
    }

    /**
     * Returns information on visible methods declared by this class, or by its interfaces or superclasses, that are
     * not constructors, and that have the given annotation or meta-annotation. See also:
     *
     * <ul>
     * <li>{@link #getDeclaredMethodInfoWithAnnotation(Class)}
     * <li>{@link #getMethodInfo()}
     * </ul>
     *
     * <p>
     * Constructors are not included -- to find annotated constructors, filter
     * {@link #getMethodAndConstructorInfo()} using {@link MethodInfoList#filter(java.util.function.Predicate)}.
     *
     * <p>
     * Requires that {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} be called
     * before scanning, otherwise throws {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public methods, unless {@link ClassGraph#ignoreMethodVisibility()}
     * was called before the scan.
     *
     * @param methodAnnotation
     *            The method annotation. Only the name of this class is used for the lookup, so the class itself
     *            does not need to have been on the scanned classpath.
     * @return the list of {@link MethodInfo} objects for visible methods of this class, its interfaces and
     *         superclasses that have the given annotation or meta-annotation, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} or {@link ClassGraph#enableAnnotationInfo()} was not
     *             called prior to initiating the scan.
     * @throws IllegalArgumentException
     *             if {@code methodAnnotation} is not an annotation type.
     */
    public MethodInfoList getMethodInfoWithAnnotation(final Class<? extends Annotation> methodAnnotation) {
        Assert.notNull(methodAnnotation, "methodAnnotation");
        Assert.isAnnotation(methodAnnotation);
        return getMethodInfoWithAnnotation(methodAnnotation.getName());
    }

    /**
     * Filter a {@link MethodInfoList} down to the methods that have a given annotation or meta-annotation.
     *
     * @param methodInfoList
     *            the methods to filter.
     * @param methodAnnotationName
     *            the name of the method annotation.
     * @return the filtered list.
     */
    private static MethodInfoList filterByAnnotation(final MethodInfoList methodInfoList,
            final String methodAnnotationName) {
        return methodInfoList.filter(methodInfo -> methodInfo.hasAnnotation(methodAnnotationName));
    }

    /**
     * Get all method annotations.
     *
     * @return A list of all annotations or meta-annotations on methods declared by the class, (not including
     *         methods declared by the interfaces or superclasses of this class), as a list of {@link ClassInfo}
     *         objects, or the empty list if none. N.B. these annotations do not contain specific annotation
     *         parameters -- call {@link MethodInfo#getAllAnnotationInfo()} to get details on specific method
     *         annotation instances.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
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
     *         parameters -- call {@link MethodInfo#getAllAnnotationInfo()} to get details on specific method
     *         annotation instances.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
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
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public ClassInfoList getClassesWithMethodAnnotation() {
        return getClassesWithFieldOrMethodAnnotationAndSubclasses(RelType.CLASSES_WITH_METHOD_ANNOTATION,
                RelType.CLASSES_WITH_NONPRIVATE_METHOD_ANNOTATION);
    }

    /**
     * Get all classes that have this class as a method parameter annotation, and their subclasses, if the method is
     * non-private.
     *
     * @return A list of classes that have a declared method with a parameter that is annotated with this annotation
     *         or meta-annotation, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableMethodInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public ClassInfoList getClassesWithMethodParameterAnnotation() {
        return getClassesWithFieldOrMethodAnnotationAndSubclasses(RelType.CLASSES_WITH_METHOD_PARAMETER_ANNOTATION,
                RelType.CLASSES_WITH_NONPRIVATE_METHOD_PARAMETER_ANNOTATION);
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
     * {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public fields, unless {@link ClassGraph#ignoreFieldVisibility()} was
     * called before the scan.
     *
     * @return the list of FieldInfo objects for visible fields declared by this class, or the empty list if no
     *         fields were found or visible.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} was not called prior to initiating the scan.
     */
    public FieldInfoList getDeclaredFieldInfo() {
        scanResult().scanSpec.checkFieldInfoEnabled();
        return fieldInfo == null ? FieldInfoList.EMPTY_LIST : unmodifiable(fieldInfo);
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
     * {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public fields, unless {@link ClassGraph#ignoreFieldVisibility()} was
     * called before the scan.
     *
     * @return the list of FieldInfo objects for visible fields of this class or its superclasses, or the empty list
     *         if no fields were found or visible.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} was not called prior to initiating the scan.
     */
    public FieldInfoList getFieldInfo() {
        scanResult().scanSpec.checkFieldInfoEnabled();
        // Implement field overriding
        final FieldInfoList fieldInfoList = new FieldInfoList();
        final Set<String> fieldNameSet = new HashSet<>();
        for (final ClassInfo ci : getFieldOverrideOrder()) {
            for (final FieldInfo fi : ci.getDeclaredFieldInfo()) {
                // If field has not been overridden by field of same name
                if (fieldNameSet.add(fi.getName())) {
                    // Add field to output order
                    fieldInfoList.add(fi);
                }
            }
        }
        return unmodifiable(fieldInfoList);
    }

    /**
     * Get the enum constants of an enum class.
     *
     * @return All enum constants of an enum class as a list of {@link FieldInfo} objects (enum constants are stored
     *         as fields in Java classes).
     * @throws IllegalStateException
     *             if this class is not an enum, or if {@link ClassGraph#enableFieldInfo()} was not called before
     *             scanning.
     */
    public FieldInfoList getEnumConstants() {
        if (!isEnum()) {
            throw new IllegalStateException("Class " + getName() + " is not an enum");
        }
        return getFieldInfo().filter(FieldInfo::isEnum);
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
     * {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public fields, unless {@link ClassGraph#ignoreFieldVisibility()} was
     * called before the scan.
     *
     * @param fieldName
     *            The field name.
     * @return the {@link FieldInfo} object for the named field declared by this class, or null if the field was not
     *         found in this class (or is not visible).
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} was not called prior to initiating the scan.
     */
    public @Nullable FieldInfo getDeclaredFieldInfo(final String fieldName) {
        Assert.notNull(fieldName, "fieldName");
        scanResult().scanSpec.checkFieldInfoEnabled();
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
     * Returns information on the named field declared by this class, or by its superclasses. See also:
     *
     * <ul>
     * <li>{@link #getDeclaredFieldInfo(String)}
     * <li>{@link #getFieldInfo()}
     * <li>{@link #getDeclaredFieldInfo()}
     * </ul>
     *
     * <p>
     * Requires that {@link ClassGraph#enableFieldInfo()} be called before scanning, otherwise throws
     * {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public fields, unless {@link ClassGraph#ignoreFieldVisibility()} was
     * called before the scan.
     *
     * @param fieldName
     *            The field name.
     * @return the {@link FieldInfo} object for the named field of this class or its superclasses, or null if the
     *         field was not found or is not visible.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} was not called prior to initiating the scan.
     */
    public @Nullable FieldInfo getFieldInfo(final String fieldName) {
        Assert.notNull(fieldName, "fieldName");
        scanResult().scanSpec.checkFieldInfoEnabled();
        // Implement field overriding
        for (final ClassInfo ci : getFieldOverrideOrder()) {
            final var fi = ci.getDeclaredFieldInfo(fieldName);
            if (fi != null) {
                return fi;
            }
        }
        return null;
    }

    /**
     * Returns information on visible fields declared by this class that have the named annotation or
     * meta-annotation. See also:
     *
     * <ul>
     * <li>{@link #getFieldInfoWithAnnotation(String)}
     * <li>{@link #getDeclaredFieldInfo()}
     * </ul>
     *
     * <p>
     * Requires that {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()} be called
     * before scanning, otherwise throws {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public fields, unless {@link ClassGraph#ignoreFieldVisibility()} was
     * called before the scan.
     *
     * @param fieldAnnotationName
     *            The name of the field annotation.
     * @return the list of {@link FieldInfo} objects for visible fields declared by this class that have the named
     *         annotation or meta-annotation, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} or {@link ClassGraph#enableAnnotationInfo()} was not
     *             called prior to initiating the scan.
     */
    public FieldInfoList getDeclaredFieldInfoWithAnnotation(final String fieldAnnotationName) {
        Assert.notNull(fieldAnnotationName, "fieldAnnotationName");
        return filterByAnnotation(getDeclaredFieldInfo(), fieldAnnotationName);
    }

    /**
     * Returns information on visible fields declared by this class that have the given annotation or
     * meta-annotation. See also:
     *
     * <ul>
     * <li>{@link #getFieldInfoWithAnnotation(Class)}
     * <li>{@link #getDeclaredFieldInfo()}
     * </ul>
     *
     * <p>
     * Requires that {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()} be called
     * before scanning, otherwise throws {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public fields, unless {@link ClassGraph#ignoreFieldVisibility()} was
     * called before the scan.
     *
     * @param fieldAnnotation
     *            The field annotation. Only the name of this class is used for the lookup, so the class itself does
     *            not need to have been on the scanned classpath.
     * @return the list of {@link FieldInfo} objects for visible fields declared by this class that have the given
     *         annotation or meta-annotation, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} or {@link ClassGraph#enableAnnotationInfo()} was not
     *             called prior to initiating the scan.
     * @throws IllegalArgumentException
     *             if {@code fieldAnnotation} is not an annotation type.
     */
    public FieldInfoList getDeclaredFieldInfoWithAnnotation(final Class<? extends Annotation> fieldAnnotation) {
        Assert.notNull(fieldAnnotation, "fieldAnnotation");
        Assert.isAnnotation(fieldAnnotation);
        return getDeclaredFieldInfoWithAnnotation(fieldAnnotation.getName());
    }

    /**
     * Returns information on visible fields declared by this class, or by its interfaces or superclasses, that have
     * the named annotation or meta-annotation. See also:
     *
     * <ul>
     * <li>{@link #getDeclaredFieldInfoWithAnnotation(String)}
     * <li>{@link #getFieldInfo()}
     * </ul>
     *
     * <p>
     * Requires that {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()} be called
     * before scanning, otherwise throws {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public fields, unless {@link ClassGraph#ignoreFieldVisibility()} was
     * called before the scan.
     *
     * @param fieldAnnotationName
     *            The name of the field annotation.
     * @return the list of {@link FieldInfo} objects for visible fields of this class, its interfaces and
     *         superclasses that have the named annotation or meta-annotation, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} or {@link ClassGraph#enableAnnotationInfo()} was not
     *             called prior to initiating the scan.
     */
    public FieldInfoList getFieldInfoWithAnnotation(final String fieldAnnotationName) {
        Assert.notNull(fieldAnnotationName, "fieldAnnotationName");
        return filterByAnnotation(getFieldInfo(), fieldAnnotationName);
    }

    /**
     * Returns information on visible fields declared by this class, or by its interfaces or superclasses, that have
     * the given annotation or meta-annotation. See also:
     *
     * <ul>
     * <li>{@link #getDeclaredFieldInfoWithAnnotation(Class)}
     * <li>{@link #getFieldInfo()}
     * </ul>
     *
     * <p>
     * Requires that {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()} be called
     * before scanning, otherwise throws {@link IllegalStateException}.
     *
     * <p>
     * By default only returns information for public fields, unless {@link ClassGraph#ignoreFieldVisibility()} was
     * called before the scan.
     *
     * @param fieldAnnotation
     *            The field annotation. Only the name of this class is used for the lookup, so the class itself does
     *            not need to have been on the scanned classpath.
     * @return the list of {@link FieldInfo} objects for visible fields of this class, its interfaces and
     *         superclasses that have the given annotation or meta-annotation, or the empty list if none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} or {@link ClassGraph#enableAnnotationInfo()} was not
     *             called prior to initiating the scan.
     * @throws IllegalArgumentException
     *             if {@code fieldAnnotation} is not an annotation type.
     */
    public FieldInfoList getFieldInfoWithAnnotation(final Class<? extends Annotation> fieldAnnotation) {
        Assert.notNull(fieldAnnotation, "fieldAnnotation");
        Assert.isAnnotation(fieldAnnotation);
        return getFieldInfoWithAnnotation(fieldAnnotation.getName());
    }

    /**
     * Filter a {@link FieldInfoList} down to the fields that have a given annotation or meta-annotation.
     *
     * @param fieldInfoList
     *            the fields to filter.
     * @param fieldAnnotationName
     *            the name of the field annotation.
     * @return the filtered list.
     */
    private static FieldInfoList filterByAnnotation(final FieldInfoList fieldInfoList,
            final String fieldAnnotationName) {
        return fieldInfoList.filter(fieldInfo -> fieldInfo.hasAnnotation(fieldAnnotationName));
    }

    /**
     * Get all field annotations.
     *
     * @return A list of all annotations on fields of this class, or the empty list if none. N.B. these annotations
     *         do not contain specific annotation parameters -- call {@link FieldInfo#getAllAnnotationInfo()} to get
     *         details on specific field annotation instances.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public ClassInfoList getFieldAnnotations() {
        return getFieldOrMethodAnnotations(RelType.FIELD_ANNOTATIONS);
    }

    /**
     * Get the classes that have this class as a field annotation or meta-annotation.
     *
     * @return A list of classes that have a field with this annotation or meta-annotation, or the empty list if
     *         none.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()} were not
     *             both called before scanning.
     */
    public ClassInfoList getClassesWithFieldAnnotation() {
        return getClassesWithFieldOrMethodAnnotationAndSubclasses(RelType.CLASSES_WITH_FIELD_ANNOTATION,
                RelType.CLASSES_WITH_NONPRIVATE_FIELD_ANNOTATION);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the parsed type signature for the class.
     *
     * @return The parsed type signature for the class, including any generic type parameters, or null if the class
     *         has no {@code Signature} attribute, which is the case unless the class declaration uses type
     *         variables or parameterized types -- in its own type parameters, its superclass, or its interfaces.
     * @throws IllegalArgumentException
     *             if the class type signature cannot be parsed (this should only be thrown in the case of classfile
     *             corruption, or a compiler bug that causes an invalid type signature to be written to the
     *             classfile).
     */
    public @Nullable ClassTypeSignature getTypeSignature() {
        synchronized (this) {
            if (typeSignatureStr == null) {
                return null;
            }
            if (typeSignature == null) {
                try {
                    typeSignature = ClassTypeSignature.parse(typeSignatureStr, this);
                    typeSignature.setScanResult(scanResult);
                    if (typeAnnotationDecorators != null) {
                        for (final ClassTypeAnnotationDecorator decorator : typeAnnotationDecorators) {
                            decorator.decorate(typeSignature);
                        }
                    }
                } catch (final TypeSignatureParseException e) {
                    throw new IllegalArgumentException("Invalid type signature for class " + getName()
                            + " in classpath element " + getClasspathElementURI() + " : " + typeSignatureStr, e);
                }
            }
        }
        return typeSignature;
    }

    /**
     * Get the type signature string for the class.
     *
     * @return The type signature string for the class, including any generic type parameters, or null if the class
     *         has no {@code Signature} attribute, which is the case unless the class declaration uses type
     *         variables or parameterized types -- in its own type parameters, its superclass, or its interfaces.
     */
    public @Nullable String getTypeSignatureString() {
        return typeSignatureStr;
    }

    /**
     * Returns the parsed type signature for this class, possibly including type parameters. If the class has no
     * {@code Signature} attribute, i.e. if neither the class nor its superclass or interfaces use type variables or
     * parameterized types, then a type descriptor is synthesized from the class, superclass and interface names and
     * returned instead (a classfile may have a class type signature, but never has a class type descriptor). May
     * include type annotations on the superclass or interface(s).
     *
     * @return The parsed generic type signature for the class, or if not available, the synthetic type descriptor
     *         for the class.
     */
    public ClassTypeSignature getTypeSignatureOrTypeDescriptor() {
        ClassTypeSignature typeSig = null;
        try {
            typeSig = getTypeSignature();
            if (typeSig != null) {
                return typeSig;
            }
        } catch (final Exception e) {
            // A corrupt classfile can make the type signature unparseable, or can make a type annotation's target
            // info point outside the type signature it annotates. Fall back to the synthesized type descriptor
            // rather than propagating, since the descriptor is built from the class, superclass and interface
            // names, which were read from a different part of the classfile.
        }
        return getTypeDescriptor();
    }

    /**
     * Returns a synthetic type descriptor for the class, created from the class name, superclass name, and
     * implemented interfaces. May include type annotations on the superclass or interface(s).
     *
     * @return The synthetic type descriptor for the class.
     */
    public ClassTypeSignature getTypeDescriptor() {
        synchronized (this) {
            if (typeDescriptor == null) {
                // The descriptor must list only the directly implemented interfaces, in classfile order, since it
                // stands in for the classfile's own super_class and interfaces[] entries, which is what the class
                // type annotation targets index into
                typeDescriptor = new ClassTypeSignature(this, getSuperclass(), getDirectSuperinterfaces());
                typeDescriptor.setScanResult(scanResult);
                if (typeAnnotationDecorators != null) {
                    for (final ClassTypeAnnotationDecorator decorator : typeAnnotationDecorators) {
                        decorator.decorate(typeDescriptor);
                    }
                }
            }
        }
        return typeDescriptor;
    }

    /**
     * Returns the name of the source file this class has been compiled from, such as {@code ClassInfo.java} or
     * {@code KClass.kt}.
     *
     * <p>
     * This field may be {@code null}.
     *
     * @return The name of the source file of this class, or {@code null} if not available
     */
    public @Nullable String getSourceFile() {
        return sourceFile;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the {@link URI} of the classpath element that this class was found within.
     *
     * @return The {@link URI} of the classpath element that this class was found within.
     * @throws IllegalStateException
     *             if the classpath element does not have a valid URI (e.g. for modules whose location URI is null),
     *             or if the classpath element is not known for this class, because this {@link ClassInfo} is a
     *             placeholder for a class that was referenced by a scanned class but was not itself scanned.
     */
    public URI getClasspathElementURI() {
        if (classfileResource == null) {
            throw new IllegalStateException("Classpath element is not known for class " + getName());
        }
        // Calling classfileResource.getClasspathElementURI() rather than classpathElement.getURI() will append any
        // automatically-stripped package root prefix
        return classfileResource.getClasspathElementURI();
    }

    /**
     * Get the {@link URL} of the classpath element or module that this class was found within.
     *
     * @return The {@link URL} of the classpath element that this class was found within.
     * @throws IllegalStateException
     *             if the classpath element URI cannot be converted to a {@link URL}, or if the classpath element is
     *             not known for this class, because this {@link ClassInfo} is a placeholder for a class that was
     *             referenced by a scanned class but was not itself scanned.
     */
    public URL getClasspathElementURL() {
        try {
            return getClasspathElementURI().toURL();
        } catch (final IllegalArgumentException | MalformedURLException e) {
            throw new IllegalStateException("Could not get classpath element URL", e);
        }
    }

    /**
     * Get the {@link File} for the classpath element package root dir or jar that this class was found within, or
     * null if this class was found in a module. (See also {@link #getModuleReference}.)
     *
     * @return The {@link File} for the classpath element package root dir or jar that this class was found within,
     *         or null if this class was found in a module (see {@link #getModuleReference}). May also return null
     *         if the classpath element was an http/https URL, and the jar was downloaded directly to RAM, rather
     *         than to a temp file on disk (e.g. if the temp dir is not writeable).
     * @throws IllegalStateException
     *             if the classpath element is not known for this class, because this {@link ClassInfo} is a
     *             placeholder for a class that was referenced by a scanned class but was not itself scanned.
     */
    public @Nullable File getClasspathElementFile() {
        if (classpathElement == null) {
            throw new IllegalStateException("Classpath element is not known for class " + getName());
        }
        return classpathElement.getFile();
    }

    /**
     * Get the module that this class was found within, as a {@link ModuleReference}, or null if this class was
     * found in a directory or jar in the classpath. (See also {@link #getClasspathElementFile()}.)
     *
     * @return The module that this class was found within, as a {@link ModuleReference}, or null if this class was
     *         found in a directory or jar in the classpath. (See also {@link #getClasspathElementFile()}.)
     * @throws IllegalStateException
     *             if the classpath element is not known for this class, because this {@link ClassInfo} is a
     *             placeholder for a class that was referenced by a scanned class but was not itself scanned.
     */
    public @Nullable ModuleReference getModuleReference() {
        if (classpathElement == null) {
            throw new IllegalStateException("Classpath element is not known for class " + getName());
        }
        return classpathElement instanceof ClasspathElementModule c ? c.getModuleReference() : null;
    }

    /**
     * The {@link Resource} for the classfile of this class.
     *
     * @return The {@link Resource} for the classfile of this class. Returns null if the classfile for this class
     *         was not actually read during the scan, e.g. because this class was not itself accepted, but was
     *         referenced by an accepted class.
     */
    public @Nullable Resource getResource() {
        return classfileResource;
    }

    /**
     * The string form ({@link Object#toString()}) of the classloader that this class was found under. This is the
     * same string that the verbose scanning log shows for the classpath element, so it can be used to work out
     * which classloader a class came from -- but it is only an identifier, not the classloader itself: ClassGraph
     * reads classfiles directly rather than through a classloader, and drops every classloader reference once the
     * classpath has been found, so that a scan cannot keep a classloader alive.
     *
     * @return The string form of the classloader that this class was found under. Returns null if the classloader
     *         is not known, e.g. because this class was not itself accepted, but was referenced by an accepted
     *         class, or because the class was found in a module loaded by the bootstrap classloader.
     */
    public @Nullable String getClassLoaderString() {
        return classpathElement == null ? null : classpathElement.getClassLoaderString();
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected String getClassName() {
        return name;
    }

    @Override
    protected ClassInfo getClassInfo() {
        return this;
    }

    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
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
     * @param log
     *            the log node, or null to skip logging
     */
    @Override
    void findReferencedClassInfo(final Map<String, ClassInfo> classNameToClassInfo,
            final Set<ClassInfo> refdClassInfo, final @Nullable LogNode log) {
        // Add this class to the set of references
        super.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        if (this.referencedClassNames != null) {
            for (final String refdClassName : this.referencedClassNames) {
                final var classInfo = ClassInfo.getOrCreateClassInfo(refdClassName, classNameToClassInfo);
                classInfo.setScanResult(scanResult);
                refdClassInfo.add(classInfo);
            }
        }
        getMethodInfo().findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        getFieldInfo().findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        getAllAnnotationInfo().findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        if (annotationDefaultParamValues != null) {
            annotationDefaultParamValues.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
        }
        try {
            final var classSig = getTypeSignature();
            if (classSig != null) {
                classSig.findReferencedClassInfo(classNameToClassInfo, refdClassInfo, log);
            }
        } catch (final IllegalArgumentException e) {
            if (log != null) {
                log.log("Illegal type signature for class " + getClassName() + ": " + getTypeSignatureString());
            }
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
     *         {@link ClassGraph#enableExternalClasses()} before {@link ClassGraph#scan()} if you want non-accepted
     *         classes to appear in the result.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableInterClassDependencies()} was not called before scanning.
     */
    public ClassInfoList getClassDependencies() {
        scanResult().scanSpec.checkInterClassDependenciesEnabled();
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
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final ClassInfo other)) {
            return false;
        }
        return name.equals(other.name);
    }

    /**
     * Use hash code of class name.
     *
     * @return the hashcode
     */
    @Override
    public int hashCode() {
        return name.hashCode();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * To string.
     *
     * @param useSimpleNames
     *            if true, strip package and outer class names from class names
     * @param buf
     *            the buffer to append to
     */
    @Override
    protected void toString(final boolean useSimpleNames, final StringBuilder buf) {
        final var initialBufEmpty = buf.length() == 0;
        if (annotationInfo != null) {
            for (final AnnotationInfo annotation : annotationInfo) {
                if (buf.length() > 0 && buf.charAt(buf.length() - 1) != ' '
                        && buf.charAt(buf.length() - 1) != '(') {
                    buf.append(' ');
                }
                annotation.toString(useSimpleNames, buf);
            }
        }
        ClassTypeSignature typeSig = null;
        try {
            typeSig = getTypeSignature();
        } catch (final Exception e) {
            // A corrupt classfile can make the type signature unparseable -- render the class as if it were
            // non-generic, rather than throwing from toString()
        }
        if (typeSig != null) {
            // Generic classes
            // N.B. pass useSimpleNames through, so that the type parameter bounds, the superclass and the
            // superinterfaces are simplified too, not just the class name (toStringInternal simplifies the class
            // name itself if useSimpleNames is true)
            typeSig.toStringInternal(name, useSimpleNames, modifiers, isAnnotation(), isInterface(), buf);
        } else {
            // Non-generic classes
            TypeUtils.modifiersToString(modifiers, ModifierType.CLASS, /* ignored */ false, buf);
            if (buf.length() > 0 && buf.charAt(buf.length() - 1) != ' ' && buf.charAt(buf.length() - 1) != '(') {
                buf.append(' ');
            }
            // Don't put class type in extends/implements clauses
            if (initialBufEmpty) {
                buf.append(isRecord() ? "record " //
                        : isEnum() ? "enum " //
                                : isAnnotation() ? "@interface " //
                                        : isInterface() ? "interface " //
                                                : "class ");
            }
            buf.append(useSimpleNames ? ClassInfo.getSimpleName(name) : name);
            if (isRecord) {
                // Add params, if this is a record class
                buf.append('(');
                var isFirstParam = true;
                for (final FieldInfo fieldInfo : getFieldInfo()) {
                    if (!isFirstParam) {
                        buf.append(", ");
                    } else {
                        isFirstParam = false;
                    }
                    fieldInfo.toString(/* includeModifiers = */ false, /* useSimpleNames = */ false, buf);
                }
                buf.append(')');
            }
            final var superclass = getSuperclass();
            if (superclass != null && !"java.lang.Object".equals(superclass.getName())) {
                buf.append(" extends ");
                superclass.toString(useSimpleNames, buf);
            }
            final var interfaces = this.filterClassInfo(RelType.IMPLEMENTED_INTERFACES, /* strictAccept = */ false)
                    .directlyRelatedClasses();
            if (!interfaces.isEmpty()) {
                buf.append(isInterface() ? " extends " : " implements ");
                var first = true;
                for (final ClassInfo iface : interfaces) {
                    if (first) {
                        first = false;
                    } else {
                        buf.append(", ");
                    }
                    iface.toString(useSimpleNames, buf);
                }
            }
        }
    }
}
