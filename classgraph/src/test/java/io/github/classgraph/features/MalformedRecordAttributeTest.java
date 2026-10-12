package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.classgraph.ClassGraph;

/**
 * The names of the record components are read from the {@code Record} attribute, to list the components in
 * declaration order. A malformed {@code Record} attribute loses only that list: the class is still scanned, and the
 * attributes after it are still read.
 */
public class MalformedRecordAttributeTest {
    /** The name of the generated record class. */
    private static final String RECORD_NAME = "Rec";

    /**
     * Generate a final record class {@code Rec} with one record component {@code int x}, whose {@code Record}
     * attribute is followed by a {@code SourceFile} attribute.
     *
     * @param componentsCount
     *            the {@code components_count} to write into the {@code Record} attribute, which holds one component
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] recordClassfileBytes(final int componentsCount) throws IOException {
        return recordClassfileBytes(componentsCount, /* secondComponentsCount = */ -1);
    }

    /**
     * Generate a final record class {@code Rec} with one record component {@code int x}, whose {@code Record}
     * attribute is followed by a {@code SourceFile} attribute, and optionally by a second {@code Record} attribute
     * that also holds the one component {@code int x}.
     *
     * @param componentsCount
     *            the {@code components_count} to write into the {@code Record} attribute, which holds one component
     * @param secondComponentsCount
     *            the {@code components_count} to write into the second {@code Record} attribute, which holds one
     *            component, or -1 for there to be no second {@code Record} attribute
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] recordClassfileBytes(final int componentsCount, final int secondComponentsCount)
            throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(61); // Major version (Java 17)

            out.writeShort(10); // Constant pool count (one greater than the number of entries)
            out.writeByte(1); // #1
            out.writeUTF(RECORD_NAME);
            out.writeByte(7); // #2: class ref to #1 (this_class)
            out.writeShort(1);
            out.writeByte(1); // #3
            out.writeUTF("java/lang/Record");
            out.writeByte(7); // #4: class ref to #3 (super_class)
            out.writeShort(3);
            out.writeByte(1); // #5
            out.writeUTF("x");
            out.writeByte(1); // #6
            out.writeUTF("I");
            out.writeByte(1); // #7
            out.writeUTF("Record");
            out.writeByte(1); // #8
            out.writeUTF("SourceFile");
            out.writeByte(1); // #9
            out.writeUTF(RECORD_NAME + ".java");

            out.writeShort(0x0031); // ACC_PUBLIC | ACC_FINAL | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces

            out.writeShort(1); // Number of fields
            // private final int x
            out.writeShort(0x0012);
            out.writeShort(5);
            out.writeShort(6);
            out.writeShort(0); // attributes_count

            out.writeShort(0); // Number of methods

            out.writeShort(secondComponentsCount == -1 ? 2 : 3); // Number of attributes
            out.writeShort(7); // "Record"
            out.writeInt(8);
            out.writeShort(componentsCount);
            out.writeShort(5); // name_index: "x"
            out.writeShort(6); // descriptor_index: "I"
            out.writeShort(0); // attributes_count
            out.writeShort(8); // "SourceFile"
            out.writeInt(2);
            out.writeShort(9); // sourcefile_index
            if (secondComponentsCount != -1) {
                out.writeShort(7); // "Record"
                out.writeInt(8);
                out.writeShort(secondComponentsCount);
                out.writeShort(5); // name_index: "x"
                out.writeShort(6); // descriptor_index: "I"
                out.writeShort(0); // attributes_count
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * A {@code Record} attribute whose {@code components_count} is larger than the number of components it holds is
     * malformed, and reading the missing components runs into the {@code SourceFile} attribute after it, and then
     * past the end of the classfile. The record is still scanned, and its source file is still read, but its
     * component list is omitted. A well-formed attribute gives the component list.
     *
     * @param componentsCount
     *            the {@code components_count} written into the {@code Record} attribute
     * @param expectedToString
     *            the expected {@code toString()} of the record
     * @param tempDir
     *            a temporary directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = { //
            "1 | public final record Rec(int x)", //
            "2 | public final record Rec" })
    public void aMalformedRecordAttributeLosesOnlyTheComponentList(final int componentsCount,
            final String expectedToString, @TempDir final Path tempDir) throws IOException {
        Files.write(tempDir.resolve(RECORD_NAME + ".class"), recordClassfileBytes(componentsCount));
        try (var scanResult = new ClassGraph().enableClassInfo().enableFieldInfo().ignoreFieldVisibility()
                .enableClasspathEntries(tempDir).scan()) {
            final var classInfo = scanResult.getClassInfo(RECORD_NAME);
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.isRecord()).isTrue();
            assertThat(classInfo.getSourceFile()).isEqualTo(RECORD_NAME + ".java");
            assertThat(classInfo).hasToString(expectedToString);
        }
    }

    /**
     * A second {@code Record} attribute, which a class should not have, replaces the component names read from the
     * first if it is well-formed, and is skipped, keeping them, if it is malformed.
     *
     * @param secondComponentsCount
     *            the {@code components_count} written into the second {@code Record} attribute
     * @param tempDir
     *            a temporary directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written.
     */
    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    public void aMalformedSecondRecordAttributeKeepsTheComponentsOfTheFirst(final int secondComponentsCount,
            @TempDir final Path tempDir) throws IOException {
        Files.write(tempDir.resolve(RECORD_NAME + ".class"), recordClassfileBytes(1, secondComponentsCount));
        try (var scanResult = new ClassGraph().enableClassInfo().enableFieldInfo().ignoreFieldVisibility()
                .enableClasspathEntries(tempDir).scan()) {
            final var classInfo = scanResult.getClassInfo(RECORD_NAME);
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.isRecord()).isTrue();
            assertThat(classInfo.getSourceFile()).isEqualTo(RECORD_NAME + ".java");
            assertThat(classInfo).hasToString("public final record Rec(int x)");
        }
    }
}
