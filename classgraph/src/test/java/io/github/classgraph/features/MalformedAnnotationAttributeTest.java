package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * The JVM does not parse the contents of the annotation attributes when it loads a class: it skips each attribute
 * using its {@code attribute_length}, and never reads the runtime invisible ones at all. A malformed annotation
 * attribute is therefore skipped, and the rest of the class is still read from where the attribute's length says
 * the next attribute starts, rather than the whole class being dropped from the scan.
 */
public class MalformedAnnotationAttributeTest {
    /** The name of the generated class. */
    private static final String CLASS_NAME = "MalformedAnnotationAttribute";

    /** The attribute that holds the runtime invisible annotation {@code @I}, or that is malformed. */
    enum Site {
        /** The class' {@code RuntimeInvisibleAnnotations} attribute. */
        CLASS_ANNOTATIONS,
        /** The class' {@code RuntimeInvisibleTypeAnnotations} attribute, annotating the superinterface. */
        CLASS_TYPE_ANNOTATIONS,
        /** The field's {@code RuntimeInvisibleAnnotations} attribute. */
        FIELD_ANNOTATIONS,
        /** The field's {@code RuntimeInvisibleTypeAnnotations} attribute, annotating the field type. */
        FIELD_TYPE_ANNOTATIONS,
        /** The method's {@code RuntimeInvisibleAnnotations} attribute. */
        METHOD_ANNOTATIONS,
        /** The method's {@code RuntimeInvisibleParameterAnnotations} attribute. */
        METHOD_PARAMETER_ANNOTATIONS,
        /** The method's {@code RuntimeInvisibleTypeAnnotations} attribute, annotating the parameter type. */
        METHOD_TYPE_ANNOTATIONS,
        /** The method's {@code AnnotationDefault} attribute. */
        ANNOTATION_DEFAULT;

        /**
         * Whether the site holds type annotations.
         *
         * @return true if the site holds type annotations.
         */
        boolean isTypeAnnotations() {
            return this == CLASS_TYPE_ANNOTATIONS || this == FIELD_TYPE_ANNOTATIONS
                    || this == METHOD_TYPE_ANNOTATIONS;
        }
    }

    /** What is wrong with the attribute at the site. */
    enum Defect {
        /** Nothing: the attribute is well formed. */
        NONE,
        /**
         * The attribute is well formed, but its {@code attribute_length} counts three bytes of padding after it.
         */
        PADDED,
        /** After a well-formed entry, an element value has the unknown tag 'X'. */
        UNKNOWN_ELEMENT_TAG,
        /**
         * After a well-formed entry, an annotation's {@code type_index} names a string that is not a descriptor.
         */
        ANNOTATION_TYPE_NOT_A_DESCRIPTOR,
        /**
         * The number of entries is one more than the attribute holds, so that reading the missing entry runs past
         * the end of the attribute, into the attribute after it or past the end of the classfile.
         */
        COUNT_TOO_LARGE,
        /** After a well-formed entry, a type annotation has the unknown {@code target_type} 0x7F. */
        UNKNOWN_TARGET_TYPE;

        /**
         * Whether the attribute is malformed.
         *
         * @return true if the attribute is malformed.
         */
        boolean isMalformed() {
            return this != NONE && this != PADDED;
        }

        /**
         * Whether a malformed entry is written after the well-formed one.
         *
         * @return true if a malformed entry is written.
         */
        boolean hasMalformedEntry() {
            return isMalformed() && this != COUNT_TOO_LARGE;
        }
    }

    // Constant pool indices
    /** {@code CONSTANT_Class} of the generated class. */
    private static final int THIS_CLASS = 2;
    /** {@code CONSTANT_Class} of {@code java.lang.Object}. */
    private static final int OBJECT_CLASS = 4;
    /** {@code CONSTANT_Class} of {@code java.lang.annotation.Annotation}. */
    private static final int ANNOTATION_CLASS = 6;
    /** The field name {@code f}. */
    private static final int FIELD_NAME = 7;
    /** The field descriptor {@code I}. */
    private static final int FIELD_DESCRIPTOR = 8;
    /** The method name {@code m}. */
    private static final int METHOD_NAME = 9;
    /** The method descriptor {@code (I)[I}. */
    private static final int METHOD_DESCRIPTOR = 10;
    /** "RuntimeVisibleAnnotations". */
    private static final int RUNTIME_VISIBLE_ANNOTATIONS = 11;
    /** "RuntimeInvisibleAnnotations". */
    private static final int RUNTIME_INVISIBLE_ANNOTATIONS = 12;
    /** "RuntimeVisibleParameterAnnotations". */
    private static final int RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS = 13;
    /** "RuntimeInvisibleParameterAnnotations". */
    private static final int RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS = 14;
    /** "RuntimeInvisibleTypeAnnotations". */
    private static final int RUNTIME_INVISIBLE_TYPE_ANNOTATIONS = 15;
    /** "AnnotationDefault". */
    private static final int ANNOTATION_DEFAULT_NAME = 16;
    /** The descriptor of the runtime visible annotation {@code @V}. */
    private static final int V_DESCRIPTOR = 17;
    /** The descriptor of the runtime invisible annotation {@code @I}. */
    private static final int I_DESCRIPTOR = 18;
    /** The annotation element name {@code value}. */
    private static final int VALUE_NAME = 19;
    /** {@code CONSTANT_Integer} 7. */
    private static final int SEVEN = 20;
    /** A string that is not a type descriptor. */
    private static final int NOT_A_DESCRIPTOR = 21;
    /** "ConstantValue". */
    private static final int CONSTANT_VALUE = 22;
    /** "MethodParameters". */
    private static final int METHOD_PARAMETERS = 23;
    /** The parameter name {@code p}. */
    private static final int PARAMETER_NAME = 24;
    /** "SourceFile". */
    private static final int SOURCE_FILE = 25;
    /** The source file name. */
    private static final int SOURCE_FILE_NAME = 26;

    /** The type annotation targets used at each type annotation site. */
    private static final byte[] CLASS_TARGET = { 0x10, 0, 0 }; // supertype_target: superinterface 0
    /** The field type annotation target. */
    private static final byte[] FIELD_TARGET = { 0x13 }; // empty_target: the field type
    /** The method type annotation target. */
    private static final byte[] METHOD_TARGET = { 0x16, 0 }; // formal_parameter_target: parameter 0

    /** Builds the bytes of a classfile structure. */
    private static class Bytes {
        /** The bytes. */
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        /** The stream that writes to the bytes. */
        final DataOutputStream out = new DataOutputStream(bytes);

        /**
         * Write a byte.
         *
         * @param b
         *            the byte
         * @return this
         * @throws IOException
         *             if the byte could not be written
         */
        Bytes u1(final int b) throws IOException {
            out.writeByte(b);
            return this;
        }

        /**
         * Write a short.
         *
         * @param s
         *            the short
         * @return this
         * @throws IOException
         *             if the short could not be written
         */
        Bytes u2(final int s) throws IOException {
            out.writeShort(s);
            return this;
        }

        /**
         * Write bytes.
         *
         * @param b
         *            the bytes
         * @return this
         * @throws IOException
         *             if the bytes could not be written
         */
        Bytes raw(final byte[] b) throws IOException {
            out.write(b);
            return this;
        }

        /**
         * Write an attribute.
         *
         * @param nameIdx
         *            the constant pool index of the attribute name
         * @param body
         *            the attribute body
         * @param lengthAdjustment
         *            the amount to add to the length of the body to get the {@code attribute_length} written
         * @return this
         * @throws IOException
         *             if the attribute could not be written
         */
        Bytes attribute(final int nameIdx, final byte[] body, final int lengthAdjustment) throws IOException {
            out.writeShort(nameIdx);
            out.writeInt(body.length + lengthAdjustment);
            out.write(body);
            return this;
        }

        /**
         * Get the bytes written so far.
         *
         * @return the bytes
         * @throws IOException
         *             if the stream could not be flushed
         */
        byte[] toByteArray() throws IOException {
            out.flush();
            return bytes.toByteArray();
        }
    }

    /**
     * The bytes of the annotation {@code @I(value = 7)}.
     *
     * @return the bytes.
     * @throws IOException
     *             if the bytes could not be written
     */
    private static byte[] annotationI() throws IOException {
        return new Bytes().u2(I_DESCRIPTOR).u2(1).u2(VALUE_NAME).u1('I').u2(SEVEN).toByteArray();
    }

    /**
     * The bytes of a malformed annotation.
     *
     * @param defect
     *            what is wrong with the annotation
     * @return the bytes.
     * @throws IOException
     *             if the bytes could not be written
     */
    private static byte[] badAnnotation(final Defect defect) throws IOException {
        return switch (defect) {
        case UNKNOWN_ELEMENT_TAG ->
            new Bytes().u2(I_DESCRIPTOR).u2(1).u2(VALUE_NAME).u1('X').u2(SEVEN).toByteArray();
        case ANNOTATION_TYPE_NOT_A_DESCRIPTOR -> new Bytes().u2(NOT_A_DESCRIPTOR).u2(0).toByteArray();
        default -> throw new IllegalArgumentException(defect.toString());
        };
    }

    /**
     * The body of the attribute at a site: a well-formed entry holding {@code @I} (or the default value 7),
     * followed by a malformed entry or padding, as the defect requires. For {@link Defect#COUNT_TOO_LARGE}, the
     * number of entries is 2 but only the well-formed entry is written.
     *
     * @param site
     *            the site
     * @param defect
     *            what is wrong with the attribute
     * @return the attribute body.
     * @throws IOException
     *             if the bytes could not be written
     */
    private static byte[] siteAttributeBody(final Site site, final Defect defect) throws IOException {
        final var numEntries = defect.isMalformed() ? 2 : 1;
        final var body = new Bytes();
        switch (site) {
        case CLASS_ANNOTATIONS, FIELD_ANNOTATIONS, METHOD_ANNOTATIONS -> {
            body.u2(numEntries).raw(annotationI());
            if (defect.hasMalformedEntry()) {
                body.raw(badAnnotation(defect));
            }
        }
        case METHOD_PARAMETER_ANNOTATIONS -> {
            body.u1(1).u2(numEntries).raw(annotationI());
            if (defect.hasMalformedEntry()) {
                body.raw(badAnnotation(defect));
            }
        }
        case CLASS_TYPE_ANNOTATIONS, FIELD_TYPE_ANNOTATIONS, METHOD_TYPE_ANNOTATIONS -> {
            final var target = site == Site.CLASS_TYPE_ANNOTATIONS ? CLASS_TARGET
                    : site == Site.FIELD_TYPE_ANNOTATIONS ? FIELD_TARGET : METHOD_TARGET;
            body.u2(numEntries).raw(target).u1(0).raw(annotationI());
            if (defect == Defect.UNKNOWN_TARGET_TYPE) {
                body.u1(0x7F).u1(0).raw(annotationI());
            } else if (defect.hasMalformedEntry()) {
                body.raw(target).u1(0).raw(badAnnotation(defect));
            }
        }
        case ANNOTATION_DEFAULT -> {
            // An array element value
            body.u1('[').u2(numEntries).u1('I').u2(SEVEN);
            if (defect == Defect.UNKNOWN_ELEMENT_TAG) {
                body.u1('X').u2(SEVEN);
            } else if (defect.hasMalformedEntry()) {
                body.u1('@').raw(badAnnotation(defect));
            }
        }
        default -> throw new IllegalArgumentException(site.toString());
        }
        if (defect == Defect.PADDED) {
            body.u1(0).u1(0).u1(0);
        }
        return body.toByteArray();
    }

    /**
     * Generate an annotation class {@code @V @interface MalformedAnnotationAttribute { @V int f = 7; @V int[] m(@V
     * int p); }}, which is compiled from source in the source file "Source.java", and which has one more attribute
     * at the given site, holding the runtime invisible annotation {@code @I}, or the default value {7} if the site
     * is {@code AnnotationDefault}. The attribute comes after the {@code @V} attributes of its class, field or
     * method, and is followed by the {@code SourceFile}, {@code ConstantValue} or {@code MethodParameters}
     * attribute, except that a class attribute with the defect {@link Defect#COUNT_TOO_LARGE} comes last in the
     * classfile.
     *
     * @param site
     *            the site of the extra attribute
     * @param defect
     *            what is wrong with the extra attribute
     * @param lengthAdjustment
     *            the amount to add to the length of the extra attribute to get its {@code attribute_length}
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileBytes(final Site site, final Defect defect, final int lengthAdjustment)
            throws IOException {
        final var siteAttribute = siteAttributeBody(site, defect);
        final var siteAttributeNameIdx = switch (site) {
        case CLASS_ANNOTATIONS, FIELD_ANNOTATIONS, METHOD_ANNOTATIONS -> RUNTIME_INVISIBLE_ANNOTATIONS;
        case METHOD_PARAMETER_ANNOTATIONS -> RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS;
        case CLASS_TYPE_ANNOTATIONS, FIELD_TYPE_ANNOTATIONS, METHOD_TYPE_ANNOTATIONS -> //
            RUNTIME_INVISIBLE_TYPE_ANNOTATIONS;
        case ANNOTATION_DEFAULT -> ANNOTATION_DEFAULT_NAME;
        };
        final var annotationsV = new Bytes().u2(1).u2(V_DESCRIPTOR).u2(0).toByteArray();

        final var classfile = new Bytes();
        final var out = classfile.out;
        out.writeInt(0xCAFEBABE);
        out.writeShort(0); // Minor version
        out.writeShort(52); // Major version (Java 8)

        out.writeShort(27); // Constant pool count (one greater than the number of entries)
        final var utf8Entries = new String[] { CLASS_NAME, null, "java/lang/Object", null,
                "java/lang/annotation/Annotation", null, "f", "I", "m", "(I)[I", "RuntimeVisibleAnnotations",
                "RuntimeInvisibleAnnotations", "RuntimeVisibleParameterAnnotations",
                "RuntimeInvisibleParameterAnnotations", "RuntimeInvisibleTypeAnnotations", "AnnotationDefault",
                "LV;", "LI;", "value", null, "NotADescriptor", "ConstantValue", "MethodParameters", "p",
                "SourceFile", "Source.java" };
        for (var i = 0; i < utf8Entries.length; i++) {
            final var cpIdx = i + 1;
            if (utf8Entries[i] != null) {
                out.writeByte(1);
                out.writeUTF(utf8Entries[i]);
            } else if (cpIdx == SEVEN) {
                out.writeByte(3);
                out.writeInt(7);
            } else {
                out.writeByte(7); // Class ref to the preceding entry
                out.writeShort(cpIdx - 1);
            }
        }

        out.writeShort(0x2601); // ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT | ACC_ANNOTATION
        out.writeShort(THIS_CLASS);
        out.writeShort(OBJECT_CLASS);
        out.writeShort(1); // Number of interfaces
        out.writeShort(ANNOTATION_CLASS);

        out.writeShort(1); // Number of fields
        // public static final int f = 7
        classfile.u2(0x0019).u2(FIELD_NAME).u2(FIELD_DESCRIPTOR);
        final var fieldHasSiteAttribute = site == Site.FIELD_ANNOTATIONS || site == Site.FIELD_TYPE_ANNOTATIONS;
        classfile.u2(fieldHasSiteAttribute ? 3 : 2);
        classfile.attribute(RUNTIME_VISIBLE_ANNOTATIONS, annotationsV, 0);
        if (fieldHasSiteAttribute) {
            classfile.attribute(siteAttributeNameIdx, siteAttribute, lengthAdjustment);
        }
        classfile.attribute(CONSTANT_VALUE, new Bytes().u2(SEVEN).toByteArray(), 0);

        out.writeShort(1); // Number of methods
        // public abstract int[] m(int p)
        classfile.u2(0x0401).u2(METHOD_NAME).u2(METHOD_DESCRIPTOR);
        final var methodHasSiteAttribute = site == Site.METHOD_ANNOTATIONS
                || site == Site.METHOD_PARAMETER_ANNOTATIONS || site == Site.METHOD_TYPE_ANNOTATIONS
                || site == Site.ANNOTATION_DEFAULT;
        classfile.u2(methodHasSiteAttribute ? 4 : 3);
        classfile.attribute(RUNTIME_VISIBLE_ANNOTATIONS, annotationsV, 0);
        classfile.attribute(RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS,
                new Bytes().u1(1).u2(1).u2(V_DESCRIPTOR).u2(0).toByteArray(), 0);
        if (methodHasSiteAttribute) {
            classfile.attribute(siteAttributeNameIdx, siteAttribute, lengthAdjustment);
        }
        classfile.attribute(METHOD_PARAMETERS, new Bytes().u1(1).u2(PARAMETER_NAME).u2(0).toByteArray(), 0);

        final var classHasSiteAttribute = site == Site.CLASS_ANNOTATIONS || site == Site.CLASS_TYPE_ANNOTATIONS;
        classfile.u2(classHasSiteAttribute ? 3 : 2); // Number of attributes
        classfile.attribute(RUNTIME_VISIBLE_ANNOTATIONS, annotationsV, 0);
        // An attribute whose number of entries is too large is put last, so that reading it runs past the end of
        // the classfile
        final var classSiteAttributeLast = defect == Defect.COUNT_TOO_LARGE;
        if (classHasSiteAttribute && !classSiteAttributeLast) {
            classfile.attribute(siteAttributeNameIdx, siteAttribute, lengthAdjustment);
        }
        classfile.attribute(SOURCE_FILE, new Bytes().u2(SOURCE_FILE_NAME).toByteArray(), 0);
        if (classHasSiteAttribute && classSiteAttributeLast) {
            classfile.attribute(siteAttributeNameIdx, siteAttribute, lengthAdjustment);
        }
        return classfile.toByteArray();
    }

    /**
     * Every site with every defect that applies to it.
     *
     * @return the sites and defects.
     */
    static Stream<Arguments> sitesAndDefects() {
        final List<Arguments> arguments = new ArrayList<>();
        for (final var site : Site.values()) {
            for (final var defect : Defect.values()) {
                if (defect != Defect.UNKNOWN_TARGET_TYPE || site.isTypeAnnotations()) {
                    arguments.add(Arguments.of(site, defect));
                }
            }
        }
        return arguments.stream();
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
     * Get the names of a list of annotations.
     *
     * @param annotationInfoList
     *            the annotations, or null
     * @return the names, or the empty list if {@code annotationInfoList} is null.
     */
    private static List<String> names(final AnnotationInfoList annotationInfoList) {
        return annotationInfoList == null ? List.of() : annotationInfoList.getNames();
    }

    /**
     * Scan the generated class.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @param classfileBytes
     *            the bytes of the classfile
     * @return the scan result
     * @throws IOException
     *             if the classfile could not be written
     */
    private static ScanResult scan(final File tempDir, final byte[] classfileBytes) throws IOException {
        Files.write(new File(tempDir, CLASS_NAME + ".class").toPath(), classfileBytes);
        return new ClassGraph().enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().enableClasspathEntries(tempDir).scan();
    }

    /**
     * The JVM loads the generated class whatever is wrong with the attribute at the site.
     *
     * @param site
     *            the site
     * @param defect
     *            what is wrong with the attribute at the site
     * @throws Exception
     *             if the class could not be loaded
     */
    @ParameterizedTest
    @MethodSource("sitesAndDefects")
    public void theJvmLoadsTheClass(final Site site, final Defect defect) throws Exception {
        final var cls = new ByteArrayClassLoader().define(classfileBytes(site, defect, 0));
        assertThat(cls.isAnnotation()).isTrue();
        assertThat(cls.getDeclaredField("f").getInt(null)).isEqualTo(7);
        assertThat(cls.getDeclaredMethod("m", int.class)).isNotNull();
    }

    /**
     * Scan the generated class, and check that everything is read except the annotations of a malformed attribute.
     *
     * @param site
     *            the site
     * @param defect
     *            what is wrong with the attribute at the site
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @ParameterizedTest
    @MethodSource("sitesAndDefects")
    public void malformedAttributeIsSkipped(final Site site, final Defect defect, @TempDir final File tempDir)
            throws IOException {
        try (var scanResult = scan(tempDir, classfileBytes(site, defect, 0))) {
            final var classInfo = scanResult.getClassInfo(CLASS_NAME);
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.isAnnotation()).isTrue();
            assertThat(classInfo.getTypeSignatureOrTypeDescriptor().getSuperinterfaceSignatures().get(0)
                    .getFullyQualifiedClassName()).isEqualTo("java.lang.annotation.Annotation");
            assertThat(classInfo.getSourceFile()).isEqualTo("Source.java");

            // The annotation @I, or the default value, is read only from the site, and only if the attribute
            // there is well formed
            final List<String> v = List.of("V");
            final List<String> vi = List.of("V", "I");
            final List<String> i = List.of("I");
            final var read = !defect.isMalformed();

            assertThat(names(classInfo.getAllAnnotationInfo()))
                    .containsExactlyInAnyOrderElementsOf(read && site == Site.CLASS_ANNOTATIONS ? vi : v);
            assertThat(names(classInfo.getTypeSignatureOrTypeDescriptor().getSuperinterfaceSignatures().get(0)
                    .getTypeAnnotationInfo())).containsExactlyInAnyOrderElementsOf(
                            read && site == Site.CLASS_TYPE_ANNOTATIONS ? i : List.of());

            final var fieldInfo = classInfo.getFieldInfo("f");
            assertThat(fieldInfo).isNotNull();
            assertThat(fieldInfo.getConstantInitializerValue()).isEqualTo(7);
            assertThat(names(fieldInfo.getAllAnnotationInfo()))
                    .containsExactlyInAnyOrderElementsOf(read && site == Site.FIELD_ANNOTATIONS ? vi : v);
            assertThat(names(fieldInfo.getTypeDescriptor().getTypeAnnotationInfo()))
                    .containsExactlyInAnyOrderElementsOf(
                            read && site == Site.FIELD_TYPE_ANNOTATIONS ? i : List.of());

            final var methodInfoList = classInfo.getMethodInfo("m");
            assertThat(methodInfoList).hasSize(1);
            final var methodInfo = methodInfoList.get(0);
            assertThat(names(methodInfo.getAllAnnotationInfo()))
                    .containsExactlyInAnyOrderElementsOf(read && site == Site.METHOD_ANNOTATIONS ? vi : v);
            final var parameterInfo = methodInfo.getParameterInfo();
            assertThat(parameterInfo).hasSize(1);
            assertThat(parameterInfo.get(0).getName()).isEqualTo("p");
            assertThat(names(parameterInfo.get(0).getAllAnnotationInfo())).containsExactlyInAnyOrderElementsOf(
                    read && site == Site.METHOD_PARAMETER_ANNOTATIONS ? vi : v);
            assertThat(names(parameterInfo.get(0).getTypeDescriptor().getTypeAnnotationInfo()))
                    .containsExactlyInAnyOrderElementsOf(
                            read && site == Site.METHOD_TYPE_ANNOTATIONS ? i : List.of());

            final var defaultValues = classInfo.getAnnotationDefaultParameterValues();
            if (read && site == Site.ANNOTATION_DEFAULT) {
                assertThat(defaultValues.getNames()).containsExactly("m");
                assertThat(defaultValues.get(0).getValue()).isEqualTo(new int[] { 7 });
            } else {
                assertThat(defaultValues).isEmpty();
            }
        }
    }

    /**
     * An annotation attribute whose {@code attribute_length} is one byte too short makes the JVM read the last byte
     * of the attribute as the start of the next attribute, and reject the class. ClassGraph also reads the next
     * attribute from where the length says it starts, and drops the class.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void attributeLengthThatIsTooShortDropsTheClass(@TempDir final File tempDir) throws IOException {
        final var bytes = classfileBytes(Site.CLASS_ANNOTATIONS, Defect.NONE, -1);
        assertThatThrownBy(() -> new ByteArrayClassLoader().define(bytes)).isInstanceOf(ClassFormatError.class);
        try (var scanResult = scan(tempDir, bytes)) {
            assertThat(scanResult.getClassInfo(CLASS_NAME)).isNull();
        }
    }
}
