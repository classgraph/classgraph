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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.lukehutch.fastclasspathscanner.utils.Join;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

/**
 * Class information that has been directly read from the binary classfile, before it is cross-linked with other
 * classes. (The cross-linking is done in a separate step to avoid the complexity of dealing with race conditions.)
 */
class ClassInfoUnlinked {
    String className;
    private final boolean isInterface;
    private final boolean isAnnotation;
    // Superclass (can be null if no superclass, or if superclass is blacklisted)
    private String superclassName;
    private List<String> implementedInterfaces;
    private List<String> annotations;
    private Set<String> methodAnnotations;
    private Set<String> fieldAnnotations;
    private Set<String> fieldTypes;
    private Map<String, Object> staticFinalFieldValues;
    private final ConcurrentHashMap<String, String> stringInternMap;
    private final ClasspathElement classpathElement;
    List<FieldInfo> fieldInfoList;
    List<MethodInfo> methodInfoList;

    private String intern(final String string) {
        if (string == null) {
            return null;
        }
        final String oldValue = stringInternMap.putIfAbsent(string, string);
        return oldValue == null ? string : oldValue;
    }

    ClassInfoUnlinked(final String className, final boolean isInterface, final boolean isAnnotation,
            final ConcurrentHashMap<String, String> stringInternMap, final ClasspathElement classpathElement) {
        this.stringInternMap = stringInternMap;
        this.className = intern(className);
        this.isInterface = isInterface;
        this.isAnnotation = isAnnotation;
        this.classpathElement = classpathElement;
    }

    void addSuperclass(final String superclassName) {
        this.superclassName = intern(superclassName);
    }

    void addImplementedInterface(final String interfaceName) {
        if (implementedInterfaces == null) {
            implementedInterfaces = new ArrayList<>();
        }
        implementedInterfaces.add(intern(interfaceName));
    }

    void addAnnotation(final String annotationName) {
        if (annotations == null) {
            annotations = new ArrayList<>();
        }
        annotations.add(intern(annotationName));
    }

    public void addMethodAnnotation(final String annotationName) {
        if (methodAnnotations == null) {
            methodAnnotations = new HashSet<>();
        }
        methodAnnotations.add(intern(annotationName));
    }

    public void addFieldAnnotation(final String annotationName) {
        if (fieldAnnotations == null) {
            fieldAnnotations = new HashSet<>();
        }
        fieldAnnotations.add(intern(annotationName));
    }

    void addFieldType(final String fieldTypeName) {
        if (fieldTypes == null) {
            fieldTypes = new HashSet<>();
        }
        fieldTypes.add(intern(fieldTypeName));
    }

    void addFieldConstantValue(final String fieldName, final Object staticFinalFieldValue) {
        if (staticFinalFieldValues == null) {
            staticFinalFieldValues = new HashMap<>();
        }
        staticFinalFieldValues.put(intern(fieldName), staticFinalFieldValue);
    }

    void addFieldInfo(final FieldInfo fieldInfo) {
        if (fieldInfoList == null) {
            fieldInfoList = new ArrayList<>();
        }
        fieldInfoList.add(fieldInfo);
    }

    void addMethodInfo(final MethodInfo methodInfo) {
        if (methodInfoList == null) {
            methodInfoList = new ArrayList<>();
        }
        methodInfoList.add(methodInfo);
    }

    /** Link classes. Not threadsafe, should be run in a single-threaded context. */
    void link(final ScanSpec scanSpec, final Map<String, ClassInfo> classNameToClassInfo, final LogNode log) {
        final ClassInfo classInfo = ClassInfo.addScannedClass(className, isInterface, isAnnotation, scanSpec,
                classNameToClassInfo, classpathElement, log);
        if (superclassName != null) {
            classInfo.addSuperclass(superclassName, classNameToClassInfo);
        }
        if (implementedInterfaces != null) {
            for (final String interfaceName : implementedInterfaces) {
                classInfo.addImplementedInterface(interfaceName, classNameToClassInfo);
            }
        }
        if (annotations != null) {
            for (final String annotationName : annotations) {
                classInfo.addAnnotation(annotationName, classNameToClassInfo);
            }
        }
        if (methodAnnotations != null) {
            for (final String annotationName : methodAnnotations) {
                classInfo.addMethodAnnotation(annotationName, classNameToClassInfo);
            }
        }
        if (fieldAnnotations != null) {
            for (final String annotationName : fieldAnnotations) {
                classInfo.addFieldAnnotation(annotationName, classNameToClassInfo);
            }
        }
        if (fieldTypes != null) {
            for (final String fieldTypeName : fieldTypes) {
                classInfo.addFieldType(fieldTypeName, classNameToClassInfo);
            }
        }
        if (staticFinalFieldValues != null) {
            for (final Entry<String, Object> ent : staticFinalFieldValues.entrySet()) {
                classInfo.addStaticFinalFieldConstantInitializerValue(ent.getKey(), ent.getValue());
            }
        }
        if (fieldInfoList != null) {
            classInfo.addFieldInfo(fieldInfoList);
        }
        if (methodInfoList != null) {
            classInfo.addMethodInfo(methodInfoList);
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
            if (annotations != null) {
                subLog.log("Annotations: " + Join.join(", ", annotations));
            }
            if (methodAnnotations != null) {
                subLog.log("Method annotations: " + Join.join(", ", methodAnnotations));
            }
            if (fieldTypes != null) {
                subLog.log("Field types: " + Join.join(", ", fieldTypes));
            }
            if (staticFinalFieldValues != null) {
                final List<String> fieldInitializers = new ArrayList<>();
                for (final Entry<String, Object> ent : staticFinalFieldValues.entrySet()) {
                    fieldInitializers.add(ent.getKey() + " = " + ent.getValue());
                }
                subLog.log("Static final field values: " + Join.join(", ", fieldInitializers));
            }
        }
    }
}