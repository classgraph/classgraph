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

import io.github.classgraph.AnnotationClassRef;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;

/**
 * An array type has at most 255 dimensions (JVMS 4.3.2 and 4.4.1), but the JVM does not check a {@code Signature}
 * attribute, an annotation's class literal, or a class name that it never resolves, so a classfile can still name
 * an array type with thousands of dimensions. Such a type must not make the whole scan fail with a
 * {@link StackOverflowError}: only the class that names it fails, and the name of a thrown exception class is kept.
 */
public class ArrayDimensionLimitTest {
    /**
     * Repeat a string.
     *
     * @param str
     *            the string
     * @param count
     *            the number of times to repeat it
     * @return the repeated string
     */
    private static String repeat(final String str, final int count) {
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < count; i++) {
            buf.append(str);
        }
        return buf.toString();
    }

    /** The descriptor of an array type with far more than 255 dimensions. */
    private static final String TOO_MANY_DIMENSIONS_DESCRIPTOR = repeat("[", 20000) + "I";

    /** The name of an array class with far more than 255 dimensions, in the "int[][]..." form. */
    private static final String TOO_MANY_DIMENSIONS_CLASS_NAME = "I" + repeat("[]", 20000);

    /** The descriptor of the annotation type {@code Ann}. */
    private static final String ANN = "LAnn;";

    /**
     * Generate a minimal public class.
     *
     * @param className
     *            the binary name of the class
     * @param superclassName
     *            the binary name of the superclass
     * @param classRefName
     *            the name held by a {@code CONSTANT_Class} entry that nothing else refers to
     * @param fieldSignature
     *            the {@code Signature} attribute of the field {@code int f}, or null for no field
     * @param annotationType
     *            the descriptor of the type of the class annotation, e.g. {@code "LAnn;"}
     * @param annotationClassLiteral
     *            the descriptor of the class literal held by the parameter {@code value} of the class annotation,
     *            or null for no annotation
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes(final String className, final String superclassName,
            final String classRefName, final String fieldSignature, final String annotationType,
            final String annotationClassLiteral) throws IOException {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(15); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1);
            out.writeUTF(className);
            // #2
            out.writeByte(7); // class ref to #1 (this_class)
            out.writeShort(1);
            // #3
            out.writeByte(1);
            out.writeUTF(superclassName);
            // #4
            out.writeByte(7); // class ref to #3 (super_class)
            out.writeShort(3);
            // #5
            out.writeByte(1);
            out.writeUTF("f");
            // #6
            out.writeByte(1);
            out.writeUTF("I");
            // #7
            out.writeByte(1);
            out.writeUTF("Signature");
            // #8
            out.writeByte(1);
            out.writeUTF(fieldSignature == null ? "I" : fieldSignature);
            // #9
            out.writeByte(1);
            out.writeUTF("RuntimeVisibleAnnotations");
            // #10
            out.writeByte(1);
            out.writeUTF(annotationType);
            // #11
            out.writeByte(1);
            out.writeUTF("value");
            // #12
            out.writeByte(1);
            out.writeUTF(annotationClassLiteral == null ? "I" : annotationClassLiteral);
            // #13
            out.writeByte(1);
            out.writeUTF(classRefName);
            // #14
            out.writeByte(7); // class ref to #13, which nothing refers to
            out.writeShort(13);

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces

            if (fieldSignature == null) {
                out.writeShort(0); // Number of fields
            } else {
                out.writeShort(1); // Number of fields
                out.writeShort(0x0001); // ACC_PUBLIC
                out.writeShort(5); // "f"
                out.writeShort(6); // "I"
                out.writeShort(1); // attributes_count
                out.writeShort(7); // "Signature"
                out.writeInt(2);
                out.writeShort(8);
            }

            out.writeShort(0); // Number of methods

            if (annotationClassLiteral == null) {
                out.writeShort(0); // Number of attributes
            } else {
                out.writeShort(1); // Number of attributes
                out.writeShort(9); // "RuntimeVisibleAnnotations"
                out.writeInt(2 + 2 + 2 + 2 + 1 + 2);
                out.writeShort(1); // num_annotations
                out.writeShort(10); // type_index: the annotation type's descriptor
                out.writeShort(1); // num_element_value_pairs
                out.writeShort(11); // "value"
                out.writeByte('c');
                out.writeShort(12); // class_info_index: the class literal's descriptor
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Generate a minimal public abstract class with one abstract method {@code void m()}, whose {@code Exceptions}
     * attribute names one class.
     *
     * @param className
     *            the binary name of the class
     * @param thrownExceptionName
     *            the name held by the {@code CONSTANT_Class} entry that the {@code Exceptions} attribute refers to
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileWithThrownException(final String className, final String thrownExceptionName)
            throws IOException {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(10); // Constant pool count (one greater than the number of entries)
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
            out.writeUTF("m");
            // #6
            out.writeByte(1);
            out.writeUTF("()V");
            // #7
            out.writeByte(1);
            out.writeUTF("Exceptions");
            // #8
            out.writeByte(1);
            out.writeUTF(thrownExceptionName);
            // #9
            out.writeByte(7); // class ref to #8 (the thrown exception)
            out.writeShort(8);

            out.writeShort(0x0421); // ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields

            out.writeShort(1); // Number of methods
            out.writeShort(0x0401); // ACC_PUBLIC | ACC_ABSTRACT
            out.writeShort(5); // "m"
            out.writeShort(6); // "()V"
            out.writeShort(1); // attributes_count
            out.writeShort(7); // "Exceptions"
            out.writeInt(2 + 2);
            out.writeShort(1); // number_of_exceptions
            out.writeShort(9); // the thrown exception's class ref

            out.writeShort(0); // Number of attributes
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Write a class whose fields, annotations and class references are all valid.
     *
     * @param dir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    private static void writeValidClass(final File dir) throws IOException {
        Files.write(new File(dir, "Valid.class").toPath(), classfileBytes("Valid", "java/lang/Object",
                "java/lang/String", /* fieldSignature = */ null, ANN, /* annotationClassLiteral = */ null));
    }

    /**
     * An annotation whose class literal is an array type with too many dimensions makes only its own class fail,
     * and a class literal with 255 dimensions is read.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws IOException
     *             if a classfile could not be written
     */
    @Test
    public void annotationClassLiteralWithTooManyDimensions(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, "Evil.class").toPath(), classfileBytes("Evil", "java/lang/Object",
                "java/lang/String", /* fieldSignature = */ null, ANN, TOO_MANY_DIMENSIONS_DESCRIPTOR));
        Files.write(new File(tempDir, "Ok.class").toPath(), classfileBytes("Ok", "java/lang/Object",
                "java/lang/String", /* fieldSignature = */ null, ANN, repeat("[", 255) + "I"));
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir).enableClassInfo()
                .enableAnnotationInfo().scan()) {
            assertThat(scanResult.getClassInfo("Evil")).isNull();
            final ClassInfo classInfo = scanResult.getClassInfo("Ok");
            assertThat(classInfo).isNotNull();
            final Object value = classInfo.getAnnotationInfo().get("Ann").getParameterValues().getValue("value");
            assertThat(value).isInstanceOf(AnnotationClassRef.class);
            assertThat(((AnnotationClassRef) value).getName()).isEqualTo("int" + repeat("[]", 255));
        }
    }

    /**
     * A class annotation whose type descriptor names an array type with too many dimensions, in the class name form
     * (an annotation type descriptor in the "[[I" form already makes the class fail, since it does not have the
     * form "L...;"), makes only its own class fail.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws IOException
     *             if a classfile could not be written
     */
    @Test
    public void annotationTypeWithTooManyDimensions(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, "Annotated.class").toPath(), classfileBytes("Annotated", "java/lang/Object",
                "java/lang/String", /* fieldSignature = */ null, "L" + TOO_MANY_DIMENSIONS_CLASS_NAME + ";", "I"));
        writeValidClass(tempDir);
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir).enableClassInfo()
                .enableAnnotationInfo().scan()) {
            assertThat(scanResult.getClassInfo("Annotated")).isNull();
            assertThat(scanResult.getClassInfo("Valid")).isNotNull();
        }
    }

    /**
     * A class whose superclass is named as an array type with too many dimensions, in either the descriptor form or
     * the class name form, is skipped, and the other classes are scanned.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws IOException
     *             if a classfile could not be written
     */
    @Test
    public void superclassWithTooManyDimensions(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, "Sub1.class").toPath(), classfileBytes("Sub1", TOO_MANY_DIMENSIONS_DESCRIPTOR,
                "java/lang/String", /* fieldSignature = */ null, ANN, /* annotationClassLiteral = */ null));
        Files.write(new File(tempDir, "Sub2.class").toPath(), classfileBytes("Sub2", TOO_MANY_DIMENSIONS_CLASS_NAME,
                "java/lang/String", /* fieldSignature = */ null, ANN, /* annotationClassLiteral = */ null));
        writeValidClass(tempDir);
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir).enableClassInfo().scan()) {
            assertThat(scanResult.getClassInfo("Sub1")).isNull();
            assertThat(scanResult.getClassInfo("Sub2")).isNull();
            assertThat(scanResult.getClassInfo("Valid")).isNotNull();
        }
    }

    /**
     * A class with a class reference to an array type with too many dimensions, in the class name form, is skipped
     * by a scan that finds the dependencies of each class, and the other classes are scanned.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws IOException
     *             if a classfile could not be written
     */
    @Test
    public void classRefWithTooManyDimensions(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, "Refs.class").toPath(),
                classfileBytes("Refs", "java/lang/Object", TOO_MANY_DIMENSIONS_CLASS_NAME,
                        /* fieldSignature = */ null, ANN, /* annotationClassLiteral = */ null));
        writeValidClass(tempDir);
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir).enableClassInfo()
                .enableInterClassDependencies().enableExternalClasses().scan()) {
            assertThat(scanResult.getClassInfo("Refs")).isNull();
            assertThat(scanResult.getClassInfo("Valid").getClassDependencies().getNames())
                    .contains("java.lang.String");
        }
    }

    /**
     * A method whose {@code Exceptions} attribute names an array type with too many dimensions, in either the
     * descriptor form or the class name form, keeps its class in a scan that reads method info, since a thrown
     * exception class is only looked up by name, and is never created. The name is kept as it is in the classfile.
     *
     * @param tempDir
     *            the directory to write the classfiles to
     * @throws IOException
     *             if a classfile could not be written
     */
    @Test
    public void thrownExceptionWithTooManyDimensions(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, "Thrower1.class").toPath(),
                classfileWithThrownException("Thrower1", TOO_MANY_DIMENSIONS_DESCRIPTOR));
        Files.write(new File(tempDir, "Thrower2.class").toPath(),
                classfileWithThrownException("Thrower2", TOO_MANY_DIMENSIONS_CLASS_NAME));
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir).enableClassInfo()
                .enableMethodInfo().scan()) {
            for (final String[] thrower : new String[][] { { "Thrower1", TOO_MANY_DIMENSIONS_DESCRIPTOR },
                    { "Thrower2", TOO_MANY_DIMENSIONS_CLASS_NAME } }) {
                final ClassInfo classInfo = scanResult.getClassInfo(thrower[0]);
                assertThat(classInfo).as(thrower[0]).isNotNull();
                final MethodInfo methodInfo = classInfo.getMethodInfo("m").get(0);
                assertThat(methodInfo.getThrownExceptionNames()).as(thrower[0]).containsExactly(thrower[1]);
                assertThat(methodInfo.getThrownExceptions()).as(thrower[0]).isEmpty();
            }
        }
    }

    /**
     * A field whose type signature is an array type with too many dimensions reports that its type signature does
     * not parse, and can still be rendered as a string.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void fieldSignatureWithTooManyDimensions(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, "Fields.class").toPath(), classfileBytes("Fields", "java/lang/Object",
                "java/lang/String", TOO_MANY_DIMENSIONS_DESCRIPTOR, ANN, /* annotationClassLiteral = */ null));
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir).enableClassInfo()
                .enableFieldInfo().scan()) {
            final FieldInfo fieldInfo = scanResult.getClassInfo("Fields").getFieldInfo("f");
            assertThatThrownBy(fieldInfo::getTypeSignature).isInstanceOf(IllegalArgumentException.class);
            assertThat(fieldInfo.getTypeSignatureOrTypeDescriptor()).hasToString("int");
            assertThat(fieldInfo.toString()).isEqualTo("public int f");
        }
    }
}
