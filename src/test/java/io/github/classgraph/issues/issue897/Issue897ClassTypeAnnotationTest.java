package io.github.classgraph.issues.issue897;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.ClassTypeSignature;
import io.github.classgraph.ScanResult;

/**
 * A type annotation on a class that cannot be matched to the class type is skipped, as it is for a field or a
 * method, rather than making the whole class type unreadable.
 */
public class Issue897ClassTypeAnnotationTest {
    /**
     * Generate a class that extends {@code java.lang.Number}, with two type annotations {@code @Ann} on the same
     * type: the first with an {@code [ARRAY]} type path, which cannot apply to a type parameter or to a class type,
     * and the second with an empty type path. (The superclass is not {@code java.lang.Object}, since the synthetic
     * type descriptor of a class has no superclass signature for {@code java.lang.Object}.)
     *
     * @param className
     *            the name of the class.
     * @param signature
     *            the class type signature, or null to write no {@code Signature} attribute.
     * @param target
     *            the {@code target_type} and {@code target_info} of both type annotations.
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes(final String className, final String signature, final byte[] target)
            throws IOException {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            // Constant pool count (one greater than the number of entries)
            out.writeShort(signature == null ? 7 : 9);
            // #1
            out.writeByte(1);
            out.writeUTF(className);
            // #2
            out.writeByte(7); // class ref to #1 (this_class)
            out.writeShort(1);
            // #3
            out.writeByte(1);
            out.writeUTF("java/lang/Number");
            // #4
            out.writeByte(7); // class ref to #3 (super_class)
            out.writeShort(3);
            // #5
            out.writeByte(1);
            out.writeUTF("RuntimeVisibleTypeAnnotations");
            // #6
            out.writeByte(1);
            out.writeUTF("LAnn;");
            if (signature != null) {
                // #7
                out.writeByte(1);
                out.writeUTF("Signature");
                // #8
                out.writeByte(1);
                out.writeUTF(signature);
            }

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields
            out.writeShort(0); // Number of methods

            out.writeShort(signature == null ? 1 : 2); // Number of attributes
            out.writeShort(5); // "RuntimeVisibleTypeAnnotations"
            out.writeInt(2 + 2 * target.length + 12);
            out.writeShort(2); // num_annotations
            // @Ann with the type path [ARRAY]
            out.write(target);
            out.writeByte(1); // type_path: path_length
            out.writeByte(0); // type_path_kind: ARRAY
            out.writeByte(0); // type_argument_index
            out.writeShort(6); // type_index: "LAnn;"
            out.writeShort(0); // num_element_value_pairs
            // @Ann with an empty type path
            out.write(target);
            out.writeByte(0); // type_path: path_length
            out.writeShort(6); // type_index: "LAnn;"
            out.writeShort(0); // num_element_value_pairs
            if (signature != null) {
                out.writeShort(7); // "Signature"
                out.writeInt(2);
                out.writeShort(8);
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * A type annotation on a type parameter of a generic class that cannot be matched is skipped, and the other is
     * still applied.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void unmatchableTypeParameterAnnotationIsSkipped(@TempDir final File tempDir) throws IOException {
        final String className = "BadTypeParameterTypePath";
        // target_type 0x00 (type parameter declaration of a generic class), type_parameter_index 0
        Files.write(new File(tempDir, className + ".class").toPath(),
                classfileBytes(className, "<T:Ljava/lang/Object;>Ljava/lang/Number;", new byte[] { 0x00, 0 }));
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir).enableClassInfo()
                .enableAnnotationInfo().scan()) {
            final ClassTypeSignature classSignature = scanResult.getClassInfo(className).getTypeSignature();
            assertThat(classSignature).isNotNull();
            assertThat(classSignature.getTypeParameters().get(0).getTypeAnnotationInfo())
                    .extracting(AnnotationInfo::getName).containsExactly("Ann");
        }
    }

    /**
     * A type annotation on the superclass of a non-generic class that cannot be matched is skipped, and the other
     * is still applied to the synthetic type descriptor of the class.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void unmatchableSuperclassAnnotationIsSkipped(@TempDir final File tempDir) throws IOException {
        final String className = "BadSuperclassTypePath";
        // target_type 0x10 (type in extends clause), supertype_index 65535 (the superclass)
        Files.write(new File(tempDir, className + ".class").toPath(),
                classfileBytes(className, null, new byte[] { 0x10, (byte) 0xFF, (byte) 0xFF }));
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir).enableClassInfo()
                .enableAnnotationInfo().scan()) {
            final ClassRefTypeSignature superclassSignature = scanResult.getClassInfo(className)
                    .getTypeDescriptor().getSuperclassSignature();
            assertThat(superclassSignature).isNotNull();
            assertThat(superclassSignature.getTypeAnnotationInfo()).extracting(AnnotationInfo::getName)
                    .containsExactly("Ann");
        }
    }
}
