package io.github.lukehutch.fastclasspathscanner.classgraph;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec;
import io.github.lukehutch.fastclasspathscanner.utils.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClassfileBinaryParser {
    /**
     * Read annotation entry from classfile.
     */
    private static String readAnnotation(final DataInputStream inp, final Object[] constantPool) throws IOException {
        final String annotationFieldDescriptor = readRefdString(inp, constantPool);
        String annotationClassName;
        if (annotationFieldDescriptor.charAt(0) == 'L'
                && annotationFieldDescriptor.charAt(annotationFieldDescriptor.length() - 1) == ';') {
            // Lcom/xyz/Annotation; -> com.xyz.Annotation
            annotationClassName = annotationFieldDescriptor.substring(1, annotationFieldDescriptor.length() - 1)
                    .replace('/', '.');
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
            // System.err.println("Invalid annotation element type tag: 0x" + Integer.toHexString(tag));
            break;
        }
    }

    /**
     * Read as usigned short constant pool reference, then look up the string in the constant pool.
     */
    private static String readRefdString(final DataInputStream inp, final Object[] constantPool) //
            throws IOException {
        return (String) constantPool[inp.readUnsignedShort()];
    }

    /**
     * Find whitelisted (non-blacklisted) type names in the given type descriptor, and add them to the set of
     * whitelisted field types.
     */
    private static HashSet<String> findWhitelistedTypeDescriptorParts(final String typeDescriptor,
            final ScanSpec scanSpec, HashSet<String> whitelistedFieldTypes) {
        // Check if the type of this field falls within a whitelisted (non-blacklisted) package,
        // and if so, record the field and its type
        final Matcher matcher = TYPE_PARAM_PATTERN.matcher(typeDescriptor);
        while (matcher.find()) {
            final String descriptorPart = matcher.group(2);
            if (scanSpec.pathIsWhitelisted(descriptorPart)) {
                if (whitelistedFieldTypes == null) {
                    whitelistedFieldTypes = new HashSet<>();
                }
                // Convert from type path to class name
                final String fieldTypeName = descriptorPart.replace('/', '.');
                // Add field type to set of whitelisted field types encountered in class
                whitelistedFieldTypes.add(fieldTypeName);
            }
        }
        return whitelistedFieldTypes;
    }

    /**
     * Directly examine contents of classfile binary header to determine annotations, implemented interfaces, the
     * super-class etc.
     * 
     * @return the information obtained as a ClassInfo object, or null if the classfile is invalid.
     */
    public static ClassInfo readClassInfoFromClassfileHeader(final String relativePath,
            final InputStream inputStream, final HashMap<String, HashMap<String, StaticFinalFieldMatchProcessor>> // 
            classNameToStaticFieldnameToMatchProcessor, final ScanSpec scanSpec) {

        try (final DataInputStream inp = new DataInputStream(new BufferedInputStream(inputStream, 8192))) {
            // Magic number
            if (inp.readInt() != 0xCAFEBABE) {
                if (FastClasspathScanner.verbose) {
                    Log.log("File does not have correct classfile magic number: " + relativePath);
                }
                return null;
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
            final String classNamePath = readRefdString(inp, constantPool);
            final String className = classNamePath.replace('/', '.');

            // Superclass name, with slashes replaced with dots
            String superclassName = readRefdString(inp, constantPool);
            if (superclassName != null) {
                superclassName = superclassName.replace('/', '.');
            }

            if (className.equals("java.lang.Object")) {
                // Don't return java.lang.Object
                return null;
            }

            // Make sure classname matches relative path
            if (!className.equals(relativePath.substring(0, relativePath.length() - 6 /* (strip off ".class") */)
                    .replace('/', '.'))) {
                if (FastClasspathScanner.verbose) {
                    Log.log("Class " + className + " is at incorrect relative path " + relativePath
                            + " -- ignoring");
                }
                return null;
            }

            // Allocate result object
            final ClassInfo classInfo = new ClassInfo( //
                    className,
                    // Annotations are marked as both interfaces and annotations
                    /* isInterface = */isInterface && !isAnnotation,
                    /* isAnnotation = */isAnnotation, //
                    superclassName);

            // Interfaces
            final int interfaceCount = inp.readUnsignedShort();
            classInfo.interfaceNames = interfaceCount > 0 ? new ArrayList<String>(interfaceCount) : null;
            for (int i = 0; i < interfaceCount; i++) {
                classInfo.interfaceNames.add(readRefdString(inp, constantPool).replace('/', '.'));
            }

            // Fields
            final HashMap<String, StaticFinalFieldMatchProcessor> staticFieldnameToMatchProcessor //
            = classNameToStaticFieldnameToMatchProcessor.get(classInfo.className);
            HashSet<String> whitelistedFieldTypes = null;
            final int fieldCount = inp.readUnsignedShort();
            for (int i = 0; i < fieldCount; i++) {
                final int accessFlags = inp.readUnsignedShort();
                // See http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6
                final boolean isStaticFinal = (accessFlags & 0x0018) == 0x0018;
                final String fieldName = readRefdString(inp, constantPool);
                final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor //
                = staticFieldnameToMatchProcessor != null ? staticFieldnameToMatchProcessor.get(fieldName) : null;
                final String fieldTypeDescriptor = readRefdString(inp, constantPool);
                final int attributesCount = inp.readUnsignedShort();

                // Check if the type of this field falls within a whitelisted (non-blacklisted) package,
                // and if so, record the field and its type
                whitelistedFieldTypes = findWhitelistedTypeDescriptorParts(fieldTypeDescriptor, scanSpec,
                        whitelistedFieldTypes);

                // Check if field is static and final
                if (!isStaticFinal && staticFinalFieldMatchProcessor != null) {
                    // Requested to match a field that is not static or not final
                    System.err.println(StaticFinalFieldMatchProcessor.class.getSimpleName()
                            + ": cannot match requested field " + classInfo.className + "." + fieldName
                            + " because it is either not static or not final");
                }
                // See if field name matches one of the requested names for this class, and if it does,
                // check if it is initialized with a constant value
                boolean foundConstantValue = false;
                for (int j = 0; j < attributesCount; j++) {
                    final String attributeName = readRefdString(inp, constantPool);
                    final int attributeLength = inp.readInt();
                    if (attributeName.equals("ConstantValue") && isStaticFinal
                            && staticFinalFieldMatchProcessor != null) {
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
                            break;
                        default:
                            // Should never happen:
                            // constant values can only be stored as an int, long, float, double or String
                            break;
                        }
                        // Call static final field match processor
                        if (FastClasspathScanner.verbose) {
                            Log.log("Found static final field " + classInfo.className + "." + fieldName + " = "
                                    + constValue);
                        }
                        staticFinalFieldMatchProcessor.processMatch(classInfo.className, fieldName, constValue);
                        foundConstantValue = true;
                    } else if (attributeName.equals("Signature")) {
                        // Check if the type signature of this field falls within a whitelisted (non-blacklisted)
                        // package, and if so, record the field type. The type signature contains type parameters,
                        // whereas the type descriptor does not.
                        final String fieldTypeSignature = readRefdString(inp, constantPool);
                        whitelistedFieldTypes = findWhitelistedTypeDescriptorParts(fieldTypeSignature, scanSpec,
                                whitelistedFieldTypes);
                    } else {
                        inp.skipBytes(attributeLength);
                    }
                    if (!foundConstantValue && isStaticFinal && staticFinalFieldMatchProcessor != null) {
                        System.err.println(StaticFinalFieldMatchProcessor.class.getSimpleName()
                                + ": Requested static final field " + classInfo.className + "." + fieldName
                                + " is not initialized with a constant literal value, so there is no "
                                + "initializer value in the constant pool of the classfile");
                    }
                }
            }
            classInfo.whitelistedFieldTypes = whitelistedFieldTypes;

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
            classInfo.annotationNames = null;
            final int attributesCount = inp.readUnsignedShort();
            for (int i = 0; i < attributesCount; i++) {
                final String attributeName = readRefdString(inp, constantPool);
                final int attributeLength = inp.readInt();
                if ("RuntimeVisibleAnnotations".equals(attributeName)) {
                    final int annotationCount = inp.readUnsignedShort();
                    for (int m = 0; m < annotationCount; m++) {
                        final String annotationName = readAnnotation(inp, constantPool);
                        // Ignore java.lang.annotation annotations (Target/Retention/Documented etc.)
                        if (!annotationName.startsWith("java.lang.annotation.")) {
                            if (classInfo.annotationNames == null) {
                                classInfo.annotationNames = new ArrayList<>();
                            }
                            classInfo.annotationNames.add(annotationName);
                        }
                    }
                } else {
                    inp.skipBytes(attributeLength);
                }
            }
            return classInfo;

        } catch (final IOException e) {
            Log.log("IOException while attempting to load classfile " + relativePath + ": " + e.getMessage());
            return null;
        }
    }
}
