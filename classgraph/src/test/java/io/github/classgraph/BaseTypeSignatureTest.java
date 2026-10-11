package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link BaseTypeSignature}, the type signature of the primitive types and void. */
public class BaseTypeSignatureTest {
    /** An annotation that can be written on a use of a type. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface NonNegative {
    }

    /** A class with a primitive-typed field and method whose types carry a type annotation. */
    public static class AnnotatedPrimitives {
        /** A field whose type is annotated. */
        public @NonNegative int annotatedField;

        /**
         * A method whose return type is annotated.
         *
         * @return zero.
         */
        public @NonNegative long annotatedMethod() {
            return 0L;
        }
    }

    /** The type signature character of each base type, and the type it stands for. */
    private static final Map<Character, Class<?>> BASE_TYPES = Map.of('B', byte.class, 'C', char.class, 'D',
            double.class, 'F', float.class, 'I', int.class, 'J', long.class, 'S', short.class, 'Z', boolean.class,
            'V', void.class);

    /** Each base type knows its signature character, its name and the class of the type it stands for. */
    @Test
    public void everyBaseTypeIsNamedAndTyped() {
        BASE_TYPES.forEach((typeSignatureChar, type) -> {
            final var signature = new BaseTypeSignature(typeSignatureChar);
            assertThat(signature.getTypeSignatureChar()).isEqualTo(typeSignatureChar);
            assertThat(signature.getTypeName()).isEqualTo(type.getName());
            assertThat(signature.getType()).isEqualTo(type);
            assertThat(signature.toString()).isEqualTo(type.getName());
            // A base type has no ClassInfo, since primitive types are not scanned
            assertThat(signature.getClassName()).isEqualTo(type.getName());
            assertThat(signature.getClassInfo()).isNull();

            assertThat(BaseTypeSignature.typeCharToString(typeSignatureChar)).isEqualTo(type.getName());
            assertThat(BaseTypeSignature.getTypeChar(type.getName())).isEqualTo(typeSignatureChar);
            assertThat(BaseTypeSignature.getType(typeSignatureChar)).isEqualTo(type);
        });
    }

    /** A type signature character that is not a base type is rejected. */
    @Test
    public void anIllegalTypeCharacterIsRejected() {
        assertThatThrownBy(() -> new BaseTypeSignature('L')).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Illegal BaseTypeSignature type: 'L'");
    }

    /** The lookups report no match, rather than throwing, for anything that is not a base type. */
    @Test
    public void typesThatAreNotBaseTypesHaveNoMatch() {
        assertThat(BaseTypeSignature.typeCharToString('L')).isNull();
        assertThat(BaseTypeSignature.getType('L')).isNull();
        assertThat(BaseTypeSignature.getTypeChar("java.lang.String")).isEqualTo('\0');
        // "Integer" is not "int" -- only the primitive type names match
        assertThat(BaseTypeSignature.getTypeChar("Integer")).isEqualTo('\0');
    }

    /** Parsing reads exactly one base type character, and leaves anything else for the caller to parse. */
    @Test
    public void parsingReadsOneBaseTypeCharacter() throws TypeSignatureParseException {
        final var parser = new TypeSignatureParser("IJ");
        assertThat(BaseTypeSignature.parse(parser).getTypeName()).isEqualTo("int");
        assertThat(BaseTypeSignature.parse(parser).getTypeName()).isEqualTo("long");
        assertThat(parser.hasMore()).isFalse();
        // Parsing at the end of the string yields null rather than throwing
        assertThat(BaseTypeSignature.parse(parser)).isNull();

        final var classTypeParser = new TypeSignatureParser("Ljava/lang/String;");
        assertThat(BaseTypeSignature.parse(classTypeParser)).isNull();
        // A failed parse does not consume anything
        assertThat(classTypeParser.getPosition()).isZero();
    }

    /** Two base types are equal if they are the same type and carry the same type annotations. */
    @Test
    public void equalityIsByTypeAndTypeAnnotations() {
        final var intSignature = new BaseTypeSignature('I');
        assertThat(intSignature).isEqualTo(intSignature).isEqualTo(new BaseTypeSignature('I'))
                .isNotEqualTo(new BaseTypeSignature('J')).isNotEqualTo("int");
        assertThat(intSignature).hasSameHashCodeAs(new BaseTypeSignature('I'));

        assertThat(intSignature.equalsIgnoringTypeParams(new BaseTypeSignature('I'))).isTrue();
        assertThat(intSignature.equalsIgnoringTypeParams(new BaseTypeSignature('J'))).isFalse();
        assertThat(intSignature.equalsIgnoringTypeParams(null)).isFalse();

        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(AnnotatedPrimitives.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var annotatedInt = scanResult.getClassInfo(AnnotatedPrimitives.class.getName())
                    .getFieldInfo("annotatedField").getTypeSignatureOrTypeDescriptor();
            // The type annotation is part of the identity of the type, but not of the type ignoring type params
            assertThat(annotatedInt).isNotEqualTo(intSignature);
            assertThat(annotatedInt.equalsIgnoringTypeParams(intSignature)).isTrue();
        }
    }

    /** A type annotation on a primitive type is shown before the type name. */
    @Test
    public void typeAnnotationsAreShownBeforeTheTypeName() {
        try (var scanResult = new ClassGraph().enableClasspath().acceptClasses(AnnotatedPrimitives.class.getName())
                .enableClassInfo().enableFieldInfo().enableMethodInfo().enableAnnotationInfo()
                .enableStaticFinalFieldConstantInitializerValues().ignoreClassVisibility().ignoreFieldVisibility()
                .ignoreMethodVisibility().scan()) {
            final var classInfo = scanResult.getClassInfo(AnnotatedPrimitives.class.getName());
            final var annotation = "@" + NonNegative.class.getName();
            final var fieldType = classInfo.getFieldInfo("annotatedField").getTypeSignatureOrTypeDescriptor();
            assertThat(fieldType.toString()).isEqualTo(annotation + " int");
            assertThat(fieldType.toStringWithSimpleNames()).isEqualTo("@NonNegative int");
            assertThat(classInfo.getMethodInfo("annotatedMethod").get(0).getTypeSignatureOrTypeDescriptor()
                    .getResultType().toString()).isEqualTo(annotation + " long");

            // A base type is given the ScanResult like any other type signature, so that a type annotation on a
            // primitive type can reach its own annotation class. (#419 used to suppress this, back when a single
            // BaseTypeSignature instance was shared between all uses of a given primitive type; instances are no
            // longer shared, and a base type is reachable from the ScanResult anyway, so suppressing it only broke
            // type annotations.)
            assertThat(((BaseTypeSignature) fieldType).scanResult).isSameAs(scanResult);
        }
    }

    /** A base type has no internal structure, so a type annotation on it must have an empty type path. */
    @Test
    public void aTypeAnnotationOnABaseTypeMustHaveAnEmptyTypePath() {
        final var intSignature = new BaseTypeSignature('I');
        final var typePath = List.of(new Classfile.TypePathNode((short) 0, (short) 0));
        assertThatThrownBy(() -> intSignature.addTypeAnnotation(typePath, new AnnotationInfo("Ann", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Base type should have empty typePath");
        assertThat(intSignature.getTypeAnnotationInfo()).isEmpty();
    }

    /**
     * Write a public field whose type is annotated with {@code @Good} and {@code @Bad}, as in a corrupt classfile:
     * the type path of {@code @Good} leads to the base type, and the type path of {@code @Bad} has one more
     * {@code [ARRAY]} step, which leads past the base type.
     *
     * @param out
     *            the output stream of the classfile
     * @param nameIndex
     *            the constant pool index of the field name
     * @param descriptorIndex
     *            the constant pool index of the field descriptor
     * @param numDimensions
     *            the number of array dimensions of the field type
     * @throws IOException
     *             if the field could not be written
     */
    private static void writeField(final DataOutputStream out, final int nameIndex, final int descriptorIndex,
            final int numDimensions) throws IOException {
        out.writeShort(0x0001); // ACC_PUBLIC
        out.writeShort(nameIndex);
        out.writeShort(descriptorIndex);
        out.writeShort(1); // Number of attributes
        out.writeShort(5); // "RuntimeVisibleTypeAnnotations"
        out.writeInt(2 + (6 + 2 * numDimensions) + (6 + 2 * (numDimensions + 1)));
        out.writeShort(2); // num_annotations
        for (var pathLength = numDimensions; pathLength <= numDimensions + 1; pathLength++) {
            out.writeByte(0x13); // target_type: type in field declaration (empty target)
            out.writeByte(pathLength); // type_path: path_length
            for (var i = 0; i < pathLength; i++) {
                out.writeByte(0); // type_path_kind: ARRAY
                out.writeByte(0); // type_argument_index
            }
            out.writeShort(pathLength == numDimensions ? 6 : 7); // type_index: "LGood;" or "LBad;"
            out.writeShort(0); // num_element_value_pairs
        }
    }

    /**
     * A type annotation in a classfile whose type path leads past a base type is skipped, rather than being placed
     * on the base type, and the other type annotations on the same type are still applied.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void aTypeAnnotationWhosePathLeadsPastABaseTypeIsSkipped(@TempDir final File tempDir)
            throws IOException {
        final var className = "BadBaseTypePath";
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)
            out.writeShort(12); // Constant pool count (one greater than the number of entries)
            final var utf8Entries = new String[] { className, null, "java/lang/Object", null,
                    "RuntimeVisibleTypeAnnotations", "LGood;", "LBad;", "intField", "I", "intArrayField", "[I" };
            for (var i = 0; i < utf8Entries.length; i++) {
                if (utf8Entries[i] == null) {
                    out.writeByte(7); // Class ref to the preceding entry
                    out.writeShort(i);
                } else {
                    out.writeByte(1);
                    out.writeUTF(utf8Entries[i]);
                }
            }
            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces
            out.writeShort(2); // Number of fields
            writeField(out, 8, 9, 0);
            writeField(out, 10, 11, 1);
            out.writeShort(0); // Number of methods
            out.writeShort(0); // Number of attributes
        }
        Files.write(new File(tempDir, className + ".class").toPath(), byteArrayOutputStream.toByteArray());

        try (var scanResult = new ClassGraph().enableClassInfo().enableFieldInfo().enableAnnotationInfo()
                .enableClasspathEntries(tempDir).scan()) {
            final var classInfo = scanResult.getClassInfo(className);
            assertThat(classInfo.getFieldInfo("intField").getTypeDescriptor().getTypeAnnotationInfo())
                    .extracting(AnnotationInfo::getName).containsExactly("Good");
            final var intArrayType = (ArrayTypeSignature) classInfo.getFieldInfo("intArrayField")
                    .getTypeDescriptor();
            assertThat(intArrayType.getTypeAnnotationInfo()).isEmpty();
            assertThat(intArrayType.getElementTypeSignature().getTypeAnnotationInfo())
                    .extracting(AnnotationInfo::getName).containsExactly("Good");
        }
    }
}
