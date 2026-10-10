package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * The {@code RuntimeVisibleParameterAnnotations} and {@code RuntimeInvisibleParameterAnnotations} attributes of a
 * method each give their own parameter count (JVMS 4.7.18 and 4.7.19), and the two counts may differ, for example
 * when a bytecode tool adds an invisible annotation to an inner class constructor whose visible annotations javac
 * wrote without the implicit outer instance parameter. Both are aligned with the last parameter, and their
 * annotations are merged.
 */
public class ParameterAnnotationCountMismatchTest {
    /** The name of the generated class. */
    private static final String CLASS_NAME = "ParameterAnnotationCountMismatch";

    /**
     * Generate an abstract class with one abstract method {@code void m(String, String)}, whose
     * {@code RuntimeVisibleParameterAnnotations} attribute covers both parameters and puts {@code @V} on the second
     * one, and whose {@code RuntimeInvisibleParameterAnnotations} attribute covers only one parameter and puts
     * {@code @I} on it.
     *
     * @param invisibleFirst
     *            true to write the invisible attribute before the visible one
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes(final boolean invisibleFirst) throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(11); // Constant pool count (one greater than the number of entries)
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
            out.writeUTF("m");
            // #6
            out.writeByte(1);
            out.writeUTF("(Ljava/lang/String;Ljava/lang/String;)V");
            // #7
            out.writeByte(1);
            out.writeUTF("RuntimeVisibleParameterAnnotations");
            // #8
            out.writeByte(1);
            out.writeUTF("RuntimeInvisibleParameterAnnotations");
            // #9
            out.writeByte(1);
            out.writeUTF("LV;");
            // #10
            out.writeByte(1);
            out.writeUTF("LI;");

            out.writeShort(0x0421); // ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(0); // Number of fields

            out.writeShort(1); // Number of methods
            // public abstract void m(String, String)
            out.writeShort(0x0401);
            out.writeShort(5);
            out.writeShort(6);
            out.writeShort(2); // attributes_count
            for (var i = 0; i < 2; i++) {
                if ((i == 0) == invisibleFirst) {
                    out.writeShort(8); // "RuntimeInvisibleParameterAnnotations"
                    out.writeInt(7);
                    out.writeByte(1); // num_parameters
                    out.writeShort(1); // num_annotations
                    out.writeShort(10); // type_index: "LI;"
                    out.writeShort(0); // num_element_value_pairs
                } else {
                    out.writeShort(7); // "RuntimeVisibleParameterAnnotations"
                    out.writeInt(9);
                    out.writeByte(2); // num_parameters
                    out.writeShort(0); // num_annotations
                    out.writeShort(1); // num_annotations
                    out.writeShort(9); // type_index: "LV;"
                    out.writeShort(0); // num_element_value_pairs
                }
            }

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
     * The JVM loads the generated class in both attribute orders.
     *
     * @throws Exception
     *             if the class could not be loaded
     */
    @Test
    public void theJvmLoadsTheClass() throws Exception {
        for (final var invisibleFirst : new boolean[] { false, true }) {
            final var cls = new ByteArrayClassLoader().define(classfileBytes(invisibleFirst));
            assertThat(cls.getDeclaredMethod("m", String.class, String.class)).isNotNull();
        }
    }

    /**
     * Scan the generated class and check that both annotations are on the second parameter.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @param invisibleFirst
     *            true to write the invisible attribute before the visible one
     * @throws IOException
     *             if the classfile could not be written
     */
    private static void checkMergedAnnotations(final File tempDir, final boolean invisibleFirst)
            throws IOException {
        Files.write(new File(tempDir, CLASS_NAME + ".class").toPath(), classfileBytes(invisibleFirst));
        try (var scanResult = new ClassGraph().enableClassInfo().enableMethodInfo().enableAnnotationInfo()
                .enableClasspathEntries(tempDir).scan()) {
            final var classInfo = scanResult.getClassInfo(CLASS_NAME);
            assertThat(classInfo).isNotNull();
            final var parameterInfo = classInfo.getMethodInfo("m").get(0).getParameterInfo();
            assertThat(parameterInfo).hasSize(2);
            assertThat(parameterInfo.get(0).getAllAnnotationInfo()).isEmpty();
            assertThat(parameterInfo.get(1).getAllAnnotationInfo().getNames()).containsExactlyInAnyOrder("V", "I");
            assertThat(scanResult.getClassesWithMethodParameterAnnotation("V").getNames())
                    .containsExactly(CLASS_NAME);
            assertThat(scanResult.getClassesWithMethodParameterAnnotation("I").getNames())
                    .containsExactly(CLASS_NAME);
        }
    }

    /**
     * The visible attribute, read first, covers more parameters than the invisible one.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void longerAttributeFirst(@TempDir final File tempDir) throws IOException {
        checkMergedAnnotations(tempDir, false);
    }

    /**
     * The invisible attribute, read first, covers fewer parameters than the visible one.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void shorterAttributeFirst(@TempDir final File tempDir) throws IOException {
        checkMergedAnnotations(tempDir, true);
    }

    /** A runtime-visible parameter annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    private @interface Visible {
    }

    /** A runtime-invisible parameter annotation. */
    @Retention(RetentionPolicy.CLASS)
    private @interface Invisible {
    }

    /** A class with an inner class. */
    static class Outer {
        /** An inner class, whose constructor has the outer instance as an implicit first parameter. */
        class Inner {
            /**
             * Constructor.
             *
             * @param x
             *            the x
             */
            Inner(@Visible @Invisible final int x) {
                // Empty
            }
        }
    }

    /**
     * The annotations of a compiled inner class constructor stay on the declared parameter, not the implicit outer
     * instance parameter, as reflection reports them.
     *
     * @throws Exception
     *             if the constructor could not be found
     */
    @Test
    public void innerClassConstructorAnnotationsStayOnTheDeclaredParameter() throws Exception {
        final var reflectedAnnotations = Outer.Inner.class.getDeclaredConstructor(Outer.class, int.class)
                .getParameterAnnotations();
        assertThat(reflectedAnnotations[reflectedAnnotations.length - 1]).hasSize(1);
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableMethodInfo()
                .enableAnnotationInfo().ignoreClassVisibility().ignoreMethodVisibility()
                .acceptClasses(Outer.Inner.class.getName()).scan()) {
            final var parameterInfo = scanResult.getClassInfo(Outer.Inner.class.getName()).getConstructorInfo()
                    .get(0).getParameterInfo();
            assertThat(parameterInfo).hasSize(2);
            assertThat(parameterInfo.get(0).getAllAnnotationInfo()).isEmpty();
            assertThat(parameterInfo.get(1).getAllAnnotationInfo().getNames())
                    .containsExactlyInAnyOrder(Visible.class.getName(), Invisible.class.getName());
        }
    }
}
