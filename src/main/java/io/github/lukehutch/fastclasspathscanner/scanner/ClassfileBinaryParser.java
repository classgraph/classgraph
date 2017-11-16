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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.lukehutch.fastclasspathscanner.scanner.AnnotationInfo.AnnotationClassRef;
import io.github.lukehutch.fastclasspathscanner.scanner.AnnotationInfo.AnnotationEnumValue;
import io.github.lukehutch.fastclasspathscanner.scanner.AnnotationInfo.AnnotationParamValue;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMapKeyToSet;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

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
                if (newBufLen <= 0) {
                    // Handle overflow
                    throw new IOException("Classfile is bigger than 2GB, cannot read it");
                }
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

    /**
     * Reads the "modified UTF8" format defined in the Java classfile spec, optionally replacing '/' with '.', and
     * optionally removing the prefix "L" and the suffix ";".
     */
    private String readString(final int strStart, final boolean replaceSlashWithDot,
            final boolean stripLSemicolon) {
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
        if (charIdx == utfLen && !stripLSemicolon) {
            return new String(chars);
        } else {
            if (stripLSemicolon) {
                if (charIdx < 2 || chars[0] != 'L' || chars[charIdx - 1] != ';') {
                    throw new RuntimeException("Expected string to start with 'L' and end with ';', got \""
                            + new String(chars) + "\"");
                }
                return new String(chars, 1, charIdx - 2);
            } else {
                return new String(chars, 0, charIdx);
            }
        }
    }

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
                            + "Please report this at https://github.com/lukehutch/fast-classpath-scanner/issues");
        }
        int cpIdxToUse;
        if (t == 1) {
            // CONSTANT_Utf8
            cpIdxToUse = cpIdx;
        } else if (t == 7 || t == 8) {
            // t == 7 => CONSTANT_Class, e.g. "[[I", "[Ljava/lang/Thread;"
            // t == 8 => CONSTANT_String
            final int indirIdx = indirectStringRefs[cpIdx];
            if (indirIdx == -1) {
                // Should not happen
                throw new RuntimeException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this at https://github.com/lukehutch/fast-classpath-scanner/issues");
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
            if (indirIdx == 0 || indirIdx == -1) {
                // Should not happen
                throw new RuntimeException("Bad string indirection index, cannot continue reading class. "
                        + "Please report this at https://github.com/lukehutch/fast-classpath-scanner/issues");
            }
            cpIdxToUse = indirIdx;
        } else {
            throw new RuntimeException("Wrong tag number " + t + " at constant pool index " + cpIdx + ", "
                    + "cannot continue reading class. Please report this at "
                    + "https://github.com/lukehutch/fast-classpath-scanner/issues");
        }
        return offset[cpIdxToUse];
    }

    /** Get a string from the constant pool, optionally replacing '/' with '.'. */
    private String getConstantPoolString(final int cpIdx, final boolean replaceSlashWithDot,
            final boolean stripLSemicolon) {
        final int constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
        return constantPoolStringOffset == 0 ? null
                : readString(constantPoolStringOffset, replaceSlashWithDot, stripLSemicolon);
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
                : readString(constantPoolStringOffset, /* replaceSlashWithDot = */ false,
                        /* stripLSemicolon = */ false);
    }

    /** Get a string from the constant pool. */
    private String getConstantPoolString(final int cpIdx) {
        return getConstantPoolString(cpIdx, /* subFieldIdx = */ 0);
    }

    /** Get the first UTF8 byte of a string in the constant pool, or '\0' if the string is null or empty. */
    private byte getConstantPoolStringFirstByte(final int cpIdx) {
        final int constantPoolStringOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
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

    /** Compare a string in the constant pool with a given constant, without constructing the String object. */
    private boolean constantPoolStringEquals(final int cpIdx, final String otherString) {
        final int strOffset = getConstantPoolStringOffset(cpIdx, /* subFieldIdx = */ 0);
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
    private Object getConstantPoolValue(final int tag, final char fieldTypeDescriptorFirstChar, final int cpIdx)
            throws IOException {
        switch (tag) {
        case 1: // Modified UTF8
        case 7: // Class
        case 8: // String
            // Forward or backward indirect reference to a modified UTF8 entry
            return getConstantPoolString(cpIdx, /* subFieldIdx = */ 0);
        case 3: // int, short, char, byte, boolean are all represented by Constant_INTEGER
        {
            final int intVal = readInt(offset[cpIdx]);
            switch (fieldTypeDescriptorFirstChar) {
            case 'I':
                return new Integer(intVal);
            case 'S':
                return new Short((short) intVal);
            case 'C':
                return new Character((char) intVal);
            case 'B':
                return new Byte((byte) intVal);
            case 'Z':
                return new Boolean(intVal != 0);
            default:
                throw new RuntimeException("Unknown Constant_INTEGER type " + fieldTypeDescriptorFirstChar + ", "
                        + "cannot continue reading class. Please report this at "
                        + "https://github.com/lukehutch/fast-classpath-scanner/issues");
            }
        }
        case 4: // float
            return new Float(Float.intBitsToFloat(readInt(offset[cpIdx])));
        case 5: // long
            return new Long(readLong(offset[cpIdx]));
        case 6: // double
            return new Double(Double.longBitsToDouble(readLong(offset[cpIdx])));
        default:
            // FastClasspathScanner doesn't currently do anything with the other types
            throw new RuntimeException("Unknown constant pool tag " + tag + ", "
                    + "cannot continue reading class. Please report this at "
                    + "https://github.com/lukehutch/fast-classpath-scanner/issues");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Read annotation entry from classfile. */
    private AnnotationInfo readAnnotation() throws IOException, InterruptedException {
        // Lcom/xyz/Annotation; -> Lcom.xyz.Annotation;
        final String annotationClassName = getConstantPoolClassDescriptor(readUnsignedShort());
        final int numElementValuePairs = readUnsignedShort();
        List<AnnotationParamValue> paramVals = null;
        if (numElementValuePairs > 0) {
            paramVals = new ArrayList<>();
        }
        for (int i = 0; i < numElementValuePairs; i++) {
            final String paramName = getConstantPoolString(readUnsignedShort()); // skip(2); // element_name_index
            final Object paramValue = readAnnotationElementValue();
            paramVals.add(new AnnotationParamValue(paramName, paramValue));
        }
        return new AnnotationInfo(annotationClassName, paramVals);
    }

    /** Read annotation element value from classfile. */
    private Object readAnnotationElementValue() throws IOException, InterruptedException {
        final int tag = (char) readUnsignedByte();
        switch (tag) {
        case 'B':
            return new Byte((byte) readInt(offset[readUnsignedShort()]));
        case 'C':
            return new Character((char) readInt(offset[readUnsignedShort()]));
        case 'D':
            return new Double(Double.longBitsToDouble(readLong(offset[readUnsignedShort()])));
        case 'F':
            return new Float(Float.intBitsToFloat(readInt(offset[readUnsignedShort()])));
        case 'I':
            return new Integer(readInt(offset[readUnsignedShort()]));
        case 'J':
            return new Long(readLong(offset[readUnsignedShort()]));
        case 'S':
            return new Short((short) readInt(offset[readUnsignedShort()]));
        case 'Z':
            return new Boolean(readInt(offset[readUnsignedShort()]) != 0);
        case 's':
            return getConstantPoolString(readUnsignedShort());
        case 'e': {
            // Return type is AnnotatinEnumVal.
            final String className = getConstantPoolClassDescriptor(readUnsignedShort());
            final String constName = getConstantPoolString(readUnsignedShort());
            return new AnnotationEnumValue(className, constName);
        }
        case 'c':
            // Return type is AnnotationClassRef (for class references in annotations)
            final String classRefTypeDescriptor = getConstantPoolString(readUnsignedShort());
            final String classRefTypeStr = ReflectionUtils.parseSimpleTypeDescriptor(classRefTypeDescriptor);
            return new AnnotationClassRef(classRefTypeStr);
        case '@':
            // Complex (nested) annotation.
            // Return type is AnnotationInfo.
            return readAnnotation();
        case '[':
            // Return type is Object[] (of nested annotation element values)
            final int count = readUnsignedShort();
            final Object[] arr = new Object[count];
            for (int i = 0; i < count; ++i) {
                // Nested annotation element value
                arr[i] = readAnnotationElementValue();
            }
            return arr;
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
    ClassInfoUnlinked readClassInfoFromClassfileHeader(final ClasspathElement classpathElement,
            final String relativePath, final InputStream inputStream, final ScanSpec scanSpec,
            final ConcurrentHashMap<String, String> stringInternMap, final LogNode log)
            throws IOException, InterruptedException {

        // This class instance can be reused across scans, to avoid re-allocating the buffer.
        // Initialize/clear fields for each new run. 
        this.inputStream = inputStream;
        className = null;
        curr = 0;

        // Read first bufferful
        used = 0;
        for (int bytesRead; used < INITIAL_BUFFER_CHUNK_SIZE
                && (bytesRead = inputStream.read(buf, used, INITIAL_BUFFER_CHUNK_SIZE - used)) != -1;) {
            used += bytesRead;
        }
        if (used == 0) {
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
                skip(4);
                break;
            case 12: // name and type
                final int nameRef = readUnsignedShort();
                final int typeRef = readUnsignedShort();
                indirectStringRefs[i] = (nameRef << 16) | typeRef;
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
            case 19: // module (for module-info.class in JDK9+)
                // see https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.4
                skip(2);
                break;
            case 20: // package (for module-info.class in JDK9+)
                // see https://docs.oracle.com/javase/specs/jvms/se9/html/jvms-4.html#jvms-4.4
                skip(2);
                break;
            default:
                throw new RuntimeException("Unknown constant pool tag " + tag[i] + " in classfile " + relativePath
                        + " (element size unknown, cannot continue reading class. Please report this on "
                        + "the FastClasspathScanner GitHub page.");
            }
        }

        // Modifier flags
        final int classModifierFlags = readUnsignedShort();
        final boolean isInterface = (classModifierFlags & 0x0200) != 0;
        final boolean isAnnotation = (classModifierFlags & 0x2000) != 0;
        final boolean isModule = (classModifierFlags & 0x8000) != 0;

        // TODO: not yet processing module-info class files
        if (isModule) {
            return null;
        }

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

        // Create holder object for the class information. This is "unlinked", in the sense that it is
        // not linked to other class info references at this point.
        final ClassInfoUnlinked classInfoUnlinked = new ClassInfoUnlinked(className, classModifierFlags,
                isInterface, isAnnotation, stringInternMap, classpathElement);

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
                    (!scanSpec.enableFieldInfo && (!scanSpec.enableFieldTypeIndexing && !matchThisStaticFinalField)
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
                if (matchThisStaticFinalField || scanSpec.enableFieldInfo) {
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
                if (scanSpec.enableFieldInfo) {
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
                List<AnnotationInfo> fieldAnnotationInfo = null;
                if (scanSpec.enableFieldInfo && fieldIsVisible) {
                    fieldAnnotationInfo = new ArrayList<>(1);
                }
                final int attributesCount = readUnsignedShort();
                for (int j = 0; j < attributesCount; j++) {
                    final int attributeNameCpIdx = readUnsignedShort();
                    final int attributeLength = readInt(); // == 2
                    // See if field name matches one of the requested names for this class, and if it does,
                    // check if it is initialized with a constant value
                    if ((isMatchedFieldName || scanSpec.enableFieldInfo)
                            && constantPoolStringEquals(attributeNameCpIdx, "ConstantValue")) {
                        // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.2
                        final int cpIdx = readUnsignedShort();
                        fieldConstValue = getConstantPoolValue(tag[cpIdx], fieldTypeDescriptorFirstChar, cpIdx);
                        // Store static final field match in ClassInfo object
                        if (isMatchedFieldName) {
                            classInfoUnlinked.addFieldConstantValue(fieldName, fieldConstValue);
                        }
                        foundFieldConstValue = true;
                    } else if ((scanSpec.enableFieldTypeIndexing || scanSpec.enableFieldInfo) && fieldIsVisible
                            && constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                        final String fieldTypeSignature = getConstantPoolString(readUnsignedShort());
                        fieldTypeDescriptor = fieldTypeSignature;
                        if (scanSpec.enableFieldTypeIndexing) {
                            // Check if the type signature of this field falls within a non-blacklisted
                            // package, and if so, record the field type. The type signature contains
                            // type parameters, whereas the type descriptor does not.
                            addFieldTypeDescriptorParts(classInfoUnlinked, fieldTypeSignature);
                            // Add type params to field type signature
                        }
                    } else if ((scanSpec.enableFieldInfo || scanSpec.enableFieldAnnotationIndexing)
                            && (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleAnnotations")
                                    || (scanSpec.annotationVisibility == RetentionPolicy.CLASS
                                            && constantPoolStringEquals(attributeNameCpIdx,
                                                    "RuntimeInvisibleAnnotations")))) {
                        // Read annotation names
                        final int annotationCount = readUnsignedShort();
                        for (int k = 0; k < annotationCount; k++) {
                            final AnnotationInfo fieldAnnotation = readAnnotation();
                            if (scanSpec.enableFieldAnnotationIndexing) {
                                classInfoUnlinked.addFieldAnnotation(fieldAnnotation);
                            }
                            if (fieldAnnotationInfo != null) {
                                fieldAnnotationInfo.add(fieldAnnotation);
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
                if (scanSpec.enableFieldInfo && fieldIsVisible) {
                    classInfoUnlinked.addFieldInfo(new FieldInfo(className, fieldName, fieldModifierFlags,
                            fieldTypeDescriptor, fieldConstValue, fieldAnnotationInfo));
                }
            }
        }

        // Methods
        final int methodCount = readUnsignedShort();
        for (int i = 0; i < methodCount; i++) {
            // Info on modifier flags: http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6
            final int methodModifierFlags = readUnsignedShort();
            final boolean isPublicMethod = ((methodModifierFlags & 0x0001) == 0x0001);
            final boolean methodIsVisible = isPublicMethod || scanSpec.ignoreMethodVisibility;

            String methodName = null;
            String methodTypeDescriptor = null;
            if (scanSpec.enableMethodInfo || isAnnotation) { // Annotations store defaults in method_info
                final int methodNameCpIdx = readUnsignedShort();
                methodName = getConstantPoolString(methodNameCpIdx);
                final int methodTypeDescriptorCpIdx = readUnsignedShort();
                methodTypeDescriptor = getConstantPoolString(methodTypeDescriptorCpIdx);
            } else {
                skip(4); // name_index, descriptor_index
            }
            final int attributesCount = readUnsignedShort();
            String[] methodParameterNames = null;
            int[] methodParameterAccessFlags = null;
            AnnotationInfo[][] methodParameterAnnotations = null;
            List<AnnotationParamValue> annotationParamDefaultValues = null;
            List<AnnotationInfo> methodAnnotationInfo = null;
            if (scanSpec.enableMethodInfo && methodIsVisible) {
                methodAnnotationInfo = new ArrayList<>(1);
            }
            if (!methodIsVisible
                    || (!scanSpec.enableMethodInfo && !isAnnotation && !scanSpec.enableMethodAnnotationIndexing)) {
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
                            final AnnotationInfo annotationInfo = readAnnotation();
                            if (scanSpec.enableMethodAnnotationIndexing) {
                                classInfoUnlinked.addMethodAnnotation(annotationInfo);
                            }
                            if (methodAnnotationInfo != null) {
                                methodAnnotationInfo.add(annotationInfo);
                            }
                        }
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "RuntimeVisibleParameterAnnotations")
                            || (scanSpec.annotationVisibility == RetentionPolicy.CLASS && constantPoolStringEquals(
                                    attributeNameCpIdx, "RuntimeInvisibleParameterAnnotations"))) {
                        final int paramCount = readUnsignedByte();
                        methodParameterAnnotations = new AnnotationInfo[paramCount][];
                        for (int k = 0; k < paramCount; k++) {
                            final int numAnnotations = readUnsignedShort();
                            methodParameterAnnotations[k] = new AnnotationInfo[numAnnotations];
                            for (int l = 0; l < numAnnotations; l++) {
                                methodParameterAnnotations[k][l] = readAnnotation();
                            }
                        }
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "MethodParameters")) {
                        // Read method parameters. For Java, these are only produced in JDK8+, and only if
                        // the commandline switch `-parameters` is provided at compiletime.
                        final int paramCount = readUnsignedByte();
                        methodParameterNames = new String[paramCount];
                        methodParameterAccessFlags = new int[paramCount];
                        for (int k = 0; k < paramCount; k++) {
                            final int cpIdx = readUnsignedShort();
                            // If the constant pool index is zero, then the parameter is unnamed => use null
                            methodParameterNames[k] = cpIdx == 0 ? null : getConstantPoolString(cpIdx);
                            methodParameterAccessFlags[k] = readUnsignedShort();
                        }
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "Signature")) {
                        // Add type params to method type signature
                        final String methodTypeSignature = getConstantPoolString(readUnsignedShort());
                        methodTypeDescriptor = methodTypeSignature;
                    } else if (constantPoolStringEquals(attributeNameCpIdx, "AnnotationDefault")) {
                        // Get annotation parameter default values
                        if (annotationParamDefaultValues == null) {
                            annotationParamDefaultValues = new ArrayList<>();
                        }
                        final Object annotationParamDefaultValue = readAnnotationElementValue();
                        annotationParamDefaultValues
                                .add(new AnnotationParamValue(methodName, annotationParamDefaultValue));
                    } else {
                        skip(attributeLength);
                    }
                }
            }
            if (methodIsVisible) {
                if (isAnnotation && annotationParamDefaultValues != null) {
                    classInfoUnlinked.addAnnotationParamDefaultValues(annotationParamDefaultValues);
                }
                if (scanSpec.enableMethodInfo) {
                    classInfoUnlinked.addMethodInfo(new MethodInfo(className, methodName, methodModifierFlags,
                            methodTypeDescriptor, methodParameterNames, methodParameterAccessFlags,
                            methodAnnotationInfo, methodParameterAnnotations));
                }
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
                    final AnnotationInfo classAnnotation = readAnnotation();
                    classInfoUnlinked.addClassAnnotation(classAnnotation);
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "InnerClasses")) {
                final int numInnerClasses = readUnsignedShort();
                for (int j = 0; j < numInnerClasses; j++) {
                    final int innerClassInfoCpIdx = readUnsignedShort();
                    final int outerClassInfoCpIdx = readUnsignedShort();
                    if (innerClassInfoCpIdx != 0 && outerClassInfoCpIdx != 0) {
                        classInfoUnlinked.addClassContainment(getConstantPoolClassName(innerClassInfoCpIdx),
                                getConstantPoolClassName(outerClassInfoCpIdx));
                    }
                    skip(2); // inner_name_idx
                    skip(2); // inner_class_access_flags
                }
            } else if (constantPoolStringEquals(attributeNameCpIdx, "EnclosingMethod")) {
                final String innermostEnclosingClassName = getConstantPoolClassName(readUnsignedShort());
                final int enclosingMethodCpIdx = readUnsignedShort();
                String enclosingMethodName;
                if (enclosingMethodCpIdx == 0) {
                    // A cpIdx of 0 (which is an invalid value) is used for anonymous inner classes declared
                    // in class initializer code, e.g. assigned to a class field.
                    enclosingMethodName = "<clinit>";
                } else {
                    enclosingMethodName = getConstantPoolString(enclosingMethodCpIdx, /* subFieldIdx = */ 0);
                    // Could also fetch field type signature with subFieldIdx = 1, if needed
                }
                // Link anonymous inner classes into the class with their containing method
                classInfoUnlinked.addClassContainment(className, innermostEnclosingClassName);
                // Also store the fully-qualified name of the enclosing method, to mark this as an anonymous
                // inner class
                classInfoUnlinked.addEnclosingMethod(innermostEnclosingClassName + "." + enclosingMethodName);
            } else {
                skip(attributeLength);
            }
        }
        return classInfoUnlinked;
    }
}
