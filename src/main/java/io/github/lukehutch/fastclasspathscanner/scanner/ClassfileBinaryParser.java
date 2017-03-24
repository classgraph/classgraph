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

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.RetentionPolicy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMapKeyToSet;

/**
 * A classfile binary format parser. Implements its own buffering to avoid the overhead of using DataInputStream.
 * This class should only be used by a single thread at a time, but can be re-used to scan multiple classfiles in
 * sequence, to avoid re-allocating buffer memory.
 */
class ClassfileBinaryParser implements AutoCloseable {
    /** The InputStream for the current classfile. Set by each call to readClassInfoFromClassfileHeader(). */
    private InputStream inputStream;

    /** The name of the current classfile. Determined early in the call to readClassInfoFromClassfileHeader(). */
    private String className;

    @Override
    public void close() {
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Buffer size for initial read. We can save some time by reading most of the classfile header in a single read
     * at the beginning of the scan.
     * 
     * (If chunk sizes are too small, significant overhead is expended in refilling the buffer. If they are too
     * large, significant overhead is expended in decompressing more of the classfile header than is needed. Testing
     * on a large classpath indicates that the defaults are reasonably optimal.)
     */
    private static final int INITIAL_BUFFER_CHUNK_SIZE = 16384;

    /** Buffer size for classfile reader. */
    private static final int SUBSEQUENT_BUFFER_CHUNK_SIZE = 4096;

    /** Bytes read from the beginning of the classfile. This array is reused across calls. */
    private byte[] buf = new byte[INITIAL_BUFFER_CHUNK_SIZE];

    /** The current read index in the classfileBytes array. */
    private int curr = 0;

    /** Bytes used in the classfileBytes array. */
    private int used = 0;

    /**
     * Read another chunk of size BUFFER_CHUNK_SIZE from the InputStream; double the size of the buffer if necessary
     * to accommodate the new chunk.
     */
    private void readMore(final int bytesRequired) throws IOException, InterruptedException {
        final int extraBytesNeeded = bytesRequired - (used - curr);
        int bytesToRequest = extraBytesNeeded + SUBSEQUENT_BUFFER_CHUNK_SIZE;
        final int maxNewUsed = used + bytesToRequest;
        if (maxNewUsed > buf.length) {
            // Ran out of space, need to increase the size of the buffer
            int newBufLen = buf.length;
            while (newBufLen < maxNewUsed) {
                newBufLen <<= 1;
            }
            buf = Arrays.copyOf(buf, newBufLen);
        }
        int extraBytesStillNotRead = extraBytesNeeded;
        while (extraBytesStillNotRead > 0) {
            final int bytesRead = inputStream.read(buf, used, bytesToRequest);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            if (bytesRead > 0) {
                used += bytesRead;
                bytesToRequest -= bytesRead;
                extraBytesStillNotRead -= bytesRead;
            } else {
                // EOF
                if (extraBytesStillNotRead > 0) {
                    throw new IOException("Premature EOF while reading classfile");
                } else {
                    break;
                }
            }
        }
    }

    /** Read an unsigned byte from the buffer. */
    private int readUnsignedByte() throws IOException, InterruptedException {
        if (curr > used - 1) {
            readMore(1);
        }
        return buf[curr++] & 0xff;
    }

    /** Read an unsigned byte from the buffer at a specific offset before the current read point. */
    @SuppressWarnings("unused")
    private int readUnsignedByte(final int offset) {
        return buf[offset] & 0xff;
    }

    /** Read an unsigned short from the buffer. */
    private int readUnsignedShort() throws IOException, InterruptedException {
        if (curr > used - 2) {
            readMore(2);
        }
        final int val = ((buf[curr] & 0xff) << 8) | (buf[curr + 1] & 0xff);
        curr += 2;
        return val;
    }

    /** Read an unsigned short from the buffer at a specific offset before the current read point. */
    private int readUnsignedShort(final int offset) {
        return ((buf[offset] & 0xff) << 8) | (buf[offset + 1] & 0xff);
    }

    /** Read an int from the buffer. */
    private int readInt() throws IOException, InterruptedException {
        if (curr > used - 4) {
            readMore(4);
        }
        final int val = ((buf[curr] & 0xff) << 24) | ((buf[curr + 1] & 0xff) << 16) | ((buf[curr + 2] & 0xff) << 8)
                | (buf[curr + 3] & 0xff);
        curr += 4;
        return val;
    }

    /** Read an int from the buffer at a specific offset before the current read point. */
    private int readInt(final int offset) throws IOException {
        return ((buf[offset] & 0xff) << 24) | ((buf[offset + 1] & 0xff) << 16) | ((buf[offset + 2] & 0xff) << 8)
                | (buf[offset + 3] & 0xff);
    }

    /** Read a long from the buffer. */
    @SuppressWarnings("unused")
    private long readLong() throws IOException, InterruptedException {
        if (curr > used - 8) {
            readMore(8);
        }
        final long val = (((long) (((buf[curr] & 0xff) << 24) | ((buf[curr + 1] & 0xff) << 16)
                | ((buf[curr + 2] & 0xff) << 8) | (buf[curr + 3] & 0xff))) << 32) | ((buf[curr + 4] & 0xff) << 24)
                | ((buf[curr + 5] & 0xff) << 16) | ((buf[curr + 6] & 0xff) << 8) | (buf[curr + 7] & 0xff);
        curr += 8;
        return val;
    }

    /** Read a long from the buffer at a specific offset before the current read point. */
    private long readLong(final int offset) throws IOException {
        return (((long) (((buf[offset] & 0xff) << 24) | ((buf[offset + 1] & 0xff) << 16)
                | ((buf[offset + 2] & 0xff) << 8) | (buf[offset + 3] & 0xff))) << 32)
                | ((buf[offset + 4] & 0xff) << 24) | ((buf[offset + 5] & 0xff) << 16)
                | ((buf[offset + 6] & 0xff) << 8) | (buf[offset + 7] & 0xff);
    }

    /** Skip the given number of bytes in the input stream. */
    private void skip(final int bytesToSkip) throws IOException, InterruptedException {
        if (curr > used - bytesToSkip) {
            readMore(bytesToSkip);
        }
        curr += bytesToSkip;
    }

    /** Reads the "modified UTF8" format defined in the Java classfile spec, optionally replacing '/' with '.'. */
    private String readString(final int strStart, final boolean replaceSlashWithDot) {
        final int utfLen = readUnsignedShort(strStart);
        final int utfStart = strStart + 2;
        final char[] chars = new char[utfLen];
        int c, c2, c3, c4;
        int byteIdx = 0;
        int charIdx = 0;
        for (; byteIdx < utfLen; byteIdx++) {
            c = buf[utfStart + byteIdx] & 0xff;
            if (c > 127) {
                break;
            }
            chars[charIdx++] = (char) (replaceSlashWithDot && c == '/' ? '.' : c);
        }
        while (byteIdx < utfLen) {
            c = buf[utfStart + byteIdx] & 0xff;
            switch (c >> 4) {
            case 0:
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
            case 7:
                byteIdx++;
                chars[charIdx++] = (char) (replaceSlashWithDot && c == '/' ? '.' : c);
                break;
            case 12:
            case 13:
                byteIdx += 2;
                if (byteIdx > utfLen) {
                    throw new RuntimeException("Bad modified UTF8");
                }
                c2 = buf[utfStart + byteIdx - 1];
                if ((c2 & 0xc0) != 0x80) {
                    throw new RuntimeException("Bad modified UTF8");
                }
                c4 = ((c & 0x1f) << 6) | (c2 & 0x3f);
                chars[charIdx++] = (char) (replaceSlashWithDot && c4 == '/' ? '.' : c4);
                break;
            case 14:
                byteIdx += 3;
                if (byteIdx > utfLen) {
                    throw new RuntimeException("Bad modified UTF8");
                }
                c2 = buf[utfStart + byteIdx - 2];
                c3 = buf[utfStart + byteIdx - 1];
                if (((c2 & 0xc0) != 0x80) || ((c3 & 0xc0) != 0x80)) {
                    throw new RuntimeException("Bad modified UTF8");
                }
                c4 = ((c & 0x0f) << 12) | ((c2 & 0x3f) << 6) | ((c3 & 0x3f) << 0);
                chars[charIdx++] = (char) (replaceSlashWithDot && c4 == '/' ? '.' : c4);
                break;
            default:
                throw new RuntimeException("Bad modified UTF8");
            }
        }
        if (charIdx < utfLen) {
            return new String(chars, 0, charIdx);
        } else {
            return new String(chars);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** The byte offset for the beginning of each entry in the constant pool. */
    private int[] offset;

    /** The tag (type) for each entry in the constant pool. */
    private int[] tag;

    /** The indirection index for String/Class entries in the constant pool. */
    private int[] indirectStringRefs;

    /** Get the byte offset within the buffer of a string from the constant pool, or 0 for a null string. */
    private int getConstantPoolStringOffset(final int CpIdx) {
        final int t = tag[CpIdx];
        if (t != 1 && t != 7 && t != 8) {
            throw new RuntimeException("Wrong tag number at constant pool index " + CpIdx + ", "
                    + "cannot continue reading class. Please report this at "
                    + "https://github.com/lukehutch/fast-classpath-scanner/issues");
        }
        int cpIdx = CpIdx;
        if (t == 7 || t == 8) {
            final int indirIdx = indirectStringRefs[CpIdx];
            if (indirIdx == -1) {
                // Should not happen
                throw new RuntimeException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this at https://github.com/lukehutch/fast-classpath-scanner/issues");
            }
            if (indirIdx == 0) {
                // I assume this represents a null string, since the zeroeth entry is unused
                return 0;
            }
            cpIdx = indirIdx;
        }
        return offset[cpIdx];
    }

    /** Get a string from the constant pool, optionally replacing '/' with '.'. */
    private String getConstantPoolString(final int CpIdx, final boolean replaceSlashWithDot) {
        final int constantPoolStringOffset = getConstantPoolStringOffset(CpIdx);
        return constantPoolStringOffset == 0 ? null : readString(constantPoolStringOffset, replaceSlashWithDot);
    }

    /** Get a string from the constant pool. */
    private String getConstantPoolString(final int CpIdx) {
        final int constantPoolStringOffset = getConstantPoolStringOffset(CpIdx);
        return constantPoolStringOffset == 0 ? null
                : readString(constantPoolStringOffset, /* replaceSlashWithDot = */ false);
    }

    /** Get the first UTF8 byte of a string in the constant pool, or '\0' if the string is null or empty. */
    private byte getConstantPoolStringFirstByte(final int CpIdx) {
        final int constantPoolStringOffset = getConstantPoolStringOffset(CpIdx);
        if (constantPoolStringOffset == 0) {
            return '\0';
        }
        final int utfLen = readUnsignedShort(constantPoolStringOffset);
        if (utfLen == 0) {
            return '\0';
        }
        return buf[constantPoolStringOffset + 2];
    }

    /** Get a string from the constant pool, and interpret it as a class name by replacing '/' with '.'. */
    private String getConstantPoolClassName(final int CpIdx) {
        return getConstantPoolString(CpIdx, /* replaceSlashWithDot = */ true);
    }

    /** Compare a string in the constant pool with a given constant, without constructing the String object. */
    private boolean constantPoolStringEquals(final int CpIdx, final String otherString) {
        final int strOffset = getConstantPoolStringOffset(CpIdx);
        if (strOffset == 0) {
            return otherString == null;
        }
        final int strLen = readUnsignedShort(strOffset);
        final int otherLen = otherString.length();
        if (strLen != otherLen) {
            return false;
        }
        final int strStart = strOffset + 2;
        for (int i = 0; i < strLen; i++) {
            if ((char) (buf[strStart + i] & 0xff) != otherString.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Get a constant from the constant pool. */
    private Object getConstantPoolValue(final int CpIdx) throws IOException {
        switch (tag[CpIdx]) {
        case 1: // Modified UTF8
            return getConstantPoolString(CpIdx);
        case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
            return new Integer(readInt(offset[CpIdx]));
        case 4: // float
            return new Float(Float.intBitsToFloat(readInt(offset[CpIdx])));
        case 5: // long
            return new Long(readLong(offset[CpIdx]));
        case 6: // double
            return new Double(Double.longBitsToDouble(readLong(offset[CpIdx])));
        case 7: // Class
        case 8: // String
            // Forward or backward indirect reference to a modified UTF8 entry
            return getConstantPoolString(CpIdx);
        default:
            // FastClasspathScanner doesn't currently do anything with the other types
            throw new RuntimeException("Constant pool entry type " + tag[CpIdx] + " unsupported, "
                    + "cannot continue reading class. Please report this at "
                    + "https://github.com/lukehutch/fast-classpath-scanner/issues");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Read annotation entry from classfile. */
    private String readAnnotation() throws IOException, InterruptedException {
        // Lcom/xyz/Annotation -> Lcom.xyz.Annotation
        final String annotationFieldDescriptor = getConstantPoolClassName(readUnsignedShort());
        String annotationClassName;
        if (annotationFieldDescriptor.charAt(0) == 'L'
                && annotationFieldDescriptor.charAt(annotationFieldDescriptor.length() - 1) == ';') {
            // Lcom.xyz.Annotation; -> com.xyz.Annotation
            annotationClassName = annotationFieldDescriptor.substring(1, annotationFieldDescriptor.length() - 1);
        } else {
            // Should not happen
            annotationClassName = annotationFieldDescriptor;
        }
        final int numElementValuePairs = readUnsignedShort();
        for (int i = 0; i < numElementValuePairs; i++) {
            skip(2); // element_name_index
            readAnnotationElementValue();
        }
        return annotationClassName;
    }

    /**
     * Read annotation element value from classfile. (These values are currently just skipped, so this function
     * returns nothing.)
     */
    private void readAnnotationElementValue() throws IOException, InterruptedException {
        final int tag = (char) readUnsignedByte();
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
            skip(2);
            break;
        case 'e':
            // enum_const_value
            skip(4);
            break;
        case 'c':
            // class_info_index
            skip(2);
            break;
        case '@':
            // Complex (nested) annotation
            readAnnotation();
            break;
        case '[':
            // array_value
            final int count = readUnsignedShort();
            for (int l = 0; l < count; ++l) {
                // Nested annotation element value
                readAnnotationElementValue();
            }
            break;
        default:
            throw new RuntimeException("Class " + className + " has unknown annotation element type tag '"
                    + ((char) tag) + "': element size unknown, cannot continue reading class. "
                    + "Please report this at https://github.com/lukehutch/fast-classpath-scanner/issues");
        }
    }

    /**
     * Find non-blacklisted type names in the given type descriptor, and add them to the set of field types.
     * 
     * Splits a type param into type pieces, e.g. "Ljava/util/Map<+Lcom/xyz/fig/shape/Shape;Ljava/lang/Integer;>;"
     * => "java/util/Map", "com/xyz/fig/shape/Shape", "java/lang/Integer".
     * 
     * Also removes array prefixes, e.g. "[[[Lcom.xyz.Widget" -> "com.xyz.Widget".
     */
    private void addFieldTypeDescriptorParts(final ClassInfoUnlinked classInfoUnlinked,
            final String typeDescriptor) {
        for (int i = 0; i < typeDescriptor.length(); i++) {
            char c = typeDescriptor.charAt(i);
            if (c == 'L') {
                final int typeNameStart = ++i;
                for (; i < typeDescriptor.length(); i++) {
                    c = typeDescriptor.charAt(i);
                    if (c == '<' || c == ';') {
                        break;
                    }
                }
                // Switch '/' package delimiter to '.' (lower overhead than String.replace('/', '.'))
                final char[] typeNameChars = new char[i - typeNameStart];
                for (int j = typeNameStart; j < i; j++) {
                    final char chr = typeDescriptor.charAt(j);
                    typeNameChars[j - typeNameStart] = chr == '/' ? '.' : chr;
                }
                final String typeName = new String(typeNameChars);
                // Check if the type of this field falls within a non-blacklisted package, and if not, add it
                classInfoUnlinked.addFieldType(typeName);
            }
        }
    }

    /**
     * Directly examine contents of classfile binary header to determine annotations, implemented interfaces, the
     * super-class etc. Creates a new ClassInfo object, and adds it to classNameToClassInfoOut. Assumes classpath
     * masking has already been performed, so that only one class of a given name will be added.
     * 
     * @throws InterruptedException
     *             if the operation was interrupted.
     */
    ClassInfoUnlinked readClassInfoFromClassfileHeader(final URL classpathElementURL, final String relativePath,
            final InputStream inputStream, final ScanSpec scanSpec,
            final ConcurrentHashMap<String, String> stringInternMap, final LogNode log)
            throws IOException, InterruptedException {

        // This class instance can be reused across scans, to avoid re-allocating the buffer.
        // Initialize/clear fields for each new run. 
        this.inputStream = inputStream;
        className = null;
        curr = 0;

        // Read first bufferful
        used = inputStream.read(buf, 0, INITIAL_BUFFER_CHUNK_SIZE);
        if (used < 0) {
            throw new IOException("Classfile " + relativePath + " is empty");
        }

        // Check magic number
        if (readInt() != 0xCAFEBABE) {
            throw new IOException("Classfile " + relativePath + " does not have correct classfile magic number");
        }

        // Minor version
        readUnsignedShort();
        // Major version
        readUnsignedShort();

        // Read size of constant pool
        final int cpCount = readUnsignedShort();

        // Allocate storage for constant pool, or reuse storage if there's enough left from the previous scan
        if (offset == null || offset.length < cpCount) {
            offset = new int[cpCount];
            tag = new int[cpCount];
            indirectStringRefs = new int[cpCount];
        }
        Arrays.fill(indirectStringRefs, 0, cpCount, -1);

        // Read constant pool entries
        for (int i = 1; i < cpCount; ++i) {
            tag[i] = readUnsignedByte();
            offset[i] = curr;
            switch (tag[i]) {
            case 1: // Modified UTF8
                final int strLen = readUnsignedShort();
                skip(strLen);
                break;
            case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
            case 4: // float
                skip(4);
                break;
            case 5: // long
            case 6: // double
                skip(8);
                i++; // double slot
                break;
            case 7: // Class
            case 8: // String
                // Forward or backward indirect reference to a modified UTF8 entry
                indirectStringRefs[i] = readUnsignedShort();
                // (no need to copy bytes over, we use indirectStringRef instead for these fields)
                break;
            case 9: // field ref
            case 10: // method ref
            case 11: // interface ref
            case 12: // name and type
                skip(4);
                break;
            case 15: // method handle
                skip(3);
                break;
            case 16: // method type
                skip(2);
                break;
            case 18: // invoke dynamic
                skip(4);
                break;
            default:
                throw new RuntimeException("Unknown constant pool tag " + tag + " in classfile " + relativePath
                        + " (element size unknown, cannot continue reading class. Please report this on "
                        + "the FastClasspathScanner GitHub page.");
            }
        }

        // Modifier flags
        final int classModifierFlags = readUnsignedShort();
        final boolean isInterface = (classModifierFlags & 0x0200) != 0;
        final boolean isAnnotation = (classModifierFlags & 0x2000) != 0;

        // The fully-qualified class name of this class, with slashes replaced with dots
        final String classNamePath = getConstantPoolString(readUnsignedShort());
        final String className = classNamePath.replace('/', '.');
        if ("java.lang.Object".equals(className)) {
            // Don't process java.lang.Object (it has a null superclass), though you can still search for
            // classes that are subclasses of java.lang.Object if you add "!" to the scan spec.
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
        final String superclassName = getConstantPoolClassName(readUnsignedShort());

        final ClassInfoUnlinked classInfoUnlinked = new ClassInfoUnlinked(className, isInterface, isAnnotation,
                stringInternMap, classpathElementURL);

        // Connect class to superclass
        classInfoUnlinked.addSuperclass(superclassName);

        // Interfaces
        final int interfaceCount = readUnsignedShort();
        for (int i = 0; i < interfaceCount; i++) {
            final String interfaceName = getConstantPoolClassName(readUnsignedShort());
            classInfoUnlinked.addImplementedInterface(interfaceName);
        }

        // Fields
        final MultiMapKeyToSet<String, String> classNameToStaticFinalFieldsToMatch = scanSpec
                .getClassNameToStaticFinalFieldsToMatch();
        final Set<String> staticFinalFieldsToMatch = classNameToStaticFinalFieldsToMatch == null ? null
                : classNameToStaticFinalFieldsToMatch.get(className);
        final boolean matchStaticFinalFields = staticFinalFieldsToMatch != null;
        final int fieldCount = readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) {
            // Info on modifier flags: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.5
            final int fieldModifierFlags = readUnsignedShort();
            final boolean isPublicField = ((fieldModifierFlags & 0x0001) == 0x0001);
            final boolean isStaticFinalField = ((fieldModifierFlags & 0x0018) == 0x0018);
            final boolean fieldIsVisible = isPublicField || scanSpec.ignoreFieldVisibility;
            final boolean matchThisStaticFinalField = matchStaticFinalFields && isStaticFinalField
                    && fieldIsVisible;
            if (!fieldIsVisible || //
                    (!scanSpec.saveFieldInfo && (!scanSpec.enableFieldTypeIndexing && !matchThisStaticFinalField)
                            && !scanSpec.enableFieldAnnotationIndexing)) {
                // Skip field
                readUnsignedShort(); // fieldNameCpIdx
                readUnsignedShort(); // fieldTypeDescriptorCpIdx
                final int attributesCount = readUnsignedShort();
                for (int j = 0; j < attributesCount; j++) {
                    readUnsignedShort(); // attributeNameCpIdx
                    final int attributeLength = readInt(); // == 2
                    skip(attributeLength);
                }
            } else {
                final int fieldNameCpIdx = readUnsignedShort();
                String fieldName = null;
                boolean isMatchedFieldName = false;
                if (matchThisStaticFinalField || scanSpec.saveFieldInfo) {
                    // Only decode fieldName if needed
                    fieldName = getConstantPoolString(fieldNameCpIdx);
                    if (matchThisStaticFinalField && staticFinalFieldsToMatch.contains(fieldName)) {
                        isMatchedFieldName = true;
                    }
                }
                final int fieldTypeDescriptorCpIdx = readUnsignedShort();
                final char fieldTypeDescriptorFirstChar = (char) getConstantPoolStringFirstByte(
                        fieldTypeDescriptorCpIdx);
                String fieldTypeDescriptor = null;
                if (scanSpec.saveFieldInfo) {
                    // Only decode full type descriptor if it is needed
                    fieldTypeDescriptor = getConstantPoolString(fieldTypeDescriptorCpIdx);
                }

                // Check if the type of this field falls within a non-blacklisted package,
                // and if so, record the field and its type
                if (scanSpec.enableFieldTypeIndexing && fieldIsVisible) {
                    addFieldTypeDescriptorParts(classInfoUnlinked, getConstantPoolString(fieldTypeDescriptorCpIdx));
                }

                Object fieldConstValue = null;
                boolean foundFieldConstValue = false;
                List<String> fieldAnnotationNames = null;
                if (scanSpec.saveFieldInfo && fieldIsVisible) {
                    fieldAnnotationNames = new ArrayList<>(1);
                }
                final int attributesCount = readUnsignedShort();
                for (int j = 0; j < attributesCount; j++) {
                    final int attributeNameCpIdx = readUnsignedShort();
                    final int attributeLength = readInt(); // == 2
                    // See if field name matches one of the requested names for this class, and if it does,
                    // check if it is initialized with a constant value
                    if ((isMatchedFieldName || scanSpec.saveFieldInfo)
                            && constantPoolStringEquals(attributeNameCpIdx, "ConstantValue")) {
                        // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.2
                        fieldConstValue = getConstantPoolValue(readUnsignedShort());
                        // byte, char, short and boolean constants are all stored as 4-byte int
                        // values -- coerce and wrap in the proper wrapper class with autoboxing
                        switch (fieldTypeDescriptorFirstChar) {
                        case 'B':
                            // byte, char, short and boolean constants are all stored as 4-byte int values.
                            // Convert and wrap in Byte object.
                            fieldConstValue = new Byte(((Integer) fieldConstValue).byteValue());
                            break;
                        case 'C':
                            // byte, char, short and boolean constants are all stored as 4-byte int values.
                            // Convert and wrap in Character object.
                            fieldConstValue = new Character((char) ((Integer) fieldConstValue).intValue());
                            break;
                        case 'S':
                            // byte, char, short and boolean constants are all stored as 4-byte int values.
                            // Convert and wrap in Short object.
                            fieldConstValue = new Short(((Integer) fieldConstValue).shortValue());
                            break;
                        case 'Z':
                            // byte, char, short and boolean constants are all stored as 4-byte int values.
                            // Convert and wrap in Boolean object.
                            fieldConstValue = new Boolean(((Integer) fieldConstValue).intValue() != 0);
                            break;
                        case 'I':
                        case 'J':
                        case 'F':
                        case 'D':
                            // int, long, float or double are already in correct wrapper type (Integer, Long,
                            // Float, Double or String) -- nothing to do
                            break;
                        default:
                            if (constantPoolStringEquals(fieldTypeDescriptorCpIdx, "Ljava/lang/String;")) {
                                // String constants are already in correct form, nothing to do
                            } else {
                                // Should never happen, constant values can only be stored as an int, long,
                                // float, double or String
                                throw new RuntimeException("Unknown constant initializer type "
                                        + getConstantPoolString(fieldTypeDescriptorCpIdx) + " for class "
                                        + className + " -- please report this at "
                                        + "https://github.com/lukehutch/fast-classpath-scanner/issues");
                            }
                            break;
                        }
                        // Store static final field match in ClassInfo object
                        if (isMatchedFieldName) {
                            classInfoUnlinked.addFieldConstantValue(fieldName, fieldConstValue);
                        }
                        foundFieldConstValue = true;
                    } else if (scanSpec.enableFieldTypeIndexing && fieldIsVisible
                            && constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                        // Check if the type signature of this field falls within a non-blacklisted
                        // package, and if so, record the field type. The type signature contains
                        // type parameters, whereas the type descriptor does not.
                        final String fieldTypeSignature = getConstantPoolString(readUnsignedShort());
                        addFieldTypeDescriptorParts(classInfoUnlinked, fieldTypeSignature);
                    } else if ((scanSpec.saveFieldInfo || scanSpec.enableFieldAnnotationIndexing)
                            && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                                    || (scanSpec.annotationVisibility == RetentionPolicy.CLASS
                                            && constantPoolStringEquals(attributeNameCpIdx,
                                                    "RuntimeInvisibleAnnotations")))) {
                        // Read annotation names
                        final int annotationCount = readUnsignedShort();
                        for (int k = 0; k < annotationCount; k++) {
                            final String annotationName = readAnnotation();
                            if (scanSpec.enableFieldAnnotationIndexing) {
                                classInfoUnlinked.addFieldAnnotation(annotationName);
                            }
                            if (fieldAnnotationNames != null) {
                                fieldAnnotationNames.add(annotationName);
                            }
                        }
                    } else {
                        // No match, just skip attribute
                        skip(attributeLength);
                    }
                    if (isMatchedFieldName && !foundFieldConstValue && log != null) {
                        boolean reasonFound = false;
                        if (!isStaticFinalField) {
                            log.log("Requested static final field match " //
                                    + classInfoUnlinked.className + "." + getConstantPoolString(fieldNameCpIdx)
                                    + " is not declared as static final");
                            reasonFound = true;
                        }
                        if (!isPublicField && !scanSpec.ignoreFieldVisibility) {
                            log.log("Requested static final field match " //
                                    + classInfoUnlinked.className + "." + getConstantPoolString(fieldNameCpIdx)
                                    + " is not declared as public, and ignoreFieldVisibility was not set to"
                                    + " true before scan");
                            reasonFound = true;
                        }
                        if (!reasonFound) {
                            log.log("Requested static final field match " //
                                    + classInfoUnlinked.className + "." + getConstantPoolString(fieldNameCpIdx)
                                    + " does not have a constant literal initializer value");
                        }
                    }
                }
                if (scanSpec.saveFieldInfo && fieldIsVisible) {
                    classInfoUnlinked.addFieldInfo(new FieldInfo(fieldName, fieldModifierFlags, fieldTypeDescriptor,
                            fieldConstValue, fieldAnnotationNames));
                }
            }
        }

        // Methods
        final int methodCount = readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            // Info on modifier flags: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6
            final int methodModifierFlags = readUnsignedShort();

            String methodName = null;
            String methodTypeDescriptor = null;
            if (scanSpec.saveMethodInfo) {
                final int methodNameCpIdx = readUnsignedShort();
                methodName = getConstantPoolString(methodNameCpIdx);
                final int methodTypeDescriptorCpIdx = readUnsignedShort();
                methodTypeDescriptor = getConstantPoolString(methodTypeDescriptorCpIdx);
            } else {
                skip(4); // name_index, descriptor_index
            }

            final int attributesCount = readUnsignedShort();
            final boolean isPublicMethod = ((methodModifierFlags & 0x0001) == 0x0001);
            final boolean methodIsVisible = isPublicMethod || scanSpec.ignoreMethodVisibility;
            List<String> methodAnnotationNames = null;
            if (scanSpec.saveMethodInfo && methodIsVisible) {
                methodAnnotationNames = new ArrayList<>(1);
            }
            if (!methodIsVisible || (!scanSpec.saveMethodInfo && !scanSpec.enableMethodAnnotationIndexing)) {
                // Skip method attributes
                for (int j = 0; j < attributesCount; j++) {
                    skip(2); // attribute_name_index
                    final int attributeLength = readInt();
                    skip(attributeLength);
                }
            } else {
                // Look for method annotations
                for (int j = 0; j < attributesCount; j++) {
                    final int attributeNameCpIdx = readUnsignedShort();
                    final int attributeLength = readInt();
                    if (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                            || (scanSpec.annotationVisibility == RetentionPolicy.CLASS && constantPoolStringEquals(
                                    attributeNameCpIdx, "RuntimeInvisibleAnnotations"))) {
                        final int annotationCount = readUnsignedShort();
                        for (int k = 0; k < annotationCount; k++) {
                            final String annotationName = readAnnotation();
                            if (scanSpec.enableMethodAnnotationIndexing) {
                                classInfoUnlinked.addMethodAnnotation(annotationName);
                            }
                            if (methodAnnotationNames != null) {
                                methodAnnotationNames.add(annotationName);
                            }
                        }
                    } else {
                        skip(attributeLength);
                    }
                }
            }
            if (scanSpec.saveMethodInfo && methodIsVisible) {
                final boolean isConstructor = "<init>".equals(methodName);
                classInfoUnlinked.addMethodInfo(new MethodInfo(isConstructor ? className : methodName,
                        methodModifierFlags, methodTypeDescriptor, methodAnnotationNames, isConstructor));
            }
        }

        // Attributes (including class annotations)
        final int attributesCount = readUnsignedShort();
        for (int i = 0; i < attributesCount; i++) {
            final int attributeNameCpIdx = readUnsignedShort();
            final int attributeLength = readInt();
            if (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                    || (scanSpec.annotationVisibility == RetentionPolicy.CLASS
                            && constantPoolStringEquals(attributeNameCpIdx, "RuntimeInvisibleAnnotations"))) {
                final int annotationCount = readUnsignedShort();
                for (int m = 0; m < annotationCount; m++) {
                    final String annotationName = readAnnotation();
                    classInfoUnlinked.addAnnotation(annotationName);
                }
            } else {
                skip(attributeLength);
            }
        }
        return classInfoUnlinked;
    }
}
