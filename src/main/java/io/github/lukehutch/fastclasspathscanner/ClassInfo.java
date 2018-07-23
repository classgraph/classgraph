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

import java.io.File;
import java.lang.annotation.Inherited;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.json.Id;
import io.github.lukehutch.fastclasspathscanner.utils.JarUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;
import io.github.lukehutch.fastclasspathscanner.utils.TypeUtils;

/** Holds metadata about a class encountered during a scan. */
public class ClassInfo extends ScanResultObject implements Comparable<ClassInfo> {
    /** Name of the class/interface/annotation. */
    @Id
    String className;

    /** Class modifier flags, e.g. Modifier.PUBLIC */
    int modifiers;

    /** True if the classfile indicated this is an interface. */
    boolean isInterface;

    /** True if the classfile indicated this is an annotation. */
    boolean isAnnotation;

    /**
     * This annotation has the {@link Inherited} meta-annotation, which means that any class that this annotation is
     * applied to also implicitly causes the annotation to annotate all subclasses too.
     */
    boolean isInherited;

    /** The class type signature string. */
    String typeSignatureStr;

    /** The class type signature, parsed. */
    transient ClassTypeSignature typeSignature;

    /** The fully-qualified containing method name, for anonymous inner classes. */
    String fullyQualifiedContainingMethodName;

    /**
     * If true, this class is only being referenced by another class' classfile as a superclass / implemented
     * interface / annotation, but this class is not itself a whitelisted (non-blacklisted) class, or in a
     * whitelisted (non-blacklisted) package.
     * 
     * If false, this classfile was matched during scanning (i.e. its classfile contents read), i.e. this class is a
     * whitelisted (and non-blacklisted) class in a whitelisted (and non-blacklisted) package.
     */
    boolean isExternalClass;

    /**
     * The classpath element file (classpath root dir or jar) that this class was found within, or null if this
     * class was found in a module.
     */
    transient File classpathElementFile;

    /**
     * The package root within a jarfile (e.g. "BOOT-INF/classes"), or the empty string if this is not a jarfile, or
     * the package root is the classpath element path (as opposed to within a subdirectory of the classpath
     * element).
     */
    transient String jarfilePackageRoot = "";

    /**
     * The classpath element module that this class was found within, or null if this class was found within a
     * directory or jar.
     */
    transient ModuleRef moduleRef;

    /** The classpath element URL (classpath root dir or jar) that this class was found within. */
    transient URL classpathElementURL;

    /** The classloaders to try to load this class with before calling a MatchProcessor. */
    transient ClassLoader[] classLoaders;

    /** The scan spec. */
    transient ScanSpec scanSpec;

    /** Info on class annotations, including optional annotation param values. */
    AnnotationInfoList annotationInfo;

    /** Info on fields. */
    FieldInfoList fieldInfo;

    /** Reverse mapping from field name to FieldInfo. */
    transient Map<String, FieldInfo> fieldNameToFieldInfo;

    /** Info on fields. */
    MethodInfoList methodInfo;

    /** For annotations, the default values of parameters. */
    List<AnnotationParamValue> annotationDefaultParamValues;

    /** The set of classes related to this one. */
    Map<RelType, Set<ClassInfo>> relatedClasses = new HashMap<>();

    /**
     * The static constant initializer values of static final fields, if a StaticFinalFieldMatchProcessor matched a
     * field in this class.
     */
    Map<String, Object> staticFinalFieldNameToConstantInitializerValue;

    // -------------------------------------------------------------------------------------------------------------

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
    }

    /** Sets back-reference to ScanSpec after deserialization. */
    void setFields(final ScanSpec scanSpec) {
        this.scanSpec = scanSpec;
        if (this.methodInfo != null) {
            for (final MethodInfo methodInfo : this.methodInfo) {
                methodInfo.classInfo = this;
                methodInfo.className = this.className;
            }
        }
    }

    /** Default constructor for deserialization. */
    ClassInfo() {
    }

    private ClassInfo(final String className, final int classModifiers, final boolean isExternalClass,
            final ScanSpec scanSpec) {
        this.className = className;
        if (className.endsWith(";")) {
            // Spot check to make sure class names were parsed from descriptors
            throw new RuntimeException("Bad class name");
        }
        this.modifiers = classModifiers;
        this.isExternalClass = isExternalClass;
        this.scanSpec = scanSpec;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Obtain a {@code Class<?>} reference for the class named by this {@link ClassInfo} object, casting it to the
     * requested interface or superclass type. Causes the ClassLoader to load the class, if it is not already
     * loaded.
     * 
     * <p>
     * Important note: since {@code superclassOrInterfaceType} is a class reference for an already-loaded class, it
     * is critical that {@code superclassOrInterfaceType} is loaded by the same classloader as the class referred to
     * by this {@code ClassInfo} object, otherwise the class cast will fail.
     * 
     * @param superclassOrInterfaceType
     *            The type to cast the loaded class to.
     * @param ignoreExceptions
     *            If true, ignore any exceptions or errors thrown during classloading, or when attempting to cast
     *            the resulting {@code Class<?>} reference to the requested type. If an exception or error is
     *            thrown, no {@code Class<?>} reference is added to the output class for the corresponding
     *            {@link ClassInfo} object, so the returned list may contain fewer items than this input list. If
     *            false, {@link IllegalArgumentException} is thrown if the class could not be loaded or cast to the
     *            requested type.
     * @return The class reference, or null, if ignoreExceptions is true and there was an exception or error loading
     *         the class.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and there were problems loading the class, or casting it to the
     *             requested type.
     */
    public <T> Class<T> getClassRef(final Class<T> superclassOrInterfaceType, final boolean ignoreExceptions) {
        return scanResult.loadClass(className, superclassOrInterfaceType, ignoreExceptions);
    }

    /**
     * Obtain a {@code Class<?>} reference for the class named by this {@link ClassInfo} object, casting it to the
     * requested interface or superclass type. Causes the ClassLoader to load the class, if it is not already
     * loaded.
     * 
     * <p>
     * Important note: since {@code superclassOrInterfaceType} is a class reference for an already-loaded class, it
     * is critical that {@code superclassOrInterfaceType} is loaded by the same classloader as the class referred to
     * by this {@code ClassInfo} object, otherwise the class cast will fail.
     * 
     * @param superclassOrInterfaceType
     *            The type to cast the loaded class to.
     * @return The class reference.
     * @throws IllegalArgumentException
     *             if there were problems loading the class or casting it to the requested type.
     */
    public <T> Class<T> getClassRef(final Class<T> superclassOrInterfaceType) {
        return getClassRef(superclassOrInterfaceType, /* ignoreExceptions = */ false);
    }

    /**
     * Obtain a {@code Class<?>} reference for the class named by this {@link ClassInfo} object. Causes the
     * ClassLoader to load the class, if it is not already loaded.
     * 
     * @return The class reference, or null, if ignoreExceptions is true and there was an exception or error loading
     *         the class.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false and there were problems loading the class.
     */
    public Class<?> getClassRef(final boolean ignoreExceptions) {
        return scanResult.loadClass(className, ignoreExceptions);
    }

    /**
     * Obtain a {@code Class<?>} reference for the class named by this {@link ClassInfo} object. Causes the
     * ClassLoader to load the class, if it is not already loaded.
     * 
     * @return The class reference.
     * @throws IllegalArgumentException
     *             if there were problems loading the class.
     */
    public Class<?> getClassRef() {
        return getClassRef(/* ignoreExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the name of this class.
     * 
     * @return The class name.
     */
    public String getClassName() {
        return className;
    }

    /**
     * Returns true if this class is an external class, i.e. was referenced by a whitelisted class as a superclass /
     * implemented interface / annotation, but is not itself a whitelisted class.
     */
    public boolean isExternalClass() {
        return isExternalClass;
    }

    /**
     * Get the class modifier flags, e.g. Modifier.PUBLIC
     * 
     * @return The class modifiers.
     */
    public int getClassModifiers() {
        return modifiers;
    }

    /**
     * Get the field modifiers as a String, e.g. "public static final". For the modifier bits, call getModifiers().
     * 
     * @return The class modifiers, in String format.
     */
    public String getModifiersStr() {
        return TypeUtils.modifiersToString(modifiers, /* isMethod = */ false);
    }

    /**
     * Test whether this ClassInfo corresponds to a public class.
     *
     * @return true if this ClassInfo corresponds to a public class.
     */
    public boolean isPublic() {
        return (modifiers & Modifier.PUBLIC) != 0;
    }

    /**
     * Test whether this ClassInfo corresponds to an abstract class.
     *
     * @return true if this ClassInfo corresponds to an abstract class.
     */
    public boolean isAbstract() {
        return (modifiers & 0x400) != 0;
    }

    /**
     * Test whether this ClassInfo corresponds to a synthetic class.
     *
     * @return true if this ClassInfo corresponds to a synthetic class.
     */
    public boolean isSynthetic() {
        return (modifiers & 0x1000) != 0;
    }

    /**
     * Test whether this ClassInfo corresponds to a final class.
     *
     * @return true if this ClassInfo corresponds to a final class.
     */
    public boolean isFinal() {
        return (modifiers & Modifier.FINAL) != 0;
    }

    /**
     * Test whether this ClassInfo corresponds to an annotation.
     *
     * @return true if this ClassInfo corresponds to an annotation.
     */
    public boolean isAnnotation() {
        return isAnnotation;
    }

    /**
     * Test whether this ClassInfo corresponds to an interface.
     *
     * @return true if this ClassInfo corresponds to an interface.
     */
    public boolean isInterface() {
        return isInterface;
    }

    /**
     * Test whether this ClassInfo corresponds to an enum.
     *
     * @return true if this ClassInfo corresponds to an enum.
     */
    public boolean isEnum() {
        return (modifiers & 0x4000) != 0;
    }

    /**
     * Get the low-level Java type signature for the class, including generic type parameters, if available (else
     * returns null).
     * 
     * @return The type signature, in string format
     */
    public String getTypeSignatureStr() {
        return typeSignatureStr;
    }

    /**
     * Get the type signature for the class, if available (else returns null).
     * 
     * @return The class type signature.
     */
    public ClassTypeSignature getTypeSignature() {
        if (typeSignatureStr == null) {
            return null;
        }
        if (typeSignature == null) {
            try {
                typeSignature = ClassTypeSignature.parse(typeSignatureStr);
            } catch (final ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return typeSignature;
    }

    /**
     * The classpath element URL (for a classpath root dir, jar or module) that this class was found within.
     * 
     * N.B. Classpath elements are handled as File objects internally. It is much faster to call
     * getClasspathElementFile() and/or getClasspathElementModule() -- the conversion of a File into a URL (via
     * File#toURI()#toURL()) is time consuming.
     * 
     * @return The classpath element, as a URL.
     */
    public URL getClasspathElementURL() {
        if (classpathElementURL == null) {
            try {
                if (moduleRef != null) {
                    classpathElementURL = moduleRef.getModuleLocation().toURL();
                } else {
                    classpathElementURL = getClasspathElementFile().toURI().toURL();
                }
            } catch (final MalformedURLException e) {
                // Shouldn't happen; File objects should always be able to be turned into URIs and then URLs
                throw new RuntimeException(e);
            }
        }
        return classpathElementURL;
    }

    /**
     * The classpath element file (classpath root dir or jar) that this class was found within, or null if this
     * class was found in a module.
     * 
     * @return The classpath element, as a File.
     */
    public File getClasspathElementFile() {
        return classpathElementFile;
    }

    /**
     * The module in the module path that this class was found within, or null if this class was found in a
     * directory or jar in the classpath.
     * 
     * @return The module, as a ModuleRef.
     */
    public ModuleRef getModuleRef() {
        return moduleRef;
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
        final ClassTypeSignature typeSig = getTypeSignature();
        if (typeSig != null) {
            return typeSig.toString(modifiers, isAnnotation, isInterface, className);
        } else {
            final StringBuilder buf = new StringBuilder();
            TypeUtils.modifiersToString(modifiers, /* isMethod = */ false, buf);
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append(isAnnotation ? "@interface "
                    : isInterface ? "interface " : (modifiers & 0x4000) != 0 ? "enum " : "class ");
            buf.append(className);
            return buf.toString();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** How classes are related. */
    static enum RelType {

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
        CLASSES_WITH_CLASS_ANNOTATION,

        // Method annotations:

        /** Annotations on one or more methods of this class. */
        METHOD_ANNOTATIONS,

        /**
         * Classes that have one or more methods annotated with this annotation, if this is an annotation.
         */
        CLASSES_WITH_METHOD_ANNOTATION,

        // Field annotations:

        /** Annotations on one or more fields of this class. */
        FIELD_ANNOTATIONS,

        /**
         * Classes that have one or more fields annotated with this annotation, if this is an annotation.
         */
        CLASSES_WITH_FIELD_ANNOTATION,
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a class with a given relationship type. Test whether the collection changed as a result of the call.
     */
    private boolean addRelatedClass(final RelType relType, final ClassInfo classInfo) {
        Set<ClassInfo> classInfoSet = relatedClasses.get(relType);
        if (classInfoSet == null) {
            relatedClasses.put(relType, classInfoSet = new HashSet<>(4));
        }
        return classInfoSet.add(classInfo);
    }

    private static final int ANNOTATION_CLASS_MODIFIER = 0x2000;

    /**
     * Get a ClassInfo object, or create it if it doesn't exist. N.B. not threadsafe, so ClassInfo objects should
     * only ever be constructed by a single thread.
     */
    private static ClassInfo getOrCreateClassInfo(final String className, final int classModifiers,
            final ScanSpec scanSpec, final Map<String, ClassInfo> classNameToClassInfo) {
        ClassInfo classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            classNameToClassInfo.put(className,
                    classInfo = new ClassInfo(className, classModifiers, /* isExternalClass = */ true, scanSpec));
        }
        classInfo.modifiers |= classModifiers;
        if ((classModifiers & ANNOTATION_CLASS_MODIFIER) != 0) {
            classInfo.isAnnotation = true;
        }
        if ((classModifiers & Modifier.INTERFACE) != 0) {
            classInfo.isInterface = true;
        }
        return classInfo;
    }

    /** Add a superclass to this class. */
    void addSuperclass(final String superclassName, final Map<String, ClassInfo> classNameToClassInfo) {
        if (superclassName != null) {
            final ClassInfo superclassClassInfo = getOrCreateClassInfo(superclassName, /* classModifiers = */ 0,
                    scanSpec, classNameToClassInfo);
            this.addRelatedClass(RelType.SUPERCLASSES, superclassClassInfo);
            superclassClassInfo.addRelatedClass(RelType.SUBCLASSES, this);
        }
    }

    /** Add an implemented interface to this class. */
    void addImplementedInterface(final String interfaceName, final Map<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo interfaceClassInfo = getOrCreateClassInfo(interfaceName,
                /* classModifiers = */ Modifier.INTERFACE, scanSpec, classNameToClassInfo);
        interfaceClassInfo.isInterface = true;
        interfaceClassInfo.modifiers |= Modifier.INTERFACE;
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

    /** Add a static final field's constant initializer value. */
    void addStaticFinalFieldConstantInitializerValue(final String fieldName, final Object constValue) {
        if (this.staticFinalFieldNameToConstantInitializerValue == null) {
            this.staticFinalFieldNameToConstantInitializerValue = new HashMap<>();
        }
        this.staticFinalFieldNameToConstantInitializerValue.put(fieldName, constValue);
    }

    /** Add an annotation to this class. */
    void addClassAnnotation(final AnnotationInfo classAnnotationInfo,
            final Map<String, ClassInfo> classNameToClassInfo) {
        final ClassInfo annotationClassInfo = getOrCreateClassInfo(classAnnotationInfo.getAnnotationName(),
                ANNOTATION_CLASS_MODIFIER, scanSpec, classNameToClassInfo);
        if (this.annotationInfo == null) {
            this.annotationInfo = new AnnotationInfoList(2);
        }
        this.annotationInfo.add(classAnnotationInfo);

        this.addRelatedClass(RelType.CLASS_ANNOTATIONS, annotationClassInfo);
        annotationClassInfo.addRelatedClass(RelType.CLASSES_WITH_CLASS_ANNOTATION, this);

        // Add back-ref from classAnnotationInfo to the annotation class' ClassInfo
        classAnnotationInfo.classInfo = annotationClassInfo;

        // Record use of @Inherited meta-annotation
        if (classAnnotationInfo.getAnnotationName().equals(Inherited.class.getName())) {
            isInherited = true;
        }
    }

    /** Add field info. */
    void addFieldInfo(final FieldInfoList fieldInfoList, final Map<String, ClassInfo> classNameToClassInfo) {
        for (final FieldInfo fieldInfo : fieldInfoList) {
            final AnnotationInfoList fieldAnnotationInfoList = fieldInfo.annotationInfo;
            if (fieldAnnotationInfoList != null) {
                for (final AnnotationInfo fieldAnnotationInfo : fieldAnnotationInfoList) {
                    final ClassInfo annotationClassInfo = getOrCreateClassInfo(
                            fieldAnnotationInfo.getAnnotationName(), ANNOTATION_CLASS_MODIFIER, scanSpec,
                            classNameToClassInfo);
                    // Mark this class as having a field with this annotation
                    this.addRelatedClass(RelType.FIELD_ANNOTATIONS, annotationClassInfo);
                    annotationClassInfo.addRelatedClass(RelType.CLASSES_WITH_FIELD_ANNOTATION, this);

                    // Add back-ref from fieldAnnotationInfo to the annotation class' ClassInfo
                    fieldAnnotationInfo.classInfo = annotationClassInfo;
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
    void addMethodInfo(final MethodInfoList methodInfoList, final Map<String, ClassInfo> classNameToClassInfo) {
        for (final MethodInfo methodInfo : methodInfoList) {
            final AnnotationInfoList methodAnnotationInfoList = methodInfo.annotationInfo;
            if (methodAnnotationInfoList != null) {
                for (final AnnotationInfo methodAnnotationInfo : methodAnnotationInfoList) {
                    final ClassInfo annotationClassInfo = getOrCreateClassInfo(
                            methodAnnotationInfo.getAnnotationName(), ANNOTATION_CLASS_MODIFIER, scanSpec,
                            classNameToClassInfo);
                    // Mark this class as having a method with this annotation
                    this.addRelatedClass(RelType.METHOD_ANNOTATIONS, annotationClassInfo);
                    annotationClassInfo.addRelatedClass(RelType.CLASSES_WITH_METHOD_ANNOTATION, this);

                    // Add back-ref from methodAnnotationInfo to the annotation class' ClassInfo
                    methodAnnotationInfo.classInfo = annotationClassInfo;
                }
            }
            final AnnotationInfo[][] methodParamAnnotationInfoList = methodInfo.parameterAnnotationInfo;
            if (methodParamAnnotationInfoList != null) {
                for (int i = 0; i < methodParamAnnotationInfoList.length; i++) {
                    final AnnotationInfo[] paramAnnotationInfoArr = methodParamAnnotationInfoList[i];
                    if (paramAnnotationInfoArr != null) {
                        for (int j = 0; j < paramAnnotationInfoArr.length; j++) {
                            final AnnotationInfo methodParamAnnotationInfo = paramAnnotationInfoArr[j];
                            final ClassInfo annotationClassInfo = getOrCreateClassInfo(
                                    methodParamAnnotationInfo.getAnnotationName(), ANNOTATION_CLASS_MODIFIER,
                                    scanSpec, classNameToClassInfo);
                            // Add back-ref from methodParamAnnotationInfo to the annotation class' ClassInfo
                            methodParamAnnotationInfo.classInfo = annotationClassInfo;
                        }
                    }
                }
            }
            // Add back-link from MethodInfo to enclosing ClassInfo instance
            methodInfo.classInfo = this;
        }
        if (this.methodInfo == null) {
            this.methodInfo = methodInfoList;
        } else {
            this.methodInfo.addAll(methodInfoList);
        }
    }

    /** Add the class type signature, including type params */
    void addTypeSignature(final String typeSignatureStr) {
        if (this.typeSignatureStr == null) {
            this.typeSignatureStr = typeSignatureStr;
        } else {
            if (typeSignatureStr != null && !this.typeSignatureStr.equals(typeSignatureStr)) {
                throw new RuntimeException("Trying to merge two classes with different type signatures for class "
                        + className + ": " + this.typeSignatureStr + " ; " + typeSignatureStr);
            }
        }
    }

    /**
     * Add annotation default values. (Only called in the case of annotation class definitions, when the annotation
     * has default parameter values.)
     */
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
        boolean classEncounteredMultipleTimes = false;
        ClassInfo classInfo = classNameToClassInfo.get(className);
        if (classInfo == null) {
            // This is the first time this class has been seen, add it
            classNameToClassInfo.put(className,
                    classInfo = new ClassInfo(className, classModifiers, /* isExternalClass = */ false, scanSpec));
        } else {
            if (!classInfo.isExternalClass) {
                classEncounteredMultipleTimes = true;
            }
        }

        // Remember which classpath element (zipfile / classpath root directory / module) the class was found in
        final ModuleRef modRef = classpathElement.getClasspathElementModuleRef();
        final File file = modRef != null ? null : classpathElement.getClasspathElementFile(log);
        if ((classInfo.moduleRef != null && modRef != null && !classInfo.moduleRef.equals(modRef))
                || (classInfo.classpathElementFile != null && file != null
                        && !classInfo.classpathElementFile.equals(file))) {
            classEncounteredMultipleTimes = true;
        }

        if (classEncounteredMultipleTimes) {
            // The same class was encountered more than once in a single jarfile -- should not happen. However,
            // actually there is no restriction for paths within a zipfile to be unique (!!), and in fact
            // zipfiles in the wild do contain the same classfiles multiple times with the same exact path,
            // e.g.: xmlbeans-2.6.0.jar!org/apache/xmlbeans/xml/stream/Location.class
            if (log != null) {
                log.log("Class " + className + " is defined in multiple different classpath elements or modules -- "
                        + "ClassInfo#getClasspathElementFile() and/or ClassInfo#getClasspathElementModuleRef "
                        + "will only return the first of these; attempting to merge info from all copies of "
                        + "the classfile");
            }
        }
        if (classInfo.classpathElementFile == null) {
            // If class was found in more than one classpath element, keep the first classpath element reference 
            classInfo.classpathElementFile = file;
            // Save jarfile package root, if any
            classInfo.jarfilePackageRoot = classpathElement.getJarfilePackageRoot();
        }
        if (classInfo.moduleRef == null) {
            // If class was found in more than one module, keep the first module reference 
            classInfo.moduleRef = modRef;
        }

        // Remember which classloader handles the class was found in, for classloading
        final ClassLoader[] classLoaders = classpathElement.getClassLoaders();
        if (classInfo.classLoaders == null) {
            classInfo.classLoaders = classLoaders;
        } else if (classLoaders != null && !classInfo.classLoaders.equals(classLoaders)) {
            // Merge together ClassLoader list (concatenate and dedup)
            final LinkedHashSet<ClassLoader> allClassLoaders = new LinkedHashSet<>(
                    Arrays.asList(classInfo.classLoaders));
            for (final ClassLoader classLoader : classLoaders) {
                allClassLoaders.add(classLoader);
            }
            final List<ClassLoader> classLoaderOrder = new ArrayList<>(allClassLoaders);
            classInfo.classLoaders = classLoaderOrder.toArray(new ClassLoader[classLoaderOrder.size()]);
        }

        // Mark the classfile as scanned
        classInfo.isExternalClass = false;

        // Merge modifiers
        classInfo.modifiers |= classModifiers;
        classInfo.isInterface |= isInterface;
        classInfo.isAnnotation |= isAnnotation;

        return classInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the names of any classes referenced in the type descriptors of this class' methods (if method info is
     * recorded).
     * 
     * @return The names of the referenced classes.
     */
    Set<String> getClassNamesReferencedInMethodTypeDescriptors() {
        final Set<String> referencedClassNames = new HashSet<>();
        if (methodInfo != null) {
            for (final MethodInfo mi : methodInfo) {
                final MethodTypeSignature methodSig = mi.getTypeSignature();
                if (methodSig != null) {
                    methodSig.getAllReferencedClassNames(referencedClassNames);
                }
            }
        }
        // Remove any reference to java.lang.Object
        referencedClassNames.remove("java.lang.Object");
        return referencedClassNames;
    }

    /**
     * Get the names of any classes (other than this class itself) referenced in the type descriptor of this class.
     * 
     * @return The names of the referenced classes.
     */
    Set<String> getClassNamesReferencedInClassTypeDescriptor() {
        final Set<String> referencedClassNames = new HashSet<>();
        final ClassTypeSignature classSig = getTypeSignature();
        if (classSig != null) {
            classSig.getAllReferencedClassNames(referencedClassNames);
        }
        // Remove self-reference, and any reference to java.lang.Object
        referencedClassNames.remove(className);
        referencedClassNames.remove("java.lang.Object");
        return referencedClassNames;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The class type to return. */
    static enum ClassType {
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

    /** Filter classes according to scan spec and class type. */
    private static Set<ClassInfo> filterClassInfo(final Collection<ClassInfo> classes, final ScanSpec scanSpec,
            final ClassType... classTypes) {
        if (classes == null) {
            return null;
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
        final Set<ClassInfo> classInfoSetFiltered = new HashSet<>(classes.size());
        for (final ClassInfo classInfo : classes) {
            // Check class type against requested type(s)
            if (includeAllTypes //
                    || includeStandardClasses && classInfo.isStandardClass()
                    || includeImplementedInterfaces && classInfo.isImplementedInterface()
                    || includeAnnotations && classInfo.isAnnotation()) {
                // Don't include external classes unless enableExternalClasses is true
                if (!classInfo.isExternalClass || scanSpec.enableExternalClasses) {
                    // If this is a system class, ignore blacklist unless the blanket blacklisting of
                    // all system jars or modules has been disabled, and this system class was specifically
                    // blacklisted by name
                    if (!scanSpec.classIsBlacklisted(classInfo.className) //
                            && (!scanSpec.blacklistSystemJarsOrModules
                                    || !JarUtils.isInSystemPackageOrModule(classInfo.className))) {
                        // Class passed filter criteria
                        classInfoSetFiltered.add(classInfo);
                    }
                }
            }
        }
        return classInfoSetFiltered;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get all classes found during the scan.
     *
     * @return A list of all classes found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllClasses(final Collection<ClassInfo> classes, final ScanSpec scanSpec,
            final ScanResult scanResult) {
        return new ClassInfoList(ClassInfo.filterClassInfo(classes, scanSpec, ClassType.ALL));
    }

    /**
     * Get all standard classes found during the scan.
     *
     * @return A list of all standard classes found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllStandardClasses(final Collection<ClassInfo> classes, final ScanSpec scanSpec,
            final ScanResult scanResult) {
        return new ClassInfoList(ClassInfo.filterClassInfo(classes, scanSpec, ClassType.STANDARD_CLASS));
    }

    /**
     * Get all implemented interface (non-annotation interface) classes found during the scan.
     *
     * @return A list of all annotation classes found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllImplementedInterfaceClasses(final Collection<ClassInfo> classes,
            final ScanSpec scanSpec, final ScanResult scanResult) {
        return new ClassInfoList(ClassInfo.filterClassInfo(classes, scanSpec, ClassType.IMPLEMENTED_INTERFACE));
    }

    /**
     * Get all annotation classes found during the scan. See also {@link #getAllInterfaceOrAnnotationClasses()}.
     *
     * @return A list of all annotation classes found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllAnnotationClasses(final Collection<ClassInfo> classes, final ScanSpec scanSpec,
            final ScanResult scanResult) {
        return new ClassInfoList(ClassInfo.filterClassInfo(classes, scanSpec, ClassType.ANNOTATION));
    }

    /**
     * Get all interface or annotation classes found during the scan. (Annotations are technically interfaces, and
     * they can be implemented.)
     *
     * @return A list of all whitelisted interfaces found during the scan, or the empty list if none.
     */
    static ClassInfoList getAllInterfacesOrAnnotationClasses(final Collection<ClassInfo> classes,
            final ScanSpec scanSpec, final ScanResult scanResult) {
        return new ClassInfoList(ClassInfo.filterClassInfo(classes, scanSpec, ClassType.INTERFACE_OR_ANNOTATION));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find all ClassInfo nodes reachable from this ClassInfo node over the given relationship type links (not
     * including this class itself).
     */
    private Entry<Set<ClassInfo>, Set<ClassInfo>> getReachableAndDirectlyRelatedClasses(final RelType relType) {
        final Set<ClassInfo> directlyRelatedClasses = this.relatedClasses.get(relType);
        if (directlyRelatedClasses == null) {
            return new SimpleEntry<>(Collections.<ClassInfo> emptySet(), Collections.<ClassInfo> emptySet());
        }
        final Set<ClassInfo> reachableClasses = new HashSet<>(directlyRelatedClasses);
        if (relType == RelType.METHOD_ANNOTATIONS || relType == RelType.FIELD_ANNOTATIONS) {
            // For method and field annotations, need to change the RelType when finding meta-annotations
            for (final ClassInfo annotation : directlyRelatedClasses) {
                reachableClasses.addAll(
                        annotation.getReachableAndDirectlyRelatedClasses(RelType.CLASS_ANNOTATIONS).getKey());
            }
        } else if (relType == RelType.CLASSES_WITH_METHOD_ANNOTATION
                || relType == RelType.CLASSES_WITH_FIELD_ANNOTATION) {
            // If looking for meta-annotated methods or fields, need to find all meta-annotated annotations, then
            // look for the methods or fields that they annotate
            for (final ClassInfo subAnnotation : this.filterClassInfo(RelType.CLASSES_WITH_CLASS_ANNOTATION,
                    ClassType.ANNOTATION)) {
                final Set<ClassInfo> annotatedClasses = subAnnotation.relatedClasses.get(relType);
                if (annotatedClasses != null) {
                    reachableClasses.addAll(annotatedClasses);
                }
            }
        } else {
            // For other relationship types, the reachable type stays the same over the transitive closure. Find the
            // transitive closure, breaking cycles where necessary.
            final LinkedList<ClassInfo> queue = new LinkedList<>();
            queue.addAll(directlyRelatedClasses);
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
        return new SimpleEntry<>(reachableClasses, directlyRelatedClasses);
    }

    /** Get the classes related to this one in the specified way. */
    ClassInfoList filterClassInfo(final RelType relType, final ClassType... classTypes) {
        final Entry<Set<ClassInfo>, Set<ClassInfo>> reachableAndDirectlyRelatedClasses = //
                getReachableAndDirectlyRelatedClasses(relType);
        final Set<ClassInfo> reachableClasses = reachableAndDirectlyRelatedClasses.getKey();
        if (reachableClasses.isEmpty()) {
            return ClassInfoList.EMPTY_LIST;
        }
        final Set<ClassInfo> directlyRelatedClasses = reachableAndDirectlyRelatedClasses.getValue();

        return new ClassInfoList(filterClassInfo(reachableClasses, scanSpec),
                filterClassInfo(directlyRelatedClasses, scanSpec));
    }

    // -------------------------------------------------------------------------------------------------------------
    // Standard classes

    /**
     * Test whether this class is a standard class (not an annotation or interface).
     *
     * @return true if this class is a standard class (not an annotation or interface).
     */
    public boolean isStandardClass() {
        return !(isAnnotation || isInterface);
    }

    /**
     * Get the subclasses of this class.
     *
     * @return the list of subclasses of this class, or the empty list if none.
     */
    public ClassInfoList getSubclasses() {
        // Make an exception for querying all subclasses of java.lang.Object
        return this.filterClassInfo(RelType.SUBCLASSES);
    }

    /**
     * Get all superclasses of this class. Does not include superinterfaces, if this is an interface (use
     * {@link #getInterfaces()} to get superinterfaces of an interface.}
     *
     * @return the list of all superclasses of this class, or the empty list if none.
     */
    public ClassInfoList getSuperclasses() {
        return this.filterClassInfo(RelType.SUPERCLASSES);
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
            return superClasses.iterator().next();
        }
    }

    /**
     * Returns true if this is an inner class (call isAnonymousInnerClass() to test if this is an anonymous inner
     * class). If true, the containing class can be determined by calling getOuterClasses() or getOuterClassNames().
     * 
     * @return True if this class is an inner class.
     */
    public boolean isInnerClass() {
        return !getOuterClasses().isEmpty();
    }

    /**
     * Returns the containing outer classes, for inner classes. Note that all containing outer classes are returned,
     * not just the innermost containing outer class. Returns the empty list if this is not an inner class.
     * 
     * @return The list of containing outer classes.
     */
    public ClassInfoList getOuterClasses() {
        return this.filterClassInfo(RelType.CONTAINED_WITHIN_OUTER_CLASS);
    }

    /**
     * Returns true if this class contains inner classes. If true, the inner classes can be determined by calling
     * getInnerClasses() or getInnerClassNames().
     * 
     * @return True if this is an outer class.
     */
    public boolean isOuterClass() {
        return !getInnerClasses().isEmpty();
    }

    /**
     * Returns the inner classes contained within this class. Returns the empty list if none.
     * 
     * @return The list of inner classes within this class.
     */
    public ClassInfoList getInnerClasses() {
        return this.filterClassInfo(RelType.CONTAINS_INNER_CLASS);
    }

    /**
     * Returns true if this is an anonymous inner class. If true, the name of the containing method can be obtained
     * by calling getFullyQualifiedContainingMethodName().
     * 
     * @return True if this is an anonymous inner class.
     */
    public boolean isAnonymousInnerClass() {
        return fullyQualifiedContainingMethodName != null;
    }

    /**
     * Get fully-qualified containing method name (i.e. fully qualified classname, followed by dot, followed by
     * method name, for the containing method that creates an anonymous inner class.
     * 
     * @return The fully-qualified method name of the method that this anonymous inner class was defined within.
     */
    public String getFullyQualifiedContainingMethodName() {
        return fullyQualifiedContainingMethodName;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /**
     * Test whether this class is an "implemented interface" (meaning a standard, non-annotation interface, or an
     * annotation that has also been implemented as an interface by some class).
     *
     * <p>
     * Annotations are interfaces, but you can also implement an annotation, so to we Test whether an interface
     * (even an annotation) is implemented by a class or extended by a subinterface, or (failing that) if it is not
     * an interface but not an annotation.
     *
     * <p>
     * (This is named "implemented interface" rather than just "interface" to distinguish it from an annotation.)
     *
     * @return true if this class is an "implemented interface".
     */
    public boolean isImplementedInterface() {
        return relatedClasses.get(RelType.CLASSES_IMPLEMENTING) != null || (isInterface && !isAnnotation);
    }

    /**
     * Get the interfaces implemented by this class or by one of its superclasses, if this is a standard class, or
     * the superinterfaces extended by this interface, if this is an interface.
     *
     * @return the list of interfaces implemented by this standard class, or by one of its superclasses. Returns the
     *         empty list if none.
     */
    public ClassInfoList getInterfaces() {
        // Classes also implement the interfaces of their superclasses
        final ClassInfoList implementedInterfaces = this.filterClassInfo(RelType.IMPLEMENTED_INTERFACES);
        final Set<ClassInfo> allInterfaces = new HashSet<>(implementedInterfaces);
        for (final ClassInfo superclass : this.filterClassInfo(RelType.SUPERCLASSES)) {
            final ClassInfoList superclassImplementedInterfaces = superclass
                    .filterClassInfo(RelType.IMPLEMENTED_INTERFACES);
            allInterfaces.addAll(superclassImplementedInterfaces);
        }
        return new ClassInfoList(allInterfaces, implementedInterfaces.directOnly());
    }

    /**
     * Get the classes that implement this interface, and their subclasses, if this is an interface, otherwise
     * returns the empty list.
     *
     * @return the list of classes implementing this interface, or the empty list if none.
     */
    public ClassInfoList getClassesImplementing() {
        if (!isInterface) {
            throw new IllegalArgumentException("Class is not an interface: " + getClassName());
        }
        // Subclasses of implementing classes also implement the interface
        final ClassInfoList implementingClasses = this.filterClassInfo(RelType.CLASSES_IMPLEMENTING);
        final Set<ClassInfo> allImplementingClasses = new HashSet<>(implementingClasses);
        for (final ClassInfo implementingClass : implementingClasses) {
            final ClassInfoList implementingSubclasses = implementingClass.filterClassInfo(RelType.SUBCLASSES);
            allImplementingClasses.addAll(implementingSubclasses);
        }
        return new ClassInfoList(allImplementingClasses, implementingClasses.directOnly());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /**
     * Get the standard classes and non-annotation interfaces that are annotated by this annotation.
     * 
     * <p>
     * Also handles the {@link Inherited} meta-annotation, which causes an annotation to annotate a class and all of
     * its subclasses.
     *
     * @return the list of standard classes and non-annotation interfaces that are annotated by the annotation
     *         corresponding to this ClassInfo class, or the empty list if none.
     */
    public ClassInfoList getClassesWithAnnotation() {
        if (!scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Cannot get classes with annotation without calling "
                    + "FastClasspathScanner#enableAnnotationInfo() before starting the scan");
        }
        if (!isAnnotation) {
            throw new IllegalArgumentException("Class is not an annotation: " + getClassName());
        }

        // Check if any of the meta-annotations on this annotation are @Inherited,
        // which causes an annotation to annotate a class and all of its subclasses.
        boolean isInherited = false;
        final Set<ClassInfo> metaAnnotations = relatedClasses.get(RelType.CLASS_ANNOTATIONS);
        if (metaAnnotations != null) {
            for (final ClassInfo metaAnnotation : metaAnnotations) {
                if (metaAnnotation.className.equals("java.lang.annotation.Inherited")) {
                    isInherited = true;
                    break;
                }
            }
        }

        // Get classes that have this annotation
        final ClassInfoList classesWithAnnotation = this.filterClassInfo(RelType.CLASSES_WITH_CLASS_ANNOTATION);

        if (isInherited) {
            // If this is an inherited annotation, add into the result all subclasses of the annotated classes 
            final Set<ClassInfo> classesWithAnnotationAndTheirSubclasses = new HashSet<>(classesWithAnnotation);
            for (final ClassInfo classWithAnnotation : classesWithAnnotation) {
                classesWithAnnotationAndTheirSubclasses.addAll(classWithAnnotation.getSubclasses());
            }
            return new ClassInfoList(classesWithAnnotationAndTheirSubclasses, classesWithAnnotation.directOnly());
        } else {
            // If not inherited, only return the annotated classes
            return classesWithAnnotation;
        }
    }

    /**
     * Get the annotations and meta-annotations on this class. (Call {@link #getAnnotationInfo()} instead, if you
     * need the parameter values of annotations, rather than just the annotation classes.)
     * 
     * <p>
     * Also handles the {@link Inherited} meta-annotation, which causes an annotation to annotate a class and all of
     * its subclasses.
     *
     * @return the list of annotations and meta-annotations on this class.
     */
    public ClassInfoList getAnnotations() {
        if (!scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Cannot get annotations without calling "
                    + "FastClasspathScanner#enableAnnotationInfo() before starting the scan");
        }

        // Get all annotations on this class
        final ClassInfoList annotationClasses = this.filterClassInfo(RelType.CLASS_ANNOTATIONS);

        // Check for any @Inherited annotations on superclasses
        Set<ClassInfo> inheritedSuperclassAnnotations = null;
        for (final ClassInfo superclass : getSuperclasses()) {
            for (final ClassInfo superclassAnnotationClass : superclass
                    .filterClassInfo(RelType.CLASS_ANNOTATIONS)) {
                boolean isInherited = false;
                for (final ClassInfo superclassAnnotationClassMetaAnnotation : //
                superclassAnnotationClass.relatedClasses.get(RelType.CLASS_ANNOTATIONS)) {
                    if (superclassAnnotationClassMetaAnnotation.getClassName().equals(Inherited.class.getName())) {
                        isInherited = true;
                        break;
                    }
                }
                if (isInherited) {
                    // inheritedSuperclassAnnotations is an inherited annotation
                    if (inheritedSuperclassAnnotations == null) {
                        inheritedSuperclassAnnotations = new HashSet<>();
                    }
                    inheritedSuperclassAnnotations.add(superclassAnnotationClass);
                }
            }
        }

        if (inheritedSuperclassAnnotations == null) {
            // No inherited superclass annotations
            return annotationClasses;
        } else {
            // Merge inherited superclass annotations and annotations on this class
            inheritedSuperclassAnnotations.addAll(annotationClasses);
            return new ClassInfoList(inheritedSuperclassAnnotations, annotationClasses.directOnly());
        }
    }

    /**
     * Get a list of annotations on this method, along with any annotation parameter values, as a list of
     * {@link AnnotationInfo} objects, or the empty list if none.
     * 
     * <p>
     * Also handles the {@link Inherited} meta-annotation, which causes an annotation to annotate a class and all of
     * its subclasses.
     * 
     * @return A list of {@link AnnotationInfo} objects for the annotations on this method, or the empty list if
     *         none.
     */
    public AnnotationInfoList getAnnotationInfo() {
        if (!scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Cannot get annotation info without calling "
                    + "FastClasspathScanner#enableAnnotationInfo() before starting the scan");
        }

        // Check for any @Inherited annotations on superclasses
        AnnotationInfoList inheritedSuperclassAnnotations = null;
        for (final ClassInfo superclass : getSuperclasses()) {
            for (final AnnotationInfo superclassAnnotationInfo : superclass.getAnnotationInfo()) {
                if (superclassAnnotationInfo.classInfo.isInherited) {
                    // inheritedSuperclassAnnotations is an inherited annotation
                    if (inheritedSuperclassAnnotations == null) {
                        inheritedSuperclassAnnotations = new AnnotationInfoList();
                    }
                    inheritedSuperclassAnnotations.add(superclassAnnotationInfo);
                }
            }
        }

        if (inheritedSuperclassAnnotations == null) {
            // No inherited superclass annotations
            return annotationInfo;
        } else {
            // Merge inherited superclass annotations and annotations on this class
            inheritedSuperclassAnnotations.addAll(annotationInfo);
            Collections.sort(inheritedSuperclassAnnotations);
            return inheritedSuperclassAnnotations;
        }
    }

    /**
     * If this is an annotation, and it has default parameter values, returns a list of the default parameter
     * values, otherwise returns the empty list.
     * 
     * @return If this is an annotation class, the list of {@link AnnotationParamValue} objects for each of the
     *         default parameter values for this annotation, otherwise the empty list.
     */
    public List<AnnotationParamValue> getAnnotationDefaultParamValues() {
        if (!scanSpec.enableAnnotationInfo) {
            throw new IllegalArgumentException("Cannot get annotation default parameter values without calling "
                    + "FastClasspathScanner#enableAnnotationInfo() before starting the scan");
        }
        if (!isAnnotation) {
            throw new IllegalArgumentException("Class is not an annotation: " + getClassName());
        }
        return annotationDefaultParamValues == null ? Collections.<AnnotationParamValue> emptyList()
                : annotationDefaultParamValues;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Methods

    /**
     * Returns information on visible methods of the class that are not constructors. (Call
     * {@link #getMethodAndConstructorInfo()} if you need methods and constructors.) There may be more than one
     * method of a given name with different type signatures, due to overloading.
     *
     * <p>
     * Requires that FastClasspathScanner#enableMethodInfo() be called before scanning, otherwise throws
     * IllegalArgumentException.
     *
     * <p>
     * By default only returns information for public methods, unless FastClasspathScanner#ignoreMethodVisibility()
     * was called before the scan.
     *
     * @return the list of MethodInfo objects for visible methods of this class, or the empty list if no methods
     *         were found or visible.
     * @throws IllegalArgumentException
     *             if FastClasspathScanner#enableMethodInfo() was not called prior to initiating the scan.
     */
    public MethodInfoList getMethodInfo() {
        if (!scanSpec.enableMethodInfo) {
            throw new IllegalArgumentException("Cannot get method info without calling "
                    + "FastClasspathScanner#enableMethodInfo() before starting the scan");
        }
        if (methodInfo == null) {
            return MethodInfoList.EMPTY_LIST;
        } else {
            final MethodInfoList nonConstructorMethods = new MethodInfoList();
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
    public MethodInfoList getConstructorInfo() {
        if (!scanSpec.enableMethodInfo) {
            throw new IllegalArgumentException("Cannot get method info without calling "
                    + "FastClasspathScanner#enableMethodInfo() before starting the scan");
        }
        if (methodInfo == null) {
            return MethodInfoList.EMPTY_LIST;
        } else {
            final MethodInfoList nonConstructorMethods = new MethodInfoList();
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
     * the method name of {@code "<init>"} and static initializer blocks have the name of {@code "<clinit>"}.
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
    public MethodInfoList getMethodAndConstructorInfo() {
        if (!scanSpec.enableMethodInfo) {
            throw new IllegalArgumentException("Cannot get method info without calling "
                    + "FastClasspathScanner#enableMethodInfo() before starting the scan");
        }
        return methodInfo == null ? MethodInfoList.EMPTY_LIST : methodInfo;
    }

    /**
     * Returns information on the method(s) or constructor(s) of the class with the given method name. Constructors
     * have the method name of {@code "<init>"}.
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
    public MethodInfoList getMethodInfo(final String methodName) {
        if (!scanSpec.enableMethodInfo) {
            throw new IllegalArgumentException("Cannot get method info without calling "
                    + "FastClasspathScanner#enableMethodInfo() before starting the scan");
        }
        if (methodInfo == null) {
            return MethodInfoList.EMPTY_LIST;
        }
        boolean hasMethodWithName = false;
        for (final MethodInfo f : methodInfo) {
            if (f.getMethodName().equals(methodName)) {
                hasMethodWithName = true;
                break;
            }
        }
        if (!hasMethodWithName) {
            return MethodInfoList.EMPTY_LIST;
        }
        final MethodInfoList methodInfoList = new MethodInfoList();
        for (final MethodInfo f : methodInfo) {
            if (f.getMethodName().equals(methodName)) {
                methodInfoList.add(f);
            }
        }
        return methodInfoList;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Method annotations

    /**
     * Get the method annotations or meta-annotations on this class.
     *
     * @return the list of method annotations or meta-annotations on this class, or the empty list if none.
     */
    public ClassInfoList getMethodAnnotations() {
        if (!scanSpec.enableAnnotationInfo || !scanSpec.enableMethodInfo) {
            throw new IllegalArgumentException("Cannot get method annotations without calling "
                    + "FastClasspathScanner#enableAnnotationInfo() and FastClasspathScanner#enableMethodInfo() "
                    + "before starting the scan");
        }
        return this.filterClassInfo(RelType.METHOD_ANNOTATIONS, ClassType.ANNOTATION);
    }

    /**
     * Get the classes that have a method with this annotation or meta-annotation.
     *
     * @return the list of classes that have a method with this annotation or meta-annotation, or the empty list if
     *         none.
     */
    public ClassInfoList getClassesWithMethodAnnotation() {
        if (!scanSpec.enableAnnotationInfo || !scanSpec.enableMethodInfo) {
            throw new IllegalArgumentException("Cannot get classes with method annotation without calling "
                    + "FastClasspathScanner#enableAnnotationInfo() and FastClasspathScanner#enableMethodInfo() "
                    + "before starting the scan");
        }
        return this.filterClassInfo(RelType.CLASSES_WITH_METHOD_ANNOTATION);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Fields

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
    public FieldInfoList getFieldInfo() {
        if (!scanSpec.enableFieldInfo) {
            throw new IllegalArgumentException("Cannot get field info without calling "
                    + "FastClasspathScanner#enableFieldInfo() before starting the scan");
        }
        return fieldInfo == null ? FieldInfoList.EMPTY_LIST : fieldInfo;
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
     * @return the list of field annotations on this class, or the empty list if none.
     */
    public ClassInfoList getFieldAnnotations() {
        if (!scanSpec.enableAnnotationInfo || !scanSpec.enableFieldInfo) {
            throw new IllegalArgumentException("Cannot get field annotations without calling "
                    + "FastClasspathScanner#enableAnnotationInfo() and FastClasspathScanner#enableFieldInfo() "
                    + "before starting the scan");
        }
        return this.filterClassInfo(RelType.FIELD_ANNOTATIONS, ClassType.ANNOTATION);
    }

    // -------------

    /**
     * Get the classes that have a field with this annotation or meta-annotation.
     *
     * @return the list of classes that have a field with this annotation or meta-annotation, or the empty list if
     *         none.
     */
    public ClassInfoList getClassesWithFieldAnnotation() {
        if (!scanSpec.enableAnnotationInfo || !scanSpec.enableFieldInfo) {
            throw new IllegalArgumentException("Cannot get classes with field annotation without calling "
                    + "FastClasspathScanner#enableAnnotationInfo() and FastClasspathScanner#enableFieldInfo() "
                    + "before starting the scan");
        }
        return this.filterClassInfo(RelType.CLASSES_WITH_FIELD_ANNOTATION);
    }
}
