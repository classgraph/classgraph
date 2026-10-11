package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Field and method type descriptors are parsed when they are first needed, not during the scan, so a corrupt
 * classfile whose descriptors do not parse is still scanned. The getters that parse a descriptor then throw
 * {@link IllegalArgumentException}, but {@code toString()} must not throw, and an annotation whose annotation class
 * has such a descriptor must still give its parameter values.
 */
public class UnparseableDescriptorTest {
    /** The name of the generated record class. */
    private static final String RECORD_NAME = "BadDescriptors";

    /** The name of the generated annotation class. */
    private static final String ANNOTATION_NAME = "BadAnn";

    /** The directory that the classfiles are written to. */
    @TempDir
    File tempDir;

    /**
     * Generate a final record class {@code BadDescriptors}, annotated with {@code @BadAnn(value = {1})}, with one
     * record component {@code x} whose field descriptor is {@code "Ljava/lang/Object"} (without its closing ';'),
     * and with one abstract method {@code m} whose method descriptor is {@code "(Ljava/lang/Object)V"}.
     *
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] recordClassfileBytes() throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(61); // Major version (Java 17)

            out.writeShort(14); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1);
            out.writeUTF(RECORD_NAME);
            // #2
            out.writeByte(7); // class ref to #1 (this_class)
            out.writeShort(1);
            // #3
            out.writeByte(1);
            out.writeUTF("java/lang/Record");
            // #4
            out.writeByte(7); // class ref to #3 (super_class)
            out.writeShort(3);
            // #5
            out.writeByte(1);
            out.writeUTF("x");
            // #6
            out.writeByte(1);
            out.writeUTF("Ljava/lang/Object"); // The field descriptor, without its closing ';'
            // #7
            out.writeByte(1);
            out.writeUTF("m");
            // #8
            out.writeByte(1);
            out.writeUTF("(Ljava/lang/Object)V"); // The method descriptor, without the ';' of its parameter
            // #9
            out.writeByte(1);
            out.writeUTF("Record");
            // #10
            out.writeByte(1);
            out.writeUTF("L" + ANNOTATION_NAME + ";");
            // #11
            out.writeByte(1);
            out.writeUTF("RuntimeVisibleAnnotations");
            // #12
            out.writeByte(1);
            out.writeUTF("value");
            // #13
            out.writeByte(3); // CONSTANT_Integer
            out.writeInt(1);

            out.writeShort(0x0031); // ACC_PUBLIC | ACC_FINAL | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces

            out.writeShort(1); // Number of fields
            // private final Object x
            out.writeShort(0x0012);
            out.writeShort(5);
            out.writeShort(6);
            out.writeShort(0); // attributes_count

            out.writeShort(1); // Number of methods
            // public abstract void m(Object)
            out.writeShort(0x0401);
            out.writeShort(7);
            out.writeShort(8);
            out.writeShort(0); // attributes_count

            out.writeShort(2); // Number of attributes
            out.writeShort(9); // "Record"
            out.writeInt(8);
            out.writeShort(1); // components_count
            out.writeShort(5); // name_index: "x"
            out.writeShort(6); // descriptor_index
            out.writeShort(0); // attributes_count
            out.writeShort(11); // "RuntimeVisibleAnnotations"
            out.writeInt(14);
            out.writeShort(1); // num_annotations
            out.writeShort(10); // type_index: "LBadAnn;"
            out.writeShort(1); // num_element_value_pairs
            out.writeShort(12); // element_name_index: "value"
            out.writeByte('['); // array element value
            out.writeShort(1); // num_values
            out.writeByte('I'); // int element value
            out.writeShort(13); // const_value_index: 1
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Generate an annotation class {@code BadAnn} with one element {@code value} whose method descriptor is
     * {@code "()[Ljava/lang/Object"} (without its closing ';').
     *
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] annotationClassfileBytes() throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(61); // Major version (Java 17)

            out.writeShort(9); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1);
            out.writeUTF(ANNOTATION_NAME);
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
            out.writeUTF("java/lang/annotation/Annotation");
            // #6
            out.writeByte(7); // class ref to #5 (superinterface)
            out.writeShort(5);
            // #7
            out.writeByte(1);
            out.writeUTF("value");
            // #8
            out.writeByte(1);
            out.writeUTF("()[Ljava/lang/Object"); // The method descriptor, without its closing ';'

            out.writeShort(0x2601); // ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT | ACC_ANNOTATION
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(1); // Number of interfaces
            out.writeShort(6);
            out.writeShort(0); // Number of fields

            out.writeShort(1); // Number of methods
            // public abstract Object[] value()
            out.writeShort(0x0401);
            out.writeShort(7);
            out.writeShort(8);
            out.writeShort(0); // attributes_count

            out.writeShort(0); // Number of attributes
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Write the classfiles to the temporary directory.
     *
     * @throws IOException
     *             if a classfile could not be written
     */
    @BeforeEach
    void writeClassfiles() throws IOException {
        Files.write(new File(tempDir, RECORD_NAME + ".class").toPath(), recordClassfileBytes());
        Files.write(new File(tempDir, ANNOTATION_NAME + ".class").toPath(), annotationClassfileBytes());
    }

    /**
     * Scan the classfiles.
     *
     * @return the scan result.
     */
    private ScanResult scan() {
        return new ClassGraph().enableClassInfo().enableFieldInfo().ignoreFieldVisibility().enableMethodInfo()
                .enableAnnotationInfo().enableClasspathEntries(tempDir).scan();
    }

    /** A field whose descriptor does not parse shows the raw descriptor in place of its type. */
    @Test
    public void fieldToStringShowsTheRawDescriptor() {
        try (var scanResult = scan()) {
            final var fieldInfo = scanResult.getClassInfo(RECORD_NAME).getDeclaredFieldInfo("x");
            assertThatThrownBy(fieldInfo::getTypeDescriptor).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(fieldInfo::getTypeSignatureOrTypeDescriptor)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(fieldInfo).hasToString("private final Ljava/lang/Object x");
        }
    }

    /** A record whose component has a descriptor that does not parse shows the raw descriptor in its toString. */
    @Test
    public void recordToStringShowsTheRawComponentDescriptor() {
        try (var scanResult = scan()) {
            assertThat(scanResult.getClassInfo(RECORD_NAME).toString()).contains("(Ljava/lang/Object x)");
        }
    }

    /** A method whose descriptor does not parse shows the raw descriptor in place of its types. */
    @Test
    public void methodToStringShowsTheRawDescriptor() {
        try (var scanResult = scan()) {
            final var methodInfo = scanResult.getClassInfo(RECORD_NAME).getDeclaredMethodInfo("m").get(0);
            assertThatThrownBy(methodInfo::getTypeDescriptor).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(methodInfo::getTypeSignatureOrTypeDescriptor)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(methodInfo.getParameterInfo()).isEmpty();
            assertThat(methodInfo).hasToString("public abstract m(Ljava/lang/Object)V");
        }
    }

    /**
     * An array-valued annotation parameter is left unconverted if the annotation class gives the parameter a type
     * descriptor that does not parse, as it is when the annotation class gives the parameter a type that is not a
     * one-dimensional array.
     */
    @Test
    public void annotationParameterValuesIgnoreAnUnparseableDescriptor() {
        try (var scanResult = scan()) {
            final var annotationInfo = scanResult.getClassInfo(RECORD_NAME).getAllAnnotationInfo()
                    .get(ANNOTATION_NAME);
            assertThat(annotationInfo.getParameterValues().getValue("value")).isEqualTo(new Object[] { 1 });
            assertThat(annotationInfo).hasToString("@BadAnn({1})");
        }
    }
}
