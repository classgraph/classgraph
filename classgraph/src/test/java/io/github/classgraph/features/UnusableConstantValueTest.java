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
 * The JVM ignores the {@code ConstantValue} attribute of a non-static field (JVMS 4.7.2), so a classfile whose
 * non-static field has a {@code ConstantValue} attribute that does not fit the field still loads. Such an attribute
 * has to be ignored, not make the whole class unreadable.
 */
public class UnusableConstantValueTest {
    /** The name of the generated class. */
    private static final String CLASS_NAME = "ConstantValues";

    /**
     * Generate a class with three fields: a static int field with the constant value 5, a non-static {@code Object}
     * field whose {@code ConstantValue} attribute holds an int, and a non-static int field whose
     * {@code ConstantValue} attribute is 4 bytes long instead of 2.
     *
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes() throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(12); // Constant pool count (one greater than the number of entries)
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
            out.writeUTF("ConstantValue");
            // #6
            out.writeByte(3); // CONSTANT_Integer
            out.writeInt(5);
            // #7
            out.writeByte(1);
            out.writeUTF("I");
            // #8
            out.writeByte(1);
            out.writeUTF("Ljava/lang/Object;");
            // #9
            out.writeByte(1);
            out.writeUTF("staticInt");
            // #10
            out.writeByte(1);
            out.writeUTF("objectWithIntValue");
            // #11
            out.writeByte(1);
            out.writeUTF("intWithLongAttribute");

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces

            out.writeShort(3); // Number of fields
            // public static final int staticInt = 5
            out.writeShort(0x0019);
            out.writeShort(9);
            out.writeShort(7);
            out.writeShort(1); // attributes_count
            out.writeShort(5); // "ConstantValue"
            out.writeInt(2);
            out.writeShort(6); // 5
            // public final Object objectWithIntValue, with an int constant value
            out.writeShort(0x0011);
            out.writeShort(10);
            out.writeShort(8);
            out.writeShort(1); // attributes_count
            out.writeShort(5); // "ConstantValue"
            out.writeInt(2);
            out.writeShort(6); // 5
            // public final int intWithLongAttribute, with a 4-byte ConstantValue attribute
            out.writeShort(0x0011);
            out.writeShort(11);
            out.writeShort(7);
            out.writeShort(1); // attributes_count
            out.writeShort(5); // "ConstantValue"
            out.writeInt(4);
            out.writeShort(6); // 5
            out.writeShort(0xFFFF); // Two extra bytes, which would be read as methods_count if not skipped

            out.writeShort(0); // Number of methods
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
     * The JVM loads the generated class.
     *
     * @throws Exception
     *             if the class could not be loaded
     */
    @Test
    public void theJvmLoadsTheClass() throws Exception {
        final var cls = new ByteArrayClassLoader().define(classfileBytes());
        assertThat(cls.getField("staticInt").getInt(null)).isEqualTo(5);
        assertThat(cls.getDeclaredFields()).hasSize(3);
    }

    /**
     * ClassGraph reads the generated class, and returns a constant initializer value only for the field whose
     * {@code ConstantValue} attribute fits it.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void anUnusableConstantValueIsIgnored(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, CLASS_NAME + ".class").toPath(), classfileBytes());
        try (var scanResult = new ClassGraph().enableClassInfo().enableFieldInfo()
                .enableStaticFinalFieldConstantInitializerValues().enableClasspathEntries(tempDir).scan()) {
            final var classInfo = scanResult.getClassInfo(CLASS_NAME);
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.getFieldInfo().getNames()).containsExactlyInAnyOrder("staticInt",
                    "objectWithIntValue", "intWithLongAttribute");
            assertThat(classInfo.getFieldInfo("staticInt").getConstantInitializerValue()).isEqualTo(5);
            assertThat(classInfo.getFieldInfo("objectWithIntValue").getConstantInitializerValue()).isNull();
            assertThat(classInfo.getFieldInfo("intWithLongAttribute").getConstantInitializerValue()).isNull();
        }
    }
}
