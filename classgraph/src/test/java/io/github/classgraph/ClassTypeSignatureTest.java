package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.Serial;
import java.io.Serializable;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.internal.types.ParseException;

/**
 * {@link ClassTypeSignature} is the generic type signature of a class declaration: its type parameters, its
 * superclass and its superinterfaces. A class that has no {@code Signature} attribute gets a synthetic type
 * descriptor built from its superclass and interface names instead.
 */
public class ClassTypeSignatureTest {
    /** A generic superclass. */
    public static class Base<T> {
    }

    /** A generic interface. */
    public interface Iface<U> {
    }

    /** A generic class with a bounded type parameter, a generic superclass and a generic interface. */
    public static class Sub<T extends Number & Comparable<T>> extends Base<T> implements Iface<T>, Serializable {
        /** Serialization version. */
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
        return new ClassGraph().acceptClasses(Base.class.getName(), Iface.class.getName(), Sub.class.getName(),
                NonGeneric.class.getName()).scan();
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
            assertThat(typeSig.getThrowsSignatures()).isNull();
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
            assertThat(typeDescriptor.getThrowsSignatures()).isNull();

            // The type descriptor is cached, and is what getTypeSignatureOrTypeDescriptor() falls back to
            assertThat(nonGeneric.getTypeDescriptor()).isSameAs(typeDescriptor);
            assertThat(nonGeneric.getTypeSignatureOrTypeDescriptor()).isSameAs(typeDescriptor);

            // A class that does have a type signature returns it in preference to the type descriptor
            final var sub = classInfo(scanResult, Sub.class);
            assertThat(sub.getTypeSignatureOrTypeDescriptor()).isSameAs(sub.getTypeSignature());
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

    /** Class type signatures are equal if their type parameters, superclass and superinterfaces are equal. */
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
     * A type annotation cannot be added to a class type signature as a whole -- the type parameters, superclass and
     * superinterfaces each take their own type annotations.
     */
    @Test
    public void aTypeAnnotationCannotBeAddedToAClassTypeSignatureAsAWhole() {
        try (var scanResult = scanFixture()) {
            final var typeSig = classInfo(scanResult, Sub.class).getTypeSignature();
            assertThat(typeSig).isNotNull();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> typeSig.addTypeAnnotation(List.of(), null));
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
                var scanResult = new ClassGraph().acceptPackages("scalapackage").overrideClassLoaders(classLoader)
                        .scan()) {
            final var typeSig = scanResult.getClassInfo("scalapackage.ScalaClass").getTypeSignature();
            assertThat(typeSig).isNotNull();
            assertThat(typeSig.getThrowsSignatures()).extracting(Object::toString)
                    .containsExactly("java.lang.IllegalArgumentException");
            assertThat(typeSig).hasToString(
                    "@throws(java.lang.IllegalArgumentException) public class scalapackage.ScalaClass<T>");
        }
    }

    /** A class type signature that cannot be parsed is rejected with a {@link ParseException}. */
    @Test
    public void aMalformedClassTypeSignatureIsRejected() {
        try (var scanResult = scanFixture()) {
            final var classInfo = classInfo(scanResult, Sub.class);

            // "X" is not a superinterface signature
            assertThatExceptionOfType(ParseException.class)
                    .isThrownBy(() -> ClassTypeSignature.parse("Ljava/lang/Object;X", classInfo))
                    .withMessageContaining("Could not parse superinterface signature");

            // "X" is neither a class reference nor a type variable, so it cannot follow "^"
            assertThatExceptionOfType(ParseException.class)
                    .isThrownBy(() -> ClassTypeSignature.parse("Ljava/lang/Object;^X", classInfo))
                    .withMessageContaining("Missing type variable signature");

            // The throws suffix is the last thing in a signature, so nothing may follow it
            assertThatExceptionOfType(ParseException.class)
                    .isThrownBy(() -> ClassTypeSignature.parse("Ljava/lang/Object;^Ljava/lang/Error;X", classInfo))
                    .withMessageContaining("Extra characters at end of type descriptor");
        }
    }

    /** A type variable may be thrown, as well as a class reference. */
    // #495
    @Test
    public void aThrowsSuffixMayNameATypeVariable() throws ParseException {
        try (var scanResult = scanFixture()) {
            final var typeSig = ClassTypeSignature.parse("<T:Ljava/lang/Throwable;>Ljava/lang/Object;^TT;",
                    classInfo(scanResult, Sub.class));
            assertThat(typeSig.getThrowsSignatures()).extracting(Object::toString).containsExactly("T");
        }
    }
}
