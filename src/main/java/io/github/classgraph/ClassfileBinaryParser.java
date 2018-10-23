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

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import io.github.classgraph.utils.InputStreamOrByteBufferAdapter;
import io.github.classgraph.utils.LogNode;

/**
 * A classfile binary format parser. Implements its own buffering to avoid the overhead of using DataInputStream.
 * This class should only be used by a single thread at a time, but can be re-used to scan multiple classfiles in
 * sequence, to avoid re-allocating buffer memory.
 */
class ClassfileBinaryParser {
    /**
     * The InputStream for the current classfile. Set by each call to readClassInfoFromClassfileHeader().
     */
    private InputStreamOrByteBufferAdapter inputStreamOrByteBuffer;

    /**
     * The name of the current classfile. Determined early in the call to readClassInfoFromClassfileHeader().
     */
    private String className;

    // -------------------------------------------------------------------------------------------------------------

    /** The byte offset for the beginning of each entry in the constant pool. */
    private int[] offset;

    /** The tag (type) for each entry in the constant pool. */
    private int[] tag;

    /** The indirection index for String/Class entries in the constant pool. */
    private int[] indirectStringRefs;

    /**
     * Get the byte offset within the buffer of a string from the constant pool, or 0 for a null string.
     *
     * @param cpIdx
     *            the constant pool index
     * @param subFieldIdx
     *            should be 0 for CONSTANT_Utf8, CONSTANT_Class and CONSTANT_String, and for
     *            CONSTANT_NameAndType_info, fetches the name for value 0, or the type descriptor for value 1.
     */
    private int getConstantPoolStringOffset(final int cpIdx, final int subFieldIdx) {
        final int t = tag[cpIdx];
        if ((t != 12 && subFieldIdx != 0) || (t == 12 && subFieldIdx != 0 && subFieldIdx != 1)) {
            throw new RuntimeException(
                    "Bad subfield index " + subFieldIdx + " for tag " + t + ", cannot continue reading class. "
                            + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
        int cpIdxToUse;
        if (t == 1) {
            // CONSTANT_Utf8
            cpIdxToUse = cpIdx;
        } else if (t == 7 || t == 8) {
            // t == 7 => CONSTANT_Class, e.g. "[[I", "[Ljava/lang/Thread;" t == 8 => CONSTANT_String
            final int indirIdx = indirectStringRefs[cpIdx];
            if (indirIdx == -1) {
                // Should not happen
                throw new RuntimeException("Bad string indirection index, cannot continue reading class. "
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
            final int indirIdx = (subFieldIdx == 0 ? (compoundIndirIdx >> 16) : compoundIndirIdx) & 0xffff;
            if (indirIdx == 0) {
                // Should not happen
                throw new RuntimeException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this at https://github.com/classgraph/classgraph/issues");
            }
            cpIdxToUse = indirIdx;
        } else {
            throw new RuntimeException("Wrong tag number " + t + " at constant pool index " + cpIdx + ", "
                    + "cannot continue reading class. Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
        }
        return offset[cpIdxToUse];
    }

    /**
     * Get a string from the constant pool, optionally replacing '/' with '.'.
     */
    private String getConstantPoolString(final int cpIdx, final boolean replaceSlashWithDot,
            final boolean stripLSemicolon) {
        final int constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        return constantPoolStringOffset == 0 ? null
                : inputStreamOrByteBuffer.readString(constantPoolStringOffset, replaceSlashWithDot,
                        stripLSemicolon);
    }

    /**
     * Get a string from the constant pool.
     *
     * @param cpIdx
     *            the constant pool index
     * @param subFieldIdx
     *            should be 0 for CONSTANT_Utf8, CONSTANT_Class and CONSTANT_String, and for
     *            CONSTANT_NameAndType_info, fetches the name for value 0, or the type descriptor for value 1.
     */
    private String getConstantPoolString(final int cpIdx, final int subFieldIdx) {
        final int constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, subFieldIdx);
        return constantPoolStringOffset == 0 ? null
                : inputStreamOrByteBuffer.readString(constantPoolStringOffset, /* replaceSlashWithDot = */ false,
                        /* stripLSemicolon = */ false);
    }

    /** Get a string from the constant pool. */
    private String getConstantPoolString(final int cpIdx) {
        return getConstantPoolString(cpIdx, /* subFieldIdx = */ 0);
    }

    /**
     * Get the first UTF8 byte of a string in the constant pool, or '\0' if the string is null or empty.
     */
    private byte getConstantPoolStringFirstByte(final int cpIdx) {
        final int constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        if (constantPoolStringOffset == 0) {
            return '\0';
        }
        final int utfLen = inputStreamOrByteBuffer.readUnsignedShort(constantPoolStringOffset);
        if (utfLen == 0) {
            return '\0';
        }
        return inputStreamOrByteBuffer.buf[constantPoolStringOffset + 2];
    }

    /**
     * Get a string from the constant pool, and interpret it as a class name by replacing '/' with '.'.
     */
    private String getConstantPoolClassName(final int CpIdx) {
        return getConstantPoolString(CpIdx, /* replaceSlashWithDot = */ true, /* stripLSemicolon = */ false);
    }

    /**
     * Get a string from the constant pool representing an internal string descriptor for a class name
     * ("Lcom/xyz/MyClass;"), and interpret it as a class name by replacing '/' with '.', and removing the leading
     * "L" and the trailing ";".
     */
    private String getConstantPoolClassDescriptor(final int CpIdx) {
        return getConstantPoolString(CpIdx, /* replaceSlashWithDot = */ true, /* stripLSemicolon = */ true);
    }

    /**
     * Compare a string in the constant pool with a given constant, without constructing the String object.
     */
    private boolean constantPoolStringEquals(final int cpIdx, final String otherString) {
        final int strOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        if (strOffset == 0) {
            return otherString == null;
        } else if (otherString == null) {
            return false;
        }
        final int strLen = inputStreamOrByteBuffer.readUnsignedShort(strOffset);
        final int otherLen = otherString.length();
        if (strLen != otherLen) {
            return false;
        }
        final int strStart = strOffset + 2;
        for (int i = 0; i < strLen; i++) {
            if ((char) (inputStreamOrByteBuffer.buf[strStart + i] & 0xff) != otherString.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Get a field constant from the constant pool. */
    private Object getFieldConstantPoolValue(final int tag, final char fieldTypeDescriptorFirstChar,
            final int cpIdx) throws IOException {
        switch (tag) {
        case 1: // Modified UTF8
        case 7: // Class -- N.B. Unused? Class references do not seem to actually be stored as constant initalizers
        case 8: // String
            // Forward or backward indirect reference to a modified UTF8 entry
            return getConstantPoolString(cpIdx, /* subFieldIdx = */ 0);
        case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
        {
            final int intVal = inputStreamOrByteBuffer.readInt(offset[cpIdx]);
            switch (fieldTypeDescriptorFirstChar) {
            case 'I':
                return Integer.valueOf(intVal);
            case 'S':
                return Short.valueOf((short) intVal);
            case 'C':
                return Character.valueOf((char) intVal);
            case 'B':
                return Byte.valueOf((byte) intVal);
            case 'Z':
                return Boolean.valueOf(intVal != 0);
            default:
                throw new RuntimeException("Unknown Constant_INTEGER type " + fieldTypeDescriptorFirstChar + ", "
                        + "cannot continue reading class. Please report this at "
                        + "https://github.com/classgraph/classgraph/issues");
            }
        }
        case 4: // float
            return Float.valueOf(Float.intBitsToFloat(inputStreamOrByteBuffer.readInt(offset[cpIdx])));
        case 5: // long
            return Long.valueOf(inputStreamOrByteBuffer.readLong(offset[cpIdx]));
        case 6: // double
            return Double.valueOf(Double.longBitsToDouble(inputStreamOrByteBuffer.readLong(offset[cpIdx])));
        default:
            // ClassGraph doesn't expect other types
            // (N.B. in particular, enum values are not stored in the constant pool, so don't need to be handled)  
            throw new RuntimeException("Unknown constant pool tag " + tag + ", "
                    + "cannot continue reading class. Please report this at "
                    + "https://github.com/classgraph/classgraph/issues");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Read annotation entry from classfile. */
    private AnnotationInfo readAnnotation() throws IOException {
        // Lcom/xyz/Annotation; -> Lcom.xyz.Annotation;
        final String annotationClassName = getConstantPoolClassDescriptor(
                inputStreamOrByteBuffer.readUnsignedShort());
        final int numElementValuePairs = inputStreamOrByteBuffer.readUnsignedShort();
        AnnotationParameterValueList paramVals = null;
        if (numElementValuePairs > 0) {
            paramVals = new AnnotationParameterValueList(numElementValuePairs);
            for (int i = 0; i < numElementValuePairs; i++) {
                final String paramName = getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
                final Object paramValue = readAnnotationElementValue();
                paramVals.add(new AnnotationParameterValue(paramName, paramValue));
            }
        }
        return new AnnotationInfo(annotationClassName, paramVals);
    }

    /** Read annotation element value from classfile. */
    private Object readAnnotationElementValue() throws IOException {
        final int tag = (char) inputStreamOrByteBuffer.readUnsignedByte();
        switch (tag) {
        case 'B':
            return Byte.valueOf(
                    (byte) inputStreamOrByteBuffer.readInt(offset[inputStreamOrByteBuffer.readUnsignedShort()]));
        case 'C':
            return Character.valueOf(
                    (char) inputStreamOrByteBuffer.readInt(offset[inputStreamOrByteBuffer.readUnsignedShort()]));
        case 'D':
            return Double.valueOf(Double.longBitsToDouble(
                    inputStreamOrByteBuffer.readLong(offset[inputStreamOrByteBuffer.readUnsignedShort()])));
        case 'F':
            return Float.valueOf(Float.intBitsToFloat(
                    inputStreamOrByteBuffer.readInt(offset[inputStreamOrByteBuffer.readUnsignedShort()])));
        case 'I':
            return Integer
                    .valueOf(inputStreamOrByteBuffer.readInt(offset[inputStreamOrByteBuffer.readUnsignedShort()]));
        case 'J':
            return Long
                    .valueOf(inputStreamOrByteBuffer.readLong(offset[inputStreamOrByteBuffer.readUnsignedShort()]));
        case 'S':
            return Short.valueOf(
                    (short) inputStreamOrByteBuffer.readInt(offset[inputStreamOrByteBuffer.readUnsignedShort()]));
        case 'Z':
            return Boolean.valueOf(
                    inputStreamOrByteBuffer.readInt(offset[inputStreamOrByteBuffer.readUnsignedShort()]) != 0);
        case 's':
            return getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
        case 'e': {
            // Return type is AnnotatinEnumVal.
            final String className = getConstantPoolClassDescriptor(inputStreamOrByteBuffer.readUnsignedShort());
            final String constName = getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
            return new AnnotationEnumValue(className, constName);
        }
        case 'c':
            // Return type is AnnotationClassRef (for class references in annotations)
            final String classRefTypeDescriptor = getConstantPoolString(
                    inputStreamOrByteBuffer.readUnsignedShort());
            return new AnnotationClassRef(classRefTypeDescriptor);
        case '@':
            // Complex (nested) annotation. Return type is AnnotationInfo.
            return readAnnotation();
        case '[':
            // Return type is Object[] (of nested annotation element values)
            final int count = inputStreamOrByteBuffer.readUnsignedShort();
            final Object[] arr = new Object[count];
            for (int i = 0; i < count; ++i) {
                // Nested annotation element value
                arr[i] = readAnnotationElementValue();
            }
            return arr;
        default:
            throw new RuntimeException("Class " + className + " has unknown annotation element type tag '"
                    + ((char) tag) + "': element size unknown, cannot continue reading class. "
                    + "Please report this at https://github.com/classgraph/classgraph/issues");
        }
    }

    private static final AnnotationInfo[] NO_ANNOTATIONS = new AnnotationInfo[0];

    /**
     * Directly examine contents of classfile binary header to determine annotations, implemented interfaces, the
     * super-class etc. Creates a new ClassInfo object, and adds it to classNameToClassInfoOut. Assumes classpath
     * masking has already been performed, so that only one class of a given name will be added.
     */
    private ClassInfoUnlinked readClassfile(final ClasspathElement classpathElement, final String relativePath,
            final boolean isExternalClass, final Resource classfileResource, final ScanSpec scanSpec,
            final LogNode log) throws IOException {
        // Minor version
        inputStreamOrByteBuffer.readUnsignedShort();
        // Major version
        inputStreamOrByteBuffer.readUnsignedShort();

        // Read size of constant pool
        final int cpCount = inputStreamOrByteBuffer.readUnsignedShort();

        // Allocate storage for constant pool, or reuse storage if there's enough left from the previous scan
        if (offset == null || offset.length < cpCount) {
            offset = new int[cpCount];
            tag = new int[cpCount];
            indirectStringRefs = new int[cpCount];
        }
        Arrays.fill(indirectStringRefs, 0, cpCount, -1);

        // Read constant pool entries
        for (int i = 1; i < cpCount; ++i) {
            tag[i] = inputStreamOrByteBuffer.readUnsignedByte();
            offset[i] = inputStreamOrByteBuffer.curr;
            switch (tag[i]) {
            case 1: // Modified UTF8
                final int strLen = inputStreamOrByteBuffer.readUnsignedShort();
                inputStreamOrByteBuffer.skip(strLen);
                break;
            case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
            case 4: // float
                inputStreamOrByteBuffer.skip(4);
                break;
            case 5: // long
            case 6: // double
                inputStreamOrByteBuffer.skip(8);
                i++; // double slot
                break;
            case 7: // Class
            case 8: // String
                // Forward or backward indirect reference to a modified UTF8 entry
                indirectStringRefs[i] = inputStreamOrByteBuffer.readUnsignedShort();
                // (no need to copy bytes over, we use indirectStringRef instead for these fields)
                break;
            case 9: // field ref
            case 10: // method ref
            case 11: // interface ref
                inputStreamOrByteBuffer.skip(4);
                break;
            case 12: // name and type
                final int nameRef = inputStreamOrByteBuffer.readUnsignedShort();
                final int typeRef = inputStreamOrByteBuffer.readUnsignedShort();
                indirectStringRefs[i] = (nameRef << 16) | typeRef;
                break;
            case 15: // method handle
                inputStreamOrByteBuffer.skip(3);
                break;
            case 16: // method type
                inputStreamOrByteBuffer.skip(2);
                break;
            case 18: // invoke dynamic
                inputStreamOrByteBuffer.skip(4);
                break;
            case 19: // module (for module-info.class in JDK9+)
                // see https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.4
                inputStreamOrByteBuffer.skip(2);
                break;
            case 20: // package (for module-info.class in JDK9+)
                // see https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.4
                inputStreamOrByteBuffer.skip(2);
                break;
            default:
                throw new RuntimeException("Unknown constant pool tag " + tag[i] + " in classfile " + relativePath
                        + " (element size unknown, cannot continue reading class). Please report this at "
                        + "https://github.com/classgraph/classgraph/issues");
            }
        }

        // Modifier flags
        final int classModifierFlags = inputStreamOrByteBuffer.readUnsignedShort();
        final boolean isInterface = (classModifierFlags & 0x0200) != 0;
        final boolean isAnnotation = (classModifierFlags & 0x2000) != 0;
        final boolean isModule = (classModifierFlags & 0x8000) != 0;
        final boolean isPackage = relativePath.regionMatches(relativePath.lastIndexOf('/') + 1,
                "package-info.class", 0, 18);

        // The fully-qualified class name of this class, with slashes replaced with dots
        final String classNamePath = getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
        className = classNamePath.replace('/', '.');
        if ("java.lang.Object".equals(className)) {
            // Don't process java.lang.Object (it has a null superclass), though you can still search for classes
            // that are subclasses of java.lang.Object (as an external class).
            if (log != null) {
                log.log("Skipping " + className);
            }
            return null;
        }

        // Check class visibility modifiers
        if (!scanSpec.ignoreClassVisibility && !Modifier.isPublic(classModifierFlags) && !isModule && !isPackage) {
            if (log != null) {
                log.log("Skipping non-public class: " + className);
            }
            return null;
        }

        // Make sure classname matches relative path
        if (!relativePath.endsWith(".class")) {
            // Should not happen
            if (log != null) {
                log.log("File " + relativePath + " does not end in \".class\"");
            }
            return null;
        }
        final int len = classNamePath.length();
        if (relativePath.length() != len + 6 || !classNamePath.regionMatches(0, relativePath, 0, len)) {
            if (log != null) {
                log.log("Class " + className + " is at incorrect relative path " + relativePath + " -- ignoring");
            }
            return null;
        }

        // Superclass name, with slashes replaced with dots
        final int superclassNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
        final String superclassName = superclassNameCpIdx > 0 ? getConstantPoolClassName(superclassNameCpIdx)
                : null;

        // Create holder object for the class information. This is "unlinked", in the sense that it is
        // not linked other class info references at this point.
        final ClassInfoUnlinked classInfoUnlinked = new ClassInfoUnlinked(className, superclassName,
                classModifierFlags, isInterface, isAnnotation, isExternalClass, classpathElement,
                classfileResource);

        // Interfaces
        final int interfaceCount = inputStreamOrByteBuffer.readUnsignedShort();
        for (int i = 0; i < interfaceCount; i++) {
            final String interfaceName = getConstantPoolClassName(inputStreamOrByteBuffer.readUnsignedShort());
            classInfoUnlinked.addImplementedInterface(interfaceName);
        }

        // Fields
        final int fieldCount = inputStreamOrByteBuffer.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) {
            // Info on modifier flags: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.5
            final int fieldModifierFlags = inputStreamOrByteBuffer.readUnsignedShort();
            final boolean isPublicField = ((fieldModifierFlags & 0x0001) == 0x0001);
            final boolean isStaticFinalField = ((fieldModifierFlags & 0x0018) == 0x0018);
            final boolean fieldIsVisible = isPublicField || scanSpec.ignoreFieldVisibility;
            final boolean getStaticFinalFieldConstValue = scanSpec.enableStaticFinalFieldConstantInitializerValues
                    && isStaticFinalField && fieldIsVisible;
            if (!fieldIsVisible || (!scanSpec.enableFieldInfo && !getStaticFinalFieldConstValue)) {
                // Skip field
                inputStreamOrByteBuffer.readUnsignedShort(); // fieldNameCpIdx
                inputStreamOrByteBuffer.readUnsignedShort(); // fieldTypeDescriptorCpIdx
                final int attributesCount = inputStreamOrByteBuffer.readUnsignedShort();
                for (int j = 0; j < attributesCount; j++) {
                    inputStreamOrByteBuffer.readUnsignedShort(); // attributeNameCpIdx
                    final int attributeLength = inputStreamOrByteBuffer.readInt(); // == 2
                    inputStreamOrByteBuffer.skip(attributeLength);
                }
            } else {
                final int fieldNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                final String fieldName = getConstantPoolString(fieldNameCpIdx);
                final int fieldTypeDescriptorCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                final char fieldTypeDescriptorFirstChar = (char) getConstantPoolStringFirstByte(
                        fieldTypeDescriptorCpIdx);
                String fieldTypeDescriptor = null;
                String fieldTypeSignature = null;
                fieldTypeDescriptor = getConstantPoolString(fieldTypeDescriptorCpIdx);

                Object fieldConstValue = null;
                AnnotationInfoList fieldAnnotationInfo = null;
                final int attributesCount = inputStreamOrByteBuffer.readUnsignedShort();
                for (int j = 0; j < attributesCount; j++) {
                    final int attributeNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                    final int attributeLength = inputStreamOrByteBuffer.readInt(); // == 2
                    // See if field name matches one of the requested names for this class, and if it does,
                    // check if it is initialized with a constant value
                    if ((getStaticFinalFieldConstValue)
                            && constantPoolStringEquals(attributeNameCpIdx, "ConstantValue")) {
                        // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.2
                        final int cpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                        fieldConstValue = getFieldConstantPoolValue(tag[cpIdx], fieldTypeDescriptorFirstChar,
                                cpIdx);
                    } else if (fieldIsVisible && constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                        fieldTypeSignature = getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
                    } else if (scanSpec.enableAnnotationInfo //
                            && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                                    || (!scanSpec.disableRuntimeInvisibleAnnotations && constantPoolStringEquals(
                                            attributeNameCpIdx, "RuntimeInvisibleAnnotations")))) {
                        // Read annotation names
                        final int fieldAnnotationCount = inputStreamOrByteBuffer.readUnsignedShort();
                        if (fieldAnnotationInfo == null && fieldAnnotationCount > 0) {
                            fieldAnnotationInfo = new AnnotationInfoList(1);
                        }
                        if (fieldAnnotationInfo != null) {
                            for (int k = 0; k < fieldAnnotationCount; k++) {
                                final AnnotationInfo fieldAnnotation = readAnnotation();
                                fieldAnnotationInfo.add(fieldAnnotation);
                            }
                        }
                    } else {
                        // No match, just skip attribute
                        inputStreamOrByteBuffer.skip(attributeLength);
                    }
                }
                if (scanSpec.enableFieldInfo && fieldIsVisible) {
                    classInfoUnlinked.addFieldInfo(new FieldInfo(className, fieldName, fieldModifierFlags,
                            fieldTypeDescriptor, fieldTypeSignature, fieldConstValue, fieldAnnotationInfo));
                }
            }
        }

        // Methods
        final int methodCount = inputStreamOrByteBuffer.readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            // Info on modifier flags: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6
            final int methodModifierFlags = inputStreamOrByteBuffer.readUnsignedShort();
            final boolean isPublicMethod = ((methodModifierFlags & 0x0001) == 0x0001);
            final boolean methodIsVisible = isPublicMethod || scanSpec.ignoreMethodVisibility;

            String methodName = null;
            String methodTypeDescriptor = null;
            String methodTypeSignature = null;
            // Always enable MethodInfo for annotations (this is how annotation constants are defined)
            final boolean enableMethodInfo = scanSpec.enableMethodInfo || isAnnotation;
            if (enableMethodInfo || isAnnotation) { // Annotations store defaults in method_info
                final int methodNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                methodName = getConstantPoolString(methodNameCpIdx);
                final int methodTypeDescriptorCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                methodTypeDescriptor = getConstantPoolString(methodTypeDescriptorCpIdx);
            } else {
                inputStreamOrByteBuffer.skip(4); // name_index, descriptor_index
            }
            final int attributesCount = inputStreamOrByteBuffer.readUnsignedShort();
            String[] methodParameterNames = null;
            int[] methodParameterModifiers = null;
            AnnotationInfo[][] methodParameterAnnotations = null;
            AnnotationInfoList methodAnnotationInfo = null;
            boolean methodHasBody = false;
            if (!methodIsVisible || (!enableMethodInfo && !isAnnotation)) {
                // Skip method attributes
                for (int j = 0; j < attributesCount; j++) {
                    inputStreamOrByteBuffer.skip(2); // attribute_name_index
                    final int attributeLength = inputStreamOrByteBuffer.readInt();
                    inputStreamOrByteBuffer.skip(attributeLength);
                }
            } else {
                // Look for method annotations
                for (int j = 0; j < attributesCount; j++) {
                    final int attributeNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                    final int attributeLength = inputStreamOrByteBuffer.readInt();
                    if (scanSpec.enableAnnotationInfo
                            && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                                    || (!scanSpec.disableRuntimeInvisibleAnnotations && constantPoolStringEquals(
                                            attributeNameCpIdx, "RuntimeInvisibleAnnotations")))) {
                        final int methodAnnotationCount = inputStreamOrByteBuffer.readUnsignedShort();
                        if (methodAnnotationInfo == null && methodAnnotationCount > 0) {
                            methodAnnotationInfo = new AnnotationInfoList(1);
                        }
                        if (methodAnnotationInfo != null) {
                            for (int k = 0; k < methodAnnotationCount; k++) {
                                final AnnotationInfo annotationInfo = readAnnotation();
                                methodAnnotationInfo.add(annotationInfo);
                            }
                        }
                    } else if (scanSpec.enableAnnotationInfo
                            && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleParameterAnnotations")
                                    || (!scanSpec.disableRuntimeInvisibleAnnotations && constantPoolStringEquals(
                                            attributeNameCpIdx, "RuntimeInvisibleParameterAnnotations")))) {
                        final int paramCount = inputStreamOrByteBuffer.readUnsignedByte();
                        methodParameterAnnotations = new AnnotationInfo[paramCount][];
                        for (int k = 0; k < paramCount; k++) {
                            final int numAnnotations = inputStreamOrByteBuffer.readUnsignedShort();
                            methodParameterAnnotations[k] = numAnnotations == 0 ? NO_ANNOTATIONS
                                    : new AnnotationInfo[numAnnotations];
                            for (int l = 0; l < numAnnotations; l++) {
                                methodParameterAnnotations[k][l] = readAnnotation();
                            }
                        }
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "MethodParameters")) {
                        // Read method parameters. For Java, these are only produced in JDK8+, and only if the
                        // commandline switch `-parameters` is provided at compiletime.
                        final int paramCount = inputStreamOrByteBuffer.readUnsignedByte();
                        methodParameterNames = new String[paramCount];
                        methodParameterModifiers = new int[paramCount];
                        for (int k = 0; k < paramCount; k++) {
                            final int cpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                            // If the constant pool index is zero, then the parameter is unnamed => use null
                            methodParameterNames[k] = cpIdx == 0 ? null : getConstantPoolString(cpIdx);
                            methodParameterModifiers[k] = inputStreamOrByteBuffer.readUnsignedShort();
                        }
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                        // Add type params to method type signature
                        methodTypeSignature = getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort());
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "AnnotationDefault")) {
                        // Get annotation parameter default value
                        final Object annotationParamDefaultValue = readAnnotationElementValue();
                        classInfoUnlinked.addAnnotationParamDefaultValue(
                                new AnnotationParameterValue(methodName, annotationParamDefaultValue));
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "Code")) {
                        methodHasBody = true;
                        inputStreamOrByteBuffer.skip(attributeLength);
                    } else {
                        inputStreamOrByteBuffer.skip(attributeLength);
                    }
                }
                // Create MethodInfo
                if (enableMethodInfo) {
                    classInfoUnlinked.addMethodInfo(new MethodInfo(className, methodName, methodAnnotationInfo,
                            methodModifierFlags, methodTypeDescriptor, methodTypeSignature, methodParameterNames,
                            methodParameterModifiers, methodParameterAnnotations, methodHasBody));
                }
            }
        }

        // Attributes (including class annotations, class type variables, etc.)
        final int attributesCount = inputStreamOrByteBuffer.readUnsignedShort();
        for (int i = 0; i < attributesCount; i++) {
            final int attributeNameCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
            final int attributeLength = inputStreamOrByteBuffer.readInt();
            if (scanSpec.enableAnnotationInfo //
                    && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                            || (!scanSpec.disableRuntimeInvisibleAnnotations && constantPoolStringEquals(
                                    attributeNameCpIdx, "RuntimeInvisibleAnnotations")))) {
                final int annotationCount = inputStreamOrByteBuffer.readUnsignedShort();
                for (int m = 0; m < annotationCount; m++) {
                    final AnnotationInfo classAnnotation = readAnnotation();
                    classInfoUnlinked.addClassAnnotation(classAnnotation);
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "InnerClasses")) {
                final int numInnerClasses = inputStreamOrByteBuffer.readUnsignedShort();
                for (int j = 0; j < numInnerClasses; j++) {
                    final int innerClassInfoCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                    final int outerClassInfoCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                    if (innerClassInfoCpIdx != 0 && outerClassInfoCpIdx != 0) {
                        classInfoUnlinked.addClassContainment(getConstantPoolClassName(innerClassInfoCpIdx),
                                getConstantPoolClassName(outerClassInfoCpIdx));
                    }
                    inputStreamOrByteBuffer.skip(2); // inner_name_idx
                    inputStreamOrByteBuffer.skip(2); // inner_class_access_flags
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                // Get class type signature, including type variables
                classInfoUnlinked
                        .addTypeSignature(getConstantPoolString(inputStreamOrByteBuffer.readUnsignedShort()));
            } else if (constantPoolStringEquals(attributeNameCpIdx, "EnclosingMethod")) {
                final String innermostEnclosingClassName = getConstantPoolClassName(
                        inputStreamOrByteBuffer.readUnsignedShort());
                final int enclosingMethodCpIdx = inputStreamOrByteBuffer.readUnsignedShort();
                String definingMethodName;
                if (enclosingMethodCpIdx == 0) {
                    // A cpIdx of 0 (which is an invalid value) is used for anonymous inner classes declared in
                    // class initializer code, e.g. assigned to a class field.
                    definingMethodName = "<clinit>";
                } else {
                    definingMethodName = getConstantPoolString(enclosingMethodCpIdx, /* subFieldIdx = */ 0);
                    // Could also fetch field type signature with subFieldIdx = 1, if needed
                }
                // Link anonymous inner classes into the class with their containing method
                classInfoUnlinked.addClassContainment(className, innermostEnclosingClassName);
                // Also store the fully-qualified name of the enclosing method, to mark this as an anonymous inner
                // class
                classInfoUnlinked.addEnclosingMethod(innermostEnclosingClassName + "." + definingMethodName);
            } else {
                inputStreamOrByteBuffer.skip(attributeLength);
            }
        }
        return classInfoUnlinked;
    }

    /**
     * Directly examine contents of classfile binary header to determine annotations, implemented interfaces, the
     * super-class etc. Creates a new ClassInfo object, and adds it to classNameToClassInfoOut. Assumes classpath
     * masking has already been performed, so that only one class of a given name will be added.
     */
    ClassInfoUnlinked readClassInfoFromClassfileHeader(final ClasspathElement classpathElement,
            final String relativePath, final Resource classfileResource, final boolean isExternalClass,
            final ScanSpec scanSpec, final LogNode log) throws IOException {
        try {
            // Open classfile as a ByteBuffer or InputStream
            this.inputStreamOrByteBuffer = classfileResource.openOrRead();

            // Read the initial chunk of data into the buffer
            inputStreamOrByteBuffer.readInitialChunk();

            // Check magic number
            if (inputStreamOrByteBuffer.readInt() != 0xCAFEBABE) {
                throw new IOException(
                        "Classfile " + relativePath + " does not have correct classfile magic number");
            }

            return readClassfile(classpathElement, relativePath, isExternalClass, classfileResource, scanSpec, log);

        } finally {
            // Close classfile InputStream (and any associated ZipEntry);
            // recycle ZipFile or ModuleReaderProxy if applicable
            classfileResource.close();
        }
    }
}
