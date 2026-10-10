package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * Tests of how methods are read from a classfile: the line number range of a method whose {@code LineNumberTable}
 * holds an entry for line 0, and a non-public method that is skipped without reading its name or descriptor.
 */
public class MethodParsingTest {
    /** The name of the generated class. */
    private static final String CLASS_NAME = "MethodLines";

    /** The line numbers in the {@code LineNumberTable} of each public method, in table order. */
    private static final int[][] LINE_NUMBERS = { { 5, 0, 7 }, { 0, 5, 7 }, { 5, 7, 0 }, { 0 } };

    /** The names of the public methods, one for each element of {@link #LINE_NUMBERS}. */
    private static final String[] METHOD_NAMES = { "a", "b", "c", "d" };

    /**
     * Generate a class with one public static method {@code void x()} for each element of {@link #LINE_NUMBERS},
     * whose {@code LineNumberTable} holds those line numbers in that order.
     *
     * @param withBadPrivateMethod
     *            if true, also add a private method whose name and descriptor indices are outside the constant pool
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes(final boolean withBadPrivateMethod) throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(8 + METHOD_NAMES.length); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1);
            out.writeUTF(CLASS_NAME);
            // #2
            out.writeByte(7); // class ref to #1 (this_class)
            out.writeShort(1);
            // #3
            out.writeByte(1);
            out.writeUTF("java/lang/Object");
            // #4
            out.writeByte(7); // class ref to #3 (super_class)
            out.writeShort(3);
            // #5
            out.writeByte(1);
            out.writeUTF("Code");
            // #6
            out.writeByte(1);
            out.writeUTF("LineNumberTable");
            // #7
            out.writeByte(1);
            out.writeUTF("()V");
            // #8 onwards
            for (final var methodName : METHOD_NAMES) {
                out.writeByte(1);
                out.writeUTF(methodName);
            }

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields

            out.writeShort(METHOD_NAMES.length + (withBadPrivateMethod ? 1 : 0)); // Number of methods
            if (withBadPrivateMethod) {
                out.writeShort(0x000A); // ACC_PRIVATE | ACC_STATIC
                out.writeShort(0xFFFF); // name_index, outside the constant pool
                out.writeShort(0xFFFF); // descriptor_index, outside the constant pool
                out.writeShort(0); // attributes_count
            }
            for (var i = 0; i < METHOD_NAMES.length; i++) {
                final var lineNumbers = LINE_NUMBERS[i];
                out.writeShort(0x0009); // ACC_PUBLIC | ACC_STATIC
                out.writeShort(8 + i); // name_index
                out.writeShort(7); // descriptor_index: "()V"
                out.writeShort(1); // attributes_count
                out.writeShort(5); // "Code"
                final var lineNumberTableLength = 2 + 4 * lineNumbers.length;
                out.writeInt(2 + 2 + 4 + 1 + 2 + 2 + 2 + 4 + lineNumberTableLength);
                out.writeShort(0); // max_stack
                out.writeShort(0); // max_locals
                out.writeInt(1); // code_length
                out.writeByte(0xB1); // return
                out.writeShort(0); // exception_table_length
                out.writeShort(1); // attributes_count
                out.writeShort(6); // "LineNumberTable"
                out.writeInt(lineNumberTableLength);
                out.writeShort(lineNumbers.length);
                for (final var lineNumber : lineNumbers) {
                    out.writeShort(0); // start_pc
                    out.writeShort(lineNumber);
                }
            }
            out.writeShort(0); // Number of attributes
        }
        return byteArrayOutputStream.toByteArray();
    }

    /** A class loader that defines one class from its bytes. */
    private static class ByteArrayClassLoader extends ClassLoader {
        /**
         * Define a class.
         *
         * @param bytes
         *            the classfile bytes
         * @return the class
         */
        Class<?> define(final byte[] bytes) {
            return defineClass(CLASS_NAME, bytes, 0, bytes.length);
        }
    }

    /**
     * The JVM loads the generated class, so a line number of 0 is allowed in a {@code LineNumberTable}.
     *
     * @throws Exception
     *             if the class could not be loaded or a method could not be called
     */
    @Test
    public void theJvmLoadsTheClass() throws Exception {
        final var cls = new ByteArrayClassLoader().define(classfileBytes(false));
        for (final var methodName : METHOD_NAMES) {
            cls.getMethod(methodName).invoke(null);
        }
    }

    /**
     * A line number of 0, which means no line, is ignored wherever it appears in the {@code LineNumberTable}.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void lineNumberZeroIsIgnored(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, CLASS_NAME + ".class").toPath(), classfileBytes(false));
        try (var scanResult = new ClassGraph().enableClassInfo().enableMethodInfo().enableClasspathEntries(tempDir)
                .scan()) {
            final var classInfo = scanResult.getClassInfo(CLASS_NAME);
            assertThat(classInfo).isNotNull();
            for (final var methodName : new String[] { "a", "b", "c" }) {
                final var methodInfo = classInfo.getMethodInfo(methodName).get(0);
                assertThat(methodInfo.getMinLineNum()).as("minimum line of " + methodName).isEqualTo(5);
                assertThat(methodInfo.getMaxLineNum()).as("maximum line of " + methodName).isEqualTo(7);
            }
            final var methodInfo = classInfo.getMethodInfo("d").get(0);
            assertThat(methodInfo.getMinLineNum()).isZero();
            assertThat(methodInfo.getMaxLineNum()).isZero();
        }
    }

    /**
     * A method that is skipped because it is not public is skipped without looking up its name or descriptor in the
     * constant pool, as a skipped field is.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void aSkippedMethodsNameIsNotRead(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, CLASS_NAME + ".class").toPath(), classfileBytes(true));
        try (var scanResult = new ClassGraph().enableClassInfo().enableMethodInfo().enableClasspathEntries(tempDir)
                .scan()) {
            final var classInfo = scanResult.getClassInfo(CLASS_NAME);
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.getMethodInfo().getNames()).containsExactlyInAnyOrder(METHOD_NAMES);
        }
    }
}
