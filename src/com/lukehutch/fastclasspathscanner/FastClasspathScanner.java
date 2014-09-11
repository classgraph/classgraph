package com.lukehutch.fastclasspathscanner;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Uber-fast, ultra-lightweight Java classpath scanner. Scans the classpath by parsing the classfile binary
 * format directly rather than by using reflection. (Reflection causes the classloader to load each class,
 * which can take an order of magnitude more time than parsing the classfile directly.)
 * 
 * This classpath scanner is able to scan directories and jar/zip files on the classpath to locate: (1)
 * classes that subclass a given class or one of its subclasses; (2) classes that implement an interface or
 * one of its subinterfaces; (3) classes that have a given annotation; and (4) file paths (even for
 * non-classfiles) anywhere on the classpath that match a given regexp.
 * 
 * 
 * Usage example (with Java 8 lambda expressions):
 * 
 * <code>
 *     new FastClasspathScanner(
 *           new String[] { "com.xyz.widget", "com.xyz.gizmo" })  // Whitelisted package prefixes to scan
 * 
 *       .matchSubclassesOf(DBModel.class,
 *           // c is a subclass of DBModel
 *           c -> System.out.println("Subclasses DBModel: " + c.getName()))
 * 
 *       .matchClassesImplementing(Runnable.class,
 *           // c is a class that implements Runnable
 *           c -> System.out.println("Implements Runnable: " + c.getName()))
 * 
 *       .matchClassesWithAnnotation(RestHandler.class,
 *           // c is a class annotated with @RestHandler
 *           c -> System.out.println("Has @RestHandler class annotation: " + c.getName()))
 * 
 * 
 *       .matchFilenamePattern("^template/.*\\.html",
 *           // templatePath is a path on the classpath that matches the above pattern;
 *           // inputStream is a stream opened on the file or zipfile entry
 *           // No need to close inputStream before exiting, it is closed by caller.
 *           (absolutePath, relativePath, inputStream) -> {
 *              try {
 *                  String template = IOUtils.toString(inputStream, "UTF-8");
 *                  System.out.println("Found template: " + absolutePath
 *                          + " (size " + template.length() + ")");
 *              } catch (IOException e) {
 *                  throw new RuntimeException(e);
 *              }
 *          })
 * 
 *       .scan();  // Actually perform the scan
 * </code>
 * 
 * Note that you need to pass a whitelist of package prefixes to scan into the constructor, and the ability
 * to detect that a class or interface extends another depends upon the entire ancestral path between the two
 * classes or interfaces having one of the whitelisted package prefixes.
 * 
 * The scanner also records the latest last-modified timestamp of any file or directory encountered, and you
 * can see if that latest last-modified timestamp has increased (indicating that something on the classpath
 * has been updated) by calling:
 * 
 * <code>
 *     boolean classpathContentsModified = fastClassPathScanner.classpathContentsModifiedSinceScan();
 * </code>
 * 
 * This can be used to enable dynamic class-reloading if something on the classpath is updated, for example
 * to support hot-replace of route handler classes in a webserver. The above call is several times faster
 * than the original call to scan(), since only modification timestamps need to be checked.
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * Inspired by: https://github.com/rmuller/infomas-asl/tree/master/annotation-detector
 * 
 * See also: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.4
 * 
 * Let me know if you find this useful!
 *
 * @author Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * @license MIT
 *
 *          The MIT License (MIT)
 *
 *          Copyright (c) 2014 Luke Hutchison
 * 
 *          Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 *          associated documentation files (the "Software"), to deal in the Software without restriction,
 *          including without limitation the rights to use, copy, modify, merge, publish, distribute,
 *          sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 *          furnished to do so, subject to the following conditions:
 * 
 *          The above copyright notice and this permission notice shall be included in all copies or
 *          substantial portions of the Software.
 * 
 *          THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 *          NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 *          NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 *          DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *          OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 * 
 */
public class FastClasspathScanner {

    /**
     * List of directory path prefixes to scan (produced from list of package prefixes passed into the
     * constructor)
     */
    private String[] pathsToScan;

    /**
     * The latest last-modified timestamp of any file, directory or sub-directory in the classpath, in millis
     * since the Unix epoch. Does not consider timestamps inside zipfiles/jarfiles, but the timestamp of the
     * zip/jarfile itself is considered.
     */
    private long lastModified = 0;

    /** A list of class matchers to call once all classes have been read in from classpath. */
    private ArrayList<ClassMatcher> classMatchers = new ArrayList<>();

    /**
     * A list of file path matchers to call when a directory or subdirectory on the classpath matches a given
     * regexp.
     */
    private ArrayList<FilePathMatcher> filePathMatchers = new ArrayList<>();

    /** A map from fully-qualified class name to the corresponding ClassInfo object. */
    private final HashMap<String, ClassInfo> classNameToClassInfo = new HashMap<>();

    /** A map from fully-qualified class name to the corresponding InterfaceInfo object. */
    private final HashMap<String, InterfaceInfo> interfaceNameToInterfaceInfo = new HashMap<>();

    /** Reverse mapping from annotation to classes that have the annotation */
    private final HashMap<String, ArrayList<String>> annotationToClasses = new HashMap<>();

    /** Reverse mapping from interface to classes that implement the interface */
    private final HashMap<String, ArrayList<String>> interfaceToClasses = new HashMap<>();

    // ------------------------------------------------------------------------------------------------------    

    /**
     * Initialize a classpath scanner, with a list of package prefixes to scan.
     * 
     * @param pacakagesToScan
     *            A list of package prefixes to scan.
     */
    public FastClasspathScanner(String[] pacakagesToScan) {
        this.pathsToScan = Stream.of(pacakagesToScan).map(p -> p.replace('.', '/') + "/")
                .toArray(String[]::new);
    }

    // ------------------------------------------------------------------------------------------------------    

    /** The method to run when a subclass of a specific class is found on the classpath. */
    @FunctionalInterface
    public interface SubclassMatchProcessor<T> {
        public void processMatch(Class<? extends T> matchingClass);
    }

    /**
     * Call the given ClassMatchProcessor if classes are found on the classpath that extend the specified
     * superclass.
     * 
     * @param superclass
     *            The superclass to match (i.e. the class that subclasses need to extend to match).
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    @SuppressWarnings("unchecked")
    public <T> FastClasspathScanner matchSubclassesOf(final Class<T> superclass,
            final SubclassMatchProcessor<T> classMatchProcessor) {
        if (superclass.isInterface()) {
            // No support yet for scanning for interfaces that extend other interfaces
            throw new IllegalArgumentException(superclass.getName()
                    + " is an interface, not a regular class");
        }
        if (superclass.isAnnotation()) {
            // No support yet for scanning for interfaces that extend other interfaces
            throw new IllegalArgumentException(superclass.getName()
                    + " is an annotation, not a regular class");
        }
        classMatchers.add(() -> {
            ClassInfo superclassInfo = classNameToClassInfo.get(superclass.getName());
            boolean foundMatches = false;
            if (superclassInfo != null) {
                // For all subclasses of the given superclass
                for (ClassInfo subclassInfo : superclassInfo.allSubclasses) {
                    try {
                        // Load class
                        Class<? extends T> klass = (Class<? extends T>) Class.forName(subclassInfo.name);
                        // Process match
                        classMatchProcessor.processMatch(klass);
                        foundMatches = true;
                    } catch (ClassNotFoundException | NoClassDefFoundError e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            if (!foundMatches) {
                // Log.info("No classes found with superclass " + superclass.getName());
            }
        });
        return this;
    }

    // ------------------------------------------------------------------------------------------------------    

    /** The method to run when a class implementing a specific interface is found on the classpath. */
    @FunctionalInterface
    public interface InterfaceMatchProcessor<T> {
        public void processMatch(Class<? extends T> matchingClass);
    }

    /**
     * Call the given ClassMatchProcessor if classes are found on the classpath that implement the specified
     * interface.
     * 
     * @param iface
     *            The interface to match (i.e. the interface that classes need to implement to match).
     * @param interfaceMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    @SuppressWarnings("unchecked")
    public <T> FastClasspathScanner matchClassesImplementing(final Class<T> iface,
            final InterfaceMatchProcessor<T> interfaceMatchProcessor) {
        if (!iface.isInterface()) {
            throw new IllegalArgumentException(iface.getName() + " is not an interface");
        }
        classMatchers.add(() -> {
            ArrayList<String> classesImplementingIface = interfaceToClasses.get(iface.getName());
            if (classesImplementingIface != null) {
                // For all classes implementing the given interface
                for (String implClass : classesImplementingIface) {
                    try {
                        // Load class
                        Class<? extends T> klass = (Class<? extends T>) Class.forName(implClass);
                        // Process match
                        interfaceMatchProcessor.processMatch(klass);
                    } catch (ClassNotFoundException | NoClassDefFoundError e) {
                        throw new RuntimeException(e);
                    }
                }
            } else {
                // Log.info("No classes found implementing interface " + iface.getName());
            }
        });
        return this;
    }

    // ------------------------------------------------------------------------------------------------------    

    /** The method to run when a class with the right matching annotation is found on the classpath. */
    @FunctionalInterface
    public interface ClassAnnotationMatchProcessor {
        public void processMatch(Class<?> matchingClass);
    }

    /**
     * Call the given ClassMatchProcessor if classes are found on the classpath that have the given
     * annotation.
     * 
     * @param annotation
     *            The class annotation to match.
     * @param classMatchProcessor
     *            the ClassMatchProcessor to call when a match is found.
     */
    public FastClasspathScanner matchClassesWithAnnotation(final Class<?> annotation,
            final ClassAnnotationMatchProcessor classMatchProcessor) {
        if (!annotation.isAnnotation()) {
            throw new IllegalArgumentException("Class " + annotation.getName() + " is not an annotation");
        }
        classMatchers.add(() -> {
            ArrayList<String> classesWithAnnotation = annotationToClasses.get(annotation.getName());
            if (classesWithAnnotation != null) {
                // For all classes with the given annotation
                for (String classWithAnnotation : classesWithAnnotation) {
                    try {
                        // Load class
                        Class<?> klass = Class.forName(classWithAnnotation);
                        // Process match
                        classMatchProcessor.processMatch(klass);
                    } catch (ClassNotFoundException | NoClassDefFoundError e) {
                        throw new RuntimeException(e);
                    }
                }
            } else {
                // Log.info("No classes found with annotation " + annotation.getName());
            }
        });
        return this;
    }

    // ------------------------------------------------------------------------------------------------------    

    /**
     * The method to run when a matching file is found on the classpath.
     */
    @FunctionalInterface
    public interface FileMatchProcessor {
        /**
         * Process a matching file.
         *  
         * @param absolutePath
         *              The path of the matching file on the filesystem. 
         * @param relativePath
         *              The path of the matching file relative to the classpath entry that contained the match.
         * @param inputStream
         *              An InputStream (either a FileInputStream or a ZipEntry InputStream) opened on the file.
         *              You do not need to close this InputStream before returning, it is closed by the caller.
         */
        public void processMatch(String absolutePath, String relativePath, InputStream inputStream);
    }

    /**
     * Call the given FileMatchProcessor if files are found on the classpath with the given regex pattern in
     * their path.
     * 
     * @param filenameMatchPattern
     *            The regex to match, e.g. "app/templates/.*\\.html"
     * @param fileMatchProcessor
     *            The FileMatchProcessor to call when each match is found.
     */
    public FastClasspathScanner matchFilenamePattern(final String filenameMatchPattern,
            final FileMatchProcessor fileMatchProcessor) {
        filePathMatchers.add(new FilePathMatcher(Pattern.compile(filenameMatchPattern), fileMatchProcessor));
        return this;
    }

    // ------------------------------------------------------------------------------------------------------    

    /** An interface used for testing if a file path matches a specified pattern. */
    private static class FilePathMatcher {
        Pattern pattern;
        FileMatchProcessor fileMatchProcessor;

        public FilePathMatcher(Pattern pattern, FileMatchProcessor fileMatchProcessor) {
            this.pattern = pattern;
            this.fileMatchProcessor = fileMatchProcessor;
        }
    }

    /** A functional interface used for testing if a class matches specified criteria. */
    @FunctionalInterface
    private static interface ClassMatcher {
        public abstract void lookForMatches();
    }

    // ------------------------------------------------------------------------------------------------------    

    /**
     * An object to hold class information. For speed purposes, this is reconstructed directly from the
     * classfile header without calling the classloader.
     */
    private static class ClassInfo {
        /** Class name */
        String name;

        /**
         * Set to true when this class is encountered in the classpath (false if the class is so far only
         * cited as a superclass)
         */
        boolean encountered;

        /** Direct superclass */
        ClassInfo directSuperclass;

        /** Direct subclasses */
        ArrayList<ClassInfo> directSubclasses = new ArrayList<>();

        /** All superclasses, including java.lang.Object. */
        HashSet<ClassInfo> allSuperclasses = new HashSet<>();

        /** All subclasses */
        HashSet<ClassInfo> allSubclasses = new HashSet<>();

        /** All interfaces */
        HashSet<String> interfaces = new HashSet<>();

        /** All annotations */
        HashSet<String> annotations = new HashSet<>();

        /** This class was encountered on the classpath. */
        public ClassInfo(String name, ArrayList<String> interfaces, HashSet<String> annotations) {
            this.name = name;
            this.encounter(interfaces, annotations);
        }

        /**
         * If called by another class, this class was previously cited as a superclass, and now has been
         * itself encountered on the classpath.
         */
        public void encounter(ArrayList<String> interfaces, HashSet<String> annotations) {
            this.encountered = true;
            this.interfaces.addAll(interfaces);
            this.annotations.addAll(annotations);
        }

        /** This class was referenced as a superclass of the given subclass. */
        public ClassInfo(String name, ClassInfo subclass) {
            this.name = name;
            this.encountered = false;
            addSubclass(subclass);
        }

        /** Connect this class to a subclass. */
        public void addSubclass(ClassInfo subclass) {
            if (subclass.directSuperclass != null && subclass.directSuperclass != this) {
                throw new RuntimeException(subclass.name + " has two superclasses: "
                        + subclass.directSuperclass.name + ", " + this.name);
            }
            subclass.directSuperclass = this;
            subclass.allSuperclasses.add(this);
            this.directSubclasses.add(subclass);
            this.allSubclasses.add(subclass);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Direct and ancestral interfaces of a given interface.
     */
    private static class InterfaceInfo {
        ArrayList<String> superInterfaces = new ArrayList<>();

        HashSet<String> allSuperInterfaces = new HashSet<>();

        public InterfaceInfo(ArrayList<String> superInterfaces) {
            this.superInterfaces.addAll(superInterfaces);
        }

    }

    // ------------------------------------------------------------------------------------------------------    

    /**
     * Recursively find all subclasses for each class; called by finalizeClassHierarchy.
     */
    private static void finalizeClassHierarchyRec(ClassInfo curr) {
        // DFS through subclasses
        for (ClassInfo subclass : curr.directSubclasses) {
            finalizeClassHierarchyRec(subclass);
        }
        // Postorder traversal of curr node to accumulate subclasses
        for (ClassInfo subclass : curr.directSubclasses) {
            curr.allSubclasses.addAll(subclass.allSubclasses);
        }
    }

    /**
     * Recursively find all superinterfaces of each interface; called by finalizeClassHierarchy.
     */
    private void finalizeInterfaceHierarchyRec(InterfaceInfo interfaceInfo) {
        // Interface inheritance is a DAG; don't double-visit nodes
        if (interfaceInfo.allSuperInterfaces.isEmpty() && !interfaceInfo.superInterfaces.isEmpty()) {
            interfaceInfo.allSuperInterfaces.addAll(interfaceInfo.superInterfaces);
            for (String iface : interfaceInfo.superInterfaces) {
                InterfaceInfo superinterfaceInfo = interfaceNameToInterfaceInfo.get(iface);
                if (superinterfaceInfo != null) {
                    finalizeInterfaceHierarchyRec(superinterfaceInfo);
                    // Merge all ancestral interfaces into list of all superinterfaces for this interface
                    interfaceInfo.allSuperInterfaces.addAll(superinterfaceInfo.allSuperInterfaces);
                }
            }
        }
    }

    /**
     * Find all superclasses and subclasses for each class once all classes have been read.
     */
    private void finalizeClassHierarchy() {
        if (classNameToClassInfo.isEmpty() && interfaceNameToInterfaceInfo.isEmpty()) {
            // If no classes or interfaces were matched, there is no hierarchy to build
            return;
        }

        // Find all root nodes (most classes and interfaces have java.lang.Object as a superclass)
        ArrayList<ClassInfo> roots = new ArrayList<>();
        for (ClassInfo classInfo : classNameToClassInfo.values()) {
            if (classInfo.directSuperclass == null) {
                roots.add(classInfo);
            }
        }

        // Accumulate all superclasses and interfaces along each branch of class hierarchy.
        // Traverse top down / breadth first from roots.
        LinkedList<ClassInfo> nodes = new LinkedList<>();
        nodes.addAll(roots);
        while (!nodes.isEmpty()) {
            ClassInfo head = nodes.removeFirst();

            if (head.directSuperclass != null) {
                // Accumulate superclasses from ancestral classes
                head.allSuperclasses.addAll(head.directSuperclass.allSuperclasses);
            }

            // Add subclasses to queue for BFS
            for (ClassInfo subclass : head.directSubclasses) {
                nodes.add(subclass);
            }
        }

        // Accumulate all subclasses along each branch of class hierarchy.
        // Traverse depth first, postorder from roots.
        for (ClassInfo root : roots) {
            finalizeClassHierarchyRec(root);
        }

        // Create reverse mapping from annotation to classes that have the annotation
        for (ClassInfo classInfo : classNameToClassInfo.values()) {
            for (String annotation : classInfo.annotations) {
                ArrayList<String> classList = annotationToClasses.get(annotation);
                if (classList == null) {
                    annotationToClasses.put(annotation, classList = new ArrayList<String>());
                }
                classList.add(classInfo.name);
            }
        }

        for (InterfaceInfo ii : interfaceNameToInterfaceInfo.values()) {
            finalizeInterfaceHierarchyRec(ii);
        }

        // Create reverse mapping from interface to classes that implement the interface
        for (ClassInfo classInfo : classNameToClassInfo.values()) {
            // Find all interfaces and superinterfaces of a class
            HashSet<String> interfaceAndSuperinterfaces = new HashSet<>();
            for (String iface : classInfo.interfaces) {
                interfaceAndSuperinterfaces.add(iface);
                InterfaceInfo ii = interfaceNameToInterfaceInfo.get(iface);
                if (ii != null) {
                    interfaceAndSuperinterfaces.addAll(ii.allSuperInterfaces);
                }
            }
            // Add a mapping from the interface or super-interface back to the class
            for (String iface : interfaceAndSuperinterfaces) {
                ArrayList<String> classList = interfaceToClasses.get(iface);
                if (classList == null) {
                    interfaceToClasses.put(iface, classList = new ArrayList<String>());
                }
                classList.add(classInfo.name);
            }
        }

        // Classes that subclass another class that implements an interface also implement that interface
        for (String iface : interfaceToClasses.keySet()) {
            ArrayList<String> classes = interfaceToClasses.get(iface);
            HashSet<String> subClasses = new HashSet<String>(classes);
            for (String klass : classes) {
                ClassInfo ci = classNameToClassInfo.get(klass);
                if (ci != null) {
                    for (ClassInfo subci : ci.allSubclasses) {
                        subClasses.add(subci.name);
                    }
                }
            }
            interfaceToClasses.put(iface, new ArrayList<>(subClasses));
        }
    }

    // ------------------------------------------------------------------------------------------------------    

    /**
     * Read annotation entry from classfile.
     */
    private String readAnnotation(final DataInputStream inp, Object[] constantPool) throws IOException {
        String annotationFieldDescriptor = readRefdString(inp, constantPool);
        String annotationClassName;
        if (annotationFieldDescriptor.charAt(0) == 'L'
                && annotationFieldDescriptor.charAt(annotationFieldDescriptor.length() - 1) == ';') {
            // Lcom/xyz/Annotation; -> com.xyz.Annotation
            annotationClassName = annotationFieldDescriptor.substring(1,
                    annotationFieldDescriptor.length() - 1).replace('/', '.');
        } else {
            // Should not happen
            annotationClassName = annotationFieldDescriptor;
        }
        int numElementValuePairs = inp.readUnsignedShort();
        for (int i = 0; i < numElementValuePairs; i++) {
            inp.skipBytes(2); // element_name_index
            readAnnotationElementValue(inp, constantPool);
        }
        return annotationClassName;
    }

    /**
     * Read annotation element value from classfile.
     */
    private void readAnnotationElementValue(final DataInputStream inp, Object[] constantPool)
            throws IOException {
        int tag = inp.readUnsignedByte();
        switch (tag) {
        case 'B':
        case 'C':
        case 'D':
        case 'F':
        case 'I':
        case 'J':
        case 'S':
        case 'Z':
        case 's':
            // const_value_index
            inp.skipBytes(2);
            break;
        case 'e':
            // enum_const_value
            inp.skipBytes(4);
            break;
        case 'c':
            // class_info_index
            inp.skipBytes(2);
            break;
        case '@':
            // Complex (nested) annotation
            readAnnotation(inp, constantPool);
            break;
        case '[':
            // array_value
            final int count = inp.readUnsignedShort();
            for (int l = 0; l < count; ++l) {
                // Nested annotation element value
                readAnnotationElementValue(inp, constantPool);
            }
            break;
        default:
            throw new ClassFormatError("Invalid annotation element type tag: 0x" + Integer.toHexString(tag));
        }
    }

    /**
     * Read a string reference from a classfile, then look up the string in the constant pool.
     */
    private static String readRefdString(DataInputStream inp, Object[] constantPool) throws IOException {
        int constantPoolIdx = inp.readUnsignedShort();
        Object constantPoolObj = constantPool[constantPoolIdx];
        return (constantPoolObj instanceof Integer ? (String) constantPool[(Integer) constantPoolObj]
                : (String) constantPoolObj);
    }

    /**
     * Directly examine contents of classfile binary header.
     */
    private void readClassInfoFromClassfileHeader(final InputStream inputStream) throws IOException {
        DataInputStream inp = new DataInputStream(new BufferedInputStream(inputStream, 1024));

        // Magic
        if (inp.readInt() != 0xCAFEBABE) {
            // Not classfile
            return;
        }

        // Minor version
        inp.readUnsignedShort();
        // Major version
        inp.readUnsignedShort();

        // Constant pool count (1-indexed, zeroth entry not used)
        int cpCount = inp.readUnsignedShort();
        // Constant pool
        Object[] constantPool = new Object[cpCount];
        for (int i = 1; i < cpCount; ++i) {
            final int tag = inp.readUnsignedByte();
            switch (tag) {
            case 1: // Modified UTF8
                constantPool[i] = inp.readUTF();
                break;
            case 3: // int
            case 4: // float
                inp.skipBytes(4);
                break;
            case 5: // long
            case 6: // double
                inp.skipBytes(8);
                i++; // double slot
                break;
            case 7: // Class
            case 8: // String
                // Forward or backward reference a Modified UTF8 entry
                constantPool[i] = inp.readUnsignedShort();
                break;
            case 9: // field ref
            case 10: // method ref
            case 11: // interface ref
            case 12: // name and type
                inp.skipBytes(4); // two shorts
                break;
            case 15: // method handle
                inp.skipBytes(3);
                break;
            case 16: // method type
                inp.skipBytes(2);
                break;
            case 18: // invoke dynamic
                inp.skipBytes(4);
                break;
            default:
                throw new ClassFormatError("Unkown tag value for constant pool entry: " + tag);
            }
        }

        // Access flags
        int flags = inp.readUnsignedShort();
        boolean isInterface = (flags & 0x0200) != 0;

        // This class name, with slashes replaced with dots
        String className = readRefdString(inp, constantPool).replace('/', '.');

        // Superclass name, with slashes replaced with dots
        String superclassName = readRefdString(inp, constantPool).replace('/', '.');

        // Interfaces
        int interfaceCount = inp.readUnsignedShort();
        ArrayList<String> interfaces = new ArrayList<>();
        for (int i = 0; i < interfaceCount; i++) {
            interfaces.add(readRefdString(inp, constantPool).replace('/', '.'));
        }

        // Fields
        int fieldCount = inp.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) {
            inp.skipBytes(6); // access_flags, name_index, descriptor_index
            int attributesCount = inp.readUnsignedShort();
            for (int j = 0; j < attributesCount; j++) {
                inp.skipBytes(2); // attribute_name_index
                int attributeLength = inp.readInt();
                inp.skipBytes(attributeLength);
            }
        }

        // Methods
        int methodCount = inp.readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            inp.skipBytes(6); // access_flags, name_index, descriptor_index
            int attributesCount = inp.readUnsignedShort();
            for (int j = 0; j < attributesCount; j++) {
                inp.skipBytes(2); // attribute_name_index
                int attributeLength = inp.readInt();
                inp.skipBytes(attributeLength);
            }
        }

        // Attributes (including class annotations)
        HashSet<String> annotations = new HashSet<>();
        int attributesCount = inp.readUnsignedShort();
        for (int i = 0; i < attributesCount; i++) {
            String attributeName = readRefdString(inp, constantPool);
            int attributeLength = inp.readInt();
            if ("RuntimeVisibleAnnotations".equals(attributeName)) {
                int annotationCount = inp.readUnsignedShort();
                for (int m = 0; m < annotationCount; m++) {
                    String annotationName = readAnnotation(inp, constantPool);
                    annotations.add(annotationName);
                }
            } else {
                inp.skipBytes(attributeLength);
            }
        }

        if (isInterface) {
            // Save the info recovered from the classfile for an interface

            // Look up InterfaceInfo object for this interface
            InterfaceInfo thisInterfaceInfo = interfaceNameToInterfaceInfo.get(className);
            if (thisInterfaceInfo == null) {
                // This interface has not been encountered before on the classpath 
                interfaceNameToInterfaceInfo.put(className,
                        thisInterfaceInfo = new InterfaceInfo(interfaces));
            } else {
                // An interface of this fully-qualified name has been encountered already earlier on
                // the classpath, so this interface is shadowed, ignore it 
                return;
            }

        } else {
            // Save the info recovered from the classfile for a class

            // Look up ClassInfo object for this class
            ClassInfo thisClassInfo = classNameToClassInfo.get(className);
            if (thisClassInfo == null) {
                // This class has not been encountered before on the classpath 
                classNameToClassInfo.put(className, thisClassInfo = new ClassInfo(className, interfaces,
                        annotations));
            } else if (thisClassInfo.encountered) {
                // A class of this fully-qualified name has been encountered already earlier on
                // the classpath, so this class is shadowed, ignore it 
                return;
            } else {
                // This is the first time this class has been encountered on the classpath, but
                // it was previously cited as a superclass of another class
                thisClassInfo.encounter(interfaces, annotations);
            }

            // Look up ClassInfo object for superclass, and connect it to this class
            ClassInfo superclassInfo = classNameToClassInfo.get(superclassName);
            if (superclassInfo == null) {
                classNameToClassInfo.put(superclassName, superclassInfo = new ClassInfo(superclassName,
                        thisClassInfo));
            } else {
                superclassInfo.addSubclass(thisClassInfo);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------    

    /**
     * Scan a file.
     */
    private void scanFile(File file, String absolutePath, String relativePath, boolean scanTimestampsOnly) throws IOException {
        lastModified = Math.max(lastModified, file.lastModified());
        if (!scanTimestampsOnly) {
            if (relativePath.endsWith(".class")) {
                // Found a classfile
                try (InputStream inputStream = new FileInputStream(file)) {
                    // Inspect header of classfile
                    readClassInfoFromClassfileHeader(inputStream);
                }
            } else {
                // For non-classfiles, match file paths against path patterns
                for (FilePathMatcher fileMatcher : filePathMatchers) {
                    if (fileMatcher.pattern.matcher(relativePath).matches()) {
                        // If there's a match, open the file as a stream and call the match processor
                        try (InputStream inputStream = new FileInputStream(file)) {
                            fileMatcher.fileMatchProcessor.processMatch(absolutePath, relativePath, inputStream);
                        }
                    }
                }
            }
        }
    }

    /**
     * Scan a directory for matching file path patterns.
     */
    private void scanDir(File dir, int ignorePrefixLen, boolean scanTimestampsOnly) throws IOException {
        String absolutePath = dir.getPath();
        String relativePath = ignorePrefixLen > absolutePath.length() ? "" : absolutePath.substring(ignorePrefixLen);
        boolean scanDirs = false, scanFiles = false;
        for (String pathToScan : pathsToScan) {
            if (relativePath.startsWith(pathToScan) || //
                    (relativePath.length() == pathToScan.length() - 1 && pathToScan.startsWith(relativePath))) {
                // In a path that has a whitelisted path as a prefix -- can start scanning files
                scanDirs = scanFiles = true;
                break;
            }
            if (pathToScan.startsWith(relativePath)) {
                // In a path that is a prefix of a whitelisted path -- keep recursively scanning dirs
                scanDirs = true;
            }
        }
        if (scanDirs || scanFiles) {
            lastModified = Math.max(lastModified, dir.lastModified());
            File[] subFiles = dir.listFiles();
            for (final File subFile : subFiles) {
                if (subFile.isDirectory()) {
                    // Recurse into subdirectory
                    scanDir(subFile, ignorePrefixLen, scanTimestampsOnly);
                } else if (scanFiles && subFile.isFile()) {
                    // Scan file
                    String leafSuffix = "/" + subFile.getName();
                    scanFile(subFile, absolutePath + leafSuffix, relativePath + leafSuffix, scanTimestampsOnly);
                }
            }
        }
    }

    /**
     * Scan a zipfile for matching file path patterns. (Does not recurse into zipfiles within zipfiles.)
     */
    private void scanZipfile(final String zipfilePath, final ZipFile zipFile, boolean scanTimestampsOnly)
            throws IOException {
        boolean timestampWarning = false;
        for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            // Scan for matching filenames
            final ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                // Only process file entries (zipfile indices contain both directory entries and
                // separate file entries for files within each directory, in lexicographic order)
                String path = entry.getName();
                boolean scanFile = false;
                for (String pathToScan : pathsToScan) {
                    if (path.startsWith(pathToScan)) {
                        // File path has a whitelisted path as a prefix -- can scan file
                        scanFile = true;
                        break;
                    }
                }
                if (scanFile) {
                    // Assumes that the clock used to timestamp zipfile entries is in sync with the
                    // clock used to timestamp regular file and directory entries in the classpath.
                    // Just in case, we check entry timestamps against the current time.
                    long entryTime = entry.getTime();
                    lastModified = Math.max(lastModified, entryTime);
                    if (entryTime > System.currentTimeMillis() && !timestampWarning) {
                        String msg = zipfilePath + " contains modification timestamps after the current time";
                        // Log.warning(msg);
                        System.err.println(msg);
                        // Only warn once
                        timestampWarning = true;
                    }
                    if (!scanTimestampsOnly) {
                        if (path.endsWith(".class")) {
                            // Found a classfile, open it as a stream and inspect header
                            try (InputStream inputStream = zipFile.getInputStream(entry)) {
                                readClassInfoFromClassfileHeader(inputStream);
                            }
                        } else {
                            // For non-classfiles, match file paths against path patterns
                            for (FilePathMatcher fileMatcher : filePathMatchers) {
                                if (fileMatcher.pattern.matcher(path).matches()) {
                                    // There's a match, open the file as a stream and call the match processor
                                    try (InputStream inputStream = zipFile.getInputStream(entry)) {
                                        fileMatcher.fileMatchProcessor.processMatch(path, path, inputStream);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------    

    /**
     * Get a list of unique elements on the classpath as File objects, preserving order.
     * Classpath elements that do not exist are not returned.
     */
    public static ArrayList<File> getUniqueClasspathElements() {
        String[] pathElements = System.getProperty("java.class.path").split(File.pathSeparator);
        HashSet<String> pathElementsSet = new HashSet<>();
        ArrayList<File> pathFiles = new ArrayList<>();
        for (String pathElement : pathElements) {
            if (pathElementsSet.add(pathElement)) {
                File file = new File(pathElement);
                if (file.exists()) {
                    pathFiles.add(file);
                }
            }
        }
        return pathFiles;
    }

    /**
     * Scan classpath for matching files. Call this after all match processors have been added.
     */
    private void scan(boolean scanTimestampsOnly) {
        // long scanStart = System.currentTimeMillis();

        if (!scanTimestampsOnly) {
            classNameToClassInfo.clear();
            interfaceNameToInterfaceInfo.clear();
            annotationToClasses.clear();
            interfaceToClasses.clear();
        }

        try {
            // Iterate through path elements and recursively scan within each directory and zipfile
            for (File pathElt : getUniqueClasspathElements()) {
                String path = pathElt.getPath();
                if (pathElt.isDirectory()) {
                    // Scan within dir path element
                    scanDir(pathElt, path.length() + 1, scanTimestampsOnly);
                } else if (pathElt.isFile()) {
                    String pathLower = path.toLowerCase();
                    if (pathLower.endsWith(".jar") || pathLower.endsWith(".zip")) {
                        // Scan within jar/zipfile path element
                        scanZipfile(path, new ZipFile(pathElt), scanTimestampsOnly);
                    } else {
                        // File listed directly on classpath
                        scanFile(pathElt, path, pathElt.getName(), scanTimestampsOnly);

                        for (FilePathMatcher fileMatcher : filePathMatchers) {
                            if (fileMatcher.pattern.matcher(path).matches()) {
                                // If there's a match, open the file as a stream and call the match processor
                                try (InputStream inputStream = new FileInputStream(pathElt)) {
                                    fileMatcher.fileMatchProcessor.processMatch(path, pathElt.getName(),
                                            inputStream);
                                }
                            }
                        }
                    }
                } else {
                    // Log.info("Skipping non-file/non-dir on classpath: " + file.getCanonicalPath());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (!scanTimestampsOnly) {
            // Finalize class hierarchy, then look for class matches
            finalizeClassHierarchy();
            for (ClassMatcher classMatcher : classMatchers) {
                classMatcher.lookForMatches();
            }
        }
        // Log.info("Classpath " + (scanTimestampsOnly ? "timestamp " : "") + "scanning took: "
        //      + (System.currentTimeMillis() - scanStart) + " ms");
    }

    /**
     * Scan classpath for matching files. Call this after all match processors have been added.
     */
    public void scan() {
        scan(/* scanTimestampsOnly = */false);
    }

    /**
     * Returns true if the classpath contents have been changed since scan() was last called. Only considers
     * classpath prefixes whitelisted in the call to the constructor.
     */
    public boolean classpathContentsModifiedSinceScan() {
        long lastModified = this.lastModified;
        scan(/* scanTimestampsOnly = */true);
        return this.lastModified > lastModified;
    }
}
