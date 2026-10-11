package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code const_value_index} of an annotation element value with tag {@code 'B'}, {@code 'C'}, {@code 'S'},
 * {@code 'Z'} or {@code 'I'} must refer to a {@code CONSTANT_Integer} entry, with tag {@code 'F'} to a
 * {@code CONSTANT_Float} entry, with tag {@code 'J'} to a {@code CONSTANT_Long} entry, and with tag {@code 'D'} to
 * a {@code CONSTANT_Double} entry (JVMS 4.7.16.1). An annotation attribute in which it refers to any other kind of
 * entry is skipped, rather than the bytes of that entry being read as a number.
 */
public class AnnotationConstantTagTest {
    /** The constant pool index of a {@code CONSTANT_Utf8} entry holding "abcdefgh". */
    private static final int UTF8_IDX = 8;

    /** The constant pool index of a {@code CONSTANT_Integer} entry holding 42. */
    private static final int INTEGER_IDX = 9;

    /** The constant pool index of a {@code CONSTANT_Float} entry holding 1.5f. */
    private static final int FLOAT_IDX = 10;

    /** The constant pool index of a {@code CONSTANT_Long} entry holding 7L. */
    private static final int LONG_IDX = 11;

    /** The constant pool index of a {@code CONSTANT_Double} entry holding 2.5. */
    private static final int DOUBLE_IDX = 13;

    /** The element value tags, mapped to the constant pool index of the entry that each must refer to. */
    private static final Map<Character, Integer> REQUIRED_IDX = Map.of('B', INTEGER_IDX, 'C', INTEGER_IDX, 'S',
            INTEGER_IDX, 'Z', INTEGER_IDX, 'I', INTEGER_IDX, 'F', FLOAT_IDX, 'J', LONG_IDX, 'D', DOUBLE_IDX);

    /** The element value tags, mapped to the value each reads from the entry it must refer to. */
    private static final Map<Character, Object> EXPECTED_VALUE = Map.of('B', (byte) 42, 'C', (char) 42, 'S',
            (short) 42, 'Z', true, 'I', 42, 'F', 1.5f, 'J', 7L, 'D', 2.5);

    /**
     * Write a classfile for a class annotated with {@code @p.Ann(v = <constant>)}.
     *
     * @param internalName
     *            the name of the class, with {@code '/'} as the separator.
     * @param elementTag
     *            the tag of the element value.
     * @param constValueIdx
     *            the constant pool index that the element value refers to.
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfile(final String internalName, final char elementTag, final int constValueIdx)
            throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)
            out.writeShort(15); // Constant pool count
            out.writeByte(1); // #1
            out.writeUTF(internalName);
            out.writeByte(7); // #2: class ref to #1
            out.writeShort(1);
            out.writeByte(1); // #3
            out.writeUTF("java/lang/Object");
            out.writeByte(7); // #4: class ref to #3
            out.writeShort(3);
            out.writeByte(1); // #5
            out.writeUTF("RuntimeVisibleAnnotations");
            out.writeByte(1); // #6
            out.writeUTF("Lp/Ann;");
            out.writeByte(1); // #7
            out.writeUTF("v");
            out.writeByte(1); // #8 (UTF8_IDX)
            out.writeUTF("abcdefgh");
            out.writeByte(3); // #9 (INTEGER_IDX)
            out.writeInt(42);
            out.writeByte(4); // #10 (FLOAT_IDX)
            out.writeFloat(1.5f);
            out.writeByte(5); // #11 (LONG_IDX), and #12
            out.writeLong(7L);
            out.writeByte(6); // #13 (DOUBLE_IDX), and #14
            out.writeDouble(2.5);
            out.writeShort(0x0421); // ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields
            out.writeShort(0); // Number of methods
            out.writeShort(1); // Number of attributes
            out.writeShort(5); // "RuntimeVisibleAnnotations"
            out.writeInt(2 + 2 + 2 + 2 + 1 + 2);
            out.writeShort(1); // num_annotations
            out.writeShort(6); // type_index
            out.writeShort(1); // num_element_value_pairs
            out.writeShort(7); // element_name_index
            out.writeByte(elementTag);
            out.writeShort(constValueIdx);
            out.writeShort(0); // Number of attributes
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * An element value is read from a constant pool entry of the kind its tag requires, and an annotation attribute
     * whose element value refers to an entry of any other kind is skipped.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void anElementValueIsReadOnlyFromAConstantOfTheKindItsTagRequires(@TempDir final Path tempDir)
            throws IOException {
        Files.createDirectories(tempDir.resolve("p"));
        final int[] indices = { UTF8_IDX, INTEGER_IDX, FLOAT_IDX, LONG_IDX, DOUBLE_IDX };
        for (final var tag : REQUIRED_IDX.keySet()) {
            for (final var idx : indices) {
                final var name = "p/C" + tag + idx;
                Files.write(tempDir.resolve(name + ".class"), classfile(name, tag, idx));
            }
        }
        try (var scanResult = new ClassGraph().enableClassInfo().enableAnnotationInfo()
                .enableClasspathEntries(tempDir).scan()) {
            for (final var tag : REQUIRED_IDX.keySet()) {
                for (final var idx : indices) {
                    final var classInfo = scanResult.getClassInfo("p.C" + tag + idx);
                    final var description = "tag '" + tag + "', constant pool index " + idx;
                    assertThat(classInfo).as(description).isNotNull();
                    final var annotationInfoList = classInfo.getAllAnnotationInfo();
                    if (idx == REQUIRED_IDX.get(tag)) {
                        assertThat(annotationInfoList).as(description).hasSize(1);
                        assertThat(annotationInfoList.get(0).getParameterValues().getValue("v")).as(description)
                                .isEqualTo(EXPECTED_VALUE.get(tag));
                    } else {
                        assertThat(annotationInfoList).as(description).isEmpty();
                    }
                }
            }
        }
    }
}
