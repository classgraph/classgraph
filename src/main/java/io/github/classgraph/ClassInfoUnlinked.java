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

import io.github.classgraph.utils.Join;
import io.github.classgraph.utils.LogNode;
import io.github.classgraph.utils.Parser.ParseException;

/**
 * Class information that has been directly read from the binary classfile, before it is cross-linked with other
 * classes. (The cross-linking is done in a separate step to avoid the complexity of dealing with race conditions.)
 */
class ClassInfoUnlinked {
    final String className;
    private final int classModifiers;
    private final boolean isInterface;
    private final boolean isAnnotation;
    private final boolean isExternalClass;
    // Superclass (can be null if no superclass, or if superclass is blacklisted)
    String superclassName;
    List<String> implementedInterfaces;
    AnnotationInfoList classAnnotations;
    private String fullyQualifiedDefiningMethodName;
    private List<SimpleEntry<String, String>> classContainmentEntries;
    private AnnotationParameterValueList annotationParamDefaultValues;
    final ClasspathElement classpathElement;
    final Resource classfileResource;
    FieldInfoList fieldInfoList;
    MethodInfoList methodInfoList;
    private String typeSignature;

    ClassInfoUnlinked(final String className, final String superclassName, final int classModifiers,
            final boolean isInterface, final boolean isAnnotation, final boolean isExternalClass,
            final ClasspathElement classpathElement, final Resource classfileResource) {
        this.className = (className);
        this.superclassName = superclassName;
        this.classModifiers = classModifiers;
        this.isInterface = isInterface;
        this.isAnnotation = isAnnotation;
        this.isExternalClass = isExternalClass;
        this.classpathElement = classpathElement;
        this.classfileResource = classfileResource;
    }

    void addTypeSignature(final String typeSignature) {
        this.typeSignature = typeSignature;
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

    public void addAnnotationParamDefaultValue(final AnnotationParameterValue annotationParamDefaultValue) {
        if (annotationParamDefaultValues == null) {
            annotationParamDefaultValues = new AnnotationParameterValueList();
        }
        this.annotationParamDefaultValues.add(annotationParamDefaultValue);
    }

    /**
     * Link classes. Not threadsafe, should be run in a single-threaded context.
     */
    void link(final ScanSpec scanSpec, final Map<String, ClassInfo> classNameToClassInfo,
            final Map<String, PackageInfo> packageNameToPackageInfo,
            final Map<String, ModuleInfo> moduleNameToModuleInfo, final LogNode log) {
        if (className.equals("module-info") || className.endsWith(".module-info")) {
            // Handle module descriptor classfile
            final ModuleRef moduleRef = classfileResource.getModuleRef();
            if (moduleRef == null) {
                if (log != null) {
                    log.log("Found module descriptor " + className + " but module was included in traditional "
                            + "classpath -- will not create ModuleInfo for this classpath element");
                }
            } else {
                String moduleName = moduleRef.getName();
                if (moduleName == null || moduleName.isEmpty()) {
                    moduleName = "<unnamed>";
                }
                ModuleInfo moduleInfo = moduleNameToModuleInfo.get(moduleName);
                if (moduleInfo == null) {
                    moduleNameToModuleInfo.put(moduleName, moduleInfo = new ModuleInfo(moduleRef));
                }
                moduleInfo.addAnnotations(classAnnotations);
            }
        } else if (className.equals("package-info") || className.endsWith(".package-info")) {
            // Handle package descriptor classfile
            final int lastDotIdx = className.lastIndexOf('.');
            final String packageName = lastDotIdx < 0 ? "" : className.substring(0, lastDotIdx);
            PackageInfo packageInfo = packageNameToPackageInfo.get(packageName);
            if (packageInfo == null) {
                packageNameToPackageInfo.put(packageName, packageInfo = new PackageInfo(packageName));
            }
            packageInfo.addAnnotations(classAnnotations);
        } else {
            // Handle regular classfile
            final ClassInfo classInfo = ClassInfo.addScannedClass(className, classModifiers, isInterface,
                    isAnnotation, isExternalClass, classNameToClassInfo, classpathElement, classfileResource,
                    scanSpec, log);
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

            final int lastDotIdx = className.lastIndexOf('.');
            final String packageName = lastDotIdx < 0 ? "" : className.substring(0, lastDotIdx);
            PackageInfo packageInfo = packageNameToPackageInfo.get(packageName);
            if (packageInfo == null) {
                packageNameToPackageInfo.put(packageName, packageInfo = new PackageInfo(packageName));
            }
            packageInfo.addClassInfo(classInfo, classNameToClassInfo);

            final ModuleRef moduleRef = classInfo.getModuleRef();
            if (moduleRef != null) {
                String moduleName = moduleRef.getName();
                if (moduleName == null || moduleName.isEmpty()) {
                    moduleName = "<unnamed>";
                }
                ModuleInfo moduleInfo = moduleNameToModuleInfo.get(moduleName);
                if (moduleInfo == null) {
                    moduleNameToModuleInfo.put(moduleName, moduleInfo = new ModuleInfo(moduleRef));
                }
                moduleInfo.addClassInfo(classInfo);
                moduleInfo.addPackageInfo(packageInfo);
            }
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
                }
                subLog.log("Class type signature: " + (typeSig == null ? typeSignature
                        : typeSig.toString(className, /* typeNameOnly = */ false, classModifiers, isAnnotation,
                                isInterface)));
            }
        }
    }
}
