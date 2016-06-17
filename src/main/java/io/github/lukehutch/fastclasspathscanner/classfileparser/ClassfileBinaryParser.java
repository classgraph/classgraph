package io.github.lukehutch.fastclasspathscanner.classfileparser;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.Log;

public class ClassfileBinaryParser {
    private ClassfileBinaryParser() {

    }

    /**
     * Read annotation entry from classfile.
     */
    private static String readAnnotation(final DataInputStream inp, final Object[] constantPool)
            throws IOException {
        // Lcom/xyz/Annotation -> Lcom.xyz.Annotation
        final String annotationFieldDescriptor = readRefdClassName(inp, constantPool);
        String annotationClassName;
        if (annotationFieldDescriptor.charAt(0) == 'L'
                && annotationFieldDescriptor.charAt(annotationFieldDescriptor.length() - 1) == ';') {
            // Lcom.xyz.Annotation; -> com.xyz.Annotation
            annotationClassName = annotationFieldDescriptor.substring(1, annotationFieldDescriptor.length() - 1);
        } else {
            // Should not happen
            annotationClassName = annotationFieldDescriptor;
        }
        final int numElementValuePairs = inp.readUnsignedShort();
        for (int i = 0; i < numElementValuePairs; i++) {
            inp.skipBytes(2); // element_name_index
            readAnnotationElementValue(inp, constantPool);
        }
        return annotationClassName;
    }

    /**
     * Split a type param into type pieces, e.g. "Ljava/util/Map<Lcom/xyz/fig/shape/Shape;Ljava/lang/Integer;>;" ->
     * ["java/util/Map", "com/xyz/fig/shape/Shape", "java/lang/Integer"]. Also removes array prefixes, e.g.
     * "[[[Lcom.xyz.Widget" -> ["com.xyz.Widget"].
     */
    private static final Pattern TYPE_PARAM_PATTERN = Pattern.compile("(^[\\[]*|[;<]+)[+-]?L([^;<>*]+)");

    /**
     * Read annotation element value from classfile.
     */
    private static void readAnnotationElementValue(final DataInputStream inp, final Object[] constantPool)
            throws IOException {
        final int tag = inp.readUnsignedByte();
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
        case 'c':
            // class_info_index
            inp.skipBytes(2);
            break;
        case 'e':
            // enum_const_value
            inp.skipBytes(4);
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
            // System.err.println("Invalid annotation element type tag: 0x" + Integer.toHexString(tag));
            break;
        }
    }

    /**
     * Read as String constant pool reference, then look up the string in the constant pool.
     */
    private static String readRefdString(final DataInputStream inp, final Object[] constantPool) //
            throws IOException {
        return (String) constantPool[inp.readUnsignedShort()];
    }

    /**
     * Read as String constant pool reference to class name path, then look up the string in the constant pool, and
     * replace all '/' with '.'.
     */
    private static String readRefdClassName(final DataInputStream inp, final Object[] constantPool) //
            throws IOException {
        final String refdString = readRefdString(inp, constantPool);
        return refdString == null ? null : refdString.replace('/', '.');
    }

    /**
     * Find non-blacklisted type names in the given type descriptor, and add them to the set of field types.
     */
    private static void addFieldTypeDescriptorParts(final String className, final String typeDescriptor,
            final ScanSpec scanSpec, final ClassInfo classInfo,
            final HashMap<String, ClassInfo> classNameToClassInfo) {
        // Check if the type of this field falls within a non-blacklisted package,
        // and if so, record the field and its type
        final Matcher matcher = TYPE_PARAM_PATTERN.matcher(typeDescriptor);
        while (matcher.find()) {
            // Convert from type path to class name
            final String descriptorPart = matcher.group(2);
            final String fieldTypeName = descriptorPart.replace('/', '.');
            // Check blacklist, always blacklist common Java types for efficiency
            if (scanSpec.classIsNotBlacklisted(fieldTypeName) && !fieldTypeName.startsWith("java.lang.")
                    && !fieldTypeName.startsWith("java.util.")) {
                if (FastClasspathScanner.verbose) {
                    Log.log("Class " + className + " has a field with type or type parameter " + fieldTypeName);
                }
                // Add field type to set of non-blacklisted field types encountered in class
                classInfo.addFieldType(fieldTypeName, classNameToClassInfo);
            }
        }
    }

    /**
     * Directly examine contents of classfile binary header to determine annotations, implemented interfaces, the
     * super-class etc.
     * 
     * @return true, if this is the first time a class was encountered on the classpath.
     */
    public static void readClassInfoFromClassfileHeader(final String relativePath, final InputStream inputStream,
            final HashMap<String, HashSet<String>> classNameToStaticFinalFieldsToMatch, final ScanSpec scanSpec, //
            final HashMap<String, ClassInfo> classNameToClassInfo) {

        try (final DataInputStream inp = new DataInputStream(new BufferedInputStream(inputStream, 8192))) {
            // Magic number
            if (inp.readInt() != 0xCAFEBABE) {
                if (FastClasspathScanner.verbose) {
                    Log.log("File does not have correct classfile magic number: " + relativePath);
                }
                return;
            }

            // Minor version
            inp.readUnsignedShort();
            // Major version
            inp.readUnsignedShort();

            // Constant pool count (1-indexed, zeroth entry not used)
            final int cpCount = inp.readUnsignedShort();
            // Constant pool
            final Object[] constantPool = new Object[cpCount];
            final int[] indirectStringRef = new int[cpCount];
            Arrays.fill(indirectStringRef, -1);
            for (int i = 1; i < cpCount; ++i) {
                final int tag = inp.readUnsignedByte();
                switch (tag) {
                case 1: // Modified UTF8
                    constantPool[i] = inp.readUTF();
                    break;
                case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
                    constantPool[i] = inp.readInt();
                    break;
                case 4: // float
                    constantPool[i] = inp.readFloat();
                    break;
                case 5: // long
                    constantPool[i] = inp.readLong();
                    i++; // double slot
                    break;
                case 6: // double
                    constantPool[i] = inp.readDouble();
                    i++; // double slot
                    break;
                case 7: // Class
                case 8: // String
                    // Forward or backward indirect reference to a modified UTF8 entry
                    indirectStringRef[i] = inp.readUnsignedShort();
                    break;
                case 9: // field ref
                case 10: // method ref
                case 11: // interface ref
                case 12: // name and type
                    // two shorts
                case 18: // invoke dynamic
                    inp.skipBytes(4);
                    break;
                case 15: // method handle
                    inp.skipBytes(3);
                    break;
                case 16: // method type
                    inp.skipBytes(2);
                    break;
                default:
                    // System.err.println("Unkown tag value for constant pool entry: " + tag);
                    break;
                }
            }
            // Resolve indirection of string references now that all the strings have been read
            // (allows forward references to strings before they have been encountered)
            for (int i = 1; i < cpCount; i++) {
                if (indirectStringRef[i] >= 0) {
                    constantPool[i] = constantPool[indirectStringRef[i]];
                }
            }

            // Access flags
            final int flags = inp.readUnsignedShort();
            final boolean isInterface = (flags & 0x0200) != 0;
            final boolean isAnnotation = (flags & 0x2000) != 0;

            // The fully-qualified class name of this class, with slashes replaced with dots
            final String className = readRefdClassName(inp, constantPool);
            if (className.equals("java.lang.Object")) {
                // Don't process java.lang.Object
                return;
            }

            // Make sure classname matches relative path
            if (!className.equals(relativePath.substring(0, relativePath.length() - 6 /* (strip off ".class") */)
                    .replace('/', '.'))) {
                if (FastClasspathScanner.verbose) {
                    Log.log("Class " + className + " is at incorrect relative path " + relativePath
                            + " -- ignoring");
                }
                return;
            }

            final ClassInfo classInfo = ClassInfo.addScannedClass(className, isInterface, isAnnotation,
                    classNameToClassInfo);
            if (classInfo == null) {
                // This class was encountered more than once on the classpath -- ignore 2nd and subsequent defs 
                if (FastClasspathScanner.verbose) {
                    Log.log(className + " occurs more than once on classpath, ignoring all but first instance");
                }
                return;
            }

            // Superclass name, with slashes replaced with dots
            final String superclassName = readRefdClassName(inp, constantPool);

            if (FastClasspathScanner.verbose) {
                Log.log("Found " //
                        + (isAnnotation ? "annotation" : isInterface ? "interface" : "class") + " " + className //
                        + (superclassName == null || superclassName.equals("java.lang.Object") ? ""
                                : " with " + (isInterface && !isAnnotation ? "superinterface" : "superclass") + " "
                                        + superclassName));
            }

            // Connect class to superclass
            if (scanSpec.classIsNotBlacklisted(superclassName)) {
                classInfo.addSuperclass(superclassName, classNameToClassInfo);
            }

            // Interfaces
            final int interfaceCount = inp.readUnsignedShort();
            for (int i = 0; i < interfaceCount; i++) {
                final String interfaceName = readRefdClassName(inp, constantPool);
                if (scanSpec.classIsNotBlacklisted(interfaceName)) {
                    if (FastClasspathScanner.verbose) {
                        Log.log("Class " + className + " implements interface " + interfaceName);
                    }
                    classInfo.addImplementedInterface(interfaceName, classNameToClassInfo);
                }
            }

            // Fields
            final HashSet<String> staticFinalFieldsToMatch = classNameToStaticFinalFieldsToMatch
                    .get(classInfo.className);
            final int fieldCount = inp.readUnsignedShort();
            for (int i = 0; i < fieldCount; i++) {
                final int accessFlags = inp.readUnsignedShort();
                // See http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6
                final boolean isStaticFinal = (accessFlags & 0x0018) == 0x0018;
                final String fieldName = readRefdString(inp, constantPool);
                final boolean isMatchedFieldName = staticFinalFieldsToMatch != null
                        && staticFinalFieldsToMatch.contains(fieldName);
                final String fieldTypeDescriptor = readRefdString(inp, constantPool);
                final int attributesCount = inp.readUnsignedShort();

                // Check if the type of this field falls within a non-blacklisted package,
                // and if so, record the field and its type
                addFieldTypeDescriptorParts(className, fieldTypeDescriptor, scanSpec, classInfo,
                        classNameToClassInfo);

                // Check if field is static and final
                if (!isStaticFinal && isMatchedFieldName) {
                    // Requested to match a field that is not static or not final
                    Log.log("Cannot match requested field " + classInfo.className + "." + fieldName
                            + " because it is either not static or not final");
                }
                // See if field name matches one of the requested names for this class, and if it does,
                // check if it is initialized with a constant value
                boolean foundConstantValue = false;
                for (int j = 0; j < attributesCount; j++) {
                    final String attributeName = readRefdString(inp, constantPool);
                    final int attributeLength = inp.readInt();
                    if (isStaticFinal && isMatchedFieldName && attributeName.equals("ConstantValue")) {
                        // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.2
                        Object constValue = constantPool[inp.readUnsignedShort()];
                        // byte, char, short and boolean constants are all stored as 4-byte int
                        // values -- coerce and wrap in the proper wrapper class with autoboxing
                        switch (fieldTypeDescriptor) {
                        case "B":
                            // Convert byte store in Integer to Byte
                            constValue = ((Integer) constValue).byteValue();
                            break;
                        case "C":
                            // Convert char stored in Integer to Character
                            constValue = (char) ((Integer) constValue).intValue();
                            break;
                        case "S":
                            // Convert char stored in Integer to Short
                            constValue = ((Integer) constValue).shortValue();
                            break;
                        case "Z":
                            // Convert char stored in Integer to Boolean
                            constValue = ((Integer) constValue).intValue() != 0;
                            break;
                        case "I":
                        case "J":
                        case "F":
                        case "D":
                        case "Ljava.lang.String;":
                            // Field is int, long, float, double or String => object is already in correct
                            // wrapper type (Integer, Long, Float, Double or String), nothing to do
                        default:
                            // Should never happen:
                            // constant values can only be stored as an int, long, float, double or String
                            break;
                        }
                        // Store static final field match in ClassInfo object
                        if (FastClasspathScanner.verbose) {
                            Log.log("Class " + className + " has field " + fieldName
                                    + " with static constant initializer " + constValue);
                        }
                        classInfo.addFieldConstantValue(fieldName, constValue);
                        foundConstantValue = true;
                    } else if (attributeName.equals("Signature")) {
                        // Check if the type signature of this field falls within a non-blacklisted
                        // package, and if so, record the field type. The type signature contains
                        // type parameters, whereas the type descriptor does not.
                        final String fieldTypeSignature = readRefdString(inp, constantPool);
                        addFieldTypeDescriptorParts(className, fieldTypeSignature, scanSpec, classInfo,
                                classNameToClassInfo);
                    } else {
                        inp.skipBytes(attributeLength);
                    }
                    if (!foundConstantValue && isStaticFinal && isMatchedFieldName) {
                        Log.log("Requested static final field " + classInfo.className + "." + fieldName
                                + " is not initialized with a constant literal value, so there is no "
                                + "initializer value in the constant pool of the classfile");
                    }
                }
            }

            // Methods
            final int methodCount = inp.readUnsignedShort();
            for (int i = 0; i < methodCount; i++) {
                inp.skipBytes(6); // access_flags, name_index, descriptor_index
                final int attributesCount = inp.readUnsignedShort();
                for (int j = 0; j < attributesCount; j++) {
                    inp.skipBytes(2); // attribute_name_index
                    final int attributeLength = inp.readInt();
                    inp.skipBytes(attributeLength);
                }
            }

            // Attributes (including class annotations)
            final int attributesCount = inp.readUnsignedShort();
            for (int i = 0; i < attributesCount; i++) {
                final String attributeName = readRefdString(inp, constantPool);
                final int attributeLength = inp.readInt();
                if ("RuntimeVisibleAnnotations".equals(attributeName)) {
                    final int annotationCount = inp.readUnsignedShort();
                    for (int m = 0; m < annotationCount; m++) {
                        final String annotationName = readAnnotation(inp, constantPool);
                        // Add non-blacklisted annotations; always ignore java.lang.annotation annotations
                        // (Target/Retention/Documented etc.)
                        if (scanSpec.classIsNotBlacklisted(annotationName)
                                && !annotationName.startsWith("java.lang.annotation.")) {
                            if (FastClasspathScanner.verbose) {
                                Log.log("Class " + className + " has annotation " + annotationName);
                            }
                            classInfo.addAnnotation(annotationName, classNameToClassInfo);
                        }
                    }
                } else {
                    inp.skipBytes(attributeLength);
                }
            }

        } catch (final IOException e) {
            Log.log("IOException while attempting to load classfile " + relativePath + ": " + e.getMessage());
        }
    }
}
