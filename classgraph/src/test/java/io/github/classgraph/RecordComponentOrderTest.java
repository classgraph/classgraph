package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The record components of a record are listed in declaration order, which is the order of the record's
 * {@code Record} attribute, not the order of its fields, which are sorted by name.
 */
public class RecordComponentOrderTest {
    /** A record whose components are not in alphabetical order. */
    public record R(int zb, String za) {
    }

    /** A record with no components. */
    public record E() {
    }

    /**
     * Scan the records of this test class.
     *
     * @return the scan result.
     */
    private static ScanResult scanRecords() {
        return new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(RecordComponentOrderTest.class.getPackage().getName()).enableClassInfo()
                .enableFieldInfo().ignoreClassVisibility().ignoreFieldVisibility().scan();
    }

    /** The record components are listed in declaration order. */
    @Test
    public void recordComponentsAreListedInDeclarationOrder() {
        try (var scanResult = scanRecords()) {
            assertThat(scanResult.getClassInfo(R.class.getName()).toString())
                    .isEqualTo("public static final record " + R.class.getName() + "(int zb, java.lang.String za)");
        }
    }

    /** Without field info, the types of the record components are not known, so no component list is rendered. */
    @Test
    public void withoutFieldInfoNoComponentListIsRendered() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptPackagesNonRecursive(RecordComponentOrderTest.class.getPackage().getName()).enableClassInfo()
                .ignoreClassVisibility().scan()) {
            assertThat(scanResult.getClassInfo(R.class.getName()).toString())
                    .isEqualTo("public static final record " + R.class.getName());
        }
    }

    /** A record with no components is listed with an empty component list. */
    @Test
    public void aRecordWithNoComponentsHasAnEmptyComponentList() {
        try (var scanResult = scanRecords()) {
            assertThat(scanResult.getClassInfo(E.class.getName()).toString())
                    .isEqualTo("public static final record " + E.class.getName() + "()");
        }
    }

    /**
     * Write the classfile of the record {@code p.R}, which has the one component {@code int x}, and a
     * {@code SourceFile} attribute after its {@code Record} attribute.
     *
     * @param componentsCount
     *            the {@code components_count} written in the {@code Record} attribute.
     * @param componentAttributeLength
     *            the {@code attribute_length} of an attribute of the component, or -1 for the component to have no
     *            attributes.
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] recordClassfile(final int componentsCount, final int componentAttributeLength)
            throws IOException {
        return recordClassfile(componentsCount, componentAttributeLength, /* secondComponentsCount = */ -1);
    }

    /**
     * Write the classfile of the record {@code p.R}, which has the one component {@code int x}, and a
     * {@code SourceFile} attribute after its {@code Record} attribute, optionally followed by a second
     * {@code Record} attribute that also lists the one component {@code int x}.
     *
     * @param componentsCount
     *            the {@code components_count} written in the {@code Record} attribute.
     * @param componentAttributeLength
     *            the {@code attribute_length} of an attribute of the component, or -1 for the component to have no
     *            attributes.
     * @param secondComponentsCount
     *            the {@code components_count} written in the second {@code Record} attribute, or -1 for there to be
     *            no second {@code Record} attribute.
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] recordClassfile(final int componentsCount, final int componentAttributeLength,
            final int secondComponentsCount) throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(61); // Major version (Java 17)
            out.writeShort(11); // Constant pool count
            out.writeByte(1); // #1
            out.writeUTF("p/R");
            out.writeByte(7); // #2: class ref to #1
            out.writeShort(1);
            out.writeByte(1); // #3
            out.writeUTF("java/lang/Record");
            out.writeByte(7); // #4: class ref to #3
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
            out.writeUTF("R.java");
            out.writeByte(1); // #10
            out.writeUTF("Signature");
            out.writeShort(0x0031); // ACC_PUBLIC | ACC_FINAL | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(1); // Number of fields
            out.writeShort(0x0012); // ACC_PRIVATE | ACC_FINAL
            out.writeShort(5); // "x"
            out.writeShort(6); // "I"
            out.writeShort(0); // Number of attributes
            out.writeShort(0); // Number of methods
            out.writeShort(secondComponentsCount == -1 ? 2 : 3); // Number of attributes
            out.writeShort(7); // "Record"
            final var hasComponentAttribute = componentAttributeLength != -1;
            out.writeInt(2 + 6 + (hasComponentAttribute ? 6 : 0));
            out.writeShort(componentsCount);
            out.writeShort(5); // name_index: "x"
            out.writeShort(6); // descriptor_index: "I"
            if (hasComponentAttribute) {
                out.writeShort(1); // attributes_count
                out.writeShort(10); // attribute_name_index: "Signature"
                out.writeInt(componentAttributeLength);
            } else {
                out.writeShort(0); // attributes_count
            }
            out.writeShort(8); // "SourceFile"
            out.writeInt(2);
            out.writeShort(9); // "R.java"
            if (secondComponentsCount != -1) {
                out.writeShort(7); // "Record"
                out.writeInt(2 + 6);
                out.writeShort(secondComponentsCount);
                out.writeShort(5); // name_index: "x"
                out.writeShort(6); // descriptor_index: "I"
                out.writeShort(0); // attributes_count
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Scan a classpath directory that holds the given classfile of {@code p.R}, and return the class.
     *
     * @param dir
     *            the directory.
     * @param classfile
     *            the classfile.
     * @return the string form of the class, followed by its source file.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static String scanRecordClassfile(final Path dir, final byte[] classfile) throws IOException {
        Files.createDirectories(dir.resolve("p"));
        Files.write(dir.resolve("p/R.class"), classfile);
        try (var scanResult = new ClassGraph().enableClassInfo().enableFieldInfo().ignoreFieldVisibility()
                .enableClasspathEntries(dir).scan()) {
            final var classInfo = scanResult.getClassInfo("p.R");
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.isRecord()).isTrue();
            return classInfo + " in " + classInfo.getSourceFile();
        }
    }

    /**
     * A malformed {@code Record} attribute is skipped, as other malformed attributes are, so the class is kept with
     * no component list, and the attribute after it is still read. The attribute is malformed if it holds more
     * components than its length leaves room for, or a component's attribute is longer than the classfile or has a
     * negative length.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void aMalformedRecordAttributeIsSkipped(@TempDir final Path tempDir) throws IOException {
        assertThat(scanRecordClassfile(tempDir.resolve("valid"), recordClassfile(1, -1)))
                .isEqualTo("public final record p.R(int x) in R.java");
        assertThat(scanRecordClassfile(tempDir.resolve("validWithAttribute"), recordClassfile(1, 0)))
                .isEqualTo("public final record p.R(int x) in R.java");
        assertThat(scanRecordClassfile(tempDir.resolve("tooManyComponents"), recordClassfile(2, -1)))
                .isEqualTo("public final record p.R in R.java");
        assertThat(scanRecordClassfile(tempDir.resolve("componentAttributeTooLong"), recordClassfile(1, 1000)))
                .isEqualTo("public final record p.R in R.java");
        assertThat(scanRecordClassfile(tempDir.resolve("negativeComponentAttributeLength"), recordClassfile(1, -2)))
                .isEqualTo("public final record p.R in R.java");
    }

    /**
     * A second {@code Record} attribute, which a class should not have, replaces the component names read from the
     * first if it is well-formed, and is skipped, keeping them, if it is malformed.
     *
     * @param tempDir
     *            a temporary directory.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void aMalformedSecondRecordAttributeKeepsTheComponentsOfTheFirst(@TempDir final Path tempDir)
            throws IOException {
        assertThat(scanRecordClassfile(tempDir.resolve("validSecond"), recordClassfile(1, -1, 1)))
                .isEqualTo("public final record p.R(int x) in R.java");
        assertThat(scanRecordClassfile(tempDir.resolve("malformedSecond"), recordClassfile(1, -1, 2)))
                .isEqualTo("public final record p.R(int x) in R.java");
    }
}
