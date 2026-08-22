package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.AnnotationClassRef;
import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.AnnotationParameterValueList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * The {@code class_info_index} of a tag {@code 'c'} annotation element value entry is specified (JVMS 4.7.16.1) to
 * refer to a {@code CONSTANT_Class} entry, but javac writes the type descriptor directly as a {@code CONSTANT_Utf8}
 * entry instead. Both encodings have to resolve to the referenced class.
 */
public class SyntheticClassRefEncodingTest {
    /**
     * Generate a minimal public class with a runtime visible annotation that has two {@code Class<?>}-valued
     * parameters: one encoded the way javac writes it (the type descriptor stored directly as a UTF8 constant), and
     * one encoded the way the JVMS specifies it (a reference to a {@code CONSTANT_Class} entry).
     *
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes() throws IOException {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(byteArrayOutputStream);
        try {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(12); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1); // modified UTF8: descriptor of the synthetic annotation type
            out.writeUTF("LSyntheticAnn;");
            // #2
            out.writeByte(1); // modified UTF8: name of the attribute holding the annotation
            out.writeUTF("RuntimeVisibleAnnotations");
            // #3
            out.writeByte(1); // modified UTF8: name of the first annotation parameter
            out.writeUTF("javacEncoding");
            // #4
            out.writeByte(1); // modified UTF8: the type descriptor itself, as javac writes it
            out.writeUTF("Ljava/lang/Object;");
            // #5
            out.writeByte(1); // modified UTF8: name of the second annotation parameter
            out.writeUTF("specEncoding");
            // #6
            out.writeByte(1); // modified UTF8: binary class name, as a CONSTANT_Class entry holds it
            out.writeUTF("java/lang/Object");
            // #7
            out.writeByte(7); // class ref to #6, as the JVMS specifies for a tag 'c' entry
            out.writeShort(6);
            // #8
            out.writeByte(1); // modified UTF8: binary class name of the scanned class
            out.writeUTF("Synthetic");
            // #9
            out.writeByte(7); // class ref to #8 (this_class)
            out.writeShort(8);
            // #10
            out.writeByte(1); // modified UTF8: binary class name of the superclass
            out.writeUTF("java/lang/Object");
            // #11
            out.writeByte(7); // class ref to #10 (super_class)
            out.writeShort(10);

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(9); // this_class
            out.writeShort(11); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields
            out.writeShort(0); // Number of methods

            // Single class attribute: RuntimeVisibleAnnotations
            out.writeShort(1);
            out.writeShort(2); // attribute_name_index => "RuntimeVisibleAnnotations"
            final int attributeLength =
                    // num_annotations + type_index + num_element_value_pairs
                    2 + 2 + 2
                    // each element value pair: name_index + tag + class_info_index
                            + 2 * (2 + 1 + 2);
            out.writeInt(attributeLength);
            out.writeShort(1); // num_annotations
            out.writeShort(1); // type_index => "LSyntheticAnn;"
            out.writeShort(2); // num_element_value_pairs
            out.writeShort(3); // parameter name => "javacEncoding"
            out.writeByte('c');
            out.writeShort(4); // class_info_index => UTF8 "Ljava/lang/Object;" (javac encoding)
            out.writeShort(5); // parameter name => "specEncoding"
            out.writeByte('c');
            out.writeShort(7); // class_info_index => Class ref to "java/lang/Object" (JVMS encoding)
        } finally {
            out.close();
        }
        return byteArrayOutputStream.toByteArray();
    }

    /** Both encodings of a tag {@code 'c'} element value resolve to the referenced class. */
    @Test
    public void bothEncodingsOfClassRefElementValuesResolve(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, "Synthetic.class").toPath(), classfileBytes());
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir).enableAnnotationInfo().scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo("Synthetic");
            assertThat(classInfo).isNotNull();
            final AnnotationInfoList annotations = classInfo.getAnnotationInfo();
            assertThat(annotations.getNames()).containsExactly("SyntheticAnn");
            final AnnotationParameterValueList parameterValues = annotations.get(0).getParameterValues();

            // javac encoding resolves
            assertThat(parameterValues.getValue("javacEncoding")).isInstanceOf(AnnotationClassRef.class);
            assertThat(((AnnotationClassRef) parameterValues.getValue("javacEncoding")).getName())
                    .isEqualTo("java.lang.Object");

            // JVMS-specified encoding also has to resolve
            assertThat(parameterValues.getValue("specEncoding")).isInstanceOf(AnnotationClassRef.class);
            assertThat(((AnnotationClassRef) parameterValues.getValue("specEncoding")).getName())
                    .isEqualTo("java.lang.Object");
        }
    }
}
