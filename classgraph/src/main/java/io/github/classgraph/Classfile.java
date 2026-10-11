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
import java.nio.ByteOrder;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.classgraph.Scanner.ClassfileScanWorkUnit;
import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.utils.CollectionUtils;
import io.github.classgraph.base.internal.utils.StringUtils;
import io.github.classgraph.vfs.reader.RandomAccessOrSequentialReader;
import org.jspecify.annotations.Nullable;

/**
 * A classfile binary format parser. Each instance parses one classfile, in its constructor, through a
 * {@link RandomAccessOrSequentialReader}, and holds what it read until {@link #link} transfers it into
 * {@link ClassInfo}, {@link PackageInfo} and {@link ModuleInfo} objects. Instances are not threadsafe and are not
 * reused.
 *
 * <p>
 * See <a href="https://docs.oracle.com/javase/specs/jvms/se26/html/jvms-4.html">the class file format spec</a>.
 */
class Classfile {
    /**
     * The {@link RandomAccessOrSequentialReader} for the current classfile, or null once the classfile has been
     * read.
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

    /**
     * The names of the record components, in declaration order, or null if this class is not a record or its
     * {@code Record} attribute is malformed.
     */
    private @Nullable List<String> recordComponentNames;

    /** Whether this class is an annotation. */
    private boolean isAnnotation;

    /**
     * The superclass name, or null if the classfile names no superclass, which is the case only for
     * {@code java.lang.Object} and {@code module-info}.
     */
    private @Nullable String superclassName;

    /** The implemented interfaces. */
    private @Nullable List<String> implementedInterfaces;

    /** The class annotations. */
    private @Nullable List<AnnotationInfo> classAnnotations;

    /** Whether the classfile has an {@code EnclosingMethod} attribute, i.e. is a local or anonymous class. */
    private boolean hasEnclosingMethodAttribute;

    /** Whether the class' own {@code InnerClasses} entry says it has no simple name, i.e. it is anonymous. */
    private boolean hasNoSimpleName;

    /** The access flags in the class' own {@code InnerClasses} entry, or -1 if it has no such entry. */
    private int ownInnerClassAccessFlags = -1;

    /**
     * The fully qualified name of the method or constructor that a local or anonymous class is declared in, or null
     * if it is not declared in a method or constructor.
     */
    private @Nullable String fullyQualifiedDefiningMethodName;

    /** Class containment entries. */
    private @Nullable List<ClassContainment> classContainmentEntries;

    /** Annotation default parameter values. */
    private @Nullable List<AnnotationParameterValue> annotationParamDefaultValues;

    /** Referenced class names. */
    private @Nullable Set<String> refdClassNames;

    /** The field info list. */
    private @Nullable List<FieldInfo> fieldInfoList;

    /** The method info list. */
    private @Nullable List<MethodInfo> methodInfoList;

    /** The type signature. */
    private @Nullable String typeSignatureStr;

    /** The source file, such as Classfile.java */
    private @Nullable String sourceFile;

    /**
     * The type annotations to add to the {@link ClassTypeSignature} instance.
     */
    private @Nullable List<TypeAnnotation<ClassTypeSignature>> classTypeAnnotations;

    /** Whether the class, or any of its fields or methods, has a type annotation. */
    private boolean hasTypeAnnotations;

    /** Why each malformed annotation attribute that was skipped could not be read, or null if none was skipped. */
    private @Nullable List<String> skippedAttributeMessages;

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

    // The constant pool is only read while the classfile is parsed, so these fields are reset to 0 and
    // NO_CONSTANT_POOL_ENTRIES once the classfile has been read.

    /** The number of constant pool entries plus one. */
    private int cpCount;

    /** The byte offset for the beginning of each entry in the constant pool. */
    private int[] entryOffset;

    /** The tag (type) for each entry in the constant pool. */
    private int[] entryTag;

    /**
     * For each Class, String, NameAndType and Module entry in the constant pool, the index of the CONSTANT_Utf8
     * entry holding its string (for a NameAndType entry, its name), or -1 for any other entry.
     */
    private int[] indirectStringRefs;

    /** An empty array, assigned to the constant pool arrays once the classfile has been read. */
    private static final int[] NO_CONSTANT_POOL_ENTRIES = {};

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

    /**
     * The largest number of bytes of a classfile that are read. A classfile is buffered up to the furthest point
     * that has been read or skipped to, and the lengths of the parts that are skipped are read out of the classfile
     * itself, so without a limit one attribute that declares a length of almost 2GB makes the scanner inflate and
     * buffer that much, from a deflated jar entry of a few megabytes. The largest classfile among the 1.28 million
     * in a large Maven repository is 1.1MB, so a classfile that would need more than this is treated as corrupt.
     */
    private static final int MAX_CLASSFILE_LENGTH = 64 * 1024 * 1024;

    /**
     * The bits of a class's {@code access_flags} that JVMS 4.1 defines: {@code ACC_PUBLIC}, {@code ACC_FINAL},
     * {@code ACC_SUPER}, {@code ACC_INTERFACE}, {@code ACC_ABSTRACT}, {@code ACC_SYNTHETIC},
     * {@code ACC_ANNOTATION}, {@code ACC_ENUM} and {@code ACC_MODULE}. The other bits are reserved, and the JVM
     * ignores them.
     */
    private static final int CLASS_ACCESS_FLAGS = 0xF631;

    /**
     * The bits of an {@code InnerClasses} entry's {@code inner_class_access_flags} that JVMS 4.7.6 defines:
     * {@code ACC_PUBLIC}, {@code ACC_PRIVATE}, {@code ACC_PROTECTED}, {@code ACC_STATIC}, {@code ACC_FINAL},
     * {@code ACC_INTERFACE}, {@code ACC_ABSTRACT}, {@code ACC_SYNTHETIC}, {@code ACC_ANNOTATION} and
     * {@code ACC_ENUM}. The other bits are reserved, and the JVM ignores them.
     */
    private static final int INNER_CLASS_ACCESS_FLAGS = 0x761F;

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

    /**
     * A view of the map from class name to class info that records the name of every class that is looked up or
     * added through it. {@link #linkClassInfo(Map)} passes it to every call that creates or links a
     * {@link ClassInfo} for another class, so that the names it records are exactly the classes that the classfile
     * brings into the class graph.
     */
    private static final class ClassNameRecordingMap extends AbstractMap<String, ClassInfo> {
        /** The map from class name to class info. */
        private final Map<String, ClassInfo> classNameToClassInfo;

        /** The names of the classes looked up or added. */
        final Set<String> classNames = new HashSet<>();

        /**
         * Constructor.
         *
         * @param classNameToClassInfo
         *            the map from class name to class info
         */
        ClassNameRecordingMap(final Map<String, ClassInfo> classNameToClassInfo) {
            this.classNameToClassInfo = classNameToClassInfo;
        }

        @Override
        public @Nullable ClassInfo get(final Object key) {
            if (key instanceof final String className) {
                classNames.add(className);
            }
            return classNameToClassInfo.get(key);
        }

        // AbstractMap#containsKey searches the entry set, which would not record the name, and takes linear time
        @Override
        public boolean containsKey(final Object key) {
            return get(key) != null;
        }

        @Override
        public @Nullable ClassInfo put(final String className, final ClassInfo classInfo) {
            classNames.add(className);
            return classNameToClassInfo.put(className, classInfo);
        }

        @Override
        public Set<Entry<String, ClassInfo>> entrySet() {
            return classNameToClassInfo.entrySet();
        }
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
     * Extend scanning to a class that this class refers to, if the class is external: schedule its classfile for
     * reading, unless the class is {@code java.lang.Object}, is accepted, was already scheduled, or has no
     * classfile that can be found.
     *
     * @param className
     *            the class name
     * @param relationship
     *            the relationship type
     * @param log
     *            the log node, or null to skip logging
     */
    private void scheduleScanningIfExternalClass(final @Nullable String className, final String relationship,
            final @Nullable LogNode log) {
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
        // A rejected class is read too, whether it was rejected by name or by package, so that the class graph
        // above it is complete. The queries leave it out of their results (see ClassInfo.isReported).
        final var classfileLocation = findClassfile(className, log);
        if (classfileLocation == null) {
            if (log != null) {
                log.log("External " + relationship + " " + className
                        + " was not found -- cannot extend scanning to this class");
            }
            return;
        }
        final var classResource = classfileLocation.classfileResource();
        final var foundInClasspathElt = classfileLocation.classpathElement();
        if (log != null) {
            // Log the extended scan as a child LogNode of the current class' scan log, since the external class is
            // not scanned at the regular place in the classpath element hierarchy traversal
            classResource.scanLog = log.log("Extending scanning to external " + relationship
                    + " in classpath element " + foundInClasspathElt + ": " + className);
        }
        if (additionalWorkUnits == null) {
            additionalWorkUnits = new ArrayList<>();
        }
        // Schedule class resource for scanning
        additionalWorkUnits
                .add(new ClassfileScanWorkUnit(foundInClasspathElt, classResource, /* isExternalClass = */ true));
    }

    /**
     * Find the classfile of a named class that the JVM would load: the classfile in the module that contains the
     * class' package, if there is one, otherwise the first classfile in classpath order.
     *
     * @param className
     *            the name of the class to find.
     * @param log
     *            the log node, or null to skip logging
     * @return the classfile resource and the classpath element it was found in, or null if the classfile was not
     *         found.
     */
    private @Nullable ClassfileLocation findClassfile(final String className, final @Nullable LogNode log) {
        // Read the copy of the classfile that the JVM would load. If the class' package is in a module, the JVM
        // loads the class from that module, never from the traditional classpath. Look in the modules that are not
        // being scanned first, so that the class graph above an accepted class is still completed through classes
        // in modules, which are not scanned unless their kind of module is asked for (#902), and so that a copy of
        // a module's class in a jar on the classpath (e.g. a javax.xml class in xml-apis) is not read instead
        final var classfilePath = ClassNames.classNameToClassfilePath(className);
        final var workUnit = unscannedModules.findClassfile(className, classfilePath, log);
        if (workUnit != null) {
            return new ClassfileLocation(workUnit.classpathElement(), workUnit.classfileResource());
        }
        // Otherwise the first copy in classpath order wins, as for classpath masking. The modules that are being
        // scanned come before all traditional classpath elements in classpathOrder. This is O(N) for each external
        // class, but each external class is looked up only once, and a directory classpath element that does not
        // hold the class's package looks up the package directory only once per scan.
        for (final ClasspathElement classpathOrderElt : classpathOrder) {
            final var classResource = classpathOrderElt.getResourceDuringScan(classfilePath);
            if (classResource != null) {
                return new ClassfileLocation(classpathOrderElt, classResource);
            }
        }
        return null;
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
     */
    private void extendScanningUpwardsFromAnnotationParameterValues(final @Nullable Object annotationParamVal,
            final @Nullable LogNode log) {
        if (annotationParamVal == null) {
            // Should not be possible -- ignore
        } else if (annotationParamVal instanceof final AnnotationInfo annotationInfo) {
            scheduleScanningIfExternalClass(annotationInfo.getClassName(), "annotation class", log);
            // Default values are not available until the scan completes, so during scanning getParameterValues()
            // and getDeclaredParameterValues() return the same list. The default values of the annotation class
            // are followed when that class is read.
            for (final AnnotationParameterValue apv : annotationInfo.getDeclaredParameterValues()) {
                extendScanningUpwardsFromAnnotationParameterValues(apv.getValue(), log);
            }
        } else if (annotationParamVal instanceof final AnnotationEnumValue annotationEnumValue) {
            scheduleScanningIfExternalClass(annotationEnumValue.getClassName(), "enum class", log);
        } else if (annotationParamVal instanceof final AnnotationClassRef annotationClassRef) {
            final String refdClassName;
            try {
                refdClassName = annotationClassRef.getClassName();
            } catch (final IllegalArgumentException e) {
                // The type descriptor of the class reference is malformed. Like the JDK, which loads the class and
                // reports the malformed value only when the annotation is read, keep the class
                if (log != null) {
                    log.log("Ignoring malformed class reference in annotation: " + annotationClassRef, e);
                }
                return;
            }
            scheduleScanningIfExternalClass(refdClassName, "class ref", log);
        } else if (annotationParamVal.getClass().isArray()) {
            for (int i = 0, n = Array.getLength(annotationParamVal); i < n; i++) {
                extendScanningUpwardsFromAnnotationParameterValues(Array.get(annotationParamVal, i), log);
            }
        } else {
            // String etc. -- ignore
        }
    }

    /**
     * Check if scanning needs to be extended upwards to an external superclass, interface, annotation, enclosing
     * class, class named in an annotation parameter value, or thrown exception type.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    private void extendScanningUpwards(final @Nullable LogNode log) {
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
        // Check if this class is a nested class, and if so, extend scanning to its enclosing class
        if (classContainmentEntries != null) {
            for (final ClassContainment classContainmentEntry : classContainmentEntries) {
                if (classContainmentEntry.innerClassName().equals(className)) {
                    scheduleScanningIfExternalClass(classContainmentEntry.outerClassName(), "enclosing class", log);
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
     */
    private void extendScanningUpwardsFromMethod(final MethodInfo methodInfo, final @Nullable LogNode log) {
        if (methodInfo.annotationInfo != null) {
            for (final AnnotationInfo methodAnnotationInfo : methodInfo.annotationInfo) {
                scheduleScanningIfExternalClass(methodAnnotationInfo.getName(), "method annotation", log);
                extendScanningUpwardsFromAnnotationParameterValues(methodAnnotationInfo, log);
            }
        }
        // A method's parameters can be annotated whether or not the method itself is annotated
        if (methodInfo.parameterAnnotationInfo != null) {
            for (final AnnotationInfo[] paramAnnInfoArr : methodInfo.parameterAnnotationInfo) {
                if (paramAnnInfoArr != null) {
                    for (final AnnotationInfo paramAnnInfo : paramAnnInfoArr) {
                        scheduleScanningIfExternalClass(paramAnnInfo.getName(), "method parameter annotation", log);
                        extendScanningUpwardsFromAnnotationParameterValues(paramAnnInfo, log);
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
     * @param classpathEltToModuleInfo
     *            map from classpath element to the module info for the module the classpath element holds, keyed by
     *            identity
     */
    void link(final Map<String, ClassInfo> classNameToClassInfo,
            final Map<String, PackageInfo> packageNameToPackageInfo,
            final Map<ClasspathElement, ModuleInfo> classpathEltToModuleInfo) {
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
        // listed as a member of its package or module unless external classes were enabled, and a rejected class
        // never is (this keeps PackageInfo and ModuleInfo in step with ScanResult#getAllClasses(), which leaves
        // these classes out)
        final var listAsMember = classInfo != null && classInfo.isReported(scanSpec);

        // Get or create PackageInfo, if this is not a module descriptor (the module descriptor's package is "")
        PackageInfo packageInfo = null;
        if (!isModuleDescriptor) {
            // Get package for this class or package descriptor. A class name is never empty, so
            // getParentPackageName cannot return null
            final var packageName = Objects.requireNonNull(PackageInfo.getParentPackageName(className));
            if (isPackageDescriptor) {
                // Add any class annotations on the package-info.class file to the PackageInfo
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
        // is null above exactly when this is a class that is not being listed as a member)
        final var moduleName = classpathElement.getModuleName();
        if (moduleName != null && (isModuleDescriptor || packageInfo != null)) {
            // Get or create the ModuleInfo object for this classpath element. It is keyed by classpath element, not
            // by module name, since two classpath elements can have the same module name (foo-1.0.jar and
            // foo-2.0.jar both derive the name "foo", and a classpath jar can have the name of a system module),
            // and each needs its own ModuleInfo, with its own location and ModuleReference
            final var moduleInfo = classpathEltToModuleInfo.computeIfAbsent(classpathElement,
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
        // addScannedClass sets the class's modifiers, including ACC_INTERFACE and ACC_ANNOTATION, to those in its
        // classfile, replacing any that other classfiles' references to the class implied
        final var classInfo = ClassInfo.addScannedClass(className, classModifiers, isExternalClass,
                classNameToClassInfo, classpathElement, classfileResource);
        classInfo.setClassfileVersion(minorVersion, majorVersion);
        classInfo.setIsRecord(isRecord);
        classInfo.setRecordComponentNames(recordComponentNames);
        classInfo.setSourceFile(sourceFile);
        // Every call below that creates or links a ClassInfo for another class must be passed linkedClassNameMap,
        // which records the names of those classes, so that the class containment entries for them are linked
        final var linkedClassNameMap = new ClassNameRecordingMap(classNameToClassInfo);
        // An interface's classfile names java.lang.Object as its superclass, but interfaces do not extend Object,
        // so don't record that link (this matches Class#getSuperclass(), which returns null for an interface)
        if (superclassName != null && !(isInterface && "java.lang.Object".equals(superclassName))) {
            classInfo.addSuperclass(superclassName, linkedClassNameMap);
        }
        if (implementedInterfaces != null) {
            for (final String interfaceName : implementedInterfaces) {
                classInfo.addImplementedInterface(interfaceName, linkedClassNameMap);
            }
        }
        if (classAnnotations != null) {
            for (final AnnotationInfo classAnnotation : classAnnotations) {
                classInfo.addClassAnnotation(classAnnotation, linkedClassNameMap);
            }
        }
        // A nested class's own InnerClasses entry holds modifiers that its access_flags cannot express, such as
        // its source-level access level and ACC_STATIC. addClassContainment does not apply the modifiers of an
        // entry to a scanned class, which this class now is, so apply them here. (The entry of a local or
        // anonymous class has an outer class index of 0, and adds no containment entry at all.)
        if (ownInnerClassAccessFlags != -1) {
            classInfo.setNestedClassModifiers(ownInnerClassAccessFlags);
        }
        if (annotationParamDefaultValues != null) {
            classInfo.addAnnotationParamDefaultValues(annotationParamDefaultValues);
        }
        if (hasEnclosingMethodAttribute) {
            classInfo.setLocalOrAnonymousClass(/* isAnonymous = */ hasNoSimpleName,
                    fullyQualifiedDefiningMethodName);
        }
        if (fieldInfoList != null) {
            classInfo.addFieldInfo(fieldInfoList, linkedClassNameMap);
        }
        if (methodInfoList != null) {
            classInfo.addMethodInfo(methodInfoList, linkedClassNameMap);
        }
        // Link the class containment entries last, once linkedClassNameMap holds the names of all the classes that
        // this classfile brought into the class graph
        if (classContainmentEntries != null) {
            linkClassContainment(classInfo, linkedClassNameMap.classNames, classNameToClassInfo);
        }
        if (typeSignatureStr != null) {
            classInfo.setTypeSignature(typeSignatureStr);
        }
        if (refdClassNames != null) {
            classInfo.addReferencedClassNames(refdClassNames);
        }
        if (classTypeAnnotations != null) {
            classInfo.setTypeAnnotations(classTypeAnnotations);
        }
        return classInfo;
    }

    /**
     * Link the class containment entries of this classfile that describe this class, the classes nested in it, and
     * the classes that this classfile brought into the class graph for another reason.
     *
     * <p>
     * A compiler writes an {@code InnerClasses} entry for every nested class that a classfile names anywhere, even
     * only in a descriptor or a signature, so linking every entry would create a {@link ClassInfo} for each such
     * class and for its enclosing classes, such as {@code java.util.Map$Entry} and {@code java.util.Map} for a
     * field of type {@code Map.Entry}. A class named only in a descriptor or a signature gets no {@link ClassInfo}
     * otherwise. So an entry is linked only if its inner class is this class, or a class that this classfile
     * brought into the class graph (a superclass, an implemented interface, the class of an annotation on the class
     * or on one of its fields, methods or method parameters, or, if
     * {@link ClassGraph#enableInterClassDependencies()} was called, a class named in the constant pool), or an
     * enclosing class of one of these, found by following the entries outward (for a local or anonymous class,
     * through the entry made from its {@code EnclosingMethod} attribute); or if its outer class is this class or a
     * class nested in it, found by following the entries inward. Only the entries of this classfile are used, so
     * the result does not depend on the order in which classfiles are linked.
     *
     * <p>
     * Where a type annotation goes in a nested type such as {@code Outer.Inner} depends on which of the nested
     * classes are inner classes (JLS 9.7.4). {@link ClassRefTypeSignature} decides this from the {@link ClassInfo}
     * objects of the classes, so if this class has type annotations, the entries that are not linked and that name
     * a non-static member class are kept in its {@link ClassInfo}, for the classes that have no {@link ClassInfo}.
     *
     * @param classInfo
     *            the {@link ClassInfo} of this class
     * @param linkedClassNames
     *            the names of the classes that this classfile created or linked a {@link ClassInfo} for
     * @param classNameToClassInfo
     *            the map from class name to class info
     */
    private void linkClassContainment(final ClassInfo classInfo, final Set<String> linkedClassNames,
            final Map<String, ClassInfo> classNameToClassInfo) {
        final var entries = Objects.requireNonNull(classContainmentEntries);
        // Follow the entries outward from this class and the classes it brought into the class graph
        final List<String> classNamesToVisit = new ArrayList<>(linkedClassNames);
        classNamesToVisit.add(className);
        if (refdClassNames != null) {
            classNamesToVisit.addAll(refdClassNames);
        }
        final Set<String> enclosedOrSelfClassNames = new HashSet<>();
        while (!classNamesToVisit.isEmpty()) {
            final var innerClassName = classNamesToVisit.remove(classNamesToVisit.size() - 1);
            if (enclosedOrSelfClassNames.add(innerClassName)) {
                for (final ClassContainment entry : entries) {
                    if (entry.innerClassName().equals(innerClassName)) {
                        classNamesToVisit.add(entry.outerClassName());
                    }
                }
            }
        }
        // Follow the entries inward from this class
        final Set<String> nestedOrSelfClassNames = new HashSet<>();
        nestedOrSelfClassNames.add(className);
        for (var changed = true; changed;) {
            changed = false;
            for (final ClassContainment entry : entries) {
                if (nestedOrSelfClassNames.contains(entry.outerClassName())
                        && nestedOrSelfClassNames.add(entry.innerClassName())) {
                    changed = true;
                }
            }
        }
        final List<ClassContainment> entriesToLink = new ArrayList<>(entries.size());
        List<ClassContainment> unlinkedInnerClasses = null;
        for (final ClassContainment entry : entries) {
            if (enclosedOrSelfClassNames.contains(entry.innerClassName())
                    || nestedOrSelfClassNames.contains(entry.outerClassName())) {
                entriesToLink.add(entry);
            } else if (hasTypeAnnotations
                    // An inner class is a nested class that is not static, and a member interface or annotation
                    // interface is always static (JLS 8.1.3). 0x2000 is ACC_ANNOTATION.
                    && (entry.innerClassModifierBits() & (Modifier.STATIC | Modifier.INTERFACE | 0x2000)) == 0) {
                if (unlinkedInnerClasses == null) {
                    unlinkedInnerClasses = new ArrayList<>();
                }
                unlinkedInnerClasses.add(entry);
            }
        }
        ClassInfo.addClassContainment(entriesToLink, className, classNameToClassInfo);
        if (unlinkedInnerClasses != null) {
            classInfo.setUnlinkedInnerClasses(unlinkedInnerClasses);
        }
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
     *            the constant pool index of a CONSTANT_Utf8, CONSTANT_Class, CONSTANT_String, CONSTANT_NameAndType
     *            or CONSTANT_Module entry. A CONSTANT_NameAndType entry gives its name.
     * @return the constant pool string offset
     * @throws ClassfileFormatException
     *             If a problem is detected
     */
    private int getConstantPoolStringOffset(final int cpIdx) throws ClassfileFormatException {
        if (cpIdx < 1 || cpIdx >= cpCount) {
            throw new ClassfileFormatException("Constant pool index " + cpIdx + ", should be in range [1, "
                    + (cpCount - 1) + "] -- cannot continue reading class. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
        final var t = entryTag[cpIdx];
        final int cpIdxToUse;
        switch (t) {
        case 0 -> {
            // Assume this means null
            return 0;
        }
        case 1 ->
            // CONSTANT_Utf8
            cpIdxToUse = cpIdx;
        case 7, 8, 12, 19 -> {
            // t == 7 => CONSTANT_Class, e.g. "[[I", "[Ljava/lang/Thread;"; t == 8 => CONSTANT_String;
            // t == 12 => CONSTANT_NameAndType, whose name is stored here; t == 19 => CONSTANT_Module
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
     * Get a string from the constant pool that holds a class name or a type descriptor, and replace '/' with '.' in
     * it.
     *
     * @param cpIdx
     *            the constant pool index
     * @param stripLSemicolon
     *            if true, strip 'L' from the beginning and ';' from the end before returning, to turn a class type
     *            descriptor into a class name
     * @return the constant pool string, with '/' replaced with '.'
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private @Nullable String getConstantPoolClassString(final int cpIdx, final boolean stripLSemicolon)
            throws ClassfileFormatException, IOException {
        final var constantPoolStringOffset = getConstantPoolStringOffset(cpIdx);
        if (constantPoolStringOffset == 0) {
            return null;
        }
        final var utfLen = reader().readUnsignedShort(constantPoolStringOffset);
        if (utfLen == 0) {
            return "";
        }
        return intern(StringUtils.normalizeTypeDescriptor(
                reader().readStringModifiedUtf8(constantPoolStringOffset + 2L, utfLen),
                /* replaceSlashWithDot = */ true, stripLSemicolon));
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
        final var constantPoolStringOffset = getConstantPoolStringOffset(cpIdx);
        if (constantPoolStringOffset == 0) {
            return null;
        }
        final var utfLen = reader().readUnsignedShort(constantPoolStringOffset);
        if (utfLen == 0) {
            return "";
        }
        return intern(reader().readStringModifiedUtf8(constantPoolStringOffset + 2L, utfLen));
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
     * @return {@code str}, which is known to be non-null
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
        return checkArrayDimensions(getConstantPoolClassString(cpIdx, /* stripLSemicolon = */ false));
    }

    /**
     * Check that a class name does not name an array type with more than {@link ArrayTypeSignature#MAX_DIMENSIONS}
     * dimensions, counting both a leading '[' (e.g. "[[I") and a trailing "[]" (e.g. "int[][]") as a dimension,
     * since {@link ClassInfo#getOrCreateClassInfo(String, Map)} counts both. The JVM never creates such an array
     * class, but it does not check a class name that it does not resolve.
     *
     * @param className
     *            the class name, or null
     * @return {@code className}
     * @throws ClassfileFormatException
     *             If the class name names an array type with too many dimensions.
     */
    private static @Nullable String checkArrayDimensions(final @Nullable String className)
            throws ClassfileFormatException {
        if (className != null) {
            var numDims = 0;
            for (var i = 0; i < className.length() && className.charAt(i) == '['; i++) {
                numDims++;
            }
            for (var end = className.length(); end >= 2 && className.charAt(end - 2) == '['
                    && className.charAt(end - 1) == ']'; end -= 2) {
                numDims++;
            }
            if (numDims > ArrayTypeSignature.MAX_DIMENSIONS) {
                // N.B. the class name can be 64kB long, so it is not put in the message
                throw new ClassfileFormatException(
                        "Class name has more than " + ArrayTypeSignature.MAX_DIMENSIONS + " array dimensions");
            }
        }
        return className;
    }

    /**
     * Check that a class name that this classfile refers to, and that a {@link ClassInfo} is to be created for, is
     * legal (see {@link ClassNames#isLegalClassName(String)}). The JVM refuses to load a classfile whose constant
     * pool has a {@code CONSTANT_Class} entry with an illegal name, though it does not check the name of the type
     * of an annotation. Without this check, a name such as {@code "p.S;"} would make the scan fail when the
     * {@link ClassInfo} is created, and an empty name would give a class with no name.
     *
     * @param name
     *            the class name, with {@code '.'} as the separator.
     * @param description
     *            a description of what the class name names, for the exception message.
     * @return {@code name}
     * @throws ClassfileFormatException
     *             If the class name is not legal.
     */
    private String checkClassName(final String name, final String description) throws ClassfileFormatException {
        if (!ClassNames.isLegalClassName(name)) {
            throw new ClassfileFormatException(
                    "Class " + className + " has an illegal " + description + ": \"" + name + "\"");
        }
        return name;
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
        return checkArrayDimensions(getConstantPoolClassString(cpIdx, /* stripLSemicolon = */ true));
    }

    /**
     * Compare a string in the constant pool with a given ASCII string, without constructing the constant pool
     * String object.
     *
     * @param cpIdx
     *            the constant pool index
     * @param asciiStr
     *            the ASCII string to compare to
     * @return true if the constant pool string is equal to {@code asciiStr}
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private boolean constantPoolStringEquals(final int cpIdx, final @Nullable String asciiStr)
            throws ClassfileFormatException, IOException {
        final var cpStrOffset = getConstantPoolStringOffset(cpIdx);
        if (cpStrOffset == 0) {
            return asciiStr == null;
        } else if (asciiStr == null) {
            return false;
        }
        final var cpStrLen = reader().readUnsignedShort(cpStrOffset);
        // The string starts after the two bytes holding its length
        return reader().contentEqualsAscii(cpStrOffset + 2L, cpStrLen, asciiStr);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Check that a constant pool index is in range and that the entry at that index has the expected tag.
     *
     * @param cpIdx
     *            the constant pool index.
     * @param expectedTag
     *            the tag that the entry must have.
     * @throws ClassfileFormatException
     *             If the index is out of range, or the entry has a different tag.
     */
    private void checkConstantPoolTag(final int cpIdx, final int expectedTag) throws ClassfileFormatException {
        if (cpIdx < 1 || cpIdx >= cpCount) {
            throw new ClassfileFormatException("Constant pool index " + cpIdx + ", should be in range [1, "
                    + (cpCount - 1) + "] -- cannot continue reading class. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
        if (entryTag[cpIdx] != expectedTag) {
            throw new ClassfileFormatException("Wrong tag number " + entryTag[cpIdx] + " at constant pool index "
                    + cpIdx + ", should be " + expectedTag);
        }
    }

    /**
     * Read an int from a {@code CONSTANT_Integer} or {@code CONSTANT_Float} entry of the constant pool.
     *
     * @param cpIdx
     *            the constant pool index.
     * @param expectedTag
     *            the tag that the entry must have: 3 for {@code CONSTANT_Integer} or 4 for {@code CONSTANT_Float}.
     * @return the int value of the entry
     * @throws IOException
     *             If an I/O exception occurred.
     * @throws ClassfileFormatException
     *             If the index is out of range, or the entry has a different tag.
     */
    private int cpReadInt(final int cpIdx, final int expectedTag) throws IOException {
        checkConstantPoolTag(cpIdx, expectedTag);
        return reader().readInt(entryOffset[cpIdx]);
    }

    /**
     * Read a long from a {@code CONSTANT_Long} or {@code CONSTANT_Double} entry of the constant pool.
     *
     * @param cpIdx
     *            the constant pool index.
     * @param expectedTag
     *            the tag that the entry must have: 5 for {@code CONSTANT_Long} or 6 for {@code CONSTANT_Double}.
     * @return the long
     * @throws IOException
     *             If an I/O exception occurred.
     * @throws ClassfileFormatException
     *             If the index is out of range, or the entry has a different tag.
     */
    private long cpReadLong(final int cpIdx, final int expectedTag) throws IOException {
        checkConstantPoolTag(cpIdx, expectedTag);
        return reader().readLong(entryOffset[cpIdx]);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the value of a field's {@code ConstantValue} attribute from the constant pool.
     *
     * @param cpIdx
     *            the constant pool index held by the {@code ConstantValue} attribute
     * @param fieldTypeDescriptor
     *            the field type descriptor
     * @return the constant value, or null if the constant pool entry does not hold a value of the field's type
     * @throws ClassfileFormatException
     *             If a problem occurs.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private @Nullable Object getFieldConstantPoolValue(final int cpIdx, final String fieldTypeDescriptor)
            throws ClassfileFormatException, IOException {
        if (cpIdx < 1 || cpIdx >= cpCount) {
            return null;
        }
        return switch (entryTag[cpIdx]) {
        // 8 => String. The JVMS (table 4.7.2-A) allows only CONSTANT_String for a String constant, but a
        // CONSTANT_Utf8 (1) or CONSTANT_Class (7) entry is read as its string rather than rejected
        case 1, 7, 8 -> fieldTypeDescriptor.equals("Ljava/lang/String;") ? getConstantPoolString(cpIdx) : null;
        // int, short, char, byte and boolean are all represented by CONSTANT_Integer
        case 3 -> switch (fieldTypeDescriptor) {
        case "I" -> cpReadInt(cpIdx, 3);
        case "S" -> (short) cpReadInt(cpIdx, 3);
        case "C" -> (char) cpReadInt(cpIdx, 3);
        case "B" -> (byte) cpReadInt(cpIdx, 3);
        case "Z" -> cpReadInt(cpIdx, 3) != 0;
        default -> null;
        };
        case 4 -> fieldTypeDescriptor.equals("F") ? Float.intBitsToFloat(cpReadInt(cpIdx, 4)) : null;
        case 5 -> fieldTypeDescriptor.equals("J") ? cpReadLong(cpIdx, 5) : null;
        case 6 -> fieldTypeDescriptor.equals("D") ? Double.longBitsToDouble(cpReadLong(cpIdx, 6)) : null;
        default -> null;
        };
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read annotation entry from classfile.
     *
     * @return the annotation, as an {@link AnnotationInfo} object, or null if the class name of the annotation, or
     *         of an annotation nested in it, is illegal.
     * @throws IOException
     *             If an IO exception occurs.
     */
    private @Nullable AnnotationInfo readAnnotation() throws IOException {
        // Lcom/xyz/Annotation; -> com.xyz.Annotation. The JVM does not check the name, and leaves out an annotation
        // whose class it cannot find. A ClassInfo is created for the type of an annotation, so an annotation whose
        // class name is illegal is read, to step over it, and then left out in the same way.
        final var annotationClassName = requireConstantPoolString(
                getConstantPoolClassDescriptor(reader().readUnsignedShort()), "annotation class name");
        var isLegal = ClassNames.isLegalClassName(annotationClassName);
        final var numElementValuePairs = reader().readUnsignedShort();
        AnnotationParameterValueList paramVals = null;
        if (numElementValuePairs > 0) {
            final List<AnnotationParameterValue> paramValList = new ArrayList<>(numElementValuePairs);
            for (var i = 0; i < numElementValuePairs; i++) {
                final var paramName = requireConstantPoolString(getConstantPoolString(reader().readUnsignedShort()),
                        "annotation parameter name");
                final var paramValue = readAnnotationElementValue();
                if (paramValue == ILLEGAL_ANNOTATION) {
                    isLegal = false;
                } else {
                    paramValList.add(new AnnotationParameterValue(paramName, paramValue));
                }
            }
            // The order of the elements of an annotation in a classfile is not specified, so sort them by name
            CollectionUtils.sortIfNotEmpty(paramValList);
            paramVals = new AnnotationParameterValueList(paramValList);
        }
        return isLegal ? new AnnotationInfo(annotationClassName, paramVals) : null;
    }

    /**
     * Returned by {@link #readAnnotationElementValue()} in place of a nested annotation whose class name is
     * illegal, or of an array that holds one.
     */
    private static final Object ILLEGAL_ANNOTATION = new Object();

    /**
     * The deepest that annotation element values may be nested, counting each array element value and each nested
     * annotation element value as one level. The reader recurses once per level, so without a limit, a crafted or
     * corrupt classfile that nests them a few thousand deep would overflow the stack, and the
     * {@link StackOverflowError} would make the whole scan fail rather than only this annotation attribute being
     * skipped. javac cannot nest arrays in an annotation, and annotation types cannot contain themselves, so real
     * annotations nest only a few levels deep.
     */
    private static final int MAX_ANNOTATION_NESTING_DEPTH = 255;

    /** How deeply the annotation element value that is being read is nested. */
    private int annotationNestingDepth;

    /**
     * Read annotation element value from classfile.
     *
     * @return the annotation element value, or {@link #ILLEGAL_ANNOTATION} if it is an annotation whose class name
     *         is illegal, or an array or annotation that holds one.
     * @throws IOException
     *             If an IO exception occurs.
     * @throws ClassfileFormatException
     *             If element values are nested more than {@link #MAX_ANNOTATION_NESTING_DEPTH} deep.
     */
    private Object readAnnotationElementValue() throws IOException, ClassfileFormatException {
        if (annotationNestingDepth >= MAX_ANNOTATION_NESTING_DEPTH) {
            throw new ClassfileFormatException("Class " + className + " has annotation element values nested more "
                    + "than " + MAX_ANNOTATION_NESTING_DEPTH + " deep");
        }
        annotationNestingDepth++;
        try {
            return readAnnotationElementValueContents();
        } finally {
            annotationNestingDepth--;
        }
    }

    /**
     * Read the contents of an annotation element value from classfile, at the nesting depth set by
     * {@link #readAnnotationElementValue()}.
     *
     * @return the annotation element value
     * @throws IOException
     *             If an IO exception occurs.
     */
    private Object readAnnotationElementValueContents() throws IOException {
        final var tag = reader().readUnsignedByte();
        // This list of element_value tags is complete and up to date as of JDK 26 (JVMS 26 table 4.7.16.1-A). No
        // tag has been added since annotations were introduced in Java SE 5.
        return switch (tag) {
        // The const_value_index of 'B', 'C', 'I', 'S' and 'Z' must refer to a CONSTANT_Integer (3) entry, of 'F'
        // to a CONSTANT_Float (4) entry, of 'J' to a CONSTANT_Long (5) entry, and of 'D' to a CONSTANT_Double (6)
        // entry (JVMS 4.7.16.1)
        case 'B' -> (byte) cpReadInt(reader().readUnsignedShort(), 3);
        case 'C' -> (char) cpReadInt(reader().readUnsignedShort(), 3);
        case 'D' -> Double.longBitsToDouble(cpReadLong(reader().readUnsignedShort(), 6));
        case 'F' -> Float.intBitsToFloat(cpReadInt(reader().readUnsignedShort(), 4));
        case 'I' -> cpReadInt(reader().readUnsignedShort(), 3);
        case 'J' -> cpReadLong(reader().readUnsignedShort(), 5);
        case 'S' -> (short) cpReadInt(reader().readUnsignedShort(), 3);
        case 'Z' -> cpReadInt(reader().readUnsignedShort(), 3) != 0;
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
        case 'c' -> {
            // Return type is AnnotationClassRef (for class references in annotations). The JVMS (4.7.16.1) says
            // the class_info_index of a tag 'c' entry refers to a CONSTANT_Utf8 entry holding a return descriptor
            // (e.g. "Ljava/lang/String;"), which is what javac writes. A CONSTANT_Class entry is also accepted.
            final var classInfoIdx = reader().readUnsignedShort();
            final var rawStr = requireConstantPoolString(getConstantPoolString(classInfoIdx),
                    "annotation class reference");
            yield new AnnotationClassRef(
                    entryTag[classInfoIdx] == 1 ? rawStr : constantPoolClassNameToTypeDescriptor(rawStr));
        }
        case '@' -> {
            // Complex (nested) annotation. Return type is AnnotationInfo.
            final var nestedAnnotation = readAnnotation();
            yield nestedAnnotation == null ? ILLEGAL_ANNOTATION : nestedAnnotation;
        }
        case '[' -> {
            // Return type is Object[] (of nested annotation element values)
            final var count = reader().readUnsignedShort();
            final var arr = new Object[count];
            var isLegal = true;
            for (var i = 0; i < count; ++i) {
                // Nested annotation element value
                arr[i] = readAnnotationElementValue();
                if (arr[i] == ILLEGAL_ANNOTATION) {
                    isLegal = false;
                }
            }
            yield isLegal ? arr : ILLEGAL_ANNOTATION;
        }
        default ->
            throw new ClassfileFormatException("Class " + className + " has unknown annotation element type tag '"
                    + ((char) tag) + "': element size unknown, cannot continue reading the attribute. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
        };
    }

    /**
     * Convert the class name stored in a {@code CONSTANT_Class} entry into a type descriptor. Per JVMS 4.4.1, the
     * name in a {@code CONSTANT_Class} entry is a binary class name (e.g. "java/lang/String"), except for array
     * types, where it is the descriptor of the array type (e.g. "[Ljava/lang/String;"). Primitive types and void
     * cannot be named by a {@code CONSTANT_Class} entry, so a name of "I" is the class {@code I}, not {@code int}.
     *
     * @param constantPoolClassName
     *            the class name from a {@code CONSTANT_Class} entry
     * @return the type descriptor of the referenced type
     */
    private static String constantPoolClassNameToTypeDescriptor(final String constantPoolClassName) {
        // An array type is already a type descriptor; a binary class name has to be wrapped in one
        return constantPoolClassName.startsWith("[") ? constantPoolClassName : "L" + constantPoolClassName + ";";
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The part of a type that a type annotation's {@code target_info} names, such as a type parameter, a bound, a
     * formal parameter or a supertype.
     *
     * @param <T>
     *            the type that the target info indexes into: {@link ClassTypeSignature} for a class,
     *            {@link MethodTypeSignature} for a method, or {@link TypeSignature} for the type of a field
     */
    @FunctionalInterface
    interface TypeAnnotationTarget<T> {
        /**
         * Add a type annotation to the part of the type that the target info names.
         *
         * @param type
         *            the type signature or type descriptor to add the annotation to
         * @param typePath
         *            the type path of the annotation within the part of the type that the target info names
         * @param annotationInfo
         *            the annotation
         * @param unlinkedInnerClasses
         *            the {@code InnerClasses} entries for inner classes that the classfile the annotation was read
         *            from did not link (see {@link ClassInfo#getUnlinkedInnerClasses()})
         * @throws IllegalArgumentException
         *             if the type path does not fit the type
         */
        void annotate(T type, List<TypePathNode> typePath, AnnotationInfo annotationInfo,
                List<ClassContainment> unlinkedInnerClasses);
    }

    /**
     * A type annotation read from a {@code RuntimeVisibleTypeAnnotations} or
     * {@code RuntimeInvisibleTypeAnnotations} attribute. It is added to the type when the type signature or type
     * descriptor is parsed, which happens after the classfile has been read.
     *
     * @param <T>
     *            the type that the annotation's target info indexes into
     * @param target
     *            the part of the type that the annotation's target info names
     * @param typePath
     *            the type path of the annotation within that part of the type
     * @param annotationInfo
     *            the annotation
     */
    record TypeAnnotation<T>(TypeAnnotationTarget<T> target, List<TypePathNode> typePath,
            AnnotationInfo annotationInfo) {
        /**
         * Add the type annotation to a type signature or type descriptor. A type annotation that cannot be matched
         * to the type (from a corrupt classfile, or a compiler bug) is skipped, rather than making the whole type
         * unreadable.
         *
         * @param type
         *            the type signature or type descriptor to add the annotation to
         * @param unlinkedInnerClasses
         *            the {@code InnerClasses} entries for inner classes that the classfile the annotation was read
         *            from did not link (see {@link ClassInfo#getUnlinkedInnerClasses()})
         */
        // #897
        void applyTo(final T type, final List<ClassContainment> unlinkedInnerClasses) {
            try {
                target.annotate(type, typePath, annotationInfo, unlinkedInnerClasses);
            } catch (final IllegalArgumentException e) {
                // Skip a type annotation that cannot be matched to the type, rather than failing to produce the
                // whole type (best effort). (#897)
            }
        }

        /**
         * Add type annotations to a type signature or type descriptor, skipping any that cannot be matched to the
         * type.
         *
         * @param <T>
         *            the type that the annotations' target info indexes into
         * @param typeAnnotations
         *            the type annotations, or null if there are none
         * @param type
         *            the type signature or type descriptor to add the annotations to
         * @param declaringClassInfo
         *            the {@link ClassInfo} of the class whose classfile the annotations were read from, or null if
         *            it is not known
         */
        static <T> void applyAll(final @Nullable List<TypeAnnotation<T>> typeAnnotations, final T type,
                final @Nullable ClassInfo declaringClassInfo) {
            if (typeAnnotations != null) {
                final var unlinkedInnerClasses = declaringClassInfo == null ? List.<ClassContainment> of()
                        : declaringClassInfo.getUnlinkedInnerClasses();
                for (final var typeAnnotation : typeAnnotations) {
                    typeAnnotation.applyTo(type, unlinkedInnerClasses);
                }
            }
        }
    }

    /**
     * One step of a type annotation's {@code type_path}, which navigates from the type named by the target info to
     * the nested type that the annotation actually applies to.
     *
     * @param typePathKind
     *            the kind of step to take: 0 to descend into an array type, 1 to descend from a type into the type
     *            nested in it, 2 to move to the bound of a wildcard type argument, or 3 to move to a type argument
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
     * if it was read first, is passed in. That list is not modified, so that it is still intact if this attribute
     * turns out to be malformed.
     *
     * @param annotationInfoList
     *            the annotations read from the other attribute, or null if this is the first of the two attributes
     *            to be encountered
     * @return a new list holding the annotations of {@code annotationInfoList} followed by those of this attribute,
     *         or {@code annotationInfoList} itself if this attribute holds no annotations
     * @throws IOException
     *             if an I/O exception occurs.
     */
    private @Nullable List<AnnotationInfo> readAnnotations(final @Nullable List<AnnotationInfo> annotationInfoList)
            throws IOException {
        final var annotationCount = reader().readUnsignedShort();
        if (annotationCount == 0) {
            return annotationInfoList;
        }
        final var annotations = newListWithCapacity(annotationInfoList, annotationCount);
        for (var i = 0; i < annotationCount; i++) {
            final var annotationInfo = readAnnotation();
            if (annotationInfo != null) {
                annotations.add(annotationInfo);
            }
        }
        return annotations;
    }

    /**
     * Create a list holding the elements of a list read from an earlier attribute, with room for the elements of
     * the attribute being read. A new list is created, rather than the earlier list being added to, so that the
     * earlier list is still intact if the attribute being read turns out to be malformed.
     *
     * @param <E>
     *            the element type
     * @param earlierElements
     *            the elements read from an earlier attribute, or null if there are none
     * @param numNewElements
     *            the number of elements in the attribute being read
     * @return a new list holding the elements of {@code earlierElements}
     */
    private static <E> List<E> newListWithCapacity(final @Nullable List<E> earlierElements,
            final int numNewElements) {
        if (earlierElements == null) {
            return new ArrayList<>(numNewElements);
        }
        final List<E> list = new ArrayList<>(earlierElements.size() + numNewElements);
        list.addAll(earlierElements);
        return list;
    }

    /**
     * Reads the contents of an annotation attribute, given what was read from the attributes of the same kind
     * before it.
     *
     * @param <T>
     *            the type of what is read from the attribute
     */
    @FunctionalInterface
    private interface AnnotationAttributeReader<T extends @Nullable Object> {
        /**
         * Read the contents of the attribute. The reader is positioned at the start of the attribute's contents.
         *
         * @param earlier
         *            what was read from the attributes of the same kind before this one. This must not be modified,
         *            since it is kept if this attribute turns out to be malformed.
         * @return {@code earlier} combined with what was read from this attribute
         * @throws IOException
         *             if the classfile could not be read, or the attribute is malformed.
         */
        T read(T earlier) throws IOException;
    }

    /**
     * Read an attribute that holds annotations or an annotation default value: a
     * {@code Runtime(In)VisibleAnnotations}, {@code Runtime(In)VisibleParameterAnnotations},
     * {@code Runtime(In)VisibleTypeAnnotations} or {@code AnnotationDefault} attribute.
     *
     * <p>
     * The JVM does not parse the contents of these attributes when it loads a class. It steps over each one using
     * its {@code attribute_length}, and never reads the runtime invisible ones, so a class whose annotation
     * attribute is malformed loads and runs normally. Such an attribute is therefore skipped, and the rest of the
     * class is still read from where the attribute's length says the next attribute starts, rather than the whole
     * class being dropped from the scan. An attribute is malformed if its contents cannot be read, or if they run
     * past its {@code attribute_length}, such as when the number of annotations is larger than the attribute holds.
     * The class is rejected only if the attribute's {@code attribute_length} runs past the end of the classfile,
     * since the JVM rejects it too.
     *
     * <p>
     * A {@code Record} attribute is read in the same way. Only the order of the record components is read from it,
     * so if it is malformed, only that order is lost.
     *
     * @param <T>
     *            the type of what is read from the attribute
     * @param attributeNameCpIdx
     *            the constant pool index of the attribute name, for the log
     * @param attributeLength
     *            the attribute's {@code attribute_length}. The reader is positioned just after it.
     * @param location
     *            where the attribute is, such as "method foo", for the log
     * @param earlier
     *            what was read from the attributes of the same kind before this one, which is returned unchanged if
     *            this attribute is malformed
     * @param attributeReader
     *            reads the contents of the attribute
     * @return what {@code attributeReader} returned, or {@code earlier} if the attribute is malformed.
     * @throws IOException
     *             if the classfile could not be read
     * @throws ClassfileFormatException
     *             if the attribute's {@code attribute_length} runs past the end of the classfile.
     */
    private <T extends @Nullable Object> T readAnnotationAttribute(final int attributeNameCpIdx,
            final int attributeLength, final String location, final T earlier,
            final AnnotationAttributeReader<T> attributeReader) throws IOException, ClassfileFormatException {
        // Reading the length of the classfile reads the whole classfile into the reader's buffer, which reading the
        // rest of the class would do anyway. An attribute_length of 2GB or more reads back as negative.
        final var attributeEnd = reader().position() + (long) attributeLength;
        if (attributeLength < 0 || attributeEnd > reader().length()) {
            throw new ClassfileFormatException("Class " + className + " has a "
                    + getConstantPoolString(attributeNameCpIdx) + " attribute in " + location + " whose length of "
                    + Integer.toUnsignedString(attributeLength) + " bytes runs past the end of the classfile");
        }
        T result;
        try {
            result = attributeReader.read(earlier);
            if (reader().position() > attributeEnd) {
                throw new ClassfileFormatException(
                        "its contents run past its length of " + attributeLength + " bytes");
            }
        } catch (final IOException | IllegalArgumentException e) {
            // Since the whole classfile has been read into the buffer, an IOException here is a read past the end
            // of the classfile, not a failure to read it
            if (skippedAttributeMessages == null) {
                skippedAttributeMessages = new ArrayList<>();
            }
            skippedAttributeMessages.add("Skipped the malformed " + getConstantPoolString(attributeNameCpIdx)
                    + " attribute of " + location + ": " + e.getMessage());
            result = earlier;
        }
        // Move to the start of the next attribute, which is where the attribute's length says it is, as the JVM
        // does. This steps over any bytes after the contents, or moves back if the contents ran past the end of the
        // attribute.
        reader().position((int) attributeEnd);
        return result;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The constant pool indices of the entries that name a referenced class, collected while the constant pool is
     * being read. Only collected if inter-class dependencies are enabled.
     *
     * @param classRefCpIdxs
     *            the indices of the modified UTF8 entries referenced by class ref (tag 7) entries
     * @param descriptorCpIdxs
     *            the indices of the modified UTF8 entries holding the descriptor of a name and type (tag 12) or
     *            method type (tag 16) entry
     */
    private record ReferencedClassCpIdxs(List<Integer> classRefCpIdxs, List<Integer> descriptorCpIdxs) {
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
     *             if the classfile could not be read.
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
            // (tag 7) or descriptors (part of tag 12 or 16). Therefore, a hybrid approach needs to be applied of
            // extracting these other class refs from the ClassInfo graph, and combining them with class names
            // extracted from the constant pool here.
            final Set<String> refdClassNamesSet = new HashSet<>();
            refdClassNames = refdClassNamesSet;
            addClassNamesFromClassRefs(referencedClassCpIdxs.classRefCpIdxs(), refdClassNamesSet, log);
            addClassNamesFromDescriptors(referencedClassCpIdxs.descriptorCpIdxs(), refdClassNamesSet, log);
        }
    }

    /**
     * Fill in {@link #entryTag}, {@link #entryOffset} and {@link #indirectStringRefs} for each constant pool entry,
     * without resolving any of the string entries.
     *
     * @param referencedClassCpIdxs
     *            if non-null, the indices of the entries that name a referenced class are collected here
     * @throws IOException
     *             if the classfile could not be read.
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
            entryOffset[i] = reader().position();
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
            // int, short, char, byte, boolean are all represented by CONSTANT_Integer;
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
            // Name and type -- an indirect reference to the modified UTF8 entry holding the name, then one to the
            // entry holding the field or method descriptor
            case 12 -> {
                indirectStringRefs[i] = reader().readUnsignedShort();
                final var descriptorRef = reader().readUnsignedShort();
                if (referencedClassCpIdxs != null) {
                    referencedClassCpIdxs.descriptorCpIdxs().add(descriptorRef);
                }
            }
            // There is no constant pool tag type 13 or 14
            // method handle
            case 15 -> reader().skip(3);
            // Method type -- an indirect reference to the modified UTF8 entry holding a method descriptor.
            // Resolving a method type resolves every class named in its descriptor (JVMS 5.4.3.5).
            case 16 -> {
                final var descriptorRef = reader().readUnsignedShort();
                if (referencedClassCpIdxs != null) {
                    referencedClassCpIdxs.descriptorCpIdxs().add(descriptorRef);
                }
            }
            // dynamic, invoke dynamic
            case 17, 18 -> reader().skip(4);
            // module (for module-info.class in JDK9+) see
            // https://docs.oracle.com/javase/specs/jvms/se26/html/jvms-4.html#jvms-4.4
            case 19 -> indirectStringRefs[i] = reader().readUnsignedShort();
            // package (for module-info.class in JDK9+) see
            // https://docs.oracle.com/javase/specs/jvms/se26/html/jvms-4.html#jvms-4.4
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
     * @param log
     *            the log node, or null to skip logging
     * @throws IOException
     *             if the classfile could not be read.
     */
    private void addClassNamesFromClassRefs(final List<Integer> classRefCpIdxs, final Set<String> refdClassNames,
            final @Nullable LogNode log) throws IOException {
        for (final int cpIdx : classRefCpIdxs) {
            final String refdClassName;
            try {
                refdClassName = getConstantPoolClassName(cpIdx);
            } catch (final ClassfileFormatException e) {
                // An array class with too many dimensions names no class that can exist, so it records no
                // dependency, and as below, one malformed reference must not cause the whole class to be dropped
                if (log != null) {
                    log.log("Skipping class reference: " + e.getMessage());
                }
                continue;
            }
            if (refdClassName != null) {
                var dependencyName = refdClassName;
                if (refdClassName.startsWith("[")) {
                    // An array class, e.g. "[Ljava.lang.String;" or "[I". Its name is an array field descriptor,
                    // which cannot have type arguments, so the element class name is taken directly from between
                    // the 'L' and the ';' rather than by the type signature parser. The parser would reject some
                    // names the JVM accepts (e.g. a name containing '<'), and one unparseable reference must not
                    // cause the whole class to be dropped.
                    var elementTypeStart = 0;
                    while (elementTypeStart < refdClassName.length()
                            && refdClassName.charAt(elementTypeStart) == '[') {
                        elementTypeStart++;
                    }
                    final var elementType = refdClassName.substring(elementTypeStart);
                    if (elementType.length() > 2 && elementType.charAt(0) == 'L' && elementType.endsWith(";")) {
                        dependencyName = elementType.substring(1, elementType.length() - 1);
                    } else {
                        if (elementType.length() != 1 && log != null) {
                            // A single character is a primitive element type, which names no class
                            log.log("Could not extract the element class name from array class reference: "
                                    + refdClassName);
                        }
                        continue;
                    }
                }
                // The JVM refuses to load a class with an illegal class name in its constant pool, but this name
                // only records a dependency, so as above, only the reference is skipped. Whether a class is in the
                // scan result then does not depend on whether dependencies are recorded.
                if (ClassNames.isLegalClassName(dependencyName)) {
                    refdClassNames.add(dependencyName);
                } else if (log != null) {
                    log.log("Skipping class reference with an illegal class name: \"" + refdClassName + "\"");
                }
            }
        }
    }

    /**
     * Add the names of the classes named by the descriptors of name and type (tag 12) and method type (tag 16)
     * constant pool entries to {@code refdClassNames}.
     *
     * @param descriptorCpIdxs
     *            the constant pool indices of the modified UTF8 entries holding the descriptors
     * @param refdClassNames
     *            the set of referenced class names to add to
     * @param log
     *            The log
     * @throws IOException
     *             if the classfile could not be read.
     */
    private void addClassNamesFromDescriptors(final List<Integer> descriptorCpIdxs,
            final Set<String> refdClassNames, final @Nullable LogNode log) throws IOException {
        for (final int cpIdx : descriptorCpIdxs) {
            final var descriptor = getConstantPoolString(cpIdx);
            if (descriptor != null) {
                try {
                    // A method descriptor starts with '('. Anything else is a field descriptor: a base type, a
                    // class type or an array type. Only the first character is checked, because a class name may
                    // contain '(' (#645).
                    if (descriptor.startsWith("(")) {
                        final var typeSig = MethodTypeSignature.parse(descriptor, /* definingClassName = */ null);
                        typeSig.findReferencedClassNames(refdClassNames);
                    } else {
                        final var typeSig = TypeSignature.parse(descriptor, /* definingClassName = */ null);
                        typeSig.findReferencedClassNames(refdClassNames);
                    }
                } catch (final TypeSignatureParseException e) {
                    if (log != null) {
                        log.log("Could not extract referenced class names from constant pool string: " + descriptor
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
        // Modifier flags. The reserved bits are dropped: the jlink-generated java.lang.invoke.*$Holder classes,
        // for example, set bit 0x0002 (ACC_PRIVATE for a nested class), and would otherwise be reported as private.
        classModifiers = reader().readUnsignedShort() & CLASS_ACCESS_FLAGS;

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
        final var isPackage = "package-info".equals(className) || className.endsWith(".package-info");
        if (!scanSpec.ignoreClassVisibility && !Modifier.isPublic(classModifiers) && !isModule && !isPackage) {
            throw new SkipClassException("Class is not public, and ignoreClassVisibility() was not called");
        }

        // Make sure classname matches relative path
        if (!ClassNames.isClassfilePath(relativePath)) {
            // Should not happen
            throw new SkipClassException("Not the path of a classfile: " + relativePath);
        }
        if (!ClassNames.classfilePathMatchesClassName(relativePath, classNamePath)) {
            throw new SkipClassException(
                    "Relative path " + relativePath + " does not match class name " + className);
        }

        // Superclass name, with slashes replaced with dots
        final var superclassNameCpIdx = reader().readUnsignedShort();
        if (superclassNameCpIdx > 0) {
            superclassName = getConstantPoolClassName(superclassNameCpIdx);
            // The JVM refuses to load a class whose superclass is an array class (JVMS 4.1)
            if (superclassName != null && superclassName.startsWith("[")) {
                throw new ClassfileFormatException(
                        "Class " + className + " has an array class as its superclass: " + superclassName);
            }
            if (superclassName != null) {
                checkClassName(superclassName, "superclass name");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read the class' interfaces.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if an interface is an array class, or has an illegal name.
     */
    private void readInterfaces() throws IOException, ClassfileFormatException {
        // Interfaces
        final var interfaceCount = reader().readUnsignedShort();
        for (var i = 0; i < interfaceCount; i++) {
            final var interfaceName = requireConstantPoolString(
                    getConstantPoolClassName(reader().readUnsignedShort()), "interface name");
            // The JVM refuses to load a class that names an array class as an interface (JVMS 4.1)
            if (interfaceName.startsWith("[")) {
                throw new ClassfileFormatException(
                        "Class " + className + " has an array class as an interface: " + interfaceName);
            }
            checkClassName(interfaceName, "interface name");
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
        // https://docs.oracle.com/javase/specs/jvms/se26/html/jvms-4.html#jvms-4.5
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
        final var fieldTypeDescriptor = requireConstantPoolString(getConstantPoolString(fieldTypeDescriptorCpIdx),
                "field type descriptor");

        List<TypeAnnotation<TypeSignature>> fieldTypeAnnotations = null;
        String fieldTypeSignatureStr = null;
        Object fieldConstValue = null;
        List<AnnotationInfo> fieldAnnotationInfo = null;
        final var attributesCount = reader().readUnsignedShort();
        for (var i = 0; i < attributesCount; i++) {
            final var attributeNameCpIdx = reader().readUnsignedShort();
            final var attributeLength = reader().readInt();
            // Read the field's constant initializer value, if any. This is read for non-static fields too, even
            // though the JVM ignores it for them (JVMS 4.7.2). Because the JVM ignores it, a non-static field's
            // ConstantValue attribute can have the wrong length or hold a value of the wrong type, and the class
            // still loads, so such an attribute is ignored here rather than rejected.
            if (getStaticFinalFieldConstValue && constantPoolStringEquals(attributeNameCpIdx, "ConstantValue")) {
                if (attributeLength == 2) {
                    fieldConstValue = getFieldConstantPoolValue(reader().readUnsignedShort(), fieldTypeDescriptor);
                } else {
                    reader().skip(attributeLength);
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                fieldTypeSignatureStr = getConstantPoolString(reader().readUnsignedShort());
            } else if (isAnnotationsAttribute(attributeNameCpIdx)) {
                fieldAnnotationInfo = readAnnotationAttribute(attributeNameCpIdx, attributeLength,
                        "field " + fieldName, fieldAnnotationInfo, this::readAnnotations);
            } else if (isTypeAnnotationsAttribute(attributeNameCpIdx)) {
                fieldTypeAnnotations = readAnnotationAttribute(attributeNameCpIdx, attributeLength,
                        "field " + fieldName, fieldTypeAnnotations, this::readFieldTypeAnnotations);
            } else {
                // No match, just skip attribute
                reader().skip(attributeLength);
            }
        }

        if (scanSpec.enableFieldInfo) {
            if (fieldInfoList == null) {
                fieldInfoList = new ArrayList<>();
            }
            fieldInfoList.add(new FieldInfo(className, fieldName, fieldModifierFlags, fieldTypeDescriptor,
                    fieldTypeSignatureStr, fieldConstValue, fieldAnnotationInfo, fieldTypeAnnotations));
        }
    }

    /**
     * Read a field's {@code RuntimeVisibleTypeAnnotations} or {@code RuntimeInvisibleTypeAnnotations} attribute
     * into a list of type annotations. The two attributes are read into the same list, so the list of the other
     * attribute, if it was read first, is passed in. That list is not modified, so that it is still intact if this
     * attribute turns out to be malformed.
     *
     * @param typeAnnotationList
     *            the type annotations read from the other attribute, or null if this is the first of the two
     *            attributes to be encountered
     * @return a new list holding the type annotations of {@code typeAnnotationList} followed by those of this
     *         attribute, or {@code typeAnnotationList} itself if this attribute holds no annotations
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if a type annotation has a target type that is not permitted in a field.
     */
    private @Nullable List<TypeAnnotation<TypeSignature>> readFieldTypeAnnotations(
            final @Nullable List<TypeAnnotation<TypeSignature>> typeAnnotationList)
            throws IOException, ClassfileFormatException {
        final var annotationCount = reader().readUnsignedShort();
        if (annotationCount == 0) {
            return typeAnnotationList;
        }
        final var typeAnnotations = newListWithCapacity(typeAnnotationList, annotationCount);
        for (var i = 0; i < annotationCount; i++) {
            final var targetType = reader().readUnsignedByte();
            // 0x13 is the only target_type that JVMS 26 table 4.7.20-A permits in field_info. Its target_info is
            // empty, since it names the field type itself. Complete and up to date as of JDK 26.
            if (targetType != 0x13) {
                throw new ClassfileFormatException("Class " + className
                        + " has unknown field type annotation target 0x" + Integer.toHexString(targetType)
                        + ": element size unknown, cannot continue reading the attribute. "
                        + "Please report this at https://github.com/classgraph/classgraph/issues");
            }
            final var typePath = readTypePath();
            final var annotationInfo = readAnnotation();
            if (annotationInfo != null) {
                typeAnnotations
                        .add(new TypeAnnotation<>(TypeSignature::addTypeAnnotation, typePath, annotationInfo));
                hasTypeAnnotations = true;
            }
        }
        return typeAnnotations;
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
        // https://docs.oracle.com/javase/specs/jvms/se26/html/jvms-4.html#jvms-4.6
        final var methodModifierFlags = reader().readUnsignedShort();
        final var isPublicMethod = (methodModifierFlags & 0x0001) == 0x0001;
        final var methodIsVisible = isPublicMethod || scanSpec.ignoreMethodVisibility;
        // Always enable MethodInfo for annotations (this is how annotation constants are defined)
        final var enableMethodInfo = scanSpec.enableMethodInfo || isAnnotation;
        if (!methodIsVisible || !enableMethodInfo) {
            // Skip method
            reader().skip(4); // name_index, descriptor_index
            final var attributesCount = reader().readUnsignedShort();
            for (var i = 0; i < attributesCount; i++) {
                reader().skip(2); // attribute_name_index
                final var attributeLength = reader().readInt();
                reader().skip(attributeLength);
            }
            return;
        }

        final var methodNameCpIdx = reader().readUnsignedShort();
        final var methodName = requireConstantPoolString(getConstantPoolString(methodNameCpIdx), "method name");
        final var methodTypeDescriptorCpIdx = reader().readUnsignedShort();
        final var methodTypeDescriptor = requireConstantPoolString(getConstantPoolString(methodTypeDescriptorCpIdx),
                "method type descriptor");
        final var attributesCount = reader().readUnsignedShort();

        List<TypeAnnotation<MethodTypeSignature>> methodTypeAnnotations = null;
        String methodTypeSignatureStr = null;
        List<AnnotationInfo> methodAnnotationInfo = null;
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
                methodAnnotationInfo = readAnnotationAttribute(attributeNameCpIdx, attributeLength,
                        "method " + methodName, methodAnnotationInfo, this::readAnnotations);
            } else if (isParameterAnnotationsAttribute(attributeNameCpIdx)) {
                methodParameterAnnotations = readAnnotationAttribute(attributeNameCpIdx, attributeLength,
                        "method " + methodName, methodParameterAnnotations, this::readMethodParameterAnnotations);
            } else if (isTypeAnnotationsAttribute(attributeNameCpIdx)) {
                methodTypeAnnotations = readAnnotationAttribute(attributeNameCpIdx, attributeLength,
                        "method " + methodName, methodTypeAnnotations, this::readMethodTypeAnnotations);
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
                // Get annotation parameter default value, or null if the attribute is malformed
                final var defaultValue = this.<@Nullable Object> readAnnotationAttribute(attributeNameCpIdx,
                        attributeLength, "method " + methodName, null, earlier -> readAnnotationElementValue());
                // A default value that is an annotation with an illegal class name, or holds one, is dropped
                if (defaultValue != null && defaultValue != ILLEGAL_ANNOTATION) {
                    if (annotationParamDefaultValues == null) {
                        annotationParamDefaultValues = new ArrayList<>();
                    }
                    this.annotationParamDefaultValues.add(new AnnotationParameterValue(methodName, defaultValue));
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Exceptions")) {
                final var exceptionCount = reader().readUnsignedShort();
                thrownExceptionNames = new String[exceptionCount];
                for (var j = 0; j < exceptionCount; j++) {
                    final var cpIdx = reader().readUnsignedShort();
                    // A thrown exception class is only looked up by name, never created, so its name is not checked
                    // for too many array dimensions, since that check would drop the whole class
                    thrownExceptionNames[j] = requireConstantPoolString(
                            getConstantPoolClassString(cpIdx, /* stripLSemicolon = */ false),
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
            methodInfoList = new ArrayList<>();
        }
        methodInfoList.add(new MethodInfo(className, methodName, methodAnnotationInfo, methodModifierFlags,
                methodTypeDescriptor, methodTypeSignatureStr, methodParameterNames, methodParameterModifiers,
                methodParameterAnnotations, methodHasBody, minLineNum, maxLineNum, methodTypeAnnotations,
                thrownExceptionNames));
    }

    /**
     * Read a method's {@code RuntimeVisibleParameterAnnotations} or {@code RuntimeInvisibleParameterAnnotations}
     * attribute. Runtime visible and runtime invisible parameter annotations are given in separate attributes, but
     * are merged into a single array of annotations for each method parameter, so if both attributes are present,
     * the parameter annotation arrays have to be enlarged when the second attribute is encountered.
     *
     * <p>
     * Each attribute gives its own parameter count (JVMS 4.7.18 and 4.7.19), and the two counts may differ, for
     * example when javac leaves the implicit outer instance parameter of an inner class constructor out of one
     * attribute and a bytecode tool writes the other attribute with a count taken from the method descriptor. Both
     * attributes are therefore aligned with the last parameter, as {@link MethodInfo} aligns all other parameter
     * metadata, and the merged array has the length of the longer attribute.
     *
     * @param parameterAnnotations
     *            the parameter annotations read from the other attribute, or null if this is the first of the two
     *            attributes to be encountered. They are not modified.
     * @return new merged parameter annotations, with an array (possibly {@link #NO_ANNOTATIONS}) for every
     *         parameter
     * @throws IOException
     *             if an I/O exception occurs.
     */
    private AnnotationInfo[][] readMethodParameterAnnotations(
            final AnnotationInfo @Nullable [][] parameterAnnotations) throws IOException {
        final var numParams = reader().readUnsignedByte();
        final var prevNumParams = parameterAnnotations == null ? 0 : parameterAnnotations.length;
        final var mergedNumParams = Math.max(numParams, prevNumParams);
        // The merged array is always a new array, and the per-parameter arrays that receive annotations from this
        // attribute are replaced by copies below, so that parameterAnnotations is still intact if this attribute
        // turns out to be malformed
        final var mergedParameterAnnotations = new AnnotationInfo[mergedNumParams][];
        if (parameterAnnotations != null) {
            // Shift the earlier attribute's entries right, so that both attributes end at the last parameter
            System.arraycopy(parameterAnnotations, 0, mergedParameterAnnotations, mergedNumParams - prevNumParams,
                    prevNumParams);
        }
        final var offset = mergedNumParams - numParams;
        for (var paramIdx = offset; paramIdx < mergedNumParams; paramIdx++) {
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
                var numParamAnnotations = annStartIdx;
                for (var annIdx = 0; annIdx < numAnnotations; annIdx++) {
                    final var annotationInfo = readAnnotation();
                    if (annotationInfo != null) {
                        mergedParameterAnnotations[paramIdx][numParamAnnotations++] = annotationInfo;
                    }
                }
                if (numParamAnnotations < mergedParameterAnnotations[paramIdx].length) {
                    // Drop the slots of annotations whose class name is illegal
                    mergedParameterAnnotations[paramIdx] = Arrays.copyOf(mergedParameterAnnotations[paramIdx],
                            numParamAnnotations);
                }
            }
        }
        for (var paramIdx = 0; paramIdx < mergedNumParams; paramIdx++) {
            if (mergedParameterAnnotations[paramIdx] == null) {
                mergedParameterAnnotations[paramIdx] = NO_ANNOTATIONS;
            }
        }
        return mergedParameterAnnotations;
    }

    /**
     * Read a method's {@code RuntimeVisibleTypeAnnotations} or {@code RuntimeInvisibleTypeAnnotations} attribute
     * into a list of type annotations. The two attributes are read into the same list, so the list of the other
     * attribute, if it was read first, is passed in. That list is not modified, so that it is still intact if this
     * attribute turns out to be malformed.
     *
     * @param typeAnnotationList
     *            the type annotations read from the other attribute, or null if this is the first of the two
     *            attributes to be encountered
     * @return a new list holding the type annotations of {@code typeAnnotationList} followed by those of this
     *         attribute, or {@code typeAnnotationList} itself if this attribute holds no annotations
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if a type annotation has a target type that is not permitted in a method.
     */
    private @Nullable List<TypeAnnotation<MethodTypeSignature>> readMethodTypeAnnotations(
            final @Nullable List<TypeAnnotation<MethodTypeSignature>> typeAnnotationList)
            throws IOException, ClassfileFormatException {
        final var annotationCount = reader().readUnsignedShort();
        if (annotationCount == 0) {
            return typeAnnotationList;
        }
        final var typeAnnotations = newListWithCapacity(typeAnnotationList, annotationCount);
        for (var i = 0; i < annotationCount; i++) {
            final var targetType = reader().readUnsignedByte();
            // JVMS 26 table 4.7.20-A permits target_types 0x01, 0x12, 0x14, 0x15, 0x16 and 0x17 in method_info; all
            // are handled below, plus 0x10 and 0x13, which are illegal here but are emitted by buggy compilers (see
            // the notes below), and are read but ignored (null target). Complete and up to date as of JDK 26.
            final TypeAnnotationTarget<MethodTypeSignature> target = switch (targetType) {
            case 0x01 -> {
                // Type parameter declaration of generic method or constructor
                final var typeParameterIndex = reader().readUnsignedByte();
                yield (methodType, typePath, annotationInfo, unlinkedInnerClasses) -> annotateTypeParameter(
                        methodType.getTypeParameters(), typeParameterIndex, typePath, annotationInfo,
                        unlinkedInnerClasses);
            }
            case 0x10 -> {
                // This target_type is not supposed to be added to methods, it is intended for ClassFile
                // annotations, but Google's Java compiler adds annotations of this type to methods in guava for
                // some reason. Just ignore these annotations.
                // (#861)
                reader().readUnsignedShort();
                yield null;
            }
            case 0x12 -> {
                // Type in bound of type parameter declaration of generic method or constructor
                final var typeParameterIndex = reader().readUnsignedByte();
                final var boundIndex = reader().readUnsignedByte();
                yield (methodType, typePath, annotationInfo, unlinkedInnerClasses) -> annotateTypeParameterBound(
                        methodType.getTypeParameters(), typeParameterIndex, boundIndex, typePath, annotationInfo,
                        unlinkedInnerClasses);
            }
            case 0x13 ->
                // Type in field or record component declaration (empty target). This target_type is not supposed to
                // be added to methods, but it seems that the JDK 17 compiler is buggy, and adds this target_type to
                // the methods of records anyway (#797). Therefore, accept this, but ignore it (the same target_type
                // should also be added to the fields of records).
                null;
            case 0x14 ->
                // Return type of method, or type of newly constructed object (empty target)
                (methodType, typePath, annotationInfo, unlinkedInnerClasses) -> methodType.getResultType()
                        .addTypeAnnotation(typePath, annotationInfo, unlinkedInnerClasses);
            case 0x15 ->
                // Receiver type of method or constructor (explicit receiver parameter) (empty target)
                (methodType, typePath, annotationInfo, unlinkedInnerClasses) -> methodType
                        .addReceiverTypeAnnotation(annotationInfo);
            case 0x16 -> {
                // Type in formal parameter declaration of method, constructor, or lambda expression.
                // N.B. formal parameter indices are dodgy, because not all compilers index parameters the same way
                // -- so be robust here. The classfile spec says "A formal_parameter_index value of i may, but is
                // not required to, correspond to the i'th parameter descriptor in the method descriptor". Also "The
                // formal_parameter_target item records that a formal parameter's type is annotated, but does not
                // record the type itself. The type may be found by inspecting the method descriptor, although a
                // formal_parameter_index value of 0 does not always indicate the first parameter descriptor in the
                // method descriptor." What the heck, guys.
                final var formalParameterIndex = reader().readUnsignedByte();
                yield (methodType, typePath, annotationInfo, unlinkedInnerClasses) -> {
                    final var parameterTypeSignatures = methodType.getParameterTypeSignatures();
                    if (formalParameterIndex < parameterTypeSignatures.size()) {
                        parameterTypeSignatures.get(formalParameterIndex).addTypeAnnotation(typePath,
                                annotationInfo, unlinkedInnerClasses);
                    }
                };
            }
            case 0x17 -> {
                // Type in throws clause of method or constructor
                final var throwsTypeIndex = reader().readUnsignedShort();
                yield (methodType, typePath, annotationInfo, unlinkedInnerClasses) -> {
                    final var throwsSignatures = methodType.getThrowsSignatures();
                    if (throwsTypeIndex < throwsSignatures.size()) {
                        throwsSignatures.get(throwsTypeIndex).addTypeAnnotation(typePath, annotationInfo,
                                unlinkedInnerClasses);
                    }
                };
            }
            default -> throw new ClassfileFormatException("Class " + className
                    + " has unknown method type annotation target 0x" + Integer.toHexString(targetType)
                    + ": element size unknown, cannot continue reading the attribute. " + "Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
            };
            final var typePath = readTypePath();
            final var annotationInfo = readAnnotation();
            if (target != null && annotationInfo != null) {
                typeAnnotations.add(new TypeAnnotation<>(target, typePath, annotationInfo));
                hasTypeAnnotations = true;
            }
        }
        return typeAnnotations;
    }

    /**
     * Add a type annotation to a type parameter of a class or method type signature (target_type 0x00 or 0x01).
     *
     * @param typeParameters
     *            the type parameters of the class or method type signature
     * @param typeParameterIndex
     *            the index of the annotated type parameter
     * @param typePath
     *            the type path of the annotation within the type parameter
     * @param annotationInfo
     *            the annotation
     * @param unlinkedInnerClasses
     *            the {@code InnerClasses} entries for inner classes that the classfile the annotation was read from
     *            did not link (see {@link ClassInfo#getUnlinkedInnerClasses()})
     */
    private static void annotateTypeParameter(final List<TypeParameter> typeParameters,
            final int typeParameterIndex, final List<TypePathNode> typePath, final AnnotationInfo annotationInfo,
            final List<ClassContainment> unlinkedInnerClasses) {
        if (typeParameterIndex < typeParameters.size()) {
            typeParameters.get(typeParameterIndex).addTypeAnnotation(typePath, annotationInfo,
                    unlinkedInnerClasses);
        }
        // else there is no type parameter at this index, e.g. because the type is a type descriptor, which has no
        // type parameters
    }

    /**
     * Add a type annotation to a bound of a type parameter of a class or method type signature (target_type 0x11 or
     * 0x12).
     *
     * @param typeParameters
     *            the type parameters of the class or method type signature
     * @param typeParameterIndex
     *            the index of the type parameter whose bound is annotated
     * @param boundIndex
     *            the index of the annotated bound: 0 for the class bound, or n for the nth interface bound
     * @param typePath
     *            the type path of the annotation within the bound
     * @param annotationInfo
     *            the annotation
     * @param unlinkedInnerClasses
     *            the {@code InnerClasses} entries for inner classes that the classfile the annotation was read from
     *            did not link (see {@link ClassInfo#getUnlinkedInnerClasses()})
     */
    private static void annotateTypeParameterBound(final List<TypeParameter> typeParameters,
            final int typeParameterIndex, final int boundIndex, final List<TypePathNode> typePath,
            final AnnotationInfo annotationInfo, final List<ClassContainment> unlinkedInnerClasses) {
        if (typeParameterIndex < typeParameters.size()) {
            final var typeParameter = typeParameters.get(typeParameterIndex);
            // boundIndex == 0 => class bound; boundIndex > 0 => interface bound
            if (boundIndex == 0) {
                final var classBound = typeParameter.getClassBound();
                if (classBound != null) {
                    classBound.addTypeAnnotation(typePath, annotationInfo, unlinkedInnerClasses);
                }
            } else {
                final var interfaceBounds = typeParameter.getInterfaceBounds();
                if (boundIndex - 1 < interfaceBounds.size()) {
                    interfaceBounds.get(boundIndex - 1).addTypeAnnotation(typePath, annotationInfo,
                            unlinkedInnerClasses);
                }
            }
        }
        // else there is no type parameter at this index, e.g. because the type is a type descriptor, which has no
        // type parameters
    }

    /**
     * The range of source code line numbers spanned by a method's body.
     *
     * @param minLineNum
     *            the lowest nonzero line number, or 0 if the method's {@code Code} attribute has no line number
     *            table or the table holds no nonzero line number
     * @param maxLineNum
     *            the highest line number, or 0 if the method's {@code Code} attribute has no line number table or
     *            the table holds no nonzero line number
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
                    // Ignore line 0, since 0 is the "unknown" value of minLineNum and maxLineNum, and a 0 entry
                    // would otherwise reset the running minimum
                    if (lineNum != 0) {
                        minLineNum = minLineNum == 0 ? lineNum : Math.min(minLineNum, lineNum);
                        maxLineNum = Math.max(maxLineNum, lineNum);
                    }
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
                classAnnotations = readAnnotationAttribute(attributeNameCpIdx, attributeLength, "the class",
                        classAnnotations, this::readAnnotations);
            } else if (isTypeAnnotationsAttribute(attributeNameCpIdx)) {
                classTypeAnnotations = readAnnotationAttribute(attributeNameCpIdx, attributeLength, "the class",
                        classTypeAnnotations, this::readClassTypeAnnotations);
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Record")) {
                isRecord = true;
                // Each record component has a field and an accessor method of the same name and type, which are
                // read with the other fields and methods. Only the components' names are read here, since the
                // order of the fields in a classfile is not specified, and the fields are sorted by name. A class
                // should have only one Record attribute; if it has another, a well-formed one replaces the names
                // read before it, and a malformed one keeps them.
                recordComponentNames = readAnnotationAttribute(attributeNameCpIdx, attributeLength, "the class",
                        recordComponentNames, earlier -> readRecordComponentNames());
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
                // https://docs.oracle.com/javase/specs/jvms/se26/html/jvms-4.html#jvms-4.7.25
                reader().skip(attributeLength - 2);
            } else {
                reader().skip(attributeLength);
            }
        }
    }

    /**
     * Read the names of the record components from the {@code record_components_info} entries of the class'
     * {@code Record} attribute (JVMS 4.7.30).
     *
     * @return the names of the record components, in declaration order.
     * @throws IOException
     *             if an I/O exception occurs, or the attribute is malformed.
     */
    private List<String> readRecordComponentNames() throws IOException {
        final var componentsCount = reader().readUnsignedShort();
        final List<String> componentNames = new ArrayList<>(componentsCount);
        for (var i = 0; i < componentsCount; i++) {
            componentNames.add(requireConstantPoolString(getConstantPoolString(reader().readUnsignedShort()),
                    "record component name"));
            reader().skip(2); // descriptor_index
            final var componentAttributesCount = reader().readUnsignedShort();
            for (var j = 0; j < componentAttributesCount; j++) {
                reader().skip(2); // attribute_name_index
                reader().skip(reader().readInt()); // attribute_length, then the attribute's contents
            }
        }
        return componentNames;
    }

    /**
     * Read the class' {@code RuntimeVisibleTypeAnnotations} or {@code RuntimeInvisibleTypeAnnotations} attribute
     * into a list of type annotations. The two attributes are read into the same list, so the list of the other
     * attribute, if it was read first, is passed in. That list is not modified, so that it is still intact if this
     * attribute turns out to be malformed.
     *
     * @param typeAnnotationList
     *            the type annotations read from the other attribute, or null if this is the first of the two
     *            attributes to be encountered
     * @return a new list holding the type annotations of {@code typeAnnotationList} followed by those of this
     *         attribute, or {@code typeAnnotationList} itself if this attribute holds no annotations
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if a type annotation has a target type that is not permitted in a class.
     */
    private @Nullable List<TypeAnnotation<ClassTypeSignature>> readClassTypeAnnotations(
            final @Nullable List<TypeAnnotation<ClassTypeSignature>> typeAnnotationList)
            throws IOException, ClassfileFormatException {
        final var annotationCount = reader().readUnsignedShort();
        if (annotationCount == 0) {
            return typeAnnotationList;
        }
        final var typeAnnotations = newListWithCapacity(typeAnnotationList, annotationCount);
        for (var i = 0; i < annotationCount; i++) {
            final var targetType = reader().readUnsignedByte();
            // 0x00, 0x10 and 0x11 are the only target_types that JVMS 26 table 4.7.20-A permits in ClassFile.
            // Complete and up to date as of JDK 26.
            final TypeAnnotationTarget<ClassTypeSignature> target = switch (targetType) {
            case 0x00 -> {
                // Type parameter declaration of generic class or interface
                final var typeParameterIndex = reader().readUnsignedByte();
                yield (classType, typePath, annotationInfo, unlinkedInnerClasses) -> annotateTypeParameter(
                        classType.getTypeParameters(), typeParameterIndex, typePath, annotationInfo,
                        unlinkedInnerClasses);
            }
            case 0x10 -> {
                // Type in extends or implements clause of class declaration (including the direct superclass or
                // direct superinterface of an anonymous class declaration), or in extends clause of interface
                // declaration
                final var supertypeIndex = reader().readUnsignedShort();
                if (supertypeIndex == 65535) {
                    // Type in extends clause of class declaration
                    yield (classType, typePath, annotationInfo, unlinkedInnerClasses) -> {
                        final var superclassSignature = classType.getSuperclassSignature();
                        if (superclassSignature != null) {
                            superclassSignature.addTypeAnnotation(typePath, annotationInfo, unlinkedInnerClasses);
                        }
                    };
                }
                // Type in implements clause of class declaration, or in extends clause of interface declaration
                yield (classType, typePath, annotationInfo, unlinkedInnerClasses) -> {
                    final var superinterfaceSignatures = classType.getSuperinterfaceSignatures();
                    if (supertypeIndex < superinterfaceSignatures.size()) {
                        superinterfaceSignatures.get(supertypeIndex).addTypeAnnotation(typePath, annotationInfo,
                                unlinkedInnerClasses);
                    }
                };
            }
            case 0x11 -> {
                // Type in bound of type parameter declaration of generic class or interface
                final var typeParameterIndex = reader().readUnsignedByte();
                final var boundIndex = reader().readUnsignedByte();
                yield (classType, typePath, annotationInfo, unlinkedInnerClasses) -> annotateTypeParameterBound(
                        classType.getTypeParameters(), typeParameterIndex, boundIndex, typePath, annotationInfo,
                        unlinkedInnerClasses);
            }
            default -> throw new ClassfileFormatException("Class " + className
                    + " has unknown class type annotation target 0x" + Integer.toHexString(targetType)
                    + ": element size unknown, cannot continue reading the attribute. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
            };
            final var typePath = readTypePath();
            final var annotationInfo = readAnnotation();
            if (annotationInfo != null) {
                typeAnnotations.add(new TypeAnnotation<>(target, typePath, annotationInfo));
                hasTypeAnnotations = true;
            }
        }
        return typeAnnotations;
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
            final var innerNameCpIdx = reader().readUnsignedShort();
            final var innerClassAccessFlags = reader().readUnsignedShort() & INNER_CLASS_ACCESS_FLAGS;
            if (innerClassInfoCpIdx != 0 && className.equals(getConstantPoolClassName(innerClassInfoCpIdx))) {
                ownInnerClassAccessFlags = innerClassAccessFlags;
                // An inner_name_index of 0 in the class' own entry means that the class is anonymous (JVMS 4.7.6)
                if (innerNameCpIdx == 0) {
                    hasNoSimpleName = true;
                }
            }
            // The outer_class_info_index of a local or anonymous class is 0; these classes are linked to the
            // class they are declared in by the EnclosingMethod attribute instead
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
                // An array class cannot be a nested or an enclosing class (JVMS 4.7.6). The JVM refuses to load a
                // class whose entry names an array as the outer class (from JDK-8270404, in JDK 17.0.1 and later;
                // JDK 17.0.0 loads it), but not one whose entry names an array as the inner class. That entry is
                // ignored, so it does not change the class info of the array class, which other classes share.
                if (outerClassName.startsWith("[")) {
                    throw new ClassfileFormatException("Class " + className
                            + " has an InnerClasses entry whose outer class is an array class: " + outerClassName);
                }
                if (innerClassName.startsWith("[")) {
                    continue;
                }
                checkClassName(innerClassName, "inner class name");
                checkClassName(outerClassName, "outer class name");
                // Store relationship between inner class and outer class
                if (classContainmentEntries == null) {
                    classContainmentEntries = new ArrayList<>();
                }
                classContainmentEntries
                        .add(new ClassContainment(innerClassName, innerClassAccessFlags, outerClassName));
            }
        }
    }

    /**
     * Read the class' {@code EnclosingMethod} attribute, which marks the class as a local or anonymous class, and
     * names the class and the method or constructor it is declared in.
     *
     * @throws IOException
     *             if an I/O exception occurs.
     * @throws ClassfileFormatException
     *             if the enclosing class or method name is missing, or the enclosing class name is illegal.
     */
    private void readEnclosingMethodAttribute() throws IOException, ClassfileFormatException {
        hasEnclosingMethodAttribute = true;
        final var innermostEnclosingClassName = requireConstantPoolString(
                getConstantPoolClassName(reader().readUnsignedShort()), "enclosing class name");
        final var enclosingMethodCpIdx = reader().readUnsignedShort();
        // An array class cannot enclose a class (JVMS 4.7.7). The JVM loads a class whose EnclosingMethod attribute
        // names one, so only the enclosing class and method are ignored.
        if (innermostEnclosingClassName.startsWith("[")) {
            return;
        }
        checkClassName(innermostEnclosingClassName, "enclosing class name");
        // A method_index of 0 means the class is declared in an initializer (a static or instance initializer
        // block, or a field initializer) rather than in a method or constructor (JVMS 4.7.7). The classfile does
        // not say which initializer, so no defining method name is recorded.
        if (enclosingMethodCpIdx != 0) {
            // method_index refers to a CONSTANT_NameAndType entry, which resolves to the method's name
            final var definingMethodName = requireConstantPoolString(getConstantPoolString(enclosingMethodCpIdx),
                    "enclosing method name");
            this.fullyQualifiedDefiningMethodName = innermostEnclosingClassName + "." + definingMethodName;
        }
        // Link local and anonymous classes to the class they are declared in
        if (classContainmentEntries == null) {
            classContainmentEntries = new ArrayList<>();
        }
        classContainmentEntries.add(new ClassContainment(className, classModifiers, innermostEnclosingClassName));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parses the classfile's header, constant pool, fields, methods and attributes, and, if scanning is extended
     * upward to external classes, schedules the external classes it refers to (superclasses, interfaces,
     * annotations and others) for scanning. The {@link ClassInfo} is created later, by {@link #link}. Assumes
     * classpath masking has already been performed, so that only one class of a given name will be added.
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
     */
    Classfile(final ClasspathElement classpathElement, final List<ClasspathElement> classpathOrder,
            final UnscannedModules unscannedModules, final Set<String> acceptedClassNamesFound,
            final Set<String> classNamesScheduledForExtendedScanning, final String relativePath,
            final Resource classfileResource, final boolean isExternalClass,
            final ConcurrentHashMap<String, String> stringInternMap,
            final WorkQueue<ClassfileScanWorkUnit> workQueue, final ScanSpec scanSpec, final @Nullable LogNode log)
            throws IOException, ClassfileFormatException, SkipClassException {
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
        try (var classfileReader = new RandomAccessOrSequentialReader(classfileResource.getVfsEntry(),
                ByteOrder.BIG_ENDIAN, MAX_CLASSFILE_LENGTH)) {
            reader = classfileReader;

            // Check magic number
            if (reader().readInt() != 0xCAFEBABE) {
                throw new ClassfileFormatException("Classfile does not have correct magic number");
            }

            // Read classfile minor and major version. The version is deliberately not checked, so that classes
            // compiled for a newer JDK than the one running the scan can still be scanned.
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

            // Release the reader and the constant pool, since nothing reads them after this point. A parsed
            // classfile waits in a queue until every classfile of the scan has been parsed, and only then is
            // linked, so keeping them would hold the constant pool of every class in the scan at the same time.
            // With cpCount set to 0, any later constant pool access fails the range check.
            reader = null;
            cpCount = 0;
            entryOffset = NO_CONSTANT_POOL_ENTRIES;
            entryTag = NO_CONSTANT_POOL_ENTRIES;
            indirectStringRefs = NO_CONSTANT_POOL_ENTRIES;
        }

        // Write class info to log
        final var subLog = logParsedClassfile(log);

        // Check if any superclasses, interfaces, annotations, enclosing classes, classes named in annotation
        // parameter values, or thrown exception types are external (non-accepted) classes that need to be
        // scheduled for scanning, so that all of the "upwards" direction of the class graph is scanned for any
        // accepted class, even if those classes are not themselves accepted (#261). This is always done: without
        // it, an accepted class whose supertypes are not accepted would have an incomplete class hierarchy.
        extendScanningUpwards(subLog);
        // If any external classes were found, schedule them for scanning
        if (additionalWorkUnits != null) {
            workQueue.addWorkUnits(additionalWorkUnits);
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
        if (skippedAttributeMessages != null) {
            for (final var skippedAttributeMessage : skippedAttributeMessages) {
                subLog.log(skippedAttributeMessage);
            }
        }
        return subLog;
    }
}
