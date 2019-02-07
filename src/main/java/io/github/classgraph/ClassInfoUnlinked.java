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
package io.github.classgraph;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.exceptions.ParseException;
import nonapi.io.github.classgraph.utils.Join;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * Class information that has been directly read from the binary classfile, before it is cross-linked with other
 * classes. (The cross-linking is done in a separate step to avoid the complexity of dealing with race conditions.)
 */
class ClassInfoUnlinked {

    /** The class name. */
    final String className;

    /** Whether this is an external class. */
    private final boolean isExternalClass;

    /** The class modifiers. */
    private int classModifiers;

    /** Whether this class is an interface. */
    private boolean isInterface;

    /** Whether this class is an annotation. */
    private boolean isAnnotation;

    /** The superclass name. (can be null if no superclass, or if superclass is blacklisted.) */
    String superclassName;

    /** The implemented interfaces. */
    List<String> implementedInterfaces;

    /** The class annotations. */
    AnnotationInfoList classAnnotations;

    /** The fully qualified name of the defining method. */
    private String fullyQualifiedDefiningMethodName;

    /** Class containment entries. */
    private List<SimpleEntry<String, String>> classContainmentEntries;

    /** Annotation default parameter values. */
    private AnnotationParameterValueList annotationParamDefaultValues;

    /** Referenced class names. */
    private final Set<String> refdClassNames;

    /** The classpath element. */
    final ClasspathElement classpathElement;

    /** The classfile resource. */
    final Resource classfileResource;

    /** The field info list. */
    FieldInfoList fieldInfoList;

    /** The method info list. */
    MethodInfoList methodInfoList;

    /** The type signature. */
    private String typeSignature;

    /**
     * Constructor.
     *
     * @param className
     *            the class name
     * @param superclassName
     *            the superclass name
     * @param isExternalClass
     *            true if external class
     * @param refdClassNames
     *            the referenced class names
     * @param classpathElement
     *            the classpath element
     * @param classfileResource
     *            the classfile resource
     */
    ClassInfoUnlinked(final String className, final String superclassName, final boolean isExternalClass,
            final Set<String> refdClassNames, final ClasspathElement classpathElement,
            final Resource classfileResource) {
        this.className = (className);
        this.superclassName = superclassName;
        this.isExternalClass = isExternalClass;
        this.refdClassNames = refdClassNames;
        this.classpathElement = classpathElement;
        this.classfileResource = classfileResource;
    }

    /**
     * Sets the modifiers.
     *
     * @param classModifiers
     *            the class modifiers
     * @param isInterface
     *            true if this class is an interface
     * @param isAnnotation
     *            true if this class is an annotation
     */
    public void setModifiers(final int classModifiers, final boolean isInterface, final boolean isAnnotation) {
        this.classModifiers = classModifiers;
        this.isInterface = isInterface;
        this.isAnnotation = isAnnotation;
    }

    /**
     * Adds the type signature.
     *
     * @param typeSignature
     *            the type signature
     */
    void addTypeSignature(final String typeSignature) {
        this.typeSignature = typeSignature;
    }

    /**
     * Adds the implemented interface.
     *
     * @param interfaceName
     *            the interface name
     */
    void addImplementedInterface(final String interfaceName) {
        if (implementedInterfaces == null) {
            implementedInterfaces = new ArrayList<>();
        }
        implementedInterfaces.add(interfaceName);
    }

    /**
     * Adds the class annotation.
     *
     * @param classAnnotation
     *            the class annotation
     */
    void addClassAnnotation(final AnnotationInfo classAnnotation) {
        if (classAnnotations == null) {
            classAnnotations = new AnnotationInfoList();
        }
        classAnnotations.add(classAnnotation);
    }

    /**
     * Adds the field info.
     *
     * @param fieldInfo
     *            the field info
     */
    void addFieldInfo(final FieldInfo fieldInfo) {
        if (fieldInfoList == null) {
            fieldInfoList = new FieldInfoList();
        }
        fieldInfoList.add(fieldInfo);
    }

    /**
     * Adds the method info.
     *
     * @param methodInfo
     *            the method info
     */
    void addMethodInfo(final MethodInfo methodInfo) {
        if (methodInfoList == null) {
            methodInfoList = new MethodInfoList();
        }
        methodInfoList.add(methodInfo);
    }

    /**
     * Adds the enclosing method.
     *
     * @param fullyQualifiedDefiningMethodName
     *            the fully qualified defining method name
     */
    public void addEnclosingMethod(final String fullyQualifiedDefiningMethodName) {
        this.fullyQualifiedDefiningMethodName = fullyQualifiedDefiningMethodName;
    }

    /**
     * Adds the class containment.
     *
     * @param innerClassName
     *            the inner class name
     * @param outerClassName
     *            the outer class name
     */
    public void addClassContainment(final String innerClassName, final String outerClassName) {
        if (classContainmentEntries == null) {
            classContainmentEntries = new ArrayList<>();
        }
        classContainmentEntries.add(new SimpleEntry<>(innerClassName, outerClassName));
    }

    /**
     * Adds the annotation param default value.
     *
     * @param annotationParamDefaultValue
     *            the annotation param default value
     */
    public void addAnnotationParamDefaultValue(final AnnotationParameterValue annotationParamDefaultValue) {
        if (annotationParamDefaultValues == null) {
            annotationParamDefaultValues = new AnnotationParameterValueList();
        }
        this.annotationParamDefaultValues.add(annotationParamDefaultValue);
    }

    /**
     * Link classes. Not threadsafe, should be run in a single-threaded context.
     *
     * @param scanSpec
     *            the scan spec
     * @param classNameToClassInfo
     *            map from class name to class info
     * @param packageNameToPackageInfo
     *            map from package name to package info
     * @param moduleNameToModuleInfo
     *            map from module name to module info
     * @param log
     *            the log
     */
    void link(final ScanSpec scanSpec, final Map<String, ClassInfo> classNameToClassInfo,
            final Map<String, PackageInfo> packageNameToPackageInfo,
            final Map<String, ModuleInfo> moduleNameToModuleInfo, final LogNode log) {
        if (className.equals("module-info")) {
            // Handle module descriptor classfile
            ModuleInfo moduleInfo = moduleNameToModuleInfo.get(classpathElement.moduleName);
            if (moduleInfo == null) {
                moduleNameToModuleInfo.put(classpathElement.moduleName,
                        moduleInfo = new ModuleInfo(classfileResource.getModuleRef(), classpathElement));
            }
            moduleInfo.addAnnotations(classAnnotations);

        } else if (className.equals("package-info") || className.endsWith(".package-info")) {
            // Handle package descriptor classfile
            final int lastDotIdx = className.lastIndexOf('.');
            final String packageName = lastDotIdx < 0 ? "" : className.substring(0, lastDotIdx);
            final PackageInfo packageInfo = PackageInfo.getOrCreatePackage(packageName, packageNameToPackageInfo);
            packageInfo.addAnnotations(classAnnotations);

        } else {
            // Handle regular classfile
            final ClassInfo classInfo = ClassInfo.addScannedClass(className, classModifiers, isExternalClass,
                    classNameToClassInfo, classpathElement, classfileResource, log);
            classInfo.setModifiers(classModifiers);
            classInfo.setIsInterface(isInterface);
            classInfo.setIsAnnotation(isAnnotation);
            if (superclassName != null) {
                classInfo.addSuperclass(superclassName, classNameToClassInfo);
            }
            if (implementedInterfaces != null) {
                for (final String interfaceName : implementedInterfaces) {
                    classInfo.addImplementedInterface(interfaceName, classNameToClassInfo);
                }
            }
            if (classAnnotations != null) {
                for (final AnnotationInfo classAnnotation : classAnnotations) {
                    classInfo.addClassAnnotation(classAnnotation, classNameToClassInfo);
                }
            }
            if (classContainmentEntries != null) {
                ClassInfo.addClassContainment(classContainmentEntries, classNameToClassInfo);
            }
            if (annotationParamDefaultValues != null) {
                classInfo.addAnnotationParamDefaultValues(annotationParamDefaultValues);
            }
            if (fullyQualifiedDefiningMethodName != null) {
                classInfo.addFullyQualifiedDefiningMethodName(fullyQualifiedDefiningMethodName);
            }
            if (fieldInfoList != null) {
                classInfo.addFieldInfo(fieldInfoList, classNameToClassInfo);
            }
            if (methodInfoList != null) {
                classInfo.addMethodInfo(methodInfoList, classNameToClassInfo);
            }
            if (typeSignature != null) {
                classInfo.addTypeSignature(typeSignature);
            }
            if (refdClassNames != null) {
                classInfo.addReferencedClassNames(refdClassNames);
            }

            final int lastDotIdx = className.lastIndexOf('.');
            final String packageName = lastDotIdx < 0 ? "" : className.substring(0, lastDotIdx);
            final PackageInfo packageInfo = PackageInfo.getOrCreatePackage(packageName, packageNameToPackageInfo);
            packageInfo.addClassInfo(classInfo);

            ModuleInfo moduleInfo = moduleNameToModuleInfo.get(classpathElement.moduleName);
            if (moduleInfo == null) {
                moduleNameToModuleInfo.put(classpathElement.moduleName,
                        moduleInfo = new ModuleInfo(classInfo.getModuleRef(), classpathElement));
            }
            moduleInfo.addClassInfo(classInfo);
            moduleInfo.addPackageInfo(packageInfo);
        }
    }

    /**
     * Write to log.
     *
     * @param log
     *            the log
     */
    void logTo(final LogNode log) {
        if (log != null) {
            final LogNode subLog = log.log("Found " //
                    + (isAnnotation ? "annotation class" : isInterface ? "interface class" : "class") //
                    + " " + className);
            if (superclassName != null) {
                subLog.log(
                        "Super" + (isInterface && !isAnnotation ? "interface" : "class") + ": " + superclassName);
            }
            if (implementedInterfaces != null) {
                subLog.log("Interfaces: " + Join.join(", ", implementedInterfaces));
            }
            if (classAnnotations != null) {
                subLog.log("Class annotations: " + Join.join(", ", classAnnotations));
            }
            if (annotationParamDefaultValues != null) {
                for (final AnnotationParameterValue apv : annotationParamDefaultValues) {
                    subLog.log("Annotation default param value: " + apv);
                }
            }
            if (fieldInfoList != null) {
                for (final FieldInfo fieldInfo : fieldInfoList) {
                    subLog.log("Field: " + fieldInfo);
                }
            }
            if (methodInfoList != null) {
                for (final MethodInfo methodInfo : methodInfoList) {
                    subLog.log("Method: " + methodInfo);
                }
            }
            if (typeSignature != null) {
                ClassTypeSignature typeSig = null;
                try {
                    typeSig = ClassTypeSignature.parse(typeSignature, /* classInfo = */ null);
                } catch (final ParseException e) {
                    // Ignore
                }
                subLog.log("Class type signature: " + (typeSig == null ? typeSignature
                        : typeSig.toString(className, /* typeNameOnly = */ false, classModifiers, isAnnotation,
                                isInterface)));
            }
        }
    }
}
