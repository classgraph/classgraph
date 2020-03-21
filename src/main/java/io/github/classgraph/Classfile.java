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

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.classgraph.Scanner.ClassfileScanWorkUnit;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.fileslice.reader.ClassfileReader;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.Join;
import nonapi.io.github.classgraph.utils.LogNode;

/**
 * A classfile binary format parser. Implements its own buffering to avoid the overhead of using DataInputStream.
 * This class should only be used by a single thread at a time, but can be re-used to scan multiple classfiles in
 * sequence, to avoid re-allocating buffer memory.
 */
class Classfile {
    /** The {@link ClassfileReader} for the current classfile. */
    private ClassfileReader reader;

    /** The classpath element that contains this classfile. */
    private final ClasspathElement classpathElement;

    /** The classpath order. */
    private final List<ClasspathElement> classpathOrder;

    /** The relative path to the classfile (should correspond to className). */
    private final String relativePath;

    /** The classfile resource. */
    private final Resource classfileResource;

    /** The string intern map. */
    private final ConcurrentHashMap<String, String> stringInternMap;

    /** The name of the class. */
    private String className;

    /** Whether this is an external class. */
    private final boolean isExternalClass;

    /** The class modifiers. */
    private int classModifiers;

    /** Whether this class is an interface. */
    private boolean isInterface;

    /** Whether this class is a record. */
    private boolean isRecord;

    /** Whether this class is an annotation. */
    private boolean isAnnotation;

    /** The superclass name. (can be null if no superclass, or if superclass is blacklisted.) */
    private String superclassName;

    /** The implemented interfaces. */
    private List<String> implementedInterfaces;

    /** The class annotations. */
    private AnnotationInfoList classAnnotations;

    /** The fully qualified name of the defining method. */
    private String fullyQualifiedDefiningMethodName;

    /** Class containment entries. */
    private List<ClassContainment> classContainmentEntries;

    /** Annotation default parameter values. */
    private AnnotationParameterValueList annotationParamDefaultValues;

    /** Referenced class names. */
    private Set<String> refdClassNames;

    /** The field info list. */
    private FieldInfoList fieldInfoList;

    /** The method info list. */
    private MethodInfoList methodInfoList;

    /** The type signature. */
    private String typeSignature;

    /** The names of whitelisted classes found in the classpath while scanning paths within classpath elements. */
    private final Set<String> whitelistedClassNamesFound;

    /**
     * The names of external (non-whitelisted) classes scheduled for extended scanning (where scanning is extended
     * upwards to superclasses, interfaces and annotations).
     */
    private final Set<String> classNamesScheduledForExtendedScanning;

    /** Any additional work units scheduled for scanning. */
    private List<ClassfileScanWorkUnit> additionalWorkUnits;

    /** The scan spec. */
    private final ScanSpec scanSpec;

    // -------------------------------------------------------------------------------------------------------------

    /** The number of constant pool entries plus one. */
    private int cpCount;

    /** The byte offset for the beginning of each entry in the constant pool. */
    private int[] entryOffset;

    /** The tag (type) for each entry in the constant pool. */
    private int[] entryTag;

    /** The indirection index for String/Class entries in the constant pool. */
    private int[] indirectStringRefs;

    // -------------------------------------------------------------------------------------------------------------

    /** An empty array for the case where there are no annotations. */
    private static final AnnotationInfo[] NO_ANNOTATIONS = new AnnotationInfo[0];

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Class containment.
     */
    static class ClassContainment {
        /** The inner class name. */
        public final String innerClassName;

        /** The inner class modifier bits. */
        public final int innerClassModifierBits;

        /** The outer class name. */
        public final String outerClassName;

        /**
         * Constructor.
         *
         * @param innerClassName
         *            the inner class name.
         * @param innerClassModifierBits
         *            the inner class modifier bits.
         * @param outerClassName
         *            the outer class name.
         */
        public ClassContainment(final String innerClassName, final int innerClassModifierBits,
                final String outerClassName) {
            this.innerClassName = innerClassName;
            this.innerClassModifierBits = innerClassModifierBits;
            this.outerClassName = outerClassName;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Thrown when a classfile's contents are not in the correct format. */
    static class ClassfileFormatException extends IOException {
        /** serialVersionUID. */
        static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param message
         *            the message
         */
        public ClassfileFormatException(final String message) {
            super(message);
        }

        /**
         * Constructor.
         *
         * @param message
         *            the message
         * @param cause
         *            the cause
         */
        public ClassfileFormatException(final String message, final Throwable cause) {
            super(message, cause);
        }

        /**
         * Speed up exception (stack trace is not needed for this exception).
         *
         * @return this
         */
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /** Thrown when a classfile needs to be skipped. */
    static class SkipClassException extends IOException {
        /** serialVersionUID. */
        static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param message
         *            the message
         */
        public SkipClassException(final String message) {
            super(message);
        }

        /**
         * Speed up exception (stack trace is not needed for this exception).
         *
         * @return this
         */
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Extend scanning to a superclass, interface or annotation.
     *
     * @param className
     *            the class name
     * @param relationship
     *            the relationship type
     * @param log
     *            the log
     */
    private void scheduleScanningIfExternalClass(final String className, final String relationship,
            final LogNode log) {
        // Don't scan Object
        if (className != null && !className.equals("java.lang.Object")
        // Don't schedule a class for scanning that was already found to be whitelisted
                && !whitelistedClassNamesFound.contains(className)
                // Only schedule each external class once for scanning, across all threads
                && classNamesScheduledForExtendedScanning.add(className)) {
            if (scanSpec.classWhiteBlackList.isBlacklisted(className)) {
                if (log != null) {
                    log.log("Cannot extend scanning upwards to external " + relationship + " " + className
                            + ", since it is blacklisted");
                }
            } else {
                // Search for the named class' classfile among classpath elements, in classpath order (this is O(N)
                // for each class, but there shouldn't be too many cases of extending scanning upwards)
                final String classfilePath = JarUtils.classNameToClassfilePath(className);
                // First check current classpath element, to avoid iterating through other classpath elements
                Resource classResource = classpathElement.getResource(classfilePath);
                ClasspathElement foundInClasspathElt = null;
                if (classResource != null) {
                    // Found the classfile in the current classpath element
                    foundInClasspathElt = classpathElement;
                } else {
                    // Didn't find the classfile in the current classpath element -- iterate through other elements
                    for (final ClasspathElement classpathOrderElt : classpathOrder) {
                        if (classpathOrderElt != classpathElement) {
                            classResource = classpathOrderElt.getResource(classfilePath);
                            if (classResource != null) {
                                foundInClasspathElt = classpathOrderElt;
                                break;
                            }
                        }
                    }
                }
                if (classResource != null) {
                    // Found class resource 
                    if (log != null) {
                        // Log the extended scan as a child LogNode of the current class' scan log, since the
                        // external class is not scanned at the regular place in the classpath element hierarchy
                        // traversal
                        classResource.scanLog = log
                                .log("Extending scanning to external " + relationship
                                        + (foundInClasspathElt == classpathElement ? " in same classpath element"
                                                : " in classpath element " + foundInClasspathElt)
                                        + ": " + className);
                    }
                    if (additionalWorkUnits == null) {
                        additionalWorkUnits = new ArrayList<>();
                    }
                    // Schedule class resource for scanning
                    additionalWorkUnits.add(new ClassfileScanWorkUnit(foundInClasspathElt, classResource,
                            /* isExternalClass = */ true));
                } else {
                    if (log != null) {
                        log.log("External " + relationship + " " + className + " was not found in "
                                + "non-blacklisted packages -- cannot extend scanning to this class");
                    }
                }
            }
        }
    }

    /**
     * Check if scanning needs to be extended upwards from an annotation parameter value.
     *
     * @param annotationParamVal
     *            the {@link AnnotationInfo} object for an annotation, or for an annotation parameter value.
     * @param log
     *            the log
     */
    private void extendScanningUpwardsFromAnnotationParameterValues(final Object annotationParamVal,
            final LogNode log) {
        if (annotationParamVal == null) {
            // Should not be possible -- ignore
        } else if (annotationParamVal instanceof AnnotationInfo) {
            final AnnotationInfo annotationInfo = (AnnotationInfo) annotationParamVal;
            scheduleScanningIfExternalClass(annotationInfo.getClassName(), "annotation class", log);
            for (final AnnotationParameterValue apv : annotationInfo.getParameterValues()) {
                extendScanningUpwardsFromAnnotationParameterValues(apv.getValue(), log);
            }
        } else if (annotationParamVal instanceof AnnotationEnumValue) {
            scheduleScanningIfExternalClass(((AnnotationEnumValue) annotationParamVal).getClassName(), "enum class",
                    log);
        } else if (annotationParamVal instanceof AnnotationClassRef) {
            scheduleScanningIfExternalClass(((AnnotationClassRef) annotationParamVal).getClassName(), "class ref",
                    log);
        } else if (annotationParamVal.getClass().isArray()) {
            for (int i = 0, n = Array.getLength(annotationParamVal); i < n; i++) {
                extendScanningUpwardsFromAnnotationParameterValues(Array.get(annotationParamVal, i), log);
            }
        } else {
            // String etc. -- ignore
        }
    }

    /**
     * Check if scanning needs to be extended upwards to an external superclass, interface or annotation.
     *
     * @param log
     *            the log
     */
    private void extendScanningUpwards(final LogNode log) {
        // Check superclass
        if (superclassName != null) {
            scheduleScanningIfExternalClass(superclassName, "superclass", log);
        }
        // Check implemented interfaces
        if (implementedInterfaces != null) {
            for (final String interfaceName : implementedInterfaces) {
                scheduleScanningIfExternalClass(interfaceName, "interface", log);
            }
        }
        // Check class annotations
        if (classAnnotations != null) {
            for (final AnnotationInfo annotationInfo : classAnnotations) {
                scheduleScanningIfExternalClass(annotationInfo.getName(), "class annotation", log);
                extendScanningUpwardsFromAnnotationParameterValues(annotationInfo, log);
            }
        }
        // Check annotation default parameter values
        if (annotationParamDefaultValues != null) {
            for (final AnnotationParameterValue apv : annotationParamDefaultValues) {
                extendScanningUpwardsFromAnnotationParameterValues(apv.getValue(), log);
            }
        }
        // Check method annotations and method parameter annotations
        if (methodInfoList != null) {
            for (final MethodInfo methodInfo : methodInfoList) {
                if (methodInfo.annotationInfo != null) {
                    for (final AnnotationInfo methodAnnotationInfo : methodInfo.annotationInfo) {
                        scheduleScanningIfExternalClass(methodAnnotationInfo.getName(), "method annotation", log);
                        extendScanningUpwardsFromAnnotationParameterValues(methodAnnotationInfo, log);
                    }
                    if (methodInfo.parameterAnnotationInfo != null
                            && methodInfo.parameterAnnotationInfo.length > 0) {
                        for (final AnnotationInfo[] paramAnnInfoArr : methodInfo.parameterAnnotationInfo) {
                            if (paramAnnInfoArr != null && paramAnnInfoArr.length > 0) {
                                for (final AnnotationInfo paramAnnInfo : paramAnnInfoArr) {
                                    scheduleScanningIfExternalClass(paramAnnInfo.getName(),
                                            "method parameter annotation", log);
                                    extendScanningUpwardsFromAnnotationParameterValues(paramAnnInfo, log);
                                }
                            }
                        }
                    }
                }
            }
        }
        // Check field annotations
        if (fieldInfoList != null) {
            for (final FieldInfo fieldInfo : fieldInfoList) {
                if (fieldInfo.annotationInfo != null) {
                    for (final AnnotationInfo fieldAnnotationInfo : fieldInfo.annotationInfo) {
                        scheduleScanningIfExternalClass(fieldAnnotationInfo.getName(), "field annotation", log);
                        extendScanningUpwardsFromAnnotationParameterValues(fieldAnnotationInfo, log);
                    }
                }
            }
        }
        // Check if this class is an inner class, and if so, extend scanning to outer class
        if (classContainmentEntries != null) {
            for (final ClassContainment classContainmentEntry : classContainmentEntries) {
                if (classContainmentEntry.innerClassName.equals(className)) {
                    scheduleScanningIfExternalClass(classContainmentEntry.outerClassName, "outer class", log);
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Link classes. Not threadsafe, should be run in a single-threaded context.
     *
     * @param classNameToClassInfo
     *            map from class name to class info
     * @param packageNameToPackageInfo
     *            map from package name to package info
     * @param moduleNameToModuleInfo
     *            map from module name to module info
     */
    void link(final Map<String, ClassInfo> classNameToClassInfo,
            final Map<String, PackageInfo> packageNameToPackageInfo,
            final Map<String, ModuleInfo> moduleNameToModuleInfo) {
        boolean isModuleDescriptor = false;
        boolean isPackageDescriptor = false;
        ClassInfo classInfo = null;
        if (className.equals("module-info")) {
            isModuleDescriptor = true;

        } else if (className.equals("package-info") || className.endsWith(".package-info")) {
            isPackageDescriptor = true;

        } else {
            // Handle regular classfile
            classInfo = ClassInfo.addScannedClass(className, classModifiers, isExternalClass, classNameToClassInfo,
                    classpathElement, classfileResource);
            classInfo.setModifiers(classModifiers);
            classInfo.setIsInterface(isInterface);
            classInfo.setIsAnnotation(isAnnotation);
            classInfo.setIsRecord(isRecord);
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
                classInfo.setTypeSignature(typeSignature);
            }
            if (refdClassNames != null) {
                classInfo.addReferencedClassNames(refdClassNames);
            }
        }

        // Get or create PackageInfo, if this is not a module descriptor (the module descriptor's package is "")
        PackageInfo packageInfo = null;
        if (!isModuleDescriptor) {
            // Get package for this class or package descriptor
            final String packageName = PackageInfo.getParentPackageName(className);
            packageInfo = PackageInfo.getOrCreatePackage(packageName, packageNameToPackageInfo);
            if (isPackageDescriptor) {
                // Add any class annotations on the package-info.class file to the ModuleInfo
                packageInfo.addAnnotations(classAnnotations);
            } else if (classInfo != null) {
                // Add ClassInfo to PackageInfo, and vice versa
                packageInfo.addClassInfo(classInfo);
                classInfo.packageInfo = packageInfo;
            }
        }

        // Get or create ModuleInfo, if there is a module name
        final String moduleName = classpathElement.getModuleName();
        if (moduleName != null) {
            // Get or create a ModuleInfo object for this module
            ModuleInfo moduleInfo = moduleNameToModuleInfo.get(moduleName);
            if (moduleInfo == null) {
                moduleNameToModuleInfo.put(moduleName,
                        moduleInfo = new ModuleInfo(classfileResource.getModuleRef(), classpathElement));
            }
            if (isModuleDescriptor) {
                // Add any class annotations on the module-info.class file to the ModuleInfo
                moduleInfo.addAnnotations(classAnnotations);
            }
            if (classInfo != null) {
                // Add ClassInfo to ModuleInfo, and vice versa
                moduleInfo.addClassInfo(classInfo);
                classInfo.moduleInfo = moduleInfo;
            }
            if (packageInfo != null) {
                // Add PackageInfo to ModuleInfo
                moduleInfo.addPackageInfo(packageInfo);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Intern a string.
     *
     * @param str
     *            the str
     * @return the string
     */
    private String intern(final String str) {
        if (str == null) {
            return null;
        }
        final String interned = stringInternMap.putIfAbsent(str, str);
        if (interned != null) {
            return interned;
        }
        return str;
    }

    /**
     * Get the byte offset within the buffer of a string from the constant pool, or 0 for a null string.
     *
     * @param cpIdx
     *            the constant pool index
     * @param subFieldIdx
     *            should be 0 for CONSTANT_Utf8, CONSTANT_Class and CONSTANT_String, and for
     *            CONSTANT_NameAndType_info, fetches the name for value 0, or the type descriptor for value 1.
     * @return the constant pool string offset
     * @throws ClassfileFormatException
     *             If a problem is detected
     */
    private int getConstantPoolStringOffset(final int cpIdx, final int subFieldIdx)
            throws ClassfileFormatException {
        if (cpIdx < 1 || cpIdx >= cpCount) {
            throw new ClassfileFormatException("Constant pool index " + cpIdx + ", should be in range [1, "
                    + (cpCount - 1) + "] -- cannot continue reading class. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
        final int t = entryTag[cpIdx];
        if ((t != 12 && subFieldIdx != 0) || (t == 12 && subFieldIdx != 0 && subFieldIdx != 1)) {
            throw new ClassfileFormatException(
                    "Bad subfield index " + subFieldIdx + " for tag " + t + ", cannot continue reading class. "
                            + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
        int cpIdxToUse;
        if (t == 0) {
            // Assume this means null
            return 0;
        } else if (t == 1) {
            // CONSTANT_Utf8
            cpIdxToUse = cpIdx;
        } else if (t == 7 || t == 8 || t == 19) {
            // t == 7 => CONSTANT_Class, e.g. "[[I", "[Ljava/lang/Thread;"; t == 8 => CONSTANT_String;
            // t == 19 => CONSTANT_Method_Info
            final int indirIdx = indirectStringRefs[cpIdx];
            if (indirIdx == -1) {
                // Should not happen
                throw new ClassfileFormatException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this at https://github.com/classgraph/classgraph/issues");
            }
            if (indirIdx == 0) {
                // I assume this represents a null string, since the zeroeth entry is unused
                return 0;
            }
            cpIdxToUse = indirIdx;
        } else if (t == 12) {
            // CONSTANT_NameAndType_info
            final int compoundIndirIdx = indirectStringRefs[cpIdx];
            if (compoundIndirIdx == -1) {
                // Should not happen
                throw new ClassfileFormatException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this at https://github.com/classgraph/classgraph/issues");
            }
            final int indirIdx = (subFieldIdx == 0 ? (compoundIndirIdx >> 16) : compoundIndirIdx) & 0xffff;
            if (indirIdx == 0) {
                // Should not happen
                throw new ClassfileFormatException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this at https://github.com/classgraph/classgraph/issues");
            }
            cpIdxToUse = indirIdx;
        } else {
            throw new ClassfileFormatException("Wrong tag number " + t + " at constant pool index " + cpIdx + ", "
                    + "cannot continue reading class. Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
        }
        if (cpIdxToUse < 1 || cpIdxToUse >= cpCount) {
            throw new ClassfileFormatException("Constant pool index " + cpIdx + ", should be in range [1, "
                    + (cpCount - 1) + "] -- cannot continue reading class. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
        return entryOffset[cpIdxToUse];
    }

    /**
     * Get a string from the constant pool, optionally replacing '/' with '.'.
     *
     * @param cpIdx
     *            the constant pool index
     * @param replaceSlashWithDot
     *            if true, replace slash with dot in the result.
     * @param stripLSemicolon
     *            if true, strip 'L' from the beginning and ';' from the end before returning (for class reference
     *            constants)
     * @return the constant pool string
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private String getConstantPoolString(final int cpIdx, final boolean replaceSlashWithDot,
            final boolean stripLSemicolon) throws ClassfileFormatException, IOException {
        final int constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        if (constantPoolStringOffset == 0) {
            return null;
        }
        final int utfLen = reader.readUnsignedShort(constantPoolStringOffset);
        if (utfLen == 0) {
            return "";
        }
        return intern(
                reader.readString(constantPoolStringOffset + 2L, utfLen, replaceSlashWithDot, stripLSemicolon));
    }

    /**
     * Get a string from the constant pool.
     *
     * @param cpIdx
     *            the constant pool index
     * @param subFieldIdx
     *            should be 0 for CONSTANT_Utf8, CONSTANT_Class and CONSTANT_String, and for
     *            CONSTANT_NameAndType_info, fetches the name for value 0, or the type descriptor for value 1.
     * @return the constant pool string
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private String getConstantPoolString(final int cpIdx, final int subFieldIdx)
            throws ClassfileFormatException, IOException {
        final int constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, subFieldIdx);
        if (constantPoolStringOffset == 0) {
            return null;
        }
        final int utfLen = reader.readUnsignedShort(constantPoolStringOffset);
        if (utfLen == 0) {
            return "";
        }
        return intern(reader.readString(constantPoolStringOffset + 2L, utfLen, /* replaceSlashWithDot = */ false,
                /* stripLSemicolon = */ false));
    }

    /**
     * Get a string from the constant pool.
     *
     * @param cpIdx
     *            the constant pool index
     * @return the constant pool string
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private String getConstantPoolString(final int cpIdx) throws ClassfileFormatException, IOException {
        return getConstantPoolString(cpIdx, /* subFieldIdx = */ 0);
    }

    /**
     * Get the first UTF8 byte of a string in the constant pool, or '\0' if the string is null or empty.
     *
     * @param cpIdx
     *            the constant pool index
     * @return the first byte of the constant pool string
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private byte getConstantPoolStringFirstByte(final int cpIdx) throws ClassfileFormatException, IOException {
        final int constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        if (constantPoolStringOffset == 0) {
            return '\0';
        }
        final int utfLen = reader.readUnsignedShort(constantPoolStringOffset);
        if (utfLen == 0) {
            return '\0';
        }
        return reader.readByte(constantPoolStringOffset + 2L);
    }

    /**
     * Get a string from the constant pool, and interpret it as a class name by replacing '/' with '.'.
     *
     * @param cpIdx
     *            the constant pool index
     * @return the constant pool class name
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private String getConstantPoolClassName(final int cpIdx) throws ClassfileFormatException, IOException {
        return getConstantPoolString(cpIdx, /* replaceSlashWithDot = */ true, /* stripLSemicolon = */ false);
    }

    /**
     * Get a string from the constant pool representing an internal string descriptor for a class name
     * ("Lcom/xyz/MyClass;"), and interpret it as a class name by replacing '/' with '.', and removing the leading
     * "L" and the trailing ";".
     *
     * @param cpIdx
     *            the constant pool index
     * @return the constant pool class descriptor
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private String getConstantPoolClassDescriptor(final int cpIdx) throws ClassfileFormatException, IOException {
        return getConstantPoolString(cpIdx, /* replaceSlashWithDot = */ true, /* stripLSemicolon = */ true);
    }

    /**
     * Compare a string in the constant pool with a given ASCII string, without constructing the constant pool
     * String object.
     *
     * @param cpIdx
     *            the constant pool index
     * @param asciiStr
     *            the ASCII string to compare to
     * @return true, if successful
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private boolean constantPoolStringEquals(final int cpIdx, final String asciiStr)
            throws ClassfileFormatException, IOException {
        final int cpStrOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        if (cpStrOffset == 0) {
            return asciiStr == null;
        } else if (asciiStr == null) {
            return false;
        }
        final int cpStrLen = reader.readUnsignedShort(cpStrOffset);
        final int asciiStrLen = asciiStr.length();
        if (cpStrLen != asciiStrLen) {
            return false;
        }
        final int cpStrStart = cpStrOffset + 2;
        reader.bufferTo(cpStrStart + cpStrLen);
        final byte[] buf = reader.buf();
        for (int i = 0; i < cpStrLen; i++) {
            if ((char) (buf[cpStrStart + i] & 0xff) != asciiStr.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read an unsigned short from the constant pool.
     *
     * @param cpIdx
     *            the constant pool index.
     * @return the unsigned short
     * @throws IOException
     *             If an I/O exception occurred.
     */
    private int cpReadUnsignedShort(final int cpIdx) throws IOException {
        if (cpIdx < 1 || cpIdx >= cpCount) {
            throw new ClassfileFormatException("Constant pool index " + cpIdx + ", should be in range [1, "
                    + (cpCount - 1) + "] -- cannot continue reading class. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
        return reader.readUnsignedShort(entryOffset[cpIdx]);
    }

    /**
     * Read an int from the constant pool.
     *
     * @param cpIdx
     *            the constant pool index.
     * @return the int
     * @throws IOException
     *             If an I/O exception occurred.
     */
    private int cpReadInt(final int cpIdx) throws IOException {
        if (cpIdx < 1 || cpIdx >= cpCount) {
            throw new ClassfileFormatException("Constant pool index " + cpIdx + ", should be in range [1, "
                    + (cpCount - 1) + "] -- cannot continue reading class. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
        return reader.readInt(entryOffset[cpIdx]);
    }

    /**
     * Read a long from the constant pool.
     *
     * @param cpIdx
     *            the constant pool index.
     * @return the long
     * @throws IOException
     *             If an I/O exception occurred.
     */
    private long cpReadLong(final int cpIdx) throws IOException {
        if (cpIdx < 1 || cpIdx >= cpCount) {
            throw new ClassfileFormatException("Constant pool index " + cpIdx + ", should be in range [1, "
                    + (cpCount - 1) + "] -- cannot continue reading class. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
        return reader.readLong(entryOffset[cpIdx]);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a field constant from the constant pool.
     *
     * @param tag
     *            the tag
     * @param fieldTypeDescriptorFirstChar
     *            the first char of the field type descriptor
     * @param cpIdx
     *            the constant pool index
     * @return the field constant pool value
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private Object getFieldConstantPoolValue(final int tag, final char fieldTypeDescriptorFirstChar,
            final int cpIdx) throws ClassfileFormatException, IOException {
        switch (tag) {
        case 1: // Modified UTF8
        case 7: // Class -- N.B. Unused? Class references do not seem to actually be stored as constant initalizers
        case 8: // String
            // Forward or backward indirect reference to a modified UTF8 entry
            return getConstantPoolString(cpIdx);
        case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
            final int intVal = cpReadInt(cpIdx);
            switch (fieldTypeDescriptorFirstChar) {
            case 'I':
                return intVal;
            case 'S':
                return (short) intVal;
            case 'C':
                return (char) intVal;
            case 'B':
                return (byte) intVal;
            case 'Z':
                return intVal != 0;
            default:
                // Fall through
            }
            throw new ClassfileFormatException("Unknown Constant_INTEGER type " + fieldTypeDescriptorFirstChar
                    + ", " + "cannot continue reading class. Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
        case 4: // float
            return Float.intBitsToFloat(cpReadInt(cpIdx));
        case 5: // long
            return cpReadLong(cpIdx);
        case 6: // double
            return Double.longBitsToDouble(cpReadLong(cpIdx));
        default:
            // ClassGraph doesn't expect other types
            // (N.B. in particular, enum values are not stored in the constant pool, so don't need to be handled)  
            throw new ClassfileFormatException("Unknown constant pool tag " + tag + ", "
                    + "cannot continue reading class. Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read annotation entry from classfile.
     *
     * @return the annotation, as an {@link AnnotationInfo} object.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private AnnotationInfo readAnnotation() throws IOException {
        // Lcom/xyz/Annotation; -> Lcom.xyz.Annotation;
        final String annotationClassName = getConstantPoolClassDescriptor(reader.readUnsignedShort());
        final int numElementValuePairs = reader.readUnsignedShort();
        AnnotationParameterValueList paramVals = null;
        if (numElementValuePairs > 0) {
            paramVals = new AnnotationParameterValueList(numElementValuePairs);
            for (int i = 0; i < numElementValuePairs; i++) {
                final String paramName = getConstantPoolString(reader.readUnsignedShort());
                final Object paramValue = readAnnotationElementValue();
                paramVals.add(new AnnotationParameterValue(paramName, paramValue));
            }
        }
        return new AnnotationInfo(annotationClassName, paramVals);
    }

    /**
     * Read annotation element value from classfile.
     *
     * @return the annotation element value
     * @throws IOException
     *             If an IO exception occurs.
     */
    private Object readAnnotationElementValue() throws IOException {
        final int tag = (char) reader.readUnsignedByte();
        switch (tag) {
        case 'B':
            return (byte) cpReadInt(reader.readUnsignedShort());
        case 'C':
            return (char) cpReadInt(reader.readUnsignedShort());
        case 'D':
            return Double.longBitsToDouble(cpReadLong(reader.readUnsignedShort()));
        case 'F':
            return Float.intBitsToFloat(cpReadInt(reader.readUnsignedShort()));
        case 'I':
            return cpReadInt(reader.readUnsignedShort());
        case 'J':
            return cpReadLong(reader.readUnsignedShort());
        case 'S':
            return (short) cpReadUnsignedShort(reader.readUnsignedShort());
        case 'Z':
            return cpReadInt(reader.readUnsignedShort()) != 0;
        case 's':
            return getConstantPoolString(reader.readUnsignedShort());
        case 'e': {
            // Return type is AnnotationEnumVal.
            final String annotationClassName = getConstantPoolClassDescriptor(reader.readUnsignedShort());
            final String annotationConstName = getConstantPoolString(reader.readUnsignedShort());
            return new AnnotationEnumValue(annotationClassName, annotationConstName);
        }
        case 'c':
            // Return type is AnnotationClassRef (for class references in annotations)
            final String classRefTypeDescriptor = getConstantPoolString(reader.readUnsignedShort());
            return new AnnotationClassRef(classRefTypeDescriptor);
        case '@':
            // Complex (nested) annotation. Return type is AnnotationInfo.
            return readAnnotation();
        case '[':
            // Return type is Object[] (of nested annotation element values)
            final int count = reader.readUnsignedShort();
            final Object[] arr = new Object[count];
            for (int i = 0; i < count; ++i) {
                // Nested annotation element value
                arr[i] = readAnnotationElementValue();
            }
            return arr;
        default:
            throw new ClassfileFormatException("Class " + className + " has unknown annotation element type tag '"
                    + ((char) tag) + "': element size unknown, cannot continue reading class. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read constant pool entries.
     *
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void readConstantPoolEntries() throws IOException {
        // Only record class dependency info if inter-class dependencies are enabled
        List<Integer> classNameCpIdxs = null;
        List<Integer> typeSignatureIdxs = null;
        if (scanSpec.enableInterClassDependencies) {
            classNameCpIdxs = new ArrayList<Integer>();
            typeSignatureIdxs = new ArrayList<Integer>();
        }

        // Read size of constant pool
        cpCount = reader.readUnsignedShort();

        // Allocate storage for constant pool
        entryOffset = new int[cpCount];
        entryTag = new int[cpCount];
        indirectStringRefs = new int[cpCount];
        Arrays.fill(indirectStringRefs, 0, cpCount, -1);

        // Read constant pool entries
        for (int i = 1, skipSlot = 0; i < cpCount; i++) {
            if (skipSlot == 1) {
                // Skip a slot (keeps Scrutinizer happy -- it doesn't like i++ in case 6)
                skipSlot = 0;
                continue;
            }
            entryTag[i] = reader.readUnsignedByte();
            entryOffset[i] = reader.currPos();
            switch (entryTag[i]) {
            case 0: // Impossible, probably buffer underflow
                throw new ClassfileFormatException("Unknown constant pool tag 0 in classfile " + relativePath
                        + " (possible buffer underflow issue). Please report this at "
                        + "https://github.com/classgraph/classgraph/issues");
            case 1: // Modified UTF8
                final int strLen = reader.readUnsignedShort();
                reader.skip(strLen);
                break;
            case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
            case 4: // float
                reader.skip(4);
                break;
            case 5: // long
            case 6: // double
                reader.skip(8);
                skipSlot = 1; // double slot
                break;
            case 7: // Class reference (format is e.g. "java/lang/String")
                // Forward or backward indirect reference to a modified UTF8 entry
                indirectStringRefs[i] = reader.readUnsignedShort();
                if (classNameCpIdxs != null) {
                    // If this is a class ref, and inter-class dependencies are enabled, record the dependency
                    classNameCpIdxs.add(indirectStringRefs[i]);
                }
                break;
            case 8: // String
                // Forward or backward indirect reference to a modified UTF8 entry
                indirectStringRefs[i] = reader.readUnsignedShort();
                break;
            case 9: // field ref
                // Refers to a class ref (case 7) and then a name and type (case 12)
                reader.skip(4);
                break;
            case 10: // method ref
                // Refers to a class ref (case 7) and then a name and type (case 12)
                reader.skip(4);
                break;
            case 11: // interface method ref
                // Refers to a class ref (case 7) and then a name and type (case 12)
                reader.skip(4);
                break;
            case 12: // name and type
                final int nameRef = reader.readUnsignedShort();
                final int typeRef = reader.readUnsignedShort();
                if (typeSignatureIdxs != null) {
                    typeSignatureIdxs.add(typeRef);
                }
                indirectStringRefs[i] = (nameRef << 16) | typeRef;
                break;
            case 15: // method handle
                reader.skip(3);
                break;
            case 16: // method type
                reader.skip(2);
                break;
            case 18: // invoke dynamic
                reader.skip(4);
                break;
            case 19: // module (for module-info.class in JDK9+)
                // see https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.4
                indirectStringRefs[i] = reader.readUnsignedShort();
                break;
            case 20: // package (for module-info.class in JDK9+)
                // see https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.4
                reader.skip(2);
                break;
            default:
                throw new ClassfileFormatException("Unknown constant pool tag " + entryTag[i]
                        + " (element size unknown, cannot continue reading class). Please report this at "
                        + "https://github.com/classgraph/classgraph/issues");
            }
        }

        // Find classes referenced in the constant pool. Note that there are some class refs that will not be
        // found this way, e.g. enum classes and class refs in annotation parameter values, since they are
        // referenced as strings (tag 1) rather than classes (tag 7) or type signatures (part of tag 12).
        // Therefore, a hybrid approach needs to be applied of extracting these other class refs from
        // the ClassInfo graph, and combining them with class names extracted from the constant pool here.
        if (classNameCpIdxs != null) {
            refdClassNames = new HashSet<>();
            // Get class names from direct class references in constant pool
            for (final int cpIdx : classNameCpIdxs) {
                final String refdClassName = getConstantPoolString(cpIdx, /* replaceSlashWithDot = */ true,
                        /* stripLSemicolon = */ false);
                if (refdClassName != null) {
                    if (refdClassName.startsWith("[")) {
                        // Parse array type signature, e.g. "[Ljava.lang.String;" -- uses '.' rather than '/'
                        try {
                            final TypeSignature typeSig = TypeSignature.parse(refdClassName.replace('.', '/'),
                                    /* definingClass = */ null);
                            typeSig.findReferencedClassNames(refdClassNames);
                        } catch (final ParseException e) {
                            // Should not happen
                            throw new ClassfileFormatException("Could not parse class name: " + refdClassName, e);
                        }
                    } else {
                        refdClassNames.add(refdClassName);
                    }
                }
            }
        }
        if (typeSignatureIdxs != null) {
            // Get class names from type signatures in "name and type" entries in constant pool
            for (final int cpIdx : typeSignatureIdxs) {
                final String typeSigStr = getConstantPoolString(cpIdx);
                if (typeSigStr != null) {
                    try {
                        if (typeSigStr.indexOf('(') >= 0 || "<init>".equals(typeSigStr)) {
                            // Parse the type signature
                            final MethodTypeSignature typeSig = MethodTypeSignature.parse(typeSigStr,
                                    /* definingClassName = */ null);
                            // Extract class names from type signature
                            typeSig.findReferencedClassNames(refdClassNames);
                        } else {
                            // Parse the type signature
                            final TypeSignature typeSig = TypeSignature.parse(typeSigStr,
                                    /* definingClassName = */ null);
                            // Extract class names from type signature
                            typeSig.findReferencedClassNames(refdClassNames);
                        }
                    } catch (final ParseException e) {
                        throw new ClassfileFormatException("Could not parse type signature: " + typeSigStr, e);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read basic class information.
     * 
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the classfile is incorrectly formatted.
     * @throws SkipClassException
     *             if the classfile needs to be skipped (e.g. the class is non-public, and ignoreClassVisibility is
     *             false)
     */
    private void readBasicClassInfo() throws IOException, ClassfileFormatException, SkipClassException {
        // Modifier flags
        classModifiers = reader.readUnsignedShort();

        isInterface = (classModifiers & 0x0200) != 0;
        isAnnotation = (classModifiers & 0x2000) != 0;

        // The fully-qualified class name of this class, with slashes replaced with dots
        final String classNamePath = getConstantPoolString(reader.readUnsignedShort());
        if (classNamePath == null) {
            throw new ClassfileFormatException("Class name is null");
        }
        className = classNamePath.replace('/', '.');
        if ("java.lang.Object".equals(className)) {
            // Don't process java.lang.Object (it has a null superclass), though you can still search for classes
            // that are subclasses of java.lang.Object (as an external class).
            throw new SkipClassException("No need to scan java.lang.Object");
        }

        // Check class visibility modifiers
        final boolean isModule = (classModifiers & 0x8000) != 0; // Equivalently filename is "module-info.class"
        final boolean isPackage = relativePath.regionMatches(relativePath.lastIndexOf('/') + 1,
                "package-info.class", 0, 18);
        if (!scanSpec.ignoreClassVisibility && !Modifier.isPublic(classModifiers) && !isModule && !isPackage) {
            throw new SkipClassException("Class is not public, and ignoreClassVisibility() was not called");
        }

        // Make sure classname matches relative path
        if (!relativePath.endsWith(".class")) {
            // Should not happen
            throw new SkipClassException("Classfile filename " + relativePath + " does not end in \".class\"");
        }
        final int len = classNamePath.length();
        if (relativePath.length() != len + 6 || !classNamePath.regionMatches(0, relativePath, 0, len)) {
            throw new SkipClassException(
                    "Relative path " + relativePath + " does not match class name " + className);
        }

        // Superclass name, with slashes replaced with dots
        final int superclassNameCpIdx = reader.readUnsignedShort();
        if (superclassNameCpIdx > 0) {
            superclassName = getConstantPoolClassName(superclassNameCpIdx);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read the class' interfaces.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     */
    private void readInterfaces() throws IOException {
        // Interfaces
        final int interfaceCount = reader.readUnsignedShort();
        for (int i = 0; i < interfaceCount; i++) {
            final String interfaceName = getConstantPoolClassName(reader.readUnsignedShort());
            if (implementedInterfaces == null) {
                implementedInterfaces = new ArrayList<>();
            }
            implementedInterfaces.add(interfaceName);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read the class' fields.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the classfile is incorrectly formatted.
     */
    private void readFields() throws IOException, ClassfileFormatException {
        // Fields
        final int fieldCount = reader.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) {
            // Info on modifier flags: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.5
            final int fieldModifierFlags = reader.readUnsignedShort();
            final boolean isPublicField = ((fieldModifierFlags & 0x0001) == 0x0001);
            final boolean fieldIsVisible = isPublicField || scanSpec.ignoreFieldVisibility;
            final boolean getStaticFinalFieldConstValue = scanSpec.enableStaticFinalFieldConstantInitializerValues
                    && fieldIsVisible;
            if (!fieldIsVisible || (!scanSpec.enableFieldInfo && !getStaticFinalFieldConstValue)) {
                // Skip field
                reader.readUnsignedShort(); // fieldNameCpIdx
                reader.readUnsignedShort(); // fieldTypeDescriptorCpIdx
                final int attributesCount = reader.readUnsignedShort();
                for (int j = 0; j < attributesCount; j++) {
                    reader.readUnsignedShort(); // attributeNameCpIdx
                    final int attributeLength = reader.readInt(); // == 2
                    reader.skip(attributeLength);
                }
            } else {
                final int fieldNameCpIdx = reader.readUnsignedShort();
                final String fieldName = getConstantPoolString(fieldNameCpIdx);
                final int fieldTypeDescriptorCpIdx = reader.readUnsignedShort();
                final char fieldTypeDescriptorFirstChar = (char) getConstantPoolStringFirstByte(
                        fieldTypeDescriptorCpIdx);
                String fieldTypeDescriptor;
                String fieldTypeSignature = null;
                fieldTypeDescriptor = getConstantPoolString(fieldTypeDescriptorCpIdx);

                Object fieldConstValue = null;
                AnnotationInfoList fieldAnnotationInfo = null;
                final int attributesCount = reader.readUnsignedShort();
                for (int j = 0; j < attributesCount; j++) {
                    final int attributeNameCpIdx = reader.readUnsignedShort();
                    final int attributeLength = reader.readInt(); // == 2
                    // See if field name matches one of the requested names for this class, and if it does,
                    // check if it is initialized with a constant value
                    if ((getStaticFinalFieldConstValue)
                            && constantPoolStringEquals(attributeNameCpIdx, "ConstantValue")) {
                        // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.2
                        final int cpIdx = reader.readUnsignedShort();
                        if (cpIdx < 1 || cpIdx >= cpCount) {
                            throw new ClassfileFormatException("Constant pool index " + cpIdx
                                    + ", should be in range [1, " + (cpCount - 1)
                                    + "] -- cannot continue reading class. "
                                    + "Please report this at https://github.com/classgraph/classgraph/issues");
                        }
                        fieldConstValue = getFieldConstantPoolValue(entryTag[cpIdx], fieldTypeDescriptorFirstChar,
                                cpIdx);
                    } else if (fieldIsVisible && constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                        fieldTypeSignature = getConstantPoolString(reader.readUnsignedShort());
                    } else if (scanSpec.enableAnnotationInfo //
                            && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                                    || (!scanSpec.disableRuntimeInvisibleAnnotations && constantPoolStringEquals(
                                            attributeNameCpIdx, "RuntimeInvisibleAnnotations")))) {
                        // Read annotation names
                        final int fieldAnnotationCount = reader.readUnsignedShort();
                        if (fieldAnnotationCount > 0) {
                            if (fieldAnnotationInfo == null) {
                                fieldAnnotationInfo = new AnnotationInfoList(1);
                            }
                            for (int k = 0; k < fieldAnnotationCount; k++) {
                                final AnnotationInfo fieldAnnotation = readAnnotation();
                                fieldAnnotationInfo.add(fieldAnnotation);
                            }
                        }
                    } else {
                        // No match, just skip attribute
                        reader.skip(attributeLength);
                    }
                }
                if (scanSpec.enableFieldInfo && fieldIsVisible) {
                    if (fieldInfoList == null) {
                        fieldInfoList = new FieldInfoList();
                    }
                    fieldInfoList.add(new FieldInfo(className, fieldName, fieldModifierFlags, fieldTypeDescriptor,
                            fieldTypeSignature, fieldConstValue, fieldAnnotationInfo));
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read the class' methods.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the classfile is incorrectly formatted.
     */
    private void readMethods() throws IOException, ClassfileFormatException {
        // Methods
        final int methodCount = reader.readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            // Info on modifier flags: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6
            final int methodModifierFlags = reader.readUnsignedShort();
            final boolean isPublicMethod = ((methodModifierFlags & 0x0001) == 0x0001);
            final boolean methodIsVisible = isPublicMethod || scanSpec.ignoreMethodVisibility;

            String methodName = null;
            String methodTypeDescriptor = null;
            String methodTypeSignature = null;
            // Always enable MethodInfo for annotations (this is how annotation constants are defined)
            final boolean enableMethodInfo = scanSpec.enableMethodInfo || isAnnotation;
            if (enableMethodInfo || isAnnotation) { // Annotations store defaults in method_info
                final int methodNameCpIdx = reader.readUnsignedShort();
                methodName = getConstantPoolString(methodNameCpIdx);
                final int methodTypeDescriptorCpIdx = reader.readUnsignedShort();
                methodTypeDescriptor = getConstantPoolString(methodTypeDescriptorCpIdx);
            } else {
                reader.skip(4); // name_index, descriptor_index
            }
            final int attributesCount = reader.readUnsignedShort();
            String[] methodParameterNames = null;
            int[] methodParameterModifiers = null;
            AnnotationInfo[][] methodParameterAnnotations = null;
            AnnotationInfoList methodAnnotationInfo = null;
            boolean methodHasBody = false;
            if (!methodIsVisible || (!enableMethodInfo && !isAnnotation)) {
                // Skip method attributes
                for (int j = 0; j < attributesCount; j++) {
                    reader.skip(2); // attribute_name_index
                    final int attributeLength = reader.readInt();
                    reader.skip(attributeLength);
                }
            } else {
                // Look for method annotations
                for (int j = 0; j < attributesCount; j++) {
                    final int attributeNameCpIdx = reader.readUnsignedShort();
                    final int attributeLength = reader.readInt();
                    if (scanSpec.enableAnnotationInfo
                            && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                                    || (!scanSpec.disableRuntimeInvisibleAnnotations && constantPoolStringEquals(
                                            attributeNameCpIdx, "RuntimeInvisibleAnnotations")))) {
                        final int methodAnnotationCount = reader.readUnsignedShort();
                        if (methodAnnotationCount > 0) {
                            if (methodAnnotationInfo == null) {
                                methodAnnotationInfo = new AnnotationInfoList(1);
                            }
                            for (int k = 0; k < methodAnnotationCount; k++) {
                                final AnnotationInfo annotationInfo = readAnnotation();
                                methodAnnotationInfo.add(annotationInfo);
                            }
                        }
                    } else if (scanSpec.enableAnnotationInfo
                            && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleParameterAnnotations")
                                    || (!scanSpec.disableRuntimeInvisibleAnnotations && constantPoolStringEquals(
                                            attributeNameCpIdx, "RuntimeInvisibleParameterAnnotations")))) {
                        // Merge together runtime visible and runtime invisible annotations into a single array
                        // of annotations for each method parameter (runtime visible and runtime invisible
                        // annotations are given in separate attributes, so if both attributes are present,
                        // have to make the parameter annotation arrays larger when the second attribute is
                        // encountered).
                        final int numParams = reader.readUnsignedByte();
                        if (methodParameterAnnotations == null) {
                            methodParameterAnnotations = new AnnotationInfo[numParams][];
                        } else if (methodParameterAnnotations.length != numParams) {
                            throw new ClassfileFormatException(
                                    "Mismatch in number of parameters between RuntimeVisibleParameterAnnotations "
                                            + "and RuntimeInvisibleParameterAnnotations");
                        }
                        for (int paramIdx = 0; paramIdx < numParams; paramIdx++) {
                            final int numAnnotations = reader.readUnsignedShort();
                            if (numAnnotations > 0) {
                                int annStartIdx = 0;
                                if (methodParameterAnnotations[paramIdx] != null) {
                                    annStartIdx = methodParameterAnnotations[paramIdx].length;
                                    methodParameterAnnotations[paramIdx] = Arrays.copyOf(
                                            methodParameterAnnotations[paramIdx], annStartIdx + numAnnotations);
                                } else {
                                    methodParameterAnnotations[paramIdx] = new AnnotationInfo[numAnnotations];
                                }
                                for (int annIdx = 0; annIdx < numAnnotations; annIdx++) {
                                    methodParameterAnnotations[paramIdx][annStartIdx + annIdx] = readAnnotation();
                                }
                            } else if (methodParameterAnnotations[paramIdx] == null) {
                                methodParameterAnnotations[paramIdx] = NO_ANNOTATIONS;
                            }
                        }
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "MethodParameters")) {
                        // Read method parameters. For Java, these are only produced in JDK8+, and only if the
                        // commandline switch `-parameters` is provided at compiletime.
                        final int paramCount = reader.readUnsignedByte();
                        methodParameterNames = new String[paramCount];
                        methodParameterModifiers = new int[paramCount];
                        for (int k = 0; k < paramCount; k++) {
                            final int cpIdx = reader.readUnsignedShort();
                            // If the constant pool index is zero, then the parameter is unnamed => use null
                            methodParameterNames[k] = cpIdx == 0 ? null : getConstantPoolString(cpIdx);
                            methodParameterModifiers[k] = reader.readUnsignedShort();
                        }
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                        // Add type params to method type signature
                        methodTypeSignature = getConstantPoolString(reader.readUnsignedShort());
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "AnnotationDefault")) {
                        if (annotationParamDefaultValues == null) {
                            annotationParamDefaultValues = new AnnotationParameterValueList();
                        }
                        this.annotationParamDefaultValues.add(new AnnotationParameterValue(methodName,
                                // Get annotation parameter default value
                                readAnnotationElementValue()));
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "Code")) {
                        methodHasBody = true;
                        reader.skip(attributeLength);
                    } else {
                        reader.skip(attributeLength);
                    }
                }
                // Create MethodInfo
                if (enableMethodInfo) {
                    if (methodInfoList == null) {
                        methodInfoList = new MethodInfoList();
                    }
                    methodInfoList.add(new MethodInfo(className, methodName, methodAnnotationInfo,
                            methodModifierFlags, methodTypeDescriptor, methodTypeSignature, methodParameterNames,
                            methodParameterModifiers, methodParameterAnnotations, methodHasBody));
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read class attributes.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the classfile is incorrectly formatted.
     */
    private void readClassAttributes() throws IOException, ClassfileFormatException {
        // Class attributes (including class annotations, class type variables, module info, etc.)
        final int attributesCount = reader.readUnsignedShort();
        for (int i = 0; i < attributesCount; i++) {
            final int attributeNameCpIdx = reader.readUnsignedShort();
            final int attributeLength = reader.readInt();
            if (scanSpec.enableAnnotationInfo //
                    && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                            || (!scanSpec.disableRuntimeInvisibleAnnotations && constantPoolStringEquals(
                                    attributeNameCpIdx, "RuntimeInvisibleAnnotations")))) {
                final int annotationCount = reader.readUnsignedShort();
                if (annotationCount > 0) {
                    if (classAnnotations == null) {
                        classAnnotations = new AnnotationInfoList();
                    }
                    for (int m = 0; m < annotationCount; m++) {
                        classAnnotations.add(readAnnotation());
                    }
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Record")) {
                isRecord = true;
                // No need to read record_components_info entries -- there is a 1:1 correspondence between
                // record components and fields/methods of the same name and type as the record component,
                // so we can just rely on the field and method reading code to work correctly with records.
                reader.skip(attributeLength);
            } else if (constantPoolStringEquals(attributeNameCpIdx, "InnerClasses")) {
                final int numInnerClasses = reader.readUnsignedShort();
                for (int j = 0; j < numInnerClasses; j++) {
                    final int innerClassInfoCpIdx = reader.readUnsignedShort();
                    final int outerClassInfoCpIdx = reader.readUnsignedShort();
                    reader.skip(2); // inner_name_idx
                    final int innerClassAccessFlags = reader.readUnsignedShort();
                    if (innerClassInfoCpIdx != 0 && outerClassInfoCpIdx != 0) {
                        final String innerClassName = getConstantPoolClassName(innerClassInfoCpIdx);
                        final String outerClassName = getConstantPoolClassName(outerClassInfoCpIdx);
                        if (innerClassName == null || outerClassName == null) {
                            // Should not happen (fix static analyzer warning)
                            throw new ClassfileFormatException("Inner and/or outer class name is null");
                        }
                        if (innerClassName.equals(outerClassName)) {
                            // Invalid according to spec
                            throw new ClassfileFormatException("Inner and outer class name cannot be the same");
                        }
                        // Record types have a Lookup inner class for boostrap methods in JDK 14 -- drop this
                        if (!("java.lang.invoke.MethodHandles$Lookup".equals(innerClassName)
                                && "java.lang.invoke.MethodHandles".equals(outerClassName))) {
                            // Store relationship between inner class and outer class
                            if (classContainmentEntries == null) {
                                classContainmentEntries = new ArrayList<>();
                            }
                            classContainmentEntries.add(
                                    new ClassContainment(innerClassName, innerClassAccessFlags, outerClassName));
                        }
                    }
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                // Get class type signature, including type variables
                typeSignature = getConstantPoolString(reader.readUnsignedShort());
            } else if (constantPoolStringEquals(attributeNameCpIdx, "EnclosingMethod")) {
                final String innermostEnclosingClassName = getConstantPoolClassName(reader.readUnsignedShort());
                final int enclosingMethodCpIdx = reader.readUnsignedShort();
                String definingMethodName;
                if (enclosingMethodCpIdx == 0) {
                    // A cpIdx of 0 (which is an invalid value) is used for anonymous inner classes declared in
                    // class initializer code, e.g. assigned to a class field.
                    definingMethodName = "<clinit>";
                } else {
                    definingMethodName = getConstantPoolString(enclosingMethodCpIdx, /* subFieldIdx = */ 0);
                    // Could also fetch method type signature using subFieldIdx = 1, if needed
                }
                // Link anonymous inner classes into the class with their containing method
                if (classContainmentEntries == null) {
                    classContainmentEntries = new ArrayList<>();
                }
                classContainmentEntries
                        .add(new ClassContainment(className, classModifiers, innermostEnclosingClassName));
                // Also store the fully-qualified name of the enclosing method, to mark this as an anonymous inner
                // class
                this.fullyQualifiedDefiningMethodName = innermostEnclosingClassName + "." + definingMethodName;
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Module")) {
                final int moduleNameCpIdx = reader.readUnsignedShort();
                classpathElement.moduleNameFromModuleDescriptor = getConstantPoolString(moduleNameCpIdx);
                // (Future work): parse the rest of the module descriptor fields, and add to ModuleInfo:
                // https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.7.25
                reader.skip(attributeLength - 2);
            } else {
                reader.skip(attributeLength);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Directly examine contents of classfile binary header to determine annotations, implemented interfaces, the
     * super-class etc. Creates a new ClassInfo object, and adds it to classNameToClassInfoOut. Assumes classpath
     * masking has already been performed, so that only one class of a given name will be added.
     *
     * @param classpathElement
     *            the classpath element
     * @param classpathOrder
     *            the classpath order
     * @param whitelistedClassNamesFound
     *            the names of whitelisted classes found in the classpath while scanning paths within classpath
     *            elements.
     * @param classNamesScheduledForExtendedScanning
     *            the names of external (non-whitelisted) classes scheduled for extended scanning (where scanning is
     *            extended upwards to superclasses, interfaces and annotations).
     * @param relativePath
     *            the relative path
     * @param classfileResource
     *            the classfile resource
     * @param isExternalClass
     *            if this is an external class
     * @param stringInternMap
     *            the string intern map
     * @param workQueue
     *            the work queue
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the log
     * @throws IOException
     *             If an IO exception occurs.
     * @throws ClassfileFormatException
     *             If a problem occurs while parsing the classfile.
     * @throws SkipClassException
     *             if the classfile needs to be skipped (e.g. the class is non-public, and ignoreClassVisibility is
     *             false)
     */
    Classfile(final ClasspathElement classpathElement, final List<ClasspathElement> classpathOrder,
            final Set<String> whitelistedClassNamesFound, final Set<String> classNamesScheduledForExtendedScanning,
            final String relativePath, final Resource classfileResource, final boolean isExternalClass,
            final ConcurrentHashMap<String, String> stringInternMap,
            final WorkQueue<ClassfileScanWorkUnit> workQueue, final ScanSpec scanSpec, final LogNode log)
            throws IOException, ClassfileFormatException, SkipClassException {
        this.classpathElement = classpathElement;
        this.classpathOrder = classpathOrder;
        this.relativePath = relativePath;
        this.whitelistedClassNamesFound = whitelistedClassNamesFound;
        this.classNamesScheduledForExtendedScanning = classNamesScheduledForExtendedScanning;
        this.classfileResource = classfileResource;
        this.isExternalClass = isExternalClass;
        this.stringInternMap = stringInternMap;
        this.scanSpec = scanSpec;

        try {
            // Open a BufferedSequentialReader for the classfile
            reader = classfileResource.openClassfile();

            // Check magic number
            if (reader.readInt() != 0xCAFEBABE) {
                throw new ClassfileFormatException("Classfile does not have correct magic number");
            }

            // Read classfile minor and major version
            reader.readUnsignedShort();
            reader.readUnsignedShort();

            // Read the constant pool
            readConstantPoolEntries();

            // Read basic class info (
            readBasicClassInfo();

            // Read interfaces
            readInterfaces();

            // Read fields
            readFields();

            // Read methods
            readMethods();

            // Read class attributes
            readClassAttributes();

        } finally {
            // Close BufferedSequentialReader
            classfileResource.close();
            reader = null;
        }

        // Write class info to log 
        final LogNode subLog = log == null ? null
                : log.log("Found " //
                        + (isAnnotation ? "annotation class" : isInterface ? "interface class" : "class") //
                        + " " + className);
        if (subLog != null) {
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
                    if (refdClassNames != null) {
                        typeSig.findReferencedClassNames(refdClassNames);
                    }
                } catch (final ParseException e) {
                    // Ignore
                }
                subLog.log("Class type signature: " + (typeSig == null ? typeSignature
                        : typeSig.toString(className, /* typeNameOnly = */ false, classModifiers, isAnnotation,
                                isInterface)));
            }
            if (refdClassNames != null) {
                final List<String> refdClassNamesSorted = new ArrayList<>(refdClassNames);
                CollectionUtils.sortIfNotEmpty(refdClassNamesSorted);
                subLog.log("Referenced class names: " + Join.join(", ", refdClassNamesSorted));
            }
        }

        // Check if any superclasses, interfaces or annotations are external (non-whitelisted) classes
        // that need to be scheduled for scanning, so that all of the "upwards" direction of the class
        // graph is scanned for any whitelisted class, even if the superclasses / interfaces / annotations
        // are not themselves whitelisted.
        if (scanSpec.extendScanningUpwardsToExternalClasses) {
            extendScanningUpwards(subLog);
            // If any external classes were found, schedule them for scanning
            if (additionalWorkUnits != null) {
                workQueue.addWorkUnits(additionalWorkUnits);
            }
        }
    }
}
