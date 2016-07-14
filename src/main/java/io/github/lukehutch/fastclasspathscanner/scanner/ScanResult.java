package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.classgraph.ClassGraphBuilder;
import io.github.lukehutch.fastclasspathscanner.utils.ThreadLog;

/** The result of a scan. */
public class ScanResult {
    private final ScanSpec scanSpec;

    /**
     * The file resources timestamped during a scan, along with their timestamp at the time of the scan. Includes
     * whitelisted files within directory classpath elements' hierarchy, and also whitelisted jarfiles (whose
     * timestamp represents the timestamp of all files within the jarfile).
     */
    private final Map<File, Long> fileToTimestamp;

    /** The max of the last modified times of all the timestamped resources. */
    private long maxLastModifiedTime = 0L;

    /** Map from classname to ClassInfo. */
    private final Map<String, ClassInfo> classNameToClassInfo;

    /** The class graph builder. */
    private final ClassGraphBuilder classGraphBuilder;

    // -------------------------------------------------------------------------------------------------------------

    public ScanResult(final ScanSpec scanSpec, final Map<String, ClassInfo> classNameToClassInfo,
            final Map<File, Long> fileToTimestamp, ThreadLog log) {
        this.scanSpec = scanSpec;
        this.classNameToClassInfo = classNameToClassInfo;

        // Build the class graph
        final long graphStartTime = System.nanoTime();
        this.classGraphBuilder = new ClassGraphBuilder(classNameToClassInfo);
        if (FastClasspathScanner.verbose) {
            log.log(1, "Built class graph", System.nanoTime() - graphStartTime);
        }

        // Find the max file last modified timestamp
        this.fileToTimestamp = fileToTimestamp;
        final long currTime = System.currentTimeMillis();
        for (final long timestamp : fileToTimestamp.values()) {
            if (timestamp > maxLastModifiedTime && timestamp < currTime) {
                maxLastModifiedTime = timestamp;
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Add a ClassInfoUnlinked object to the scan results, build a ClassInfo object from it, and cross-link the new
     * ClassInfo objects with the others added so far. N.B. This is not thread-safe, so this method should always be
     * called from the same thread.
     */
    void addClassInfoUnlinked(final ClassInfoUnlinked classInfoUnlinked) {
        classInfoUnlinked.link(classNameToClassInfo);
    }

    Map<String, ClassInfo> getClassNameToClassInfo() {
        return classNameToClassInfo;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Returns true if the classpath contents have been modified since the last scan. Checks the timestamps of files
     * and jarfiles encountered during the previous scan to see if they have changed. Does not perform a full scan,
     * so cannot detect the addition of files to whitelisted paths in regular (non-jar) directories -- you need to
     * perform a full scan to detect those changes. However, can detect the deletion of files from whitelisted
     * directories, and changes to the contents of jarfiles (since the timestamp of the whole jarfile changes).
     */
    public boolean classpathContentsModifiedSinceScan() {
        for (final Entry<File, Long> ent : fileToTimestamp.entrySet()) {
            if (ent.getKey().lastModified() != ent.getValue()) {
                return true;
            }
        }
        return false;
    }

    public long classpathContentsLastModifiedTime() {
        return maxLastModifiedTime;
    }

    // -------------------------------------------------------------------------------------------------------------
    // Classes

    /** Get the sorted unique names of all classes, interfaces and annotations found during the scan. */
    public List<String> getNamesOfAllClasses() {
        return classGraphBuilder.getNamesOfAllClasses();
    }

    /** Get the sorted unique names of all standard (non-interface/annotation) classes found during the scan. */
    public List<String> getNamesOfAllStandardClasses() {
        return classGraphBuilder.getNamesOfAllStandardClasses();
    }

    /** Return the sorted list of names of all subclasses of the named class. */
    public List<String> getNamesOfSubclassesOf(final String className) {
        return classGraphBuilder.getNamesOfSubclassesOf(className);
    }

    /**
     * Returns the names of classes on the classpath that extend the specified superclass. Should be called after
     * scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the scanner before
     * the call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public synchronized List<String> getNamesOfSubclassesOf(final Class<?> superclass) {
        return classGraphBuilder.getNamesOfSubclassesOf(scanSpec.getStandardClassName(superclass));
    }

    /** Return the sorted list of names of all superclasses of the named class. */
    public List<String> getNamesOfSuperclassesOf(final String className) {
        return classGraphBuilder.getNamesOfSuperclassesOf(className);
    }

    /**
     * Returns the names of classes on the classpath that are superclasses of the specified subclass. Should be
     * called after scan(), and returns matching classes whether or not a SubclassMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param subclass
     *            The subclass to match (i.e. the class that needs to extend a superclass for the superclass to
     *            match).
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public synchronized List<String> getNamesOfSuperclassesOf(final Class<?> subclass) {
        return getNamesOfSuperclassesOf(scanSpec.getStandardClassName(subclass));
    }

    /**
     * Return a sorted list of classes that have a field of the named type, where the field type is in a whitelisted
     * (non-blacklisted) package.
     */
    public List<String> getNamesOfClassesWithFieldOfType(final String fieldTypeName) {
        scanSpec.checkClassIsNotBlacklisted(fieldTypeName);
        return classGraphBuilder.getNamesOfClassesWithFieldOfType(fieldTypeName);
    }

    /**
     * Returns the names of classes that have a field of the given type. Returns classes that have fields with the
     * same type as the requested type, array fields with an element type that matches the requested type, and
     * fields of parameterized type that have a type parameter of the requested type. The field type must be
     * declared in a package that is whitelisted (and not blacklisted).
     */
    public synchronized List<String> getNamesOfClassesWithFieldOfType(final Class<?> fieldType) {
        final String fieldTypeName = fieldType.getName();
        return getNamesOfClassesWithFieldOfType(fieldTypeName);
    }

    // -------------------------------------------------------------------------------------------------------------
    // Interfaces

    /** Return the sorted unique names of all interface classes found during the scan. */
    public List<String> getNamesOfAllInterfaceClasses() {
        return classGraphBuilder.getNamesOfAllInterfaceClasses();
    }

    /** Return the sorted list of names of all subinterfaces of the named interface. */
    public List<String> getNamesOfSubinterfacesOf(final String interfaceName) {
        return classGraphBuilder.getNamesOfSubinterfacesOf(interfaceName);
    }

    /**
     * Returns the names of interfaces on the classpath that extend a given superinterface. Should be called after
     * scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching interfaces, just returns their
     * names.
     * 
     * @param superInterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public synchronized List<String> getNamesOfSubinterfacesOf(final Class<?> superInterface) {
        return getNamesOfSubinterfacesOf(scanSpec.getInterfaceName(superInterface));
    }

    /** Return the names of all superinterfaces of the named interface. */
    public List<String> getNamesOfSuperinterfacesOf(final String interfaceName) {
        return classGraphBuilder.getNamesOfSuperinterfacesOf(interfaceName);
    }

    /**
     * Returns the names of interfaces on the classpath that are superinterfaces of a given subinterface. Should be
     * called after scan(), and returns matching interfaces whether or not a SubinterfaceMatchProcessor was added to
     * the scanner before the call to scan(). Does not call the classloader on the matching interfaces, just returns
     * their names.
     * 
     * @param subInterface
     *            The superinterface to match (i.e. the interface that subinterfaces need to extend to match).
     * @return A list of the names of matching interfaces, or the empty list if none.
     */
    public synchronized List<String> getNamesOfSuperinterfacesOf(final Class<?> subInterface) {
        return getNamesOfSuperinterfacesOf(scanSpec.getInterfaceName(subInterface));
    }

    /** Return the sorted list of names of all classes implementing the named interface. */
    public List<String> getNamesOfClassesImplementing(final String interfaceName) {
        return classGraphBuilder.getNamesOfClassesImplementing(interfaceName);
    }

    /**
     * Returns the names of classes on the classpath that implement the specified interface or a subinterface, or
     * whose superclasses implement the specified interface or a sub-interface. Should be called after scan(), and
     * returns matching interfaces whether or not an InterfaceMatchProcessor was added to the scanner before the
     * call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterface
     *            The interface that classes need to implement to match.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public synchronized List<String> getNamesOfClassesImplementing(final Class<?> implementedInterface) {
        return getNamesOfClassesImplementing(scanSpec.getInterfaceName(implementedInterface));
    }

    /**
     * Returns the names of classes on the classpath that implement (or have superclasses that implement) all of the
     * specified interfaces or their subinterfaces. Should be called after scan(), and returns matching interfaces
     * whether or not an InterfaceMatchProcessor was added to the scanner before the call to scan(). Does not call
     * the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterfaceNames
     *            The name of the interfaces that classes need to implement.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public synchronized List<String> getNamesOfClassesImplementingAllOf(final String... implementedInterfaceNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (int i = 0; i < implementedInterfaceNames.length; i++) {
            final String implementedInterfaceName = implementedInterfaceNames[i];
            final List<String> namesOfImplementingClasses = getNamesOfClassesImplementing(implementedInterfaceName);
            if (i == 0) {
                classNames.addAll(namesOfImplementingClasses);
            } else {
                classNames.retainAll(namesOfImplementingClasses);
            }
        }
        return new ArrayList<>(classNames);
    }

    /**
     * Returns the names of classes on the classpath that implement (or have superclasses that implement) all of the
     * specified interfaces or their subinterfaces. Should be called after scan(), and returns matching interfaces
     * whether or not an InterfaceMatchProcessor was added to the scanner before the call to scan(). Does not call
     * the classloader on the matching classes, just returns their names.
     * 
     * @param implementedInterfaces
     *            The name of the interfaces that classes need to implement.
     * @return A list of the names of matching classes, or the empty list if none.
     */
    public synchronized List<String> getNamesOfClassesImplementingAllOf(final Class<?>... implementedInterfaces) {
        return getNamesOfClassesImplementingAllOf(scanSpec.getInterfaceNames(implementedInterfaces));
    }

    // -------------------------------------------------------------------------------------------------------------
    // Annotations

    /** Return the sorted unique names of all annotation classes found during the scan. */
    public List<String> getNamesOfAllAnnotationClasses() {
        return classGraphBuilder.getNamesOfAllAnnotationClasses();
    }

    /**
     * Return the sorted list of names of all standard classes or non-annotation interfaces with the named class
     * annotation or meta-annotation.
     */
    public List<String> getNamesOfClassesWithAnnotation(final String annotationName) {
        return classGraphBuilder.getNamesOfClassesWithAnnotation(annotationName);
    }

    /**
     * Returns the names of classes on the classpath that have the specified annotation. Should be called after
     * scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the scanner
     * before the call to scan(). Does not call the classloader on the matching classes, just returns their names.
     * 
     * @param annotation
     *            The class annotation.
     * @return A list of the names of classes with the class annotation, or the empty list if none.
     */
    public synchronized List<String> getNamesOfClassesWithAnnotation(final Class<?> annotation) {
        return getNamesOfClassesWithAnnotation(scanSpec.getAnnotationName(annotation));
    }

    /**
     * Returns the names of classes on the classpath that have all of the specified annotations. Should be called
     * after scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param annotationNames
     *            The annotation names.
     * @return A list of the names of classes that have all of the annotations, or the empty list if none.
     */
    public synchronized List<String> getNamesOfClassesWithAnnotationsAllOf(final String... annotationNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (int i = 0; i < annotationNames.length; i++) {
            final String annotationName = annotationNames[i];
            final List<String> namesOfClassesWithMetaAnnotation = getNamesOfClassesWithAnnotation(annotationName);
            if (i == 0) {
                classNames.addAll(namesOfClassesWithMetaAnnotation);
            } else {
                classNames.retainAll(namesOfClassesWithMetaAnnotation);
            }
        }
        return new ArrayList<>(classNames);
    }

    /**
     * Returns the names of classes on the classpath that have all of the specified annotations. Should be called
     * after scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param annotations
     *            The annotations.
     * @return A list of the names of classes that have all of the annotations, or the empty list if none.
     */
    public synchronized List<String> getNamesOfClassesWithAnnotationsAllOf(final Class<?>... annotations) {
        return getNamesOfClassesWithAnnotationsAllOf(scanSpec.getAnnotationNames(annotations));
    }

    /**
     * Returns the names of classes on the classpath that have any of the specified annotations. Should be called
     * after scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param annotationNames
     *            The annotation names.
     * @return A list of the names of classes that have one or more of the annotations, or the empty list if none.
     */
    public synchronized List<String> getNamesOfClassesWithAnnotationsAnyOf(final String... annotationNames) {
        final HashSet<String> classNames = new HashSet<>();
        for (final String annotationName : annotationNames) {
            classNames.addAll(getNamesOfClassesWithAnnotation(annotationName));
        }
        return new ArrayList<>(classNames);
    }

    /**
     * Returns the names of classes on the classpath that have any of the specified annotations. Should be called
     * after scan(), and returns matching classes whether or not a ClassAnnotationMatchProcessor was added to the
     * scanner before the call to scan(). Does not call the classloader on the matching classes, just returns their
     * names.
     * 
     * @param annotations
     *            The annotations.
     * @return A list of the names of classes that have one or more of the annotations, or the empty list if none.
     */
    public synchronized List<String> getNamesOfClassesWithAnnotationsAnyOf(final Class<?>... annotations) {
        return getNamesOfClassesWithAnnotationsAnyOf(scanSpec.getAnnotationNames(annotations));
    }

    /** Return the sorted list of names of all annotations and meta-annotations on the named class. */
    public List<String> getNamesOfAnnotationsOnClass(final String classOrInterfaceName) {
        return classGraphBuilder.getNamesOfAnnotationsOnClass(classOrInterfaceName);
    }

    /**
     * Return the names of all annotations and meta-annotations on the specified class or interface.
     * 
     * @param classOrInterface
     *            The class or interface.
     * @return A list of the names of annotations and meta-annotations on the class, or the empty list if none.
     */
    public synchronized List<String> getNamesOfAnnotationsOnClass(final Class<?> classOrInterface) {
        return getNamesOfAnnotationsOnClass(scanSpec.getClassOrInterfaceName(classOrInterface));
    }

    /** Return the sorted list of names of all meta-annotations on the named annotation. */
    public List<String> getNamesOfMetaAnnotationsOnAnnotation(final String annotationName) {
        return classGraphBuilder.getNamesOfMetaAnnotationsOnAnnotation(annotationName);
    }

    /**
     * Return the names of all meta-annotations on the specified annotation.
     * 
     * @param annotation
     *            The specified annotation.
     * @return A list of the names of meta-annotations on the specified annotation, or the empty list if none.
     */
    public synchronized List<String> getNamesOfMetaAnnotationsOnAnnotation(final Class<?> annotation) {
        return getNamesOfMetaAnnotationsOnAnnotation(scanSpec.getAnnotationName(annotation));
    }

    /** Return the names of all annotations that have the named meta-annotation. */
    public List<String> getNamesOfAnnotationsWithMetaAnnotation(final String metaAnnotationName) {
        return classGraphBuilder.getNamesOfAnnotationsWithMetaAnnotation(metaAnnotationName);
    }

    /**
     * Return the names of all annotations that are annotated with the specified meta-annotation.
     * 
     * @param metaAnnotation
     *            The specified meta-annotation.
     * @return A list of the names of annotations that are annotated with the specified meta annotation, or the
     *         empty list if none.
     */
    public synchronized List<String> getNamesOfAnnotationsWithMetaAnnotation(final Class<?> metaAnnotation) {
        return getNamesOfAnnotationsWithMetaAnnotation(scanSpec.getAnnotationName(metaAnnotation));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Generates a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * sizeX and sizeY parameters are the image output size to use (in inches) when GraphViz is asked to render the
     * .dot file.
     */
    public synchronized String generateClassGraphDotFile(final float sizeX, final float sizeY) {
        return classGraphBuilder.generateClassGraphDotFile(sizeX, sizeY);
    }

}
