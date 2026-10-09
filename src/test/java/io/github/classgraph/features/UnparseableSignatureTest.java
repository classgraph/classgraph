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
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodParameterInfo;
import io.github.classgraph.MethodTypeSignature;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeSignature;

/**
 * The JVM does not check the {@code Signature} attribute of a class or method when it loads the class, so a
 * classfile whose signatures do not parse still loads. Such a signature has to be treated as absent wherever the
 * type descriptor can stand in for it, rather than make a scan or a query fail.
 */
public class UnparseableSignatureTest {
    /** The name of the generated class. */
    private static final String CLASS_NAME = "UnparseableSignatures";

    /**
     * Generate an abstract class whose class signature is truncated, with one abstract method
     * {@code void method(String)} whose method signature is truncated, and whose parameter has the type annotation
     * {@code @Ann}.
     *
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes() throws IOException {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(byteArrayOutputStream)) {
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
            out.writeUTF("Signature");
            // #6
            out.writeByte(1);
            out.writeUTF("Ljava/lang/Object"); // The class signature, without its closing ';'
            // #7
            out.writeByte(1);
            out.writeUTF("method");
            // #8
            out.writeByte(1);
            out.writeUTF("(Ljava/lang/String;)V");
            // #9
            out.writeByte(1);
            out.writeUTF("(Ljava/lang/String;"); // The method signature, without its ')' and return type
            // #10
            out.writeByte(1);
            out.writeUTF("RuntimeVisibleTypeAnnotations");
            // #11
            out.writeByte(1);
            out.writeUTF("LAnn;");

            out.writeShort(0x0421); // ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields

            out.writeShort(1); // Number of methods
            // public abstract void method(String)
            out.writeShort(0x0401);
            out.writeShort(7);
            out.writeShort(8);
            out.writeShort(2); // attributes_count
            out.writeShort(5); // "Signature"
            out.writeInt(2);
            out.writeShort(9);
            out.writeShort(10); // "RuntimeVisibleTypeAnnotations"
            out.writeInt(9);
            out.writeShort(1); // num_annotations
            out.writeByte(0x16); // target_type: formal parameter
            out.writeByte(0); // formal_parameter_index
            out.writeByte(0); // type_path: path_length
            out.writeShort(11); // type_index: "LAnn;"
            out.writeShort(0); // num_element_value_pairs

            out.writeShort(1); // Number of attributes
            out.writeShort(5); // "Signature"
            out.writeInt(2);
            out.writeShort(6);
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
        final Class<?> cls = new ByteArrayClassLoader().define(classfileBytes());
        assertThat(cls.getDeclaredMethod("method", String.class)).isNotNull();
    }

    /**
     * A scan that finds the dependencies of each class reads the parameter types of each method, and gets them from
     * the type descriptor of a method whose signature does not parse.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void aScanThatFindsDependenciesSucceeds(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, CLASS_NAME + ".class").toPath(), classfileBytes());
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir).enableClassInfo()
                .enableMethodInfo().enableAnnotationInfo().enableInterClassDependencies().enableExternalClasses()
                .scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(CLASS_NAME);
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.getClassDependencies().getNames()).contains("java.lang.String");
        }
    }

    /**
     * A method whose signature does not parse reports its type descriptor, with its type annotations, in place of
     * the signature.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void aMethodFallsBackToItsTypeDescriptor(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, CLASS_NAME + ".class").toPath(), classfileBytes());
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir).enableClassInfo()
                .enableMethodInfo().enableAnnotationInfo().scan()) {
            final MethodInfo methodInfo = scanResult.getClassInfo(CLASS_NAME).getMethodInfo("method").get(0);
            assertThatThrownBy(methodInfo::getTypeSignature).isInstanceOf(IllegalArgumentException.class);
            final MethodTypeSignature typeDescriptor = methodInfo.getTypeDescriptor();
            assertThat(methodInfo.getTypeSignatureOrTypeDescriptor()).isSameAs(typeDescriptor);
            final MethodParameterInfo[] parameterInfo = methodInfo.getParameterInfo();
            assertThat(parameterInfo).hasSize(1);
            assertThat(parameterInfo[0].getTypeSignature()).isNull();
            assertThat(parameterInfo[0].getTypeDescriptor()).hasToString("@Ann java.lang.String");
            assertThat(parameterInfo[0].getTypeSignatureOrTypeDescriptor())
                    .isSameAs(parameterInfo[0].getTypeDescriptor());
            assertThat(methodInfo).hasToString("public abstract void method(@Ann java.lang.String)");
        }
    }

    /**
     * Resolving the type variables of a type against a class whose signature does not parse treats the class as
     * supplying no type arguments.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void resolvingTypeVariablesSkipsAClassSignatureThatDoesNotParse(@TempDir final File tempDir)
            throws IOException {
        Files.write(new File(tempDir, CLASS_NAME + ".class").toPath(), classfileBytes());
        try (ScanResult scanResult = new ClassGraph().overrideClasspath(tempDir).enableClassInfo()
                .enableMethodInfo().scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(CLASS_NAME);
            assertThatThrownBy(classInfo::getTypeSignature).isInstanceOf(IllegalArgumentException.class);
            final TypeSignature resultType = classInfo.getMethodInfo("method").get(0).getTypeDescriptor()
                    .getResultType();
            assertThat(resultType.resolveTypeVariables(classInfo)).isSameAs(resultType);
        }
    }
}
