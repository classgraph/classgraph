package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ClassTypeSignature} is the generic type signature of a class declaration: its type parameters, its
 * superclass and its superinterfaces. A class that has no {@code Signature} attribute gets a synthetic type
 * descriptor built from its superclass and interface names instead.
 */
public class ClassTypeSignatureTest {
    /** A generic superclass. */
    // The type parameter is the fixture, and is deliberately not used by the body
    @SuppressWarnings("unused")
    public static class Base<T> {
    }

    /** A generic interface. */
    // The type parameter is the fixture, and is deliberately not used by the body
    @SuppressWarnings("unused")
    public interface Iface<U> {
    }

    /** A generic class with a bounded type parameter, a generic superclass and a generic interface. */
    public static class Sub<T extends Number & Comparable<T>> extends Base<T> implements Iface<T>, Serializable {
        /** serialVersionUID. */
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** A class that uses no type variables or parameterized types, so it has no {@code Signature} attribute. */
    public static class NonGeneric implements Cloneable {
    }

    /**
     * Scan the fixture classes.
     *
     * @return the scan result.
     */
    private static ScanResult scanFixture() {
        return new ClassGraph().enableClassInfo().enableClasspath().acceptClasses(Base.class.getName(),
                Iface.class.getName(), Sub.class.getName(), NonGeneric.class.getName()).scan();
    }

    /**
     * Get the {@link ClassInfo} for a fixture class.
     *
     * @param scanResult
     *            the scan result.
     * @param cls
     *            the class.
     * @return the {@link ClassInfo}.
     */
    private static ClassInfo classInfo(final ScanResult scanResult, final Class<?> cls) {
        final var classInfo = scanResult.getClassInfo(cls.getName());
        assertThat(classInfo).as("ClassInfo for " + cls.getName()).isNotNull();
        return classInfo;
    }

    /** The type parameters, superclass and superinterfaces of a generic class are all read from its signature. */
    @Test
    public void theTypeParametersSuperclassAndInterfacesAreRead() {
        try (var scanResult = scanFixture()) {
            final var typeSig = classInfo(scanResult, Sub.class).getTypeSignature();
            assertThat(typeSig).isNotNull();

            assertThat(typeSig.getTypeParameters()).hasSize(1);
            assertThat(typeSig.getTypeParameters().get(0))
                    .hasToString("T extends java.lang.Number & java.lang.Comparable<T>");

            assertThat(typeSig.getSuperclassSignature()).hasToString(Base.class.getName() + "<T>");
            assertThat(typeSig.getSuperinterfaceSignatures()).extracting(Object::toString)
                    .containsExactly(Iface.class.getName() + "<T>", "java.io.Serializable");

            // A class type signature belongs to the class it was parsed for
            assertThat(typeSig.getClassInfo()).isSameAs(classInfo(scanResult, Sub.class));
            assertThat(typeSig.getClassName()).isEqualTo(Sub.class.getName());

            // Only Scala class signatures have a throws suffix
            assertThat(typeSig.getThrowsSignatures()).isEmpty();
        }
    }

    /** A class with no {@code Signature} attribute gets a synthetic type descriptor instead. */
    @Test
    public void aClassWithNoSignatureAttributeGetsASyntheticTypeDescriptor() {
        try (var scanResult = scanFixture()) {
            final var nonGeneric = classInfo(scanResult, NonGeneric.class);
            assertThat(nonGeneric.getTypeSignatureString()).isNull();
            assertThat(nonGeneric.getTypeSignature()).isNull();

            final var typeDescriptor = nonGeneric.getTypeDescriptor();
            assertThat(typeDescriptor.getTypeParameters()).isEmpty();
            assertThat(typeDescriptor.getSuperclassSignature()).hasToString("java.lang.Object");
            assertThat(typeDescriptor.getSuperinterfaceSignatures()).extracting(Object::toString)
                    .containsExactly("java.lang.Cloneable");
            assertThat(typeDescriptor.getThrowsSignatures()).isEmpty();

            // The type descriptor is cached, and is what getTypeSignatureOrTypeDescriptor() falls back to
            assertThat(nonGeneric.getTypeDescriptor()).isSameAs(typeDescriptor);
            assertThat(nonGeneric.getTypeSignatureOrTypeDescriptor()).isSameAs(typeDescriptor);

            // A class that does have a type signature returns it in preference to the type descriptor
            final var sub = classInfo(scanResult, Sub.class);
            assertThat(sub.getTypeSignatureOrTypeDescriptor()).isSameAs(sub.getTypeSignature());
        }
    }

    /**
     * Generate a class {@code Impl} with no {@code Signature} attribute, whose superclass {@code Base:x} and first
     * interface {@code I:x} have names that the JVM accepts but that cannot be written as a class reference in a
     * type signature, since ':' ends a name there. The second interface is {@code I2}. The superclass and the first
     * interface have the type annotation {@code @TA}.
     *
     * @return the bytes of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] unparseableSupertypeNamesClassfileBytes() throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(11); // Constant pool count (one greater than the number of entries)
            // #1, #3, #5, #7: the names of this class, the superclass and the two interfaces
            // #2, #4, #6, #8: class refs to them
            final var names = new String[] { "Impl", "Base:x", "I:x", "I2" };
            for (var i = 0; i < names.length; i++) {
                out.writeByte(1);
                out.writeUTF(names[i]);
                out.writeByte(7);
                out.writeShort(2 * i + 1);
            }
            // #9
            out.writeByte(1);
            out.writeUTF("RuntimeVisibleTypeAnnotations");
            // #10
            out.writeByte(1);
            out.writeUTF("LTA;");

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(2); // Number of interfaces
            out.writeShort(6);
            out.writeShort(8);
            out.writeShort(0); // Number of fields
            out.writeShort(0); // Number of methods

            out.writeShort(1); // Number of attributes
            out.writeShort(9); // "RuntimeVisibleTypeAnnotations"
            out.writeInt(2 + 2 * 8);
            out.writeShort(2); // num_annotations
            for (final int supertypeIndex : new int[] { 0xFFFF, 0 }) {
                out.writeByte(0x10); // target_type: type in extends or implements clause
                out.writeShort(supertypeIndex); // 65535 for the superclass, else an index into interfaces[]
                out.writeByte(0); // type_path: path_length
                out.writeShort(10); // type_index: "LTA;"
                out.writeShort(0); // num_element_value_pairs
            }
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * The synthetic type descriptor has the superclass and one superinterface signature for each interface, in
     * classfile order, even for supertype names that cannot be parsed as a class reference, so that a type
     * annotation on a supertype stays on the supertype it was written for.
     *
     * @param tempDir
     *            the directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written
     */
    @Test
    public void theSyntheticTypeDescriptorKeepsEverySupertype(@TempDir final File tempDir) throws IOException {
        Files.write(new File(tempDir, "Impl.class").toPath(), unparseableSupertypeNamesClassfileBytes());
        try (var scanResult = new ClassGraph().enableClassInfo().enableAnnotationInfo()
                .enableClasspathEntries(tempDir).scan()) {
            final var typeDescriptor = scanResult.getClassInfo("Impl").getTypeDescriptor();
            final var superclassSignature = typeDescriptor.getSuperclassSignature();
            assertThat(superclassSignature).isNotNull();
            assertThat(superclassSignature.getFullyQualifiedClassName()).isEqualTo("Base:x");
            assertThat(superclassSignature.getTypeAnnotationInfo()).extracting(AnnotationInfo::getName)
                    .containsExactly("TA");
            final var superinterfaceSignatures = typeDescriptor.getSuperinterfaceSignatures();
            assertThat(superinterfaceSignatures).extracting(ClassRefTypeSignature::getFullyQualifiedClassName)
                    .containsExactly("I:x", "I2");
            assertThat(superinterfaceSignatures.get(0).getTypeAnnotationInfo()).extracting(AnnotationInfo::getName)
                    .containsExactly("TA");
            assertThat(superinterfaceSignatures.get(1).getTypeAnnotationInfo()).isEmpty();
        }
    }

    /** A class type signature renders as the class declaration it was compiled from. */
    @Test
    public void aClassTypeSignatureRendersAsAClassDeclaration() {
        try (var scanResult = scanFixture()) {
            assertThat(classInfo(scanResult, Sub.class).getTypeSignature()).hasToString("public static class "
                    + Sub.class.getName() + "<T extends java.lang.Number & java.lang.Comparable<T>> extends "
                    + Base.class.getName() + "<T> implements " + Iface.class.getName()
                    + "<T>, java.io.Serializable");

            // "extends java.lang.Object" is omitted, and an interface "extends" its superinterfaces
            assertThat(classInfo(scanResult, NonGeneric.class).getTypeDescriptor()).hasToString(
                    "public static class " + NonGeneric.class.getName() + " implements " + "java.lang.Cloneable");
            assertThat(classInfo(scanResult, Iface.class).getTypeSignature())
                    .hasToString("public abstract static interface " + Iface.class.getName() + "<U>");
        }
    }

    /**
     * Two classes with the same type parameters and supertypes do not have equal type signatures, and neither do
     * two signatures that differ only in their throws suffix.
     */
    @Test
    public void signaturesOfDifferentClassesOrThrowsSuffixesAreNotEqual() throws Exception {
        try (var scanResult = scanFixture()) {
            final var base = classInfo(scanResult, Base.class);
            final var iface = classInfo(scanResult, Iface.class);
            final var baseSig = ClassTypeSignature.parse("<T:Ljava/lang/Object;>Ljava/lang/Object;", base);
            final var ifaceSig = ClassTypeSignature.parse("<T:Ljava/lang/Object;>Ljava/lang/Object;", iface);
            assertThat(baseSig).isNotEqualTo(ifaceSig);

            final var throwsSig = ClassTypeSignature
                    .parse("<T:Ljava/lang/Object;>Ljava/lang/Object;^Ljava/lang/Exception;", base);
            assertThat(baseSig).isNotEqualTo(throwsSig);
            final var sameThrowsSig = ClassTypeSignature
                    .parse("<T:Ljava/lang/Object;>Ljava/lang/Object;^Ljava/lang/Exception;", base);
            assertThat(sameThrowsSig).isEqualTo(throwsSig).hasSameHashCodeAs(throwsSig);
        }
    }

    /**
     * Class type signatures are equal if they belong to the same class, and their type parameters, superclass,
     * superinterfaces and throws suffixes are equal.
     */
    @Test
    public void signaturesAreComparedByTypeParametersSuperclassAndInterfaces() {
        try (var scanResult = scanFixture(); var scanResult2 = scanFixture()) {
            final var typeSig = classInfo(scanResult, Sub.class).getTypeSignature();
            final var sameTypeSig = classInfo(scanResult2, Sub.class).getTypeSignature();
            assertThat(sameTypeSig).isNotSameAs(typeSig).isEqualTo(typeSig).hasSameHashCodeAs(typeSig);

            final var otherTypeSig = classInfo(scanResult, NonGeneric.class).getTypeDescriptor();
            assertThat(typeSig).isEqualTo(typeSig).isNotEqualTo(otherTypeSig).isNotEqualTo(null)
                    .isNotEqualTo(typeSig.toString());
        }
    }

    /**
     * The Scala compiler emits a {@code throws} suffix on a class type signature when the class is tagged with
     * {@code @throws}, which the classfile spec does not allow. ClassGraph parses it anyway, and renders it.
     */
    // #495
    @Test
    public void aScalaThrowsSuffixIsParsedAndRendered() throws Exception {
        final var resourceURL = ClassTypeSignatureTest.class.getClassLoader().getResource("scalapackage.zip");
        assertThat(resourceURL).isNotNull();
        try (var classLoader = new URLClassLoader(new URL[] { resourceURL }, null);
                var scanResult = new ClassGraph().enableClassInfo().acceptPackages("scalapackage")
                        .enableClassLoaders(classLoader).scan()) {
            final var typeSig = scanResult.getClassInfo("scalapackage.ScalaClass").getTypeSignature();
            assertThat(typeSig).isNotNull();
            assertThat(typeSig.getThrowsSignatures()).extracting(Object::toString)
                    .containsExactly("java.lang.IllegalArgumentException");
            assertThat(typeSig).hasToString(
                    "@throws(java.lang.IllegalArgumentException) public class scalapackage.ScalaClass<T>");
        }
    }

    /** A class type signature that cannot be parsed is rejected with a {@link TypeSignatureParseException}. */
    @Test
    public void aMalformedClassTypeSignatureIsRejected() {
        try (var scanResult = scanFixture()) {
            final var classInfo = classInfo(scanResult, Sub.class);

            // "X" is not a superinterface signature
            assertThatExceptionOfType(TypeSignatureParseException.class)
                    .isThrownBy(() -> ClassTypeSignature.parse("Ljava/lang/Object;X", classInfo))
                    .withMessageContaining("Could not parse superinterface signature");

            // "X" is neither a class reference nor a type variable, so it cannot follow "^"
            assertThatExceptionOfType(TypeSignatureParseException.class)
                    .isThrownBy(() -> ClassTypeSignature.parse("Ljava/lang/Object;^X", classInfo))
                    .withMessageContaining("Missing type variable signature");

            // The throws suffix is the last thing in a signature, so nothing may follow it
            assertThatExceptionOfType(TypeSignatureParseException.class)
                    .isThrownBy(() -> ClassTypeSignature.parse("Ljava/lang/Object;^Ljava/lang/Error;X", classInfo))
                    .withMessageContaining("Extra characters at end of type descriptor");
        }
    }

    /** A type variable may be thrown, as well as a class reference. */
    // #495
    @Test
    public void aThrowsSuffixMayNameATypeVariable() throws TypeSignatureParseException {
        try (var scanResult = scanFixture()) {
            final var typeSig = ClassTypeSignature.parse("<T:Ljava/lang/Throwable;>Ljava/lang/Object;^TT;",
                    classInfo(scanResult, Sub.class));
            assertThat(typeSig.getThrowsSignatures()).extracting(Object::toString).containsExactly("T");
        }
    }

    /** A throws suffix may name several types, which are kept in the order they appear in. */
    // #495
    @Test
    public void aThrowsSuffixMayNameSeveralTypes() throws TypeSignatureParseException {
        try (var scanResult = scanFixture()) {
            final var typeSig = ClassTypeSignature.parse(
                    "<E:Ljava/lang/Exception;>Ljava/lang/Object;^TE;^Ljava/io/IOException;",
                    classInfo(scanResult, Sub.class));
            assertThat(typeSig.getThrowsSignatures()).extracting(Object::toString).containsExactly("E",
                    "java.io.IOException");
            assertThat(typeSig.toString()).startsWith("@throws(E) @throws(java.io.IOException) ");
        }
    }
}
