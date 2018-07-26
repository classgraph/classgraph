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

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.lukehutch.fastclasspathscanner.utils.Join;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;

/**
 * Class information that has been directly read from the binary classfile, before it is cross-linked with other
 * classes. (The cross-linking is done in a separate step to avoid the complexity of dealing with race conditions.)
 */
class ClassInfoUnlinked {
    String className;
    private final int classModifiers;
    private final boolean isInterface;
    private final boolean isAnnotation;
    // Superclass (can be null if no superclass, or if superclass is blacklisted)
    private String superclassName;
    private List<String> implementedInterfaces;
    private AnnotationInfoList classAnnotations;
    private String fullyQualifiedDefiningMethodName;
    private List<SimpleEntry<String, String>> classContainmentEntries;
    List<AnnotationParameterValue> annotationParamDefaultValues;
    final ClasspathElement classpathElement;
    FieldInfoList fieldInfoList;
    MethodInfoList methodInfoList;
    private String typeSignature;

    ClassInfoUnlinked(final String className, final int classModifiers, final boolean isInterface,
            final boolean isAnnotation, final ClasspathElement classpathElement) {
        this.className = (className);
        this.classModifiers = classModifiers;
        this.isInterface = isInterface;
        this.isAnnotation = isAnnotation;
        this.classpathElement = classpathElement;
    }

    void addTypeSignature(final String typeSignature) {
        this.typeSignature = typeSignature;
    }

    void addSuperclass(final String superclassName) {
        this.superclassName = superclassName;
    }

    void addImplementedInterface(final String interfaceName) {
        if (implementedInterfaces == null) {
            implementedInterfaces = new ArrayList<>();
        }
        implementedInterfaces.add(interfaceName);
    }

    void addClassAnnotation(final AnnotationInfo classAnnotation) {
        if (classAnnotations == null) {
            classAnnotations = new AnnotationInfoList();
        }
        classAnnotations.add(classAnnotation);
    }

    void addFieldInfo(final FieldInfo fieldInfo) {
        if (fieldInfoList == null) {
            fieldInfoList = new FieldInfoList();
        }
        fieldInfoList.add(fieldInfo);
    }

    void addMethodInfo(final MethodInfo methodInfo) {
        if (methodInfoList == null) {
            methodInfoList = new MethodInfoList();
        }
        methodInfoList.add(methodInfo);
    }

    public void addEnclosingMethod(final String fullyQualifiedDefiningMethodName) {
        this.fullyQualifiedDefiningMethodName = fullyQualifiedDefiningMethodName;
    }

    public void addClassContainment(final String innerClassName, final String outerClassName) {
        if (classContainmentEntries == null) {
            classContainmentEntries = new ArrayList<>();
        }
        classContainmentEntries.add(new SimpleEntry<>(innerClassName, outerClassName));
    }

    public void addAnnotationParamDefaultValues(final List<AnnotationParameterValue> annotationParamDefaultValues) {
        this.annotationParamDefaultValues = annotationParamDefaultValues;
    }

    /** Link classes. Not threadsafe, should be run in a single-threaded context. */
    void link(final ScanSpec scanSpec, final Map<String, ClassInfo> classNameToClassInfo, final LogNode log) {
        final ClassInfo classInfo = ClassInfo.addScannedClass(className, classModifiers, isInterface, isAnnotation,
                classNameToClassInfo, classpathElement, scanSpec, log);
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
    }

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
                subLog.log("Annotation default param values: " + Join.join(", ", annotationParamDefaultValues));
            }
            if (methodInfoList != null) {
                subLog.log("Method info: " + Join.join(", ", methodInfoList));
            }
            if (fieldInfoList != null) {
                subLog.log("Field info: " + Join.join(", ", fieldInfoList));
            }
            if (typeSignature != null) {
                ClassTypeSignature typeSig = null;
                try {
                    typeSig = ClassTypeSignature.parse(className, typeSignature, /* scanResult = */ null);
                } catch (final ParseException e) {
                }
                subLog.log("Class type signature: " + (typeSig == null ? typeSignature
                        : typeSig.toString(classModifiers, isAnnotation, isInterface, className)));
            }
        }
    }
}
