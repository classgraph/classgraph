package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * ClassGraph parses type signatures and annotations recursively, so input that is nested deeply enough would make
 * the parser overflow the stack, and the resulting {@link StackOverflowError} would make the whole scan fail. javac
 * never nests anything nearly this deeply, but one corrupt or crafted classfile in a scanned jar must not make the
 * scan fail for every other class. Past a fixed nesting depth, the input is treated as malformed instead.
 */
public class NestingDepthLimitTest {
    /** The name of the class with deeply nested input. */
    private static final String CLASS_NAME = "DeeplyNested";

    /** The name of a plain class in the same directory. */
    private static final String NEIGHBOR_NAME = "Neighbor";

    /**
     * How deeply type arguments are nested: deep enough to overflow the stack if the depth is not limited.
     */
    private static final int DEPTH = 10000;

    /**
     * A class type signature whose type arguments are nested {@link #DEPTH} deep, {@code La<La<...La;...>;>;}.
     * Short class names keep it within the 65535-byte limit of a constant pool string.
     *
     * @return the type signature.
     */
    private static String deeplyNestedTypeArguments() {
        return "La<".repeat(DEPTH) + "La;" + ">;".repeat(DEPTH);
    }

    /**
     * Generate a class that extends {@code java.lang.Object}, optionally with a class {@code Signature} attribute,
     * and optionally with a constant pool {@code Fieldref} whose {@code NameAndType} has the given descriptor.
     *
     * @param className
     *            the class name
     * @param classSignature
     *            the class signature, or null for none
     * @param fieldDescriptor
     *            the descriptor of the field reference, or null for none
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes(final String className, final String classSignature,
            final String fieldDescriptor) throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(11); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1);
            out.writeUTF(className);
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
            out.writeUTF("Signature");
            // #6
            out.writeByte(1);
            out.writeUTF(classSignature == null ? "Ljava/lang/Object;" : classSignature);
            // #7
            out.writeByte(1);
            out.writeUTF("f");
            // #8
            out.writeByte(1);
            out.writeUTF(fieldDescriptor == null ? "I" : fieldDescriptor);
            // #9
            out.writeByte(12); // NameAndType: #7, #8
            out.writeShort(7);
            out.writeShort(8);
            // #10
            out.writeByte(9); // Fieldref: #2, #9
            out.writeShort(2);
            out.writeShort(9);

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields
            out.writeShort(0); // Number of methods

            if (classSignature == null) {
                out.writeShort(0); // Number of attributes
            } else {
                out.writeShort(1); // Number of attributes
                out.writeShort(5); // "Signature"
                out.writeInt(2);
                out.writeShort(6);
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Write the class with deeply nested input and a plain neighbor class to a directory, and scan the directory
     * with inter-class dependencies enabled, which parses every descriptor in the constant pool and every class
     * signature during the scan.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @param classSignature
     *            the class signature of the deeply nested class, or null for none
     * @param fieldDescriptor
     *            the descriptor of the deeply nested class's field reference, or null for none
     * @return the scan result.
     * @throws IOException
     *             if a classfile could not be written
     */
    private static ScanResult scan(final File tempDir, final String classSignature, final String fieldDescriptor)
            throws IOException {
        Files.write(new File(tempDir, CLASS_NAME + ".class").toPath(),
                classfileBytes(CLASS_NAME, classSignature, fieldDescriptor));
        Files.write(new File(tempDir, NEIGHBOR_NAME + ".class").toPath(),
                classfileBytes(NEIGHBOR_NAME, null, null));
        return new ClassGraph().enableClassInfo().enableInterClassDependencies().enableExternalClasses()
                .enableClasspathEntries(tempDir).scan();
    }

    /**
     * A field reference whose descriptor has deeply nested type arguments is skipped when the dependencies of the
     * class are found, and the rest of the scan is unaffected.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws IOException
     *             if a classfile could not be written
     */
    @Test
    public void aDeeplyNestedDescriptorIsSkipped(@TempDir final File tempDir) throws IOException {
        try (var scanResult = scan(tempDir, null, deeplyNestedTypeArguments())) {
            final var classInfo = scanResult.getClassInfo(CLASS_NAME);
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.getClassDependencies().getNames()).doesNotContain("a");
            assertThat(scanResult.getClassInfo(NEIGHBOR_NAME)).isNotNull();
        }
    }

    /**
     * A class signature whose type arguments are deeply nested is treated like any other class signature that does
     * not parse: the scan succeeds, and the type descriptor stands in for the signature.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws IOException
     *             if a classfile could not be written
     */
    @Test
    public void aDeeplyNestedClassSignatureDoesNotParse(@TempDir final File tempDir) throws IOException {
        try (var scanResult = scan(tempDir, "Ljava/lang/Object;" + deeplyNestedTypeArguments(), null)) {
            final var classInfo = scanResult.getClassInfo(CLASS_NAME);
            assertThat(classInfo).isNotNull();
            assertThatThrownBy(classInfo::getTypeSignature).isInstanceOf(IllegalArgumentException.class);
            assertThat(classInfo.getTypeSignatureOrTypeDescriptor()).isSameAs(classInfo.getTypeDescriptor());
            assertThat(scanResult.getClassInfo(NEIGHBOR_NAME)).isNotNull();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * How deeply annotation element values are nested: deep enough to overflow the stack if the depth is not
     * limited.
     */
    private static final int DEEP_ANNOTATION_DEPTH = 50000;

    /** The deepest nesting of annotation element values that is read. */
    private static final int MAX_ANNOTATION_DEPTH = 255;

    /**
     * Generate a class that extends {@code java.lang.Object} and has a {@code RuntimeVisibleAnnotations} attribute
     * holding one annotation {@code @A(v = ...)}, whose element value is a chain of {@code depth} element values,
     * each nested in the one before it. Every element value of the chain but the last is either an array of one
     * element ({@code [}) or an annotation {@code @A} with one element ({@code @}), and the last is the {@code int}
     * 1.
     *
     * @param className
     *            the class name
     * @param depth
     *            the number of element values in the chain
     * @param nestingTag
     *            the tag of the element values that nest, {@code '['} or {@code '@'}
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] annotatedClassfileBytes(final String className, final int depth, final char nestingTag)
            throws IOException {
        final var elementValueBytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(elementValueBytes)) {
            for (var i = 0; i < depth - 1; i++) {
                out.writeByte(nestingTag);
                if (nestingTag == '@') {
                    out.writeShort(6); // type_index: "LA;"
                    out.writeShort(1); // num_element_value_pairs
                    out.writeShort(7); // element_name_index: "v"
                } else {
                    out.writeShort(1); // num_values
                }
            }
            out.writeByte('I');
            out.writeShort(8); // const_value_index: 1
        }
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(9); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1);
            out.writeUTF(className);
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
            out.writeUTF("RuntimeVisibleAnnotations");
            // #6
            out.writeByte(1);
            out.writeUTF("LA;");
            // #7
            out.writeByte(1);
            out.writeUTF("v");
            // #8
            out.writeByte(3); // Integer
            out.writeInt(1);

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields
            out.writeShort(0); // Number of methods

            out.writeShort(1); // Number of attributes
            out.writeShort(5); // "RuntimeVisibleAnnotations"
            out.writeInt(2 + 6 + elementValueBytes.size());
            out.writeShort(1); // num_annotations
            out.writeShort(6); // type_index: "LA;"
            out.writeShort(1); // num_element_value_pairs
            out.writeShort(7); // element_name_index: "v"
            elementValueBytes.writeTo(out);
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Write a class whose annotation has nested element values, and a plain neighbor class, to a directory, scan
     * the directory with annotation info enabled, and check that the scan finds both classes.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @param depth
     *            the number of nested element values
     * @param nestingTag
     *            the tag of the element values that nest, {@code '['} or {@code '@'}
     * @return whether the class kept its annotation.
     * @throws IOException
     *             if a classfile could not be written
     */
    private static boolean scanKeepsAnnotation(final File tempDir, final int depth, final char nestingTag)
            throws IOException {
        Files.write(new File(tempDir, CLASS_NAME + ".class").toPath(),
                annotatedClassfileBytes(CLASS_NAME, depth, nestingTag));
        Files.write(new File(tempDir, NEIGHBOR_NAME + ".class").toPath(),
                classfileBytes(NEIGHBOR_NAME, null, null));
        try (var scanResult = new ClassGraph().enableClassInfo().enableAnnotationInfo()
                .enableClasspathEntries(tempDir).scan()) {
            final var classInfo = scanResult.getClassInfo(CLASS_NAME);
            assertThat(classInfo).isNotNull();
            assertThat(scanResult.getClassInfo(NEIGHBOR_NAME)).isNotNull();
            return classInfo.getAllAnnotationInfo().getNames().contains("A");
        }
    }

    /**
     * An annotation whose element value nests arrays deeply is skipped, and the rest of the scan is unaffected.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws IOException
     *             if a classfile could not be written
     */
    @Test
    public void aDeeplyNestedAnnotationArrayIsSkipped(@TempDir final File tempDir) throws IOException {
        assertThat(scanKeepsAnnotation(tempDir, DEEP_ANNOTATION_DEPTH, '[')).isFalse();
    }

    /**
     * An annotation whose element value nests annotations deeply is skipped, and the rest of the scan is
     * unaffected.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws IOException
     *             if a classfile could not be written
     */
    @Test
    public void aDeeplyNestedAnnotationIsSkipped(@TempDir final File tempDir) throws IOException {
        assertThat(scanKeepsAnnotation(tempDir, DEEP_ANNOTATION_DEPTH, '@')).isFalse();
    }

    /**
     * Element values nested up to the limit are read, and one more level makes the annotation be skipped.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws IOException
     *             if a classfile could not be written
     */
    @Test
    public void annotationNestingIsReadUpToTheLimit(@TempDir final File tempDir) throws IOException {
        assertThat(scanKeepsAnnotation(tempDir, MAX_ANNOTATION_DEPTH, '[')).isTrue();
        assertThat(scanKeepsAnnotation(tempDir, MAX_ANNOTATION_DEPTH + 1, '[')).isFalse();
        assertThat(scanKeepsAnnotation(tempDir, MAX_ANNOTATION_DEPTH, '@')).isTrue();
        assertThat(scanKeepsAnnotation(tempDir, MAX_ANNOTATION_DEPTH + 1, '@')).isFalse();
    }
}
