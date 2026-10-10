package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code Class<?>}-valued annotation parameter (tag {@code 'c'}) holds a type descriptor, which a bytecode
 * generator can get wrong, for example by writing the internal name {@code java/lang/String} instead of the
 * descriptor {@code Ljava/lang/String;}. The JVM still loads such a class, and reports the malformed value only
 * when the annotation is read. ClassGraph also has to keep the class, and report the malformed value only when its
 * class name is read.
 */
public class MalformedAnnotationClassRefTest {
    /** The name of the annotation that holds the class references. */
    private static final String ANN = "Ann";

    /** A class reference that is not a type descriptor at all. */
    private static final String GARBAGE = "garbage";

    /** A class reference that holds an internal class name instead of a type descriptor. */
    private static final String INTERNAL_NAME = "java/lang/String";

    /** A class reference that holds a type variable, which parses as a type signature but names no class. */
    private static final String TYPE_VARIABLE = "TT;";

    /** Builds a minimal classfile. */
    private static final class ClassfileWriter {
        /** The UTF8 strings in the constant pool, mapped to their index. */
        private final Map<String, Integer> utf8Entries = new LinkedHashMap<>();

        /** The class refs in the constant pool, mapped from the index of their name to their own index. */
        private final Map<Integer, Integer> classEntries = new LinkedHashMap<>();

        /**
         * The constant pool entries in order, each a UTF8 string or the index of the name of a class ref. Entry
         * {@code i} of this list has index {@code i + 1}.
         */
        private final List<Object> entries = new ArrayList<>();

        /**
         * Get the index of a UTF8 constant pool entry, adding it if needed.
         *
         * @param str
         *            the string.
         * @return the constant pool index.
         */
        int utf8(final String str) {
            return utf8Entries.computeIfAbsent(str, s -> {
                entries.add(s);
                return entries.size();
            });
        }

        /**
         * Get the index of a class ref constant pool entry, adding it if needed.
         *
         * @param internalName
         *            the internal name of the class.
         * @return the constant pool index.
         */
        int classRef(final String internalName) {
            final var nameIdx = utf8(internalName);
            return classEntries.computeIfAbsent(nameIdx, i -> {
                entries.add(i);
                return entries.size();
            });
        }

        /**
         * Write an annotation with one {@code Class<?>}-valued parameter named {@code value}.
         *
         * @param out
         *            the output.
         * @param classRefStr
         *            the string held by the class reference.
         * @throws IOException
         *             if the annotation could not be written.
         */
        void writeAnnotation(final DataOutputStream out, final String classRefStr) throws IOException {
            out.writeShort(utf8("L" + ANN + ";")); // type_index
            out.writeShort(1); // num_element_value_pairs
            out.writeShort(utf8("value"));
            out.writeByte('c');
            out.writeShort(utf8(classRefStr));
        }

        /**
         * Write a class whose annotations hold the given class references.
         *
         * @param className
         *            the class name.
         * @param classAnnotationRef
         *            the class reference held by the class annotation, or null for no class annotation.
         * @param methodAnnotationRef
         *            the class reference held by the annotation on the abstract method {@code m()}, or null for no
         *            method.
         * @param fieldTypeAnnotationRefs
         *            the class references held by the type annotations on the type of the field {@code f}, or an
         *            empty array for no field.
         * @return the classfile bytes.
         * @throws IOException
         *             if the classfile could not be written.
         */
        byte[] write(final String className, final String classAnnotationRef, final String methodAnnotationRef,
                final String... fieldTypeAnnotationRefs) throws IOException {
            // Write the body first, so that every constant pool entry it uses is known
            final var bodyBytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bodyBytes)) {
                out.writeShort(0x0421); // ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT
                out.writeShort(classRef(className)); // this_class
                out.writeShort(classRef("java/lang/Object")); // super_class
                out.writeShort(0); // Number of interfaces

                // Fields
                if (fieldTypeAnnotationRefs.length == 0) {
                    out.writeShort(0);
                } else {
                    out.writeShort(1);
                    out.writeShort(0x0001); // ACC_PUBLIC
                    out.writeShort(utf8("f"));
                    out.writeShort(utf8("Ljava/lang/String;"));
                    out.writeShort(1); // attributes_count
                    out.writeShort(utf8("RuntimeVisibleTypeAnnotations"));
                    final var attrBytes = new ByteArrayOutputStream();
                    try (var attrOut = new DataOutputStream(attrBytes)) {
                        attrOut.writeShort(fieldTypeAnnotationRefs.length); // num_annotations
                        for (final String ref : fieldTypeAnnotationRefs) {
                            attrOut.writeByte(0x13); // target_type: type in a field declaration
                            attrOut.writeByte(0); // type_path: path_length
                            writeAnnotation(attrOut, ref);
                        }
                    }
                    out.writeInt(attrBytes.size());
                    attrBytes.writeTo(out);
                }

                // Methods
                if (methodAnnotationRef == null) {
                    out.writeShort(0);
                } else {
                    out.writeShort(1);
                    out.writeShort(0x0401); // ACC_PUBLIC | ACC_ABSTRACT
                    out.writeShort(utf8("m"));
                    out.writeShort(utf8("()V"));
                    out.writeShort(1); // attributes_count
                    writeAnnotationsAttribute(out, methodAnnotationRef);
                }

                // Class attributes
                if (classAnnotationRef == null) {
                    out.writeShort(0);
                } else {
                    out.writeShort(1);
                    writeAnnotationsAttribute(out, classAnnotationRef);
                }
            }

            final var classfileBytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(classfileBytes)) {
                out.writeInt(0xCAFEBABE);
                out.writeShort(0); // Minor version
                out.writeShort(52); // Major version (Java 8)
                out.writeShort(entries.size() + 1); // Constant pool count
                for (final Object entry : entries) {
                    if (entry instanceof final String str) {
                        out.writeByte(1); // CONSTANT_Utf8
                        out.writeUTF(str);
                    } else {
                        out.writeByte(7); // CONSTANT_Class
                        out.writeShort((Integer) entry);
                    }
                }
                bodyBytes.writeTo(out);
            }
            return classfileBytes.toByteArray();
        }

        /**
         * Write a {@code RuntimeVisibleAnnotations} attribute holding one annotation.
         *
         * @param out
         *            the output.
         * @param classRefStr
         *            the string held by the class reference of the annotation.
         * @throws IOException
         *             if the attribute could not be written.
         */
        private void writeAnnotationsAttribute(final DataOutputStream out, final String classRefStr)
                throws IOException {
            out.writeShort(utf8("RuntimeVisibleAnnotations"));
            final var attrBytes = new ByteArrayOutputStream();
            try (var attrOut = new DataOutputStream(attrBytes)) {
                attrOut.writeShort(1); // num_annotations
                writeAnnotation(attrOut, classRefStr);
            }
            out.writeInt(attrBytes.size());
            attrBytes.writeTo(out);
        }
    }

    /**
     * Write the test classes: {@code ClassAnn}, with a malformed class reference in a class annotation,
     * {@code MethodAnn}, with one in a method annotation, {@code TypeVarAnn}, with a type variable in a class
     * annotation, and {@code FieldTypeAnn}, whose field type has two type annotations, one with a malformed class
     * reference.
     *
     * @param dir
     *            the directory to write the classfiles to.
     * @throws IOException
     *             if a classfile could not be written.
     */
    private static void writeClassfiles(final Path dir) throws IOException {
        Files.write(dir.resolve("ClassAnn.class"), new ClassfileWriter().write("ClassAnn", GARBAGE, null));
        Files.write(dir.resolve("TypeVarAnn.class"),
                new ClassfileWriter().write("TypeVarAnn", TYPE_VARIABLE, null));
        Files.write(dir.resolve("MethodAnn.class"), new ClassfileWriter().write("MethodAnn", null, INTERNAL_NAME));
        Files.write(dir.resolve("FieldTypeAnn.class"),
                new ClassfileWriter().write("FieldTypeAnn", null, null, GARBAGE, "Ljava/lang/String;"));
    }

    /**
     * Scan the test classes.
     *
     * @param dir
     *            the directory holding the classfiles.
     * @param enableInterClassDependencies
     *            whether to enable inter-class dependencies.
     * @return the scan result.
     */
    private static ScanResult scan(final Path dir, final boolean enableInterClassDependencies) {
        final var classGraph = new ClassGraph().enableClasspathEntries(dir.toString()).enableClassInfo()
                .enableMethodInfo().enableFieldInfo().enableAnnotationInfo();
        if (enableInterClassDependencies) {
            classGraph.enableInterClassDependencies();
        }
        return classGraph.scan();
    }

    /**
     * Get the class reference held by the only parameter of an annotation.
     *
     * @param annotationInfo
     *            the annotation.
     * @return the class reference.
     */
    private static AnnotationClassRef classRef(final AnnotationInfo annotationInfo) {
        final var value = annotationInfo.getParameterValues().getValue("value");
        assertThat(value).isInstanceOf(AnnotationClassRef.class);
        return (AnnotationClassRef) value;
    }

    /**
     * A class that has a malformed class reference in a class or method annotation is kept, and the malformed class
     * reference can be printed, hashed and compared, but reading its class name throws.
     */
    @Test
    public void malformedClassRefInDeclarationAnnotationKeepsTheClass(@TempDir final Path dir) throws IOException {
        writeClassfiles(dir);
        for (final boolean enableInterClassDependencies : new boolean[] { false, true }) {
            try (var scanResult = scan(dir, enableInterClassDependencies)) {
                assertThat(scanResult.getAllClasses().getNames()).contains("ClassAnn", "MethodAnn", "TypeVarAnn",
                        "FieldTypeAnn");
                assertThat(scanResult.getClassesWithAnnotation(ANN).getNames()).containsExactly("ClassAnn",
                        "TypeVarAnn");
                assertThat(scanResult.getClassesWithMethodAnnotation(ANN).getNames()).containsExactly("MethodAnn");

                final var classAnnotation = scanResult.getClassInfo("ClassAnn").getAllAnnotationInfo().get(ANN);
                final var classRef = classRef(classAnnotation);
                assertThat(classRef).hasToString(GARBAGE + ".class");
                assertThat(classAnnotation).hasToString("@" + ANN + "(" + GARBAGE + ".class)");
                assertThat(new HashSet<>(List.of(classAnnotation))).containsExactly(classAnnotation);
                assertThatThrownBy(classRef::getName).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(classRef::getClassInfo).isInstanceOf(IllegalArgumentException.class);

                final var methodClassRef = classRef(scanResult.getClassInfo("MethodAnn").getMethodInfo("m").get(0)
                        .getAllAnnotationInfo().get(ANN));
                assertThat(methodClassRef).hasToString(INTERNAL_NAME + ".class");
                assertThatThrownBy(methodClassRef::getName).isInstanceOf(IllegalArgumentException.class);

                final var typeVarClassRef = classRef(
                        scanResult.getClassInfo("TypeVarAnn").getAllAnnotationInfo().get(ANN));
                assertThat(typeVarClassRef).hasToString(TYPE_VARIABLE + ".class");
                assertThatThrownBy(typeVarClassRef::getName).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(typeVarClassRef::getClassInfo).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    /**
     * A malformed class reference in a type annotation does not stop the annotation from being printed, hashed or
     * sorted.
     */
    @Test
    public void malformedClassRefInTypeAnnotationCanBePrintedAndHashed(@TempDir final Path dir) throws IOException {
        writeClassfiles(dir);
        try (var scanResult = scan(dir, /* enableInterClassDependencies = */ false)) {
            final var classInfo = scanResult.getClassInfo("FieldTypeAnn");
            assertThat(classInfo).isNotNull();
            final var fieldInfo = classInfo.getFieldInfo("f");
            // Two type annotations are sorted, which compares their parameter values
            final var typeAnnotations = fieldInfo.getTypeDescriptor().getTypeAnnotationInfo();
            assertThat(typeAnnotations).hasSize(2);
            assertThat(new HashSet<>(typeAnnotations)).hasSize(2);
            assertThat(fieldInfo.toString()).contains("@" + ANN + "(" + GARBAGE + ".class)",
                    "@" + ANN + "(java.lang.String.class)");
            assertThat(classInfo.getFieldInfo().toString()).contains(GARBAGE + ".class");
        }
    }

    /**
     * Two class references are equal if and only if they hold the same type descriptor, whether or not it is
     * malformed.
     */
    @Test
    public void classRefsWithTheSameDescriptorAreEqual() {
        final var garbage = new AnnotationClassRef(GARBAGE);
        assertThat(garbage).isEqualTo(new AnnotationClassRef(GARBAGE))
                .hasSameHashCodeAs(new AnnotationClassRef(GARBAGE))
                .isNotEqualTo(new AnnotationClassRef(INTERNAL_NAME))
                .isNotEqualTo(new AnnotationClassRef("Ljava/lang/String;"));
        assertThat(new AnnotationClassRef("Ljava/lang/String;"))
                .isEqualTo(new AnnotationClassRef("Ljava/lang/String;"))
                .hasSameHashCodeAs(new AnnotationClassRef("Ljava/lang/String;"))
                .isNotEqualTo(new AnnotationClassRef("[Ljava/lang/String;"));
    }
}
