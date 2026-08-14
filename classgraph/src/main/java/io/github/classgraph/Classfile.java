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

import java.io.IOException;
import java.io.Serial;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.classgraph.Scanner.ClassfileScanWorkUnit;
import io.github.classgraph.base.internal.concurrency.WorkQueue;
import io.github.classgraph.base.internal.parser.ParseException;
import io.github.classgraph.base.internal.utils.CollectionUtils;
import io.github.classgraph.base.internal.utils.JarUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.base.internal.utils.StringUtils;
import io.github.classgraph.internal.scanspec.ScanSpec;
import io.github.classgraph.vfs.internal.slice.reader.RandomAccessOrSequentialReader;
import org.jspecify.annotations.Nullable;

/**
 * A classfile binary format parser. Implements its own buffering to avoid the overhead of using DataInputStream.
 * This class should only be used by a single thread at a time, but can be re-used to scan multiple classfiles in
 * sequence, to avoid re-allocating buffer memory.
 *
 * <p>
 * See <a href="https://docs.oracle.com/javase/specs/jvms/se16/html/jvms-4.html">the class file format spec</a>.
 */
class Classfile {
    /**
     * The {@link RandomAccessOrSequentialReader} for the current classfile, or null once the classfile has been read.
     */
    private @Nullable RandomAccessOrSequentialReader reader;

    /** The classpath element that contains this classfile. */
    private final ClasspathElement classpathElement;

    /** The classpath order. */
    private final List<ClasspathElement> classpathOrder;

    /**
     * The modules that are not being scanned, but whose classfiles may still be read in order to complete the class
     * graph above an accepted class.
     */
    private final UnscannedModules unscannedModules;

    /** The relative path to the classfile (should correspond to className). */
    private final String relativePath;

    /** The classfile resource. */
    private final Resource classfileResource;

    /** The string intern map. */
    private final ConcurrentHashMap<String, String> stringInternMap;

    /** The name of the class. */
    private String className;

    /** The minor version of the classfile format. */
    private int minorVersion;

    /** The major version of the classfile format. */
    private int majorVersion;

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

    /**
     * The superclass name. (can be null if no superclass, or if superclass is rejected.)
     */
    private @Nullable String superclassName;

    /** The implemented interfaces. */
    private @Nullable List<String> implementedInterfaces;

    /** The class annotations. */
    private @Nullable AnnotationInfoList classAnnotations;

    /** The fully qualified name of the defining method. */
    private @Nullable String fullyQualifiedDefiningMethodName;

    /** Class containment entries. */
    private @Nullable List<ClassContainment> classContainmentEntries;

    /** Annotation default parameter values. */
    private @Nullable AnnotationParameterValueList annotationParamDefaultValues;

    /** Referenced class names. */
    private @Nullable Set<String> refdClassNames;

    /** The field info list. */
    private @Nullable FieldInfoList fieldInfoList;

    /** The method info list. */
    private @Nullable MethodInfoList methodInfoList;

    /** The type signature. */
    private @Nullable String typeSignatureStr;

    /** The source file, such as Classfile.java */
    private @Nullable String sourceFile;

    /**
     * The type annotation decorators for the {@link ClassTypeSignature} instance.
     */
    private @Nullable List<ClassTypeAnnotationDecorator> classTypeAnnotationDecorators;

    /**
     * The names of accepted classes found in the classpath while scanning paths within classpath elements.
     */
    private final Set<String> acceptedClassNamesFound;

    /**
     * The names of external (non-accepted) classes scheduled for extended scanning (where scanning is extended
     * upwards to superclasses, interfaces and annotations).
     */
    private final Set<String> classNamesScheduledForExtendedScanning;

    /** Any additional work units scheduled for scanning. */
    private @Nullable List<ClassfileScanWorkUnit> additionalWorkUnits;

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

    /**
     * Get the {@link RandomAccessOrSequentialReader} for the current classfile.
     *
     * @return the reader
     * @throws NullPointerException
     *             if the classfile has already been read.
     */
    private RandomAccessOrSequentialReader reader() {
        return Objects.requireNonNull(reader);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** An empty array for the case where there are no annotations. */
    private static final AnnotationInfo[] NO_ANNOTATIONS = {};

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Class containment.
     *
     * @param innerClassName
     *            the inner class name.
     * @param innerClassModifierBits
     *            the inner class modifier bits.
     * @param outerClassName
     *            the outer class name.
     */
    record ClassContainment(String innerClassName, int innerClassModifierBits, String outerClassName) {
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Thrown when a classfile's contents are not in the correct format. */
    static class ClassfileFormatException extends IOException {
        /** serialVersionUID. */
        @Serial
        private static final long serialVersionUID = 1L;

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
        @Serial
        private static final long serialVersionUID = 1L;

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
     *            the log node, or null to skip logging
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    private void scheduleScanningIfExternalClass(final @Nullable String className, final String relationship,
            final @Nullable LogNode log) throws InterruptedException {
        // Don't extend scanning upwards to Object -- it has no superclass, interfaces or annotations, so scanning
        // it adds nothing to the class graph (the superclass link to Object is recorded from the class name alone,
        // without needing to scan Object itself)
        if (className == null || "java.lang.Object".equals(className)
        // Don't schedule a class for scanning that was already found to be accepted
                || acceptedClassNamesFound.contains(className)
                // Only schedule each external class once for scanning, across all threads
                || !classNamesScheduledForExtendedScanning.add(className)) {
            return;
        }
        if (scanSpec.classAcceptReject.isRejected(className)) {
            if (log != null) {
                log.log("Cannot extend scanning upwards to external " + relationship + " " + className
                        + ", since it is rejected");
            }
            return;
        }
        final var classfileLocation = findClassfile(className, log);
        if (classfileLocation == null) {
            if (log != null) {
                log.log("External " + relationship + " " + className + " was not found in "
                        + "non-rejected packages -- cannot extend scanning to this class");
            }
            return;
        }
        final var classResource = classfileLocation.classfileResource();
        final var foundInClasspathElt = classfileLocation.classpathElement();
        if (log != null) {
            // Log the extended scan as a child LogNode of the current class' scan log, since the external class is
            // not scanned at the regular place in the classpath element hierarchy traversal
            classResource.scanLog = log.log("Extending scanning to external " + relationship
                    + (foundInClasspathElt == classpathElement ? " in same classpath element"
                            : " in classpath element " + foundInClasspathElt)
                    + ": " + className);
        }
        if (additionalWorkUnits == null) {
            additionalWorkUnits = new ArrayList<>();
        }
        // Schedule class resource for scanning
        additionalWorkUnits
                .add(new ClassfileScanWorkUnit(foundInClasspathElt, classResource, /* isExternalClass = */ true));
    }

    /**
     * Find the classfile of a named class, searching the classpath elements that are being scanned first, then the
     * modules that are not being scanned.
     *
     * @param className
     *            the name of the class to find.
     * @param log
     *            the log node, or null to skip logging
     * @return the classfile resource and the classpath element it was found in, or null if the classfile was not
     *         found.
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    private @Nullable ClassfileLocation findClassfile(final String className, final @Nullable LogNode log)
            throws InterruptedException {
        // Search for the named class' classfile among classpath elements, in classpath order (this is O(N) for each
        // class, but there shouldn't be too many cases of extending scanning upwards)
        final var classfilePath = JarUtils.classNameToClassfilePath(className);
        // First check current classpath element, to avoid iterating through other classpath elements
        final var classResource = classpathElement.getResource(classfilePath);
        if (classResource != null) {
            return new ClassfileLocation(classpathElement, classResource);
        }
        // Didn't find the classfile in the current classpath element -- iterate through other elements
        for (final ClasspathElement classpathOrderElt : classpathOrder) {
            if (classpathOrderElt != classpathElement) {
                final var classResourceInOtherElt = classpathOrderElt.getResource(classfilePath);
                if (classResourceInOtherElt != null) {
                    return new ClassfileLocation(classpathOrderElt, classResourceInOtherElt);
                }
            }
        }
        // The classfile is not in any classpath element that is being scanned. Look in the modules that are not
        // being scanned, so that the class graph above an accepted class is still completed through classes in
        // system modules, which are not scanned unless they are asked for (#902)
        final var workUnit = unscannedModules.findClassfile(className, classfilePath, log);
        return workUnit == null ? null
                : new ClassfileLocation(workUnit.classpathElement(), workUnit.classfileResource());
    }

    /**
     * The location of a classfile.
     *
     * @param classpathElement
     *            the classpath element the classfile was found in.
     * @param classfileResource
     *            the classfile resource.
     */
    private record ClassfileLocation(ClasspathElement classpathElement, Resource classfileResource) {
    }

    /**
     * Check if scanning needs to be extended upwards from an annotation parameter value.
     *
     * @param annotationParamVal
     *            the {@link AnnotationInfo} object for an annotation, or for an annotation parameter value, or
     *            null.
     * @param log
     *            the log node, or null to skip logging
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    private void extendScanningUpwardsFromAnnotationParameterValues(final @Nullable Object annotationParamVal,
            final @Nullable LogNode log) throws InterruptedException {
        if (annotationParamVal == null) {
            // Should not be possible -- ignore
        } else if (annotationParamVal instanceof final AnnotationInfo annotationInfo) {
            scheduleScanningIfExternalClass(annotationInfo.getClassName(), "annotation class", log);
            // Call the package-private accessor, so that the annotation's parameter value list is not frozen
            // while scanning is still in progress
            for (final AnnotationParameterValue apv : annotationInfo
                    .getParameterValues(/* includeDefaultValues = */ true)) {
                extendScanningUpwardsFromAnnotationParameterValues(apv.getValue(), log);
            }
        } else if (annotationParamVal instanceof final AnnotationEnumValue annotationEnumValue) {
            scheduleScanningIfExternalClass(annotationEnumValue.getClassName(), "enum class", log);
        } else if (annotationParamVal instanceof final AnnotationClassRef annotationClassRef) {
            scheduleScanningIfExternalClass(annotationClassRef.getClassName(), "class ref", log);
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
     *            the log node, or null to skip logging
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    private void extendScanningUpwards(final @Nullable LogNode log) throws InterruptedException {
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
        // Check method annotations, method parameter annotations and thrown exception types
        if (methodInfoList != null) {
            for (final MethodInfo methodInfo : methodInfoList) {
                extendScanningUpwardsFromMethod(methodInfo, log);
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
                if (classContainmentEntry.innerClassName().equals(className)) {
                    scheduleScanningIfExternalClass(classContainmentEntry.outerClassName(), "outer class", log);
                }
            }
        }
    }

    /**
     * Check if scanning needs to be extended upwards to the external annotation classes and thrown exception types
     * of one of the class' methods.
     *
     * @param methodInfo
     *            the method
     * @param log
     *            the log node, or null to skip logging
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    private void extendScanningUpwardsFromMethod(final MethodInfo methodInfo, final @Nullable LogNode log)
            throws InterruptedException {
        if (methodInfo.annotationInfo != null) {
            for (final AnnotationInfo methodAnnotationInfo : methodInfo.annotationInfo) {
                scheduleScanningIfExternalClass(methodAnnotationInfo.getName(), "method annotation", log);
                extendScanningUpwardsFromAnnotationParameterValues(methodAnnotationInfo, log);
            }
            if (methodInfo.parameterAnnotationInfo != null) {
                for (final AnnotationInfo[] paramAnnInfoArr : methodInfo.parameterAnnotationInfo) {
                    if (paramAnnInfoArr != null) {
                        for (final AnnotationInfo paramAnnInfo : paramAnnInfoArr) {
                            scheduleScanningIfExternalClass(paramAnnInfo.getName(), "method parameter annotation",
                                    log);
                            extendScanningUpwardsFromAnnotationParameterValues(paramAnnInfo, log);
                        }
                    }
                }
            }
        }
        for (final String thrownExceptionName : methodInfo.getThrownExceptionNames()) {
            scheduleScanningIfExternalClass(thrownExceptionName, "method throws", log);
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
        var isModuleDescriptor = false;
        var isPackageDescriptor = false;
        ClassInfo classInfo = null;
        if ("module-info".equals(className)) {
            isModuleDescriptor = true;

        } else if ("package-info".equals(className) || className.endsWith(".package-info")) {
            isPackageDescriptor = true;

        } else {
            // Handle regular classfile
            classInfo = linkClassInfo(classNameToClassInfo);
        }

        // An external class was only read so that an accepted class' own declarations can be reported, so it is not
        // listed as a member of its package or module (this keeps PackageInfo and ModuleInfo in step with
        // ScanResult#getAllClasses(), which leaves external classes out)
        final var listAsMember = !isExternalClass || scanSpec.enableExternalClasses;

        // Get or create PackageInfo, if this is not a module descriptor (the module descriptor's package is "")
        PackageInfo packageInfo = null;
        if (!isModuleDescriptor) {
            // Get package for this class or package descriptor A class name is never empty, so getParentPackageName
            // cannot return null
            final var packageName = Objects.requireNonNull(PackageInfo.getParentPackageName(className));
            if (isPackageDescriptor) {
                // Add any class annotations on the package-info.class file to the ModuleInfo
                packageInfo = PackageInfo.getOrCreatePackage(packageName, packageNameToPackageInfo, scanSpec);
                packageInfo.addAnnotations(classAnnotations);
            } else if (classInfo != null && listAsMember) {
                // Add ClassInfo to PackageInfo, and vice versa
                packageInfo = PackageInfo.getOrCreatePackage(packageName, packageNameToPackageInfo, scanSpec);
                packageInfo.addClassInfo(classInfo);
                classInfo.packageInfo = packageInfo;
            }
        }

        // Get or create ModuleInfo, if there is a module name and there is something to record in it (packageInfo
        // is null above exactly when this is an external class that is not being listed as a member)
        final var moduleName = classpathElement.getModuleName();
        if (moduleName != null && (isModuleDescriptor || packageInfo != null)) {
            // Get or create a ModuleInfo object for this module
            final var moduleInfo = moduleNameToModuleInfo.computeIfAbsent(moduleName,
                    k -> new ModuleInfo(classfileResource.getModuleReference(), classpathElement, moduleName));
            if (isModuleDescriptor) {
                // Add any class annotations on the module-info.class file to the ModuleInfo
                moduleInfo.addAnnotations(classAnnotations);
            }
            if (classInfo != null && listAsMember) {
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

    /**
     * Create the {@link ClassInfo} object for this class, and transfer everything that was read from the classfile
     * into it. Not threadsafe, should be run in a single-threaded context.
     *
     * @param classNameToClassInfo
     *            map from class name to class info
     * @return the {@link ClassInfo} object for this class.
     */
    private ClassInfo linkClassInfo(final Map<String, ClassInfo> classNameToClassInfo) {
        final var classInfo = ClassInfo.addScannedClass(className, classModifiers, isExternalClass,
                classNameToClassInfo, classpathElement, classfileResource);
        classInfo.setClassfileVersion(minorVersion, majorVersion);
        classInfo.setModifiers(classModifiers);
        classInfo.setIsInterface(isInterface);
        classInfo.setIsAnnotation(isAnnotation);
        classInfo.setIsRecord(isRecord);
        classInfo.setSourceFile(sourceFile);
        // An interface's classfile names java.lang.Object as its superclass, but interfaces do not extend Object, so
        // don't record that link (this matches Class#getSuperclass(), which returns null for an interface)
        if (superclassName != null && !(isInterface && "java.lang.Object".equals(superclassName))) {
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
        if (typeSignatureStr != null) {
            classInfo.setTypeSignature(typeSignatureStr);
        }
        if (refdClassNames != null) {
            classInfo.addReferencedClassNames(refdClassNames);
        }
        if (classTypeAnnotationDecorators != null) {
            classInfo.addTypeDecorators(classTypeAnnotationDecorators);
        }
        return classInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Intern a string.
     *
     * @param str
     *            the str
     * @return the interned string, or null if str is null
     */
    private @Nullable String intern(final @Nullable String str) {
        if (str == null) {
            return null;
        }
        final var interned = stringInternMap.putIfAbsent(str, str);
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
        final var t = entryTag[cpIdx];
        if ((t != 12 && subFieldIdx != 0) || (t == 12 && subFieldIdx != 0 && subFieldIdx != 1)) {
            throw new ClassfileFormatException(
                    "Bad subfield index " + subFieldIdx + " for tag " + t + ", cannot continue reading class. "
                            + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
        final int cpIdxToUse;
        switch (t) {
        case 0 -> {
            // Assume this means null
            return 0;
        }
        case 1 ->
            // CONSTANT_Utf8
            cpIdxToUse = cpIdx;
        case 7, 8, 19 -> {
            // t == 7 => CONSTANT_Class, e.g. "[[I", "[Ljava/lang/Thread;"; t == 8 =>
            // CONSTANT_String;
            // t == 19 => CONSTANT_Method_Info
            final var indirIdx = indirectStringRefs[cpIdx];
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
        }
        case 12 -> {
            // CONSTANT_NameAndType_info
            final var compoundIndirIdx = indirectStringRefs[cpIdx];
            if (compoundIndirIdx == -1) {
                // Should not happen
                throw new ClassfileFormatException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this at https://github.com/classgraph/classgraph/issues");
            }
            final var indirIdx = (subFieldIdx == 0 ? (compoundIndirIdx >> 16) : compoundIndirIdx) & 0xffff;
            if (indirIdx == 0) {
                // Should not happen
                throw new ClassfileFormatException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this at https://github.com/classgraph/classgraph/issues");
            }
            cpIdxToUse = indirIdx;
        }
        default -> throw new ClassfileFormatException("Wrong tag number " + t + " at constant pool index " + cpIdx
                + ", cannot continue reading class. Please report this at "
                + "https://github.com/classgraph/classgraph/issues");
        }
        if (cpIdxToUse < 1 || cpIdxToUse >= cpCount) {
            throw new ClassfileFormatException("Constant pool index " + cpIdxToUse + ", should be in range [1, "
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
    private @Nullable String getConstantPoolString(final int cpIdx, final boolean replaceSlashWithDot,
            final boolean stripLSemicolon) throws ClassfileFormatException, IOException {
        final var constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        if (constantPoolStringOffset == 0) {
            return null;
        }
        final var utfLen = reader().readUnsignedShort(constantPoolStringOffset);
        if (utfLen == 0) {
            return "";
        }
        return intern(
                reader().readString(constantPoolStringOffset + 2L, utfLen, replaceSlashWithDot, stripLSemicolon));
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
    private @Nullable String getConstantPoolString(final int cpIdx, final int subFieldIdx)
            throws ClassfileFormatException, IOException {
        final var constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, subFieldIdx);
        if (constantPoolStringOffset == 0) {
            return null;
        }
        final var utfLen = reader().readUnsignedShort(constantPoolStringOffset);
        if (utfLen == 0) {
            return "";
        }
        return intern(reader().readString(constantPoolStringOffset + 2L, utfLen, /* replaceSlashWithDot = */ false,
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
    private @Nullable String getConstantPoolString(final int cpIdx) throws ClassfileFormatException, IOException {
        return getConstantPoolString(cpIdx, /* subFieldIdx = */ 0);
    }

    /**
     * Check that a string read from the constant pool is non-null. The constant pool string accessors return null
     * only for a null string reference (a zero index), which cannot occur in a valid classfile in the positions
     * this method is used to check.
     *
     * @param str
     *            the string that was read from the constant pool
     * @param description
     *            a description of what the string represents, for the exception message
     * @return the string
     * @throws ClassfileFormatException
     *             If the string is null.
     */
    private String requireConstantPoolString(final @Nullable String str, final String description)
            throws ClassfileFormatException {
        if (str == null) {
            throw new ClassfileFormatException(
                    "Class " + className + " has a null " + description + " in its constant pool");
        }
        return str;
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
        final var constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        if (constantPoolStringOffset == 0) {
            return '\0';
        }
        final var utfLen = reader().readUnsignedShort(constantPoolStringOffset);
        if (utfLen == 0) {
            return '\0';
        }
        return reader().readByte(constantPoolStringOffset + 2L);
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
    private @Nullable String getConstantPoolClassName(final int cpIdx)
            throws ClassfileFormatException, IOException {
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
    private @Nullable String getConstantPoolClassDescriptor(final int cpIdx)
            throws ClassfileFormatException, IOException {
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
    private boolean constantPoolStringEquals(final int cpIdx, final @Nullable String asciiStr)
            throws ClassfileFormatException, IOException {
        final var cpStrOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        if (cpStrOffset == 0) {
            return asciiStr == null;
        } else if (asciiStr == null) {
            return false;
        }
        final var cpStrLen = reader().readUnsignedShort(cpStrOffset);
        final var asciiStrLen = asciiStr.length();
        if (cpStrLen != asciiStrLen) {
            return false;
        }
        final var cpStrStart = cpStrOffset + 2;
        reader().bufferTo(cpStrStart + cpStrLen);
        final var buf = reader().buf();
        for (var i = 0; i < cpStrLen; i++) {
            if ((char) (buf[cpStrStart + i] & 0xff) != asciiStr.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------------------------------------------

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
        return reader().readInt(entryOffset[cpIdx]);
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
        return reader().readLong(entryOffset[cpIdx]);
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
     * @return the field constant pool value, or null if the constant pool entry is a null string reference
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private @Nullable Object getFieldConstantPoolValue(final int tag, final char fieldTypeDescriptorFirstChar,
            final int cpIdx) throws ClassfileFormatException, IOException {
        return switch (tag) {
        // 1 => Modified UTF8; 7 => Class (N.B. unused? class references do not seem to actually be stored as
        // constant initializers); 8 => String
        case 1, 7, 8 ->
            // Forward or backward indirect reference to a modified UTF8 entry
            getConstantPoolString(cpIdx);
        case 3 -> {
            // int, short, char, byte, boolean are all represented by Constant_INTEGER
            final var intVal = cpReadInt(cpIdx);
            yield switch (fieldTypeDescriptorFirstChar) {
            case 'I' -> intVal;
            case 'S' -> (short) intVal;
            case 'C' -> (char) intVal;
            case 'B' -> (byte) intVal;
            case 'Z' -> intVal != 0;
            default -> throw new ClassfileFormatException("Unknown Constant_INTEGER type "
                    + fieldTypeDescriptorFirstChar + ", cannot continue reading class. Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
            };
        }
        case 4 -> // float
            Float.intBitsToFloat(cpReadInt(cpIdx));
        case 5 -> // long
            cpReadLong(cpIdx);
        case 6 -> // double
            Double.longBitsToDouble(cpReadLong(cpIdx));
        default ->
            // ClassGraph doesn't expect other types (N.B. in particular, enum values are not stored in the constant
            // pool, so don't need to be handled)
            throw new ClassfileFormatException("Unknown field constant pool tag " + tag + ", "
                    + "cannot continue reading class. Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
        };
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
        final var annotationClassName = requireConstantPoolString(
                getConstantPoolClassDescriptor(reader().readUnsignedShort()), "annotation class name");
        final var numElementValuePairs = reader().readUnsignedShort();
        AnnotationParameterValueList paramVals = null;
        if (numElementValuePairs > 0) {
            paramVals = new AnnotationParameterValueList(numElementValuePairs);
            for (var i = 0; i < numElementValuePairs; i++) {
                final var paramName = requireConstantPoolString(getConstantPoolString(reader().readUnsignedShort()),
                        "annotation parameter name");
                final var paramValue = readAnnotationElementValue();
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
        final var tag = reader().readUnsignedByte();
        // This list of element_value tags is complete and up to date as of JDK 26 (JVMS 26 table 4.7.16.1-A). No
        // tag has been added since annotations were introduced in Java SE 5.
        return switch (tag) {
        case 'B' -> (byte) cpReadInt(reader().readUnsignedShort());
        case 'C' -> (char) cpReadInt(reader().readUnsignedShort());
        case 'D' -> Double.longBitsToDouble(cpReadLong(reader().readUnsignedShort()));
        case 'F' -> Float.intBitsToFloat(cpReadInt(reader().readUnsignedShort()));
        case 'I' -> cpReadInt(reader().readUnsignedShort());
        case 'J' -> cpReadLong(reader().readUnsignedShort());
        case 'S' -> (short) cpReadInt(reader().readUnsignedShort());
        case 'Z' -> cpReadInt(reader().readUnsignedShort()) != 0;
        case 's' -> requireConstantPoolString(getConstantPoolString(reader().readUnsignedShort()),
                "annotation element value");
        case 'e' -> {
            // Return type is AnnotationEnumVal.
            final var annotationClassName = requireConstantPoolString(
                    getConstantPoolClassDescriptor(reader().readUnsignedShort()), "annotation enum class name");
            final var annotationConstName = requireConstantPoolString(
                    getConstantPoolString(reader().readUnsignedShort()), "annotation enum constant name");
            yield new AnnotationEnumValue(annotationClassName, annotationConstName);
        }
        case 'c' ->
            // Return type is AnnotationClassRef (for class references in annotations)
            new AnnotationClassRef(requireConstantPoolString(getConstantPoolString(reader().readUnsignedShort()),
                    "annotation class reference"));
        case '@' ->
            // Complex (nested) annotation. Return type is AnnotationInfo.
            readAnnotation();
        case '[' -> {
            // Return type is Object[] (of nested annotation element values)
            final var count = reader().readUnsignedShort();
            final var arr = new Object[count];
            for (var i = 0; i < count; ++i) {
                // Nested annotation element value
                arr[i] = readAnnotationElementValue();
            }
            yield arr;
        }
        default ->
            throw new ClassfileFormatException("Class " + className + " has unknown annotation element type tag '"
                    + ((char) tag) + "': element size unknown, cannot continue reading class. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
        };
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Adds a type annotation to the part of a class type signature that a type annotation's target info points at.
     */
    @FunctionalInterface
    interface ClassTypeAnnotationDecorator {
        /**
         * Add the type annotation to the class type signature.
         *
         * @param classTypeSignature
         *            the class type signature to decorate
         */
        void decorate(ClassTypeSignature classTypeSignature);
    }

    /**
     * Adds a type annotation to the part of a method type signature that a type annotation's target info points at.
     */
    @FunctionalInterface
    interface MethodTypeAnnotationDecorator {
        /**
         * Add the type annotation to the method type signature.
         *
         * @param methodTypeSignature
         *            the method type signature to decorate
         */
        void decorate(MethodTypeSignature methodTypeSignature);
    }

    /**
     * Adds a type annotation to the part of a type signature that a type annotation's type path points at.
     */
    @FunctionalInterface
    interface TypeAnnotationDecorator {
        /**
         * Add the type annotation to the type signature.
         *
         * @param typeSignature
         *            the type signature to decorate
         */
        void decorate(TypeSignature typeSignature);
    }

    /**
     * One step of a type annotation's {@code type_path}, which navigates from the type named by the target info to
     * the nested type that the annotation actually applies to.
     *
     * @param typePathKind
     *            the kind of step to take: 0 to descend into an array type, 1 to descend into the enclosing type of
     *            a nested type, 2 to move to the bound of a wildcard type argument, or 3 to move to a type argument
     * @param typeArgumentIdx
     *            the index of the type argument to move to, if {@code typePathKind} is 3, otherwise 0
     */
    record TypePathNode(short typePathKind, short typeArgumentIdx) {
        @Override
        public String toString() {
            return "(" + typePathKind + "," + typeArgumentIdx + ")";
        }
    }

    /**
     * Read a type annotation's {@code type_path} structure.
     *
     * @return the steps of the type path, or the empty list if the annotation applies directly to the type named by
     *         the target info
     * @throws IOException
     *             if the classfile could not be read
     */
    private List<TypePathNode> readTypePath() throws IOException {
        final var typePathLength = reader().readUnsignedByte();
        if (typePathLength == 0) {
            return List.of();
        } else {
            final List<TypePathNode> list = new ArrayList<>(typePathLength);
            for (var i = 0; i < typePathLength; i++) {
                final var typePathKind = (short) reader().readUnsignedByte();
                final var typeArgumentIdx = (short) reader().readUnsignedByte();
                list.add(new TypePathNode(typePathKind, typeArgumentIdx));
            }
            return list;
        }
    }

    /**
     * Test whether an attribute holds annotations that should be read, i.e. whether annotation info was enabled,
     * and the attribute is either the runtime visible form, or the runtime invisible form and runtime invisible
     * annotations were not disabled.
     *
     * @param attributeNameCpIdx
     *            the constant pool index of the attribute name
     * @param runtimeVisibleAttributeName
     *            the name of the runtime visible form of the attribute
     * @param runtimeInvisibleAttributeName
     *            the name of the runtime invisible form of the attribute
     * @return true if the attribute holds annotations that should be read.
     * @throws IOException
     *             if the classfile could not be read
     */
    private boolean isAnnotationAttribute(final int attributeNameCpIdx, final String runtimeVisibleAttributeName,
            final String runtimeInvisibleAttributeName) throws IOException {
        return scanSpec.enableAnnotationInfo
                && (constantPoolStringEquals(attributeNameCpIdx, runtimeVisibleAttributeName)
                        || (!scanSpec.disableRuntimeInvisibleAnnotations
                                && constantPoolStringEquals(attributeNameCpIdx, runtimeInvisibleAttributeName)));
    }

    /**
     * Test whether an attribute holds declaration annotations that should be read.
     *
     * @param attributeNameCpIdx
     *            the constant pool index of the attribute name
     * @return true if the attribute holds declaration annotations that should be read.
     * @throws IOException
     *             if the classfile could not be read
     */
    private boolean isAnnotationsAttribute(final int attributeNameCpIdx) throws IOException {
        return isAnnotationAttribute(attributeNameCpIdx, "RuntimeVisibleAnnotations",
                "RuntimeInvisibleAnnotations");
    }

    /**
     * Test whether an attribute holds type annotations that should be read.
     *
     * @param attributeNameCpIdx
     *            the constant pool index of the attribute name
     * @return true if the attribute holds type annotations that should be read.
     * @throws IOException
     *             if the classfile could not be read
     */
    private boolean isTypeAnnotationsAttribute(final int attributeNameCpIdx) throws IOException {
        return isAnnotationAttribute(attributeNameCpIdx, "RuntimeVisibleTypeAnnotations",
                "RuntimeInvisibleTypeAnnotations");
    }

    /**
     * Test whether an attribute holds method parameter annotations that should be read.
     *
     * @param attributeNameCpIdx
     *            the constant pool index of the attribute name
     * @return true if the attribute holds method parameter annotations that should be read.
     * @throws IOException
     *             if the classfile could not be read
     */
    private boolean isParameterAnnotationsAttribute(final int attributeNameCpIdx) throws IOException {
        return isAnnotationAttribute(attributeNameCpIdx, "RuntimeVisibleParameterAnnotations",
                "RuntimeInvisibleParameterAnnotations");
    }

    /**
     * Read the annotations of a {@code RuntimeVisibleAnnotations} or {@code RuntimeInvisibleAnnotations} attribute
     * into an annotation list. The two attributes are read into the same list, so the list of the other attribute,
     * if it was read first, is passed in.
     *
     * @param annotationInfoList
     *            the annotations read from the other attribute, or null if this is the first of the two attributes
     *            to be encountered
     * @return the annotation list, which is created if it was null and this attribute holds any annotations, or
     *         null if the attribute holds no annotations and the list had not been created yet
     * @throws IOException
     *             if an I/O exception occurs.
     */
    private @Nullable AnnotationInfoList readAnnotations(final @Nullable AnnotationInfoList annotationInfoList)
            throws IOException {
        final var annotationCount = reader().readUnsignedShort();
        if (annotationCount == 0) {
            return annotationInfoList;
        }
        final var annotations = annotationInfoList == null ? new AnnotationInfoList(annotationCount)
                : annotationInfoList;
        for (var i = 0; i < annotationCount; i++) {
            annotations.add(readAnnotation());
        }
        return annotations;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read only the name of the class defined by a classfile, skipping everything else. This is a cut-down version
     * of {@link #readConstantPoolEntries(LogNode)} followed by {@link #readBasicClassInfo()}, for the case where a
     * classfile has to be read before scanning has started, so none of the scanning context needed by the
     * {@link Classfile} constructor is available yet. (Used to check whether a candidate package root really is a
     * package root -- see {@link ClasspathElement#getClassNameDisprovingPackageRoot(RandomAccessOrSequentialReader, String)}.)
     *
     * @param reader
     *            a reader for the classfile.
     * @return the name of the class defined by the classfile, in binary form, with {@code '/'} as the package
     *         separator (e.g. {@code "java/lang/String"}), or null if the class name could not be read.
     */
    static @Nullable String readClassName(final RandomAccessOrSequentialReader reader) {
        try {
            // Skip the magic number and the minor and major version
            reader.skip(8);

            // Read the constant pool, recording only the offset and tag of each entry, and for tag 7 (class ref)
            // entries, the index of the tag 1 (modified UTF8) entry that holds the class name
            final var cpCount = reader.readUnsignedShort();
            final var entryOffset = new int[cpCount];
            final var entryTag = new int[cpCount];
            final var indirectStringRefs = new int[cpCount];
            for (int i = 1, skipSlot = 0; i < cpCount; i++) {
                if (skipSlot == 1) {
                    skipSlot = 0;
                    continue;
                }
                entryTag[i] = reader.readUnsignedByte();
                entryOffset[i] = reader.currPos();
                // Same set of constant pool tags as readConstantPoolEntries() -- see the note there. Complete and
                // up to date as of JDK 26.
                switch (entryTag[i]) {
                // Modified UTF8
                case 1 -> reader.skip(reader.readUnsignedShort());
                // int, float
                case 3, 4 -> reader.skip(4);
                // long, double
                case 5, 6 -> {
                    reader.skip(8);
                    skipSlot = 1; // double slot
                }
                // Class reference
                case 7 -> indirectStringRefs[i] = reader.readUnsignedShort();
                // String, method type, module, package
                case 8, 16, 19, 20 -> reader.skip(2);
                // field ref, method ref, interface method ref, name and type, dynamic, invoke dynamic
                case 9, 10, 11, 12, 17, 18 -> reader.skip(4);
                // method handle
                case 15 -> reader.skip(3);
                default -> {
                    // Unknown tag -- the size of the entry is unknown, so reading cannot continue
                    return null;
                }
                }
            }

            // Skip the access flags, then read the constant pool index of the class' own name
            reader.skip(2);
            final var thisClassCpIdx = reader.readUnsignedShort();
            if (thisClassCpIdx < 1 || thisClassCpIdx >= cpCount || entryTag[thisClassCpIdx] != 7) {
                return null;
            }
            final var classNameCpIdx = indirectStringRefs[thisClassCpIdx];
            if (classNameCpIdx < 1 || classNameCpIdx >= cpCount || entryTag[classNameCpIdx] != 1) {
                return null;
            }
            final var classNameOffset = entryOffset[classNameCpIdx];
            final var classNameLen = reader.readUnsignedShort(classNameOffset);
            return classNameLen == 0 ? null : reader.readString(classNameOffset + 2L, classNameLen);
        } catch (final IOException | RuntimeException e) {
            // Could not read or parse the classfile
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The constant pool indices of the entries that name a referenced class, collected while the constant pool is
     * being read. Only collected if inter-class dependencies are enabled.
     *
     * @param classRefCpIdxs
     *            the indices of the modified UTF8 entries referenced by class ref (tag 7) entries
     * @param nameAndTypeCpIdxs
     *            the indices of the modified UTF8 entries holding the type signature of a name and type (tag 12)
     *            entry
     */
    private record ReferencedClassCpIdxs(List<Integer> classRefCpIdxs, List<Integer> nameAndTypeCpIdxs) {
        /** Constructor. */
        ReferencedClassCpIdxs() {
            this(new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * Read constant pool entries, and if inter-class dependencies are enabled, extract the names of the classes
     * referenced by the constant pool.
     *
     * @param log
     *            The log
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void readConstantPoolEntries(final @Nullable LogNode log) throws IOException {
        // Only record class dependency info if inter-class dependencies are enabled
        final var referencedClassCpIdxs = scanSpec.enableInterClassDependencies ? new ReferencedClassCpIdxs()
                : null;

        // Read size of constant pool, and allocate storage for the entries
        cpCount = reader().readUnsignedShort();
        entryOffset = new int[cpCount];
        entryTag = new int[cpCount];
        indirectStringRefs = new int[cpCount];
        Arrays.fill(indirectStringRefs, 0, cpCount, -1);

        parseConstantPoolEntries(referencedClassCpIdxs);

        if (referencedClassCpIdxs != null) {
            // Note that there are some class refs that will not be found this way, e.g. enum classes and class refs
            // in annotation parameter values, since they are referenced as strings (tag 1) rather than classes
            // (tag 7) or type signatures (part of tag 12). Therefore, a hybrid approach needs to be applied of
            // extracting these other class refs from the ClassInfo graph, and combining them with class names
            // extracted from the constant pool here.
            final Set<String> refdClassNamesSet = new HashSet<>();
            refdClassNames = refdClassNamesSet;
            addClassNamesFromClassRefs(referencedClassCpIdxs.classRefCpIdxs(), refdClassNamesSet);
            addClassNamesFromTypeSignatures(referencedClassCpIdxs.nameAndTypeCpIdxs(), refdClassNamesSet, log);
        }
    }

    /**
     * Fill in {@link #entryTag}, {@link #entryOffset} and {@link #indirectStringRefs} for each constant pool entry,
     * without resolving any of the string entries.
     *
     * @param referencedClassCpIdxs
     *            if non-null, the indices of the entries that name a referenced class are collected here
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void parseConstantPoolEntries(final @Nullable ReferencedClassCpIdxs referencedClassCpIdxs)
            throws IOException {
        for (int i = 1, skipSlot = 0; i < cpCount; i++) {
            if (skipSlot == 1) {
                // Skip the second of the two constant pool slots taken up by a long or double constant
                skipSlot = 0;
                continue;
            }
            entryTag[i] = reader().readUnsignedByte();
            entryOffset[i] = reader().currPos();
            // This list of constant pool tags is complete and up to date as of JDK 26 (JVMS 26 table 4.4-B). The
            // newest tag is CONSTANT_Dynamic (17), added in Java SE 11; no tag has been added since. If a future
            // JDK adds a tag, an entry of unknown size follows it, so parsing has to fail (below).
            switch (entryTag[i]) {
            // Tag 0 is not a valid constant pool tag in any version of the JVMS, so reaching it means either the
            // classfile is corrupt, or the reader has lost alignment with the start of a constant pool entry
            case 0 -> throw new ClassfileFormatException("Invalid constant pool tag 0 in classfile " + relativePath
                    + " (possible buffer underflow issue). Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
            // Modified UTF8
            case 1 -> {
                final var strLen = reader().readUnsignedShort();
                reader().skip(strLen);
            }
            // There is no constant pool tag type 2
            // int, short, char, byte, boolean are all represented by Constant_INTEGER;
            // float
            case 3, 4 -> reader().skip(4);
            // long, double
            case 5, 6 -> {
                reader().skip(8);
                skipSlot = 1; // double slot
            }
            // Class reference (format is e.g. "java/lang/String")
            case 7 -> {
                // Forward or backward indirect reference to a modified UTF8 entry
                indirectStringRefs[i] = reader().readUnsignedShort();
                if (referencedClassCpIdxs != null) {
                    // If this is a class ref, and inter-class dependencies are enabled, record the dependency
                    referencedClassCpIdxs.classRefCpIdxs().add(indirectStringRefs[i]);
                }
            }
            // String -- forward or backward indirect reference to a modified UTF8 entry
            case 8 -> indirectStringRefs[i] = reader().readUnsignedShort();
            // Field ref, method ref, interface method ref -- each refers to a class ref (case 7) and then a name
            // and type (case 12)
            case 9, 10, 11 -> reader().skip(4);
            // Name and type
            case 12 -> {
                final var nameRef = reader().readUnsignedShort();
                final var typeRef = reader().readUnsignedShort();
                if (referencedClassCpIdxs != null) {
                    referencedClassCpIdxs.nameAndTypeCpIdxs().add(typeRef);
                }
                indirectStringRefs[i] = (nameRef << 16) | typeRef;
            }
            // There is no constant pool tag type 13 or 14 method handle
            case 15 -> reader().skip(3);
            // method type
            case 16 -> reader().skip(2);
            // dynamic, invoke dynamic
            case 17, 18 -> reader().skip(4);
            // module (for module-info.class in JDK9+) see
            // https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.4
            case 19 -> indirectStringRefs[i] = reader().readUnsignedShort();
            // package (for module-info.class in JDK9+) see
            // https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.4
            case 20 -> reader().skip(2);
            default -> throw new ClassfileFormatException("Unknown constant pool tag " + entryTag[i]
                    + " (element size unknown, cannot continue reading class). Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
            }
        }
    }

    /**
     * Add the names of the classes named by class ref (tag 7) constant pool entries to {@code refdClassNames}.
     *
     * @param classRefCpIdxs
     *            the constant pool indices of the modified UTF8 entries referenced by class ref entries
     * @param refdClassNames
     *            the set of referenced class names to add to
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void addClassNamesFromClassRefs(final List<Integer> classRefCpIdxs, final Set<String> refdClassNames)
            throws IOException {
        for (final int cpIdx : classRefCpIdxs) {
            final var refdClassName = getConstantPoolString(cpIdx, /* replaceSlashWithDot = */ true,
                    /* stripLSemicolon = */ false);
            if (refdClassName != null) {
                if (refdClassName.startsWith("[")) {
                    // Parse array type signature, e.g. "[Ljava.lang.String;" -- uses '.' rather than '/'
                    try {
                        final var typeSig = TypeSignature.parse(refdClassName.replace('.', '/'),
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

    /**
     * Add the names of the classes named by the type signatures of name and type (tag 12) constant pool entries to
     * {@code refdClassNames}.
     *
     * @param nameAndTypeCpIdxs
     *            the constant pool indices of the modified UTF8 entries holding the type signatures
     * @param refdClassNames
     *            the set of referenced class names to add to
     * @param log
     *            The log
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void addClassNamesFromTypeSignatures(final List<Integer> nameAndTypeCpIdxs,
            final Set<String> refdClassNames, final @Nullable LogNode log) throws IOException {
        for (final int cpIdx : nameAndTypeCpIdxs) {
            final var typeSigStr = getConstantPoolString(cpIdx);
            if (typeSigStr != null) {
                try {
                    if (typeSigStr.startsWith("L") && typeSigStr.endsWith(";")) {
                        // Parse the class name
                        final var typeSig = TypeSignature.parse(typeSigStr, /* definingClassName = */ null);
                        // Extract class names from type signature
                        typeSig.findReferencedClassNames(refdClassNames);
                    } else if (typeSigStr.indexOf('(') >= 0 || "<init>".equals(typeSigStr)) {
                        // Parse the type signature
                        final var typeSig = MethodTypeSignature.parse(typeSigStr, /* definingClassName = */ null);
                        // Extract class names from type signature
                        typeSig.findReferencedClassNames(refdClassNames);
                    } else {
                        if (log != null) {
                            log.log("Could not extract referenced class names from constant pool string: "
                                    + typeSigStr);
                        }
                    }
                } catch (final ParseException e) {
                    if (log != null) {
                        log.log("Could not extract referenced class names from constant pool string: " + typeSigStr
                                + " : " + e);
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
        classModifiers = reader().readUnsignedShort();

        isInterface = (classModifiers & 0x0200) != 0;
        isAnnotation = (classModifiers & 0x2000) != 0;

        // The fully-qualified class name of this class, with slashes replaced with dots
        final var classNamePath = getConstantPoolString(reader().readUnsignedShort());
        if (classNamePath == null) {
            throw new ClassfileFormatException("Class name is null");
        }
        className = classNamePath.replace('/', '.');

        // Check class visibility modifiers
        final var isModule = (classModifiers & 0x8000) != 0; // Equivalently filename is "module-info.class"
        final var isPackage = relativePath.regionMatches(relativePath.lastIndexOf('/') + 1, "package-info.class", 0,
                18);
        if (!scanSpec.ignoreClassVisibility && !Modifier.isPublic(classModifiers) && !isModule && !isPackage) {
            throw new SkipClassException("Class is not public, and ignoreClassVisibility() was not called");
        }

        // Make sure classname matches relative path
        if (!relativePath.endsWith(".class")) {
            // Should not happen
            throw new SkipClassException("Classfile filename " + relativePath + " does not end in \".class\"");
        }
        final var len = classNamePath.length();
        if (relativePath.length() != len + 6 || !classNamePath.regionMatches(0, relativePath, 0, len)) {
            throw new SkipClassException(
                    "Relative path " + relativePath + " does not match class name " + className);
        }

        // Superclass name, with slashes replaced with dots
        final var superclassNameCpIdx = reader().readUnsignedShort();
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
        final var interfaceCount = reader().readUnsignedShort();
        for (var i = 0; i < interfaceCount; i++) {
            final var interfaceName = requireConstantPoolString(
                    getConstantPoolClassName(reader().readUnsignedShort()), "interface name");
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
        final var fieldCount = reader().readUnsignedShort();
        for (var i = 0; i < fieldCount; i++) {
            readField();
        }
    }

    /**
     * Read one of the class' fields, adding a {@link FieldInfo} to {@link #fieldInfoList} if field info is enabled
     * and the field is visible.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the classfile is incorrectly formatted.
     */
    private void readField() throws IOException, ClassfileFormatException {
        // Info on modifier flags:
        // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.5
        final var fieldModifierFlags = reader().readUnsignedShort();
        final var isPublicField = (fieldModifierFlags & 0x0001) == 0x0001;
        final var fieldIsVisible = isPublicField || scanSpec.ignoreFieldVisibility;
        final var getStaticFinalFieldConstValue = scanSpec.enableStaticFinalFieldConstantInitializerValues
                && fieldIsVisible;
        if (!fieldIsVisible || (!scanSpec.enableFieldInfo && !getStaticFinalFieldConstValue)) {
            // Skip field
            reader().skip(4); // name_index, descriptor_index
            final var attributesCount = reader().readUnsignedShort();
            for (var i = 0; i < attributesCount; i++) {
                reader().skip(2); // attribute_name_index
                final var attributeLength = reader().readInt();
                reader().skip(attributeLength);
            }
            return;
        }

        final var fieldNameCpIdx = reader().readUnsignedShort();
        final var fieldName = requireConstantPoolString(getConstantPoolString(fieldNameCpIdx), "field name");
        final var fieldTypeDescriptorCpIdx = reader().readUnsignedShort();
        final var fieldTypeDescriptorFirstChar = (char) getConstantPoolStringFirstByte(fieldTypeDescriptorCpIdx);
        final var fieldTypeDescriptor = requireConstantPoolString(getConstantPoolString(fieldTypeDescriptorCpIdx),
                "field type descriptor");

        List<TypeAnnotationDecorator> fieldTypeAnnotationDecorators = null;
        String fieldTypeSignatureStr = null;
        Object fieldConstValue = null;
        AnnotationInfoList fieldAnnotationInfo = null;
        final var attributesCount = reader().readUnsignedShort();
        for (var i = 0; i < attributesCount; i++) {
            final var attributeNameCpIdx = reader().readUnsignedShort();
            final var attributeLength = reader().readInt();
            // See if field name matches one of the requested names for this class, and if it does, check if
            // it is initialized with a constant value
            if (getStaticFinalFieldConstValue && constantPoolStringEquals(attributeNameCpIdx, "ConstantValue")) {
                // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.2
                final var cpIdx = reader().readUnsignedShort();
                if (cpIdx < 1 || cpIdx >= cpCount) {
                    throw new ClassfileFormatException("Constant pool index " + cpIdx + ", should be in range [1, "
                            + (cpCount - 1) + "] -- cannot continue reading class. "
                            + "Please report this at https://github.com/classgraph/classgraph/issues");
                }
                fieldConstValue = getFieldConstantPoolValue(entryTag[cpIdx], fieldTypeDescriptorFirstChar, cpIdx);
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                fieldTypeSignatureStr = getConstantPoolString(reader().readUnsignedShort());
            } else if (isAnnotationsAttribute(attributeNameCpIdx)) {
                fieldAnnotationInfo = readAnnotations(fieldAnnotationInfo);
            } else if (isTypeAnnotationsAttribute(attributeNameCpIdx)) {
                final var decorators = readFieldTypeAnnotationDecorators();
                if (decorators != null) {
                    fieldTypeAnnotationDecorators = decorators;
                }
            } else {
                // No match, just skip attribute
                reader().skip(attributeLength);
            }
        }

        if (scanSpec.enableFieldInfo) {
            if (fieldInfoList == null) {
                fieldInfoList = new FieldInfoList();
            }
            fieldInfoList.add(new FieldInfo(className, fieldName, fieldModifierFlags, fieldTypeDescriptor,
                    fieldTypeSignatureStr, fieldConstValue, fieldAnnotationInfo, fieldTypeAnnotationDecorators));
        }
    }

    /**
     * Read a field's {@code RuntimeVisibleTypeAnnotations} or {@code RuntimeInvisibleTypeAnnotations} attribute.
     *
     * @return one decorator per type annotation, which adds the annotation to the annotated type of a field type
     *         signature, or null if the attribute holds no annotations
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if a type annotation has a target type that is not permitted in a field.
     */
    private @Nullable List<TypeAnnotationDecorator> readFieldTypeAnnotationDecorators()
            throws IOException, ClassfileFormatException {
        final var annotationCount = reader().readUnsignedShort();
        if (annotationCount == 0) {
            return null;
        }
        final List<TypeAnnotationDecorator> decorators = new ArrayList<>(annotationCount);
        for (var i = 0; i < annotationCount; i++) {
            final var targetType = reader().readUnsignedByte();
            // 0x13 is the only target_type that JVMS 26 table 4.7.20-A permits in field_info.
            // Complete and up to date as of JDK 26.
            if (targetType != 0x13) {
                throw new ClassfileFormatException("Class " + className
                        + " has unknown field type annotation target 0x" + Integer.toHexString(targetType)
                        + ": element size unknown, cannot continue reading class. "
                        + "Please report this at https://github.com/classgraph/classgraph/issues");
            }
            final var typePath = readTypePath();
            final var annotationInfo = readAnnotation();
            decorators.add(typeSignature -> typeSignature.addTypeAnnotation(typePath, annotationInfo));
        }
        return decorators;
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
        final var methodCount = reader().readUnsignedShort();
        for (var i = 0; i < methodCount; i++) {
            readMethod();
        }
    }

    /**
     * Read one of the class' methods, adding a {@link MethodInfo} to {@link #methodInfoList} if method info is
     * enabled and the method is visible.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the classfile is incorrectly formatted.
     */
    private void readMethod() throws IOException, ClassfileFormatException {
        // Info on modifier flags:
        // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6
        final var methodModifierFlags = reader().readUnsignedShort();
        final var isPublicMethod = (methodModifierFlags & 0x0001) == 0x0001;
        final var methodIsVisible = isPublicMethod || scanSpec.ignoreMethodVisibility;
        String methodName = null;
        String methodTypeDescriptor = null;
        // Always enable MethodInfo for annotations (this is how annotation constants are defined)
        final var enableMethodInfo = scanSpec.enableMethodInfo || isAnnotation;
        if (enableMethodInfo) {
            final var methodNameCpIdx = reader().readUnsignedShort();
            methodName = getConstantPoolString(methodNameCpIdx);
            final var methodTypeDescriptorCpIdx = reader().readUnsignedShort();
            methodTypeDescriptor = getConstantPoolString(methodTypeDescriptorCpIdx);
        } else {
            reader().skip(4); // name_index, descriptor_index
        }
        final var attributesCount = reader().readUnsignedShort();
        if (!methodIsVisible || !enableMethodInfo) {
            // Skip method attributes
            for (var i = 0; i < attributesCount; i++) {
                reader().skip(2); // attribute_name_index
                final var attributeLength = reader().readInt();
                reader().skip(attributeLength);
            }
            return;
        }
        if (methodName == null || methodTypeDescriptor == null) {
            // Should not happen for a valid classfile (enableMethodInfo is true here, so the method name and type
            // descriptor were read from the constant pool)
            throw new ClassfileFormatException("Method name and/or type descriptor is null");
        }

        List<MethodTypeAnnotationDecorator> methodTypeAnnotationDecorators = null;
        String methodTypeSignatureStr = null;
        AnnotationInfoList methodAnnotationInfo = null;
        AnnotationInfo[][] methodParameterAnnotations = null;
        @Nullable
        String[] methodParameterNames = null;
        int[] methodParameterModifiers = null;
        String[] thrownExceptionNames = null;
        var methodHasBody = false;
        var minLineNum = 0;
        var maxLineNum = 0;
        for (var i = 0; i < attributesCount; i++) {
            final var attributeNameCpIdx = reader().readUnsignedShort();
            final var attributeLength = reader().readInt();
            if (isAnnotationsAttribute(attributeNameCpIdx)) {
                methodAnnotationInfo = readAnnotations(methodAnnotationInfo);
            } else if (isParameterAnnotationsAttribute(attributeNameCpIdx)) {
                methodParameterAnnotations = readMethodParameterAnnotations(methodParameterAnnotations);
            } else if (isTypeAnnotationsAttribute(attributeNameCpIdx)) {
                final var decorators = readMethodTypeAnnotationDecorators();
                if (decorators != null) {
                    methodTypeAnnotationDecorators = decorators;
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "MethodParameters")) {
                // Read method parameters. For Java, these are only produced in JDK8+, and only if the commandline
                // switch `-parameters` is provided at compiletime.
                final var paramCount = reader().readUnsignedByte();
                methodParameterNames = new String[paramCount];
                methodParameterModifiers = new int[paramCount];
                for (var j = 0; j < paramCount; j++) {
                    final var cpIdx = reader().readUnsignedShort();
                    // If the constant pool index is zero, then the parameter is unnamed => use null
                    methodParameterNames[j] = cpIdx == 0 ? null : getConstantPoolString(cpIdx);
                    methodParameterModifiers[j] = reader().readUnsignedShort();
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                // Add type params to method type signature
                methodTypeSignatureStr = getConstantPoolString(reader().readUnsignedShort());
            } else if (constantPoolStringEquals(attributeNameCpIdx, "AnnotationDefault")) {
                if (annotationParamDefaultValues == null) {
                    annotationParamDefaultValues = new AnnotationParameterValueList();
                }
                this.annotationParamDefaultValues.add(new AnnotationParameterValue(methodName,
                        // Get annotation parameter default value
                        readAnnotationElementValue()));
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Exceptions")) {
                final var exceptionCount = reader().readUnsignedShort();
                thrownExceptionNames = new String[exceptionCount];
                for (var j = 0; j < exceptionCount; j++) {
                    final var cpIdx = reader().readUnsignedShort();
                    thrownExceptionNames[j] = requireConstantPoolString(getConstantPoolClassName(cpIdx),
                            "thrown exception class name");
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Code")) {
                methodHasBody = true;
                final var lineNumberRange = readCodeAttribute();
                minLineNum = lineNumberRange.minLineNum();
                maxLineNum = lineNumberRange.maxLineNum();
            } else {
                reader().skip(attributeLength);
            }
        }

        // Create MethodInfo
        if (methodInfoList == null) {
            methodInfoList = new MethodInfoList();
        }
        methodInfoList.add(new MethodInfo(className, methodName, methodAnnotationInfo, methodModifierFlags,
                methodTypeDescriptor, methodTypeSignatureStr, methodParameterNames, methodParameterModifiers,
                methodParameterAnnotations, methodHasBody, minLineNum, maxLineNum, methodTypeAnnotationDecorators,
                thrownExceptionNames));
    }

    /**
     * Read a method's {@code RuntimeVisibleParameterAnnotations} or {@code RuntimeInvisibleParameterAnnotations}
     * attribute. Runtime visible and runtime invisible parameter annotations are given in separate attributes, but
     * are merged into a single array of annotations for each method parameter, so if both attributes are present,
     * the parameter annotation arrays have to be enlarged when the second attribute is encountered.
     *
     * @param parameterAnnotations
     *            the parameter annotations read from the other attribute, or null if this is the first of the two
     *            attributes to be encountered
     * @return the merged parameter annotations
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the two attributes disagree on the number of parameters.
     */
    private AnnotationInfo[][] readMethodParameterAnnotations(
            final AnnotationInfo @Nullable [][] parameterAnnotations) throws IOException, ClassfileFormatException {
        final var numParams = reader().readUnsignedByte();
        final AnnotationInfo[][] mergedParameterAnnotations;
        if (parameterAnnotations == null) {
            mergedParameterAnnotations = new AnnotationInfo[numParams][];
        } else if (parameterAnnotations.length != numParams) {
            throw new ClassfileFormatException(
                    "Mismatch in number of parameters between RuntimeVisibleParameterAnnotations "
                            + "and RuntimeInvisibleParameterAnnotations");
        } else {
            mergedParameterAnnotations = parameterAnnotations;
        }
        for (var paramIdx = 0; paramIdx < numParams; paramIdx++) {
            final var numAnnotations = reader().readUnsignedShort();
            if (numAnnotations > 0) {
                var annStartIdx = 0;
                if (mergedParameterAnnotations[paramIdx] != null) {
                    annStartIdx = mergedParameterAnnotations[paramIdx].length;
                    mergedParameterAnnotations[paramIdx] = Arrays.copyOf(mergedParameterAnnotations[paramIdx],
                            annStartIdx + numAnnotations);
                } else {
                    mergedParameterAnnotations[paramIdx] = new AnnotationInfo[numAnnotations];
                }
                for (var annIdx = 0; annIdx < numAnnotations; annIdx++) {
                    mergedParameterAnnotations[paramIdx][annStartIdx + annIdx] = readAnnotation();
                }
            } else if (mergedParameterAnnotations[paramIdx] == null) {
                mergedParameterAnnotations[paramIdx] = NO_ANNOTATIONS;
            }
        }
        return mergedParameterAnnotations;
    }

    /**
     * Read a method's {@code RuntimeVisibleTypeAnnotations} or {@code RuntimeInvisibleTypeAnnotations} attribute.
     *
     * @return one decorator per type annotation, which adds the annotation to the annotated type of a method type
     *         signature, or null if the attribute holds no annotations
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if a type annotation has a target type that is not permitted in a method.
     */
    private @Nullable List<MethodTypeAnnotationDecorator> readMethodTypeAnnotationDecorators()
            throws IOException, ClassfileFormatException {
        final var annotationCount = reader().readUnsignedShort();
        if (annotationCount == 0) {
            return null;
        }
        final List<MethodTypeAnnotationDecorator> decorators = new ArrayList<>(annotationCount);
        for (var i = 0; i < annotationCount; i++) {
            final var targetType = reader().readUnsignedByte();
            final int typeParameterIndex;
            final int boundIndex;
            final int formalParameterIndex;
            final int throwsTypeIndex;
            // JVMS 26 table 4.7.20-A permits target_types 0x01, 0x12, 0x14, 0x15, 0x16 and 0x17 in method_info; all
            // are handled below, plus 0x10 and 0x13, which are illegal here but are emitted by buggy compilers (see
            // the notes below). Complete and up to date as of JDK 26.
            switch (targetType) {
            case 0x01 -> {
                // Type parameter declaration of generic method or constructor
                typeParameterIndex = reader().readUnsignedByte();
                boundIndex = -1;
                formalParameterIndex = -1;
                throwsTypeIndex = -1;
            }
            case 0x10 -> {
                // This target_type is not supposed to be added to methods, it is intended for ClassFile
                // annotations, but Google's Java compiler adds annotations of this type to methods in guava for
                // some reason. Just ignore these annotations.
                // (#861)
                reader().readUnsignedShort();
                typeParameterIndex = -1;
                boundIndex = -1;
                formalParameterIndex = -1;
                throwsTypeIndex = -1;
            }
            case 0x12 -> {
                // Type in bound of type parameter declaration of generic method or constructor
                typeParameterIndex = reader().readUnsignedByte();
                boundIndex = reader().readUnsignedByte();
                formalParameterIndex = -1;
                throwsTypeIndex = -1;
            }
            case 0x13, 0x14, 0x15 -> {
                // 0x13: Type in field or record component declaration (empty target). This target_type is not
                // supposed to be added to methods, but it seems that the JDK 17 compiler is buggy, and adds this
                // target_type to the methods of records anyway (#797). Therefore, accept this, but ignore it (the
                // same target_type should also be added to the fields of records). 0x14: Return type of method, or
                // type of newly constructed object (empty target). 0x15: Receiver type of method or constructor
                // (empty target).
                typeParameterIndex = -1;
                boundIndex = -1;
                formalParameterIndex = -1;
                throwsTypeIndex = -1;
            }
            case 0x16 -> {
                // Type in formal parameter declaration of method, constructor, or lambda expression
                typeParameterIndex = -1;
                boundIndex = -1;
                formalParameterIndex = reader().readUnsignedByte();
                throwsTypeIndex = -1;
            }
            case 0x17 -> {
                // Type in throws clause of method or constructor
                typeParameterIndex = -1;
                boundIndex = -1;
                formalParameterIndex = -1;
                throwsTypeIndex = reader().readUnsignedShort();
            }
            default -> throw new ClassfileFormatException("Class " + className
                    + " has unknown method type annotation target 0x" + Integer.toHexString(targetType)
                    + ": element size unknown, cannot continue reading class. " + "Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
            }
            final var typePath = readTypePath();
            final var annotationInfo = readAnnotation();
            decorators.add(methodTypeSignature -> decorateMethodTypeSignature(methodTypeSignature, targetType,
                    typeParameterIndex, boundIndex, formalParameterIndex, throwsTypeIndex, typePath,
                    annotationInfo));
        }
        return decorators;
    }

    /**
     * Add a type annotation to the type of a method type signature that the annotation's target info names.
     *
     * @param methodTypeSignature
     *            the method type signature to decorate
     * @param targetType
     *            the {@code target_type} of the type annotation
     * @param typeParameterIndex
     *            the index of the annotated type parameter, or -1 if the target info does not name one
     * @param boundIndex
     *            the index of the annotated bound of a type parameter, or -1 if the target info does not name one
     * @param formalParameterIndex
     *            the index of the annotated formal parameter, or -1 if the target info does not name one
     * @param throwsTypeIndex
     *            the index of the annotated thrown type, or -1 if the target info does not name one
     * @param typePath
     *            the type path of the annotation within the annotated type
     * @param annotationInfo
     *            the annotation
     */
    private static void decorateMethodTypeSignature(final MethodTypeSignature methodTypeSignature,
            final int targetType, final int typeParameterIndex, final int boundIndex,
            final int formalParameterIndex, final int throwsTypeIndex, final List<TypePathNode> typePath,
            final AnnotationInfo annotationInfo) {
        switch (targetType) {
        case 0x01 -> {
            // Type parameter declaration of generic method or constructor
            final var typeParameters = methodTypeSignature.getTypeParameters();
            if (typeParameters != null && typeParameterIndex < typeParameters.size()) {
                typeParameters.get(typeParameterIndex).addTypeAnnotation(typePath, annotationInfo);
            }
            // else this is a method type descriptor, not a method type signature, so there are no type parameters
        }
        case 0x12 -> {
            // Type in bound of type parameter declaration of generic method or constructor
            final var typeParameters = methodTypeSignature.getTypeParameters();
            if (typeParameters != null && typeParameterIndex < typeParameters.size()) {
                final var typeParameter = typeParameters.get(typeParameterIndex);
                // boundIndex == 0 => class bound; boundIndex > 0 => interface bound
                if (boundIndex == 0) {
                    final var classBound = typeParameter.getClassBound();
                    if (classBound != null) {
                        classBound.addTypeAnnotation(typePath, annotationInfo);
                    }
                } else {
                    final var interfaceBounds = typeParameter.getInterfaceBounds();
                    if (interfaceBounds != null && boundIndex - 1 < interfaceBounds.size()) {
                        interfaceBounds.get(boundIndex - 1).addTypeAnnotation(typePath, annotationInfo);
                    }
                }
            }
            // else this is a method type descriptor, not a method type signature, so there are no type parameters
        }
        case 0x14 ->
            // Return type of method, or type of newly constructed object
            methodTypeSignature.getResultType().addTypeAnnotation(typePath, annotationInfo);
        case 0x15 ->
            // Receiver type of method or constructor (explicit receiver parameter)
            methodTypeSignature.addReceiverTypeAnnotation(annotationInfo);
        case 0x16 -> {
            // Type in formal parameter declaration of method, constructor, or lambda expression.
            // N.B. formal parameter indices are dodgy, because not all compilers index parameters the same way --
            // so be robust here. The classfile spec says "A formal_parameter_index value of i may, but is not
            // required to, correspond to the i'th parameter descriptor in the method descriptor". Also "The
            // formal_parameter_target item records that a formal parameter's type is annotated, but does not record
            // the type itself. The type may be found by inspecting the method descriptor, although a
            // formal_parameter_index value of 0 does not always indicate the first parameter descriptor in the
            // method descriptor." What the heck, guys.
            final var parameterTypeSignatures = methodTypeSignature.getParameterTypeSignatures();
            if (formalParameterIndex < parameterTypeSignatures.size()) {
                parameterTypeSignatures.get(formalParameterIndex).addTypeAnnotation(typePath, annotationInfo);
            }
        }
        case 0x17 -> {
            // Type in throws clause of method or constructor
            final var throwsSignatures = methodTypeSignature.getThrowsSignatures();
            if (throwsSignatures != null && throwsTypeIndex < throwsSignatures.size()) {
                throwsSignatures.get(throwsTypeIndex).addTypeAnnotation(typePath, annotationInfo);
            }
        }
        default -> {
            // Ignore other target types (e.g. 0x10 and 0x13, which are emitted for methods by some buggy compilers)
        }
        }
    }

    /**
     * The range of source code line numbers spanned by a method's body.
     *
     * @param minLineNum
     *            the lowest line number, or 0 if the method's {@code Code} attribute has no line number table
     * @param maxLineNum
     *            the highest line number, or 0 if the method's {@code Code} attribute has no line number table
     */
    private record LineNumberRange(int minLineNum, int maxLineNum) {
    }

    /**
     * Read a method's {@code Code} attribute, skipping over the bytecode itself.
     *
     * @return the range of source code line numbers spanned by the method's body
     * @throws IOException
     *             if an I/O exception occurs.
     */
    private LineNumberRange readCodeAttribute() throws IOException {
        reader().skip(4); // max_stack, max_locals
        final var codeLength = reader().readInt();
        reader().skip(codeLength);
        final var exceptionTableLength = reader().readUnsignedShort();
        reader().skip(8 * exceptionTableLength);
        var minLineNum = 0;
        var maxLineNum = 0;
        final var codeAttrCount = reader().readUnsignedShort();
        for (var i = 0; i < codeAttrCount; i++) {
            final var codeAttrCpIdx = reader().readUnsignedShort();
            final var codeAttrLen = reader().readInt();
            if (constantPoolStringEquals(codeAttrCpIdx, "LineNumberTable")) {
                final var lineNumTableLen = reader().readUnsignedShort();
                for (var j = 0; j < lineNumTableLen; j++) {
                    reader().skip(2); // start_pc
                    final var lineNum = reader().readUnsignedShort();
                    minLineNum = minLineNum == 0 ? lineNum : Math.min(minLineNum, lineNum);
                    maxLineNum = maxLineNum == 0 ? lineNum : Math.max(maxLineNum, lineNum);
                }
            } else {
                reader().skip(codeAttrLen);
            }
        }
        return new LineNumberRange(minLineNum, maxLineNum);
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
        final var attributesCount = reader().readUnsignedShort();
        for (var i = 0; i < attributesCount; i++) {
            final var attributeNameCpIdx = reader().readUnsignedShort();
            final var attributeLength = reader().readInt();
            if (isAnnotationsAttribute(attributeNameCpIdx)) {
                classAnnotations = readAnnotations(classAnnotations);
            } else if (isTypeAnnotationsAttribute(attributeNameCpIdx)) {
                final var decorators = readClassTypeAnnotationDecorators();
                if (decorators != null) {
                    classTypeAnnotationDecorators = decorators;
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Record")) {
                isRecord = true;
                // No need to read record_components_info entries -- there is a 1:1 correspondence between record
                // components and fields/methods of the same name and type as the record component, so we can just
                // rely on the field and method reading code to work correctly with records.
                reader().skip(attributeLength);
            } else if (constantPoolStringEquals(attributeNameCpIdx, "InnerClasses")) {
                readInnerClassesAttribute();
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                // Get class type signature, including type variables
                typeSignatureStr = getConstantPoolString(reader().readUnsignedShort());
            } else if (constantPoolStringEquals(attributeNameCpIdx, "SourceFile")) {
                sourceFile = getConstantPoolString(reader().readUnsignedShort());
            } else if (constantPoolStringEquals(attributeNameCpIdx, "EnclosingMethod")) {
                readEnclosingMethodAttribute();
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Module")) {
                final var moduleNameCpIdx = reader().readUnsignedShort();
                classpathElement.moduleNameFromModuleDescriptor = getConstantPoolString(moduleNameCpIdx);
                // (Future work): parse the rest of the module descriptor fields, and add to ModuleInfo:
                // https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.7.25
                reader().skip(attributeLength - 2);
            } else {
                reader().skip(attributeLength);
            }
        }
    }

    /**
     * Read the class' {@code RuntimeVisibleTypeAnnotations} or {@code RuntimeInvisibleTypeAnnotations} attribute.
     *
     * @return one decorator per type annotation, which adds the annotation to the annotated type of the class type
     *         signature, or null if the attribute holds no annotations
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if a type annotation has a target type that is not permitted in a class.
     */
    private @Nullable List<ClassTypeAnnotationDecorator> readClassTypeAnnotationDecorators()
            throws IOException, ClassfileFormatException {
        final var annotationCount = reader().readUnsignedShort();
        if (annotationCount == 0) {
            return null;
        }
        final List<ClassTypeAnnotationDecorator> decorators = new ArrayList<>(annotationCount);
        for (var i = 0; i < annotationCount; i++) {
            final var targetType = reader().readUnsignedByte();
            final int typeParameterIndex;
            final int supertypeIndex;
            final int boundIndex;
            // 0x00, 0x10 and 0x11 are the only target_types that JVMS 26 table 4.7.20-A permits in ClassFile.
            // Complete and up to date as of JDK 26.
            switch (targetType) {
            case 0x00 -> {
                // Type parameter declaration of generic class or interface
                typeParameterIndex = reader().readUnsignedByte();
                supertypeIndex = -1;
                boundIndex = -1;
            }
            case 0x10 -> {
                // Type in extends or implements clause of class declaration (including the direct superclass or
                // direct superinterface of an anonymous class declaration), or in extends clause of interface
                // declaration
                supertypeIndex = reader().readUnsignedShort();
                typeParameterIndex = -1;
                boundIndex = -1;
            }
            case 0x11 -> {
                // Type in bound of type parameter declaration of generic class or interface
                typeParameterIndex = reader().readUnsignedByte();
                boundIndex = reader().readUnsignedByte();
                supertypeIndex = -1;
            }
            default -> throw new ClassfileFormatException("Class " + className
                    + " has unknown class type annotation target 0x" + Integer.toHexString(targetType)
                    + ": element size unknown, cannot continue reading class. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
            }
            final var typePath = readTypePath();
            final var annotationInfo = readAnnotation();
            decorators.add(classTypeSignature -> decorateClassTypeSignature(classTypeSignature, targetType,
                    typeParameterIndex, supertypeIndex, boundIndex, typePath, annotationInfo));
        }
        return decorators;
    }

    /**
     * Add a type annotation to the type of a class type signature that the annotation's target info names.
     *
     * @param classTypeSignature
     *            the class type signature to decorate
     * @param targetType
     *            the {@code target_type} of the type annotation
     * @param typeParameterIndex
     *            the index of the annotated type parameter, or -1 if the target info does not name one
     * @param supertypeIndex
     *            the index of the annotated superinterface, or 65535 for the superclass, or -1 if the target info
     *            does not name a supertype
     * @param boundIndex
     *            the index of the annotated bound of a type parameter, or -1 if the target info does not name one
     * @param typePath
     *            the type path of the annotation within the annotated type
     * @param annotationInfo
     *            the annotation
     */
    private static void decorateClassTypeSignature(final ClassTypeSignature classTypeSignature,
            final int targetType, final int typeParameterIndex, final int supertypeIndex, final int boundIndex,
            final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
        switch (targetType) {
        case 0x00 -> {
            // Type parameter declaration of generic class or interface
            final var typeParameters = classTypeSignature.getTypeParameters();
            if (typeParameters != null && typeParameterIndex < typeParameters.size()) {
                typeParameters.get(typeParameterIndex).addTypeAnnotation(typePath, annotationInfo);
            }
        }
        case 0x10 -> {
            // Type in extends or implements clause of class declaration (including the direct superclass or direct
            // superinterface of an anonymous class declaration), or in extends clause of interface declaration
            if (supertypeIndex == 65535) {
                // Type in extends clause of class declaration
                final var superclassSignature = classTypeSignature.getSuperclassSignature();
                if (superclassSignature != null) {
                    superclassSignature.addTypeAnnotation(typePath, annotationInfo);
                }
            } else {
                // Type in implements clause of interface declaration
                final var superinterfaceSignatures = classTypeSignature.getSuperinterfaceSignatures();
                if (supertypeIndex < superinterfaceSignatures.size()) {
                    superinterfaceSignatures.get(supertypeIndex).addTypeAnnotation(typePath, annotationInfo);
                }
            }
        }
        case 0x11 -> {
            // Type in bound of type parameter declaration of generic class or interface
            final var typeParameters = classTypeSignature.getTypeParameters();
            if (typeParameters != null && typeParameterIndex < typeParameters.size()) {
                final var typeParameter = typeParameters.get(typeParameterIndex);
                // boundIndex == 0 => class bound; boundIndex > 0 => interface bound
                if (boundIndex == 0) {
                    final var classBound = typeParameter.getClassBound();
                    if (classBound != null) {
                        classBound.addTypeAnnotation(typePath, annotationInfo);
                    }
                } else {
                    final var interfaceBounds = typeParameter.getInterfaceBounds();
                    if (interfaceBounds != null && boundIndex - 1 < interfaceBounds.size()) {
                        interfaceBounds.get(boundIndex - 1).addTypeAnnotation(typePath, annotationInfo);
                    }
                }
            }
        }
        default -> {
            // No other target types are permitted in ClassFile
        }
        }
    }

    /**
     * Read the class' {@code InnerClasses} attribute, recording the relationship between each inner class and its
     * outer class.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if an inner class entry is invalid.
     */
    private void readInnerClassesAttribute() throws IOException, ClassfileFormatException {
        final var numInnerClasses = reader().readUnsignedShort();
        for (var i = 0; i < numInnerClasses; i++) {
            final var innerClassInfoCpIdx = reader().readUnsignedShort();
            final var outerClassInfoCpIdx = reader().readUnsignedShort();
            reader().skip(2); // inner_name_idx
            final var innerClassAccessFlags = reader().readUnsignedShort();
            if (innerClassInfoCpIdx != 0 && outerClassInfoCpIdx != 0) {
                final var innerClassName = getConstantPoolClassName(innerClassInfoCpIdx);
                final var outerClassName = getConstantPoolClassName(outerClassInfoCpIdx);
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
                    classContainmentEntries
                            .add(new ClassContainment(innerClassName, innerClassAccessFlags, outerClassName));
                }
            }
        }
    }

    /**
     * Read the class' {@code EnclosingMethod} attribute, which marks the class as an anonymous inner class, and
     * names the method it is declared in.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the enclosing class or method name is missing.
     */
    private void readEnclosingMethodAttribute() throws IOException, ClassfileFormatException {
        final var innermostEnclosingClassName = requireConstantPoolString(
                getConstantPoolClassName(reader().readUnsignedShort()), "enclosing class name");
        final var enclosingMethodCpIdx = reader().readUnsignedShort();
        final String definingMethodName;
        if (enclosingMethodCpIdx == 0) {
            // A cpIdx of 0 (which is an invalid value) is used for anonymous inner classes declared in class
            // initializer code, e.g. assigned to a class field.
            definingMethodName = "<clinit>";
        } else {
            definingMethodName = requireConstantPoolString(
                    getConstantPoolString(enclosingMethodCpIdx, /* subFieldIdx = */ 0), "enclosing method name");
            // Could also fetch method type signature using subFieldIdx = 1, if needed
        }
        // Link anonymous inner classes into the class with their containing method
        if (classContainmentEntries == null) {
            classContainmentEntries = new ArrayList<>();
        }
        classContainmentEntries.add(new ClassContainment(className, classModifiers, innermostEnclosingClassName));
        // Also store the fully-qualified name of the enclosing method, to mark this as an anonymous inner class
        this.fullyQualifiedDefiningMethodName = innermostEnclosingClassName + "." + definingMethodName;
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
     * @param unscannedModules
     *            the modules that are not being scanned, but whose classfiles may still be read in order to
     *            complete the class graph above an accepted class
     * @param acceptedClassNamesFound
     *            the names of accepted classes found in the classpath while scanning paths within classpath
     *            elements.
     * @param classNamesScheduledForExtendedScanning
     *            the names of external (non-accepted) classes scheduled for extended scanning (where scanning is
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
     *            the log node, or null to skip logging
     * @throws IOException
     *             If an IO exception occurs.
     * @throws ClassfileFormatException
     *             If a problem occurs while parsing the classfile.
     * @throws SkipClassException
     *             if the classfile needs to be skipped (e.g. the class is non-public, and ignoreClassVisibility is
     *             false)
     * @throws InterruptedException
     *             if the thread was interrupted
     */
    Classfile(final ClasspathElement classpathElement, final List<ClasspathElement> classpathOrder,
            final UnscannedModules unscannedModules, final Set<String> acceptedClassNamesFound,
            final Set<String> classNamesScheduledForExtendedScanning, final String relativePath,
            final Resource classfileResource, final boolean isExternalClass,
            final ConcurrentHashMap<String, String> stringInternMap,
            final WorkQueue<ClassfileScanWorkUnit> workQueue, final ScanSpec scanSpec, final @Nullable LogNode log)
            throws IOException, ClassfileFormatException, SkipClassException, InterruptedException {
        this.classpathElement = classpathElement;
        this.classpathOrder = classpathOrder;
        this.unscannedModules = unscannedModules;
        this.relativePath = relativePath;
        this.acceptedClassNamesFound = acceptedClassNamesFound;
        this.classNamesScheduledForExtendedScanning = classNamesScheduledForExtendedScanning;
        this.classfileResource = classfileResource;
        this.isExternalClass = isExternalClass;
        this.stringInternMap = stringInternMap;
        this.scanSpec = scanSpec;

        // Read the classfile through the virtual filesystem, which knows the fastest way to hand over the bytes of
        // the kind of classpath element the classfile is in
        try (var classfileReader = new RandomAccessOrSequentialReader(classfileResource.getVfsEntry())) {
            reader = classfileReader;

            // Check magic number
            if (reader().readInt() != 0xCAFEBABE) {
                throw new ClassfileFormatException("Classfile does not have correct magic number");
            }

            // Read classfile minor and major version
            minorVersion = reader().readUnsignedShort();
            majorVersion = reader().readUnsignedShort();

            // Read the constant pool
            readConstantPoolEntries(log);

            // Read basic class info
            readBasicClassInfo();

            // Read interfaces
            readInterfaces();

            // Read fields
            readFields();

            // Read methods
            readMethods();

            // Read class attributes
            readClassAttributes();

            reader = null;
        }

        // Write class info to log
        final var subLog = logParsedClassfile(log);

        // Check if any superclasses, interfaces or annotations are external (non-accepted) classes that need to be
        // scheduled for scanning, so that all of the "upwards" direction of the class graph is scanned for any
        // accepted class, even if the superclasses / interfaces / annotations are not themselves accepted.
        if (scanSpec.extendScanningUpwardsToExternalClasses) {
            extendScanningUpwards(subLog);
            // If any external classes were found, schedule them for scanning
            if (additionalWorkUnits != null) {
                workQueue.addWorkUnits(additionalWorkUnits);
            }
        }
    }

    /**
     * Write everything that was read from the classfile to the log.
     *
     * @param log
     *            the log node, or null to skip logging
     * @return the log node that the class' details were written to, or null if logging is disabled. Anything else
     *         that is logged for this class should be logged to this node.
     */
    private @Nullable LogNode logParsedClassfile(final @Nullable LogNode log) {
        if (log == null) {
            return null;
        }
        final var subLog = log.log("Found " //
                + (isAnnotation ? "annotation class" : isInterface ? "interface class" : "class") //
                + " " + className);
        if (superclassName != null) {
            // An interface names its superinterfaces in its interface list, not in its superclass slot, which
            // always holds java.lang.Object -- so this is the superclass whatever kind of class this is
            subLog.log("Superclass: " + superclassName);
        }
        if (implementedInterfaces != null) {
            subLog.log("Interfaces: " + StringUtils.join(", ", implementedInterfaces));
        }
        if (classAnnotations != null) {
            subLog.log("Class annotations: " + StringUtils.join(", ", classAnnotations));
        }
        if (annotationParamDefaultValues != null) {
            for (final AnnotationParameterValue apv : annotationParamDefaultValues) {
                subLog.log("Annotation default param value: " + apv);
            }
        }
        if (fieldInfoList != null) {
            for (final FieldInfo fieldInfo : fieldInfoList) {
                final var modifierStr = fieldInfo.getModifiersString();
                subLog.log("Field: " + modifierStr + (modifierStr.isEmpty() ? "" : " ") + fieldInfo.getName());
            }
        }
        if (methodInfoList != null) {
            for (final MethodInfo methodInfo : methodInfoList) {
                final var modifierStr = methodInfo.getModifiersString();
                subLog.log("Method: " + modifierStr + (modifierStr.isEmpty() ? "" : " ") + methodInfo.getName());
            }
        }
        if (typeSignatureStr != null) {
            subLog.log("Class type signature: " + typeSignatureStr);
        }
        if (refdClassNames != null) {
            final List<String> refdClassNamesSorted = new ArrayList<>(refdClassNames);
            CollectionUtils.sortIfNotEmpty(refdClassNamesSorted);
            subLog.log("Additional referenced class names: " + StringUtils.join(", ", refdClassNamesSorted));
        }
        return subLog;
    }
}
