package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A '$' in a class reference usually separates the name of a nested class from the name of its enclosing class, but
 * it can also be part of a name: Scala names the class of an {@code object} by appending '$' to the object's name,
 * and synthetic classes can contain "$$".
 *
 * <p>
 * Also tests the type annotations on the suffixes (nested classes) of a class reference.
 */
public class ClassRefTypeSignatureTest {
    /**
     * Parse a class reference.
     *
     * @param typeSignatureStr
     *            the class reference, in JVM type signature format.
     * @return the parsed class reference.
     * @throws TypeSignatureParseException
     *             if the class reference could not be parsed.
     */
    private static ClassRefTypeSignature parse(final String typeSignatureStr) throws TypeSignatureParseException {
        final var typeSignature = TypeSignature.parse(typeSignatureStr, /* definingClass = */ null);
        assertThat(typeSignature).isInstanceOf(ClassRefTypeSignature.class);
        return (ClassRefTypeSignature) typeSignature;
    }

    /** A class name that ends in '$' keeps the '$'. */
    @Test
    public void aTrailingDollarSignIsPartOfTheName() throws TypeSignatureParseException {
        final var sig = parse("Lp/Obj$;");
        assertThat(sig.getBaseClassName()).isEqualTo("p.Obj$");
        assertThat(sig.getSuffixes()).isEmpty();
        assertThat(sig.getFullyQualifiedClassName()).isEqualTo("p.Obj$");
        assertThat(sig).hasToString("p.Obj$");
    }

    /**
     * A Scala {@code object} nested in a generic class, e.g. {@code class Outer[T] { object Inner }}, keeps the
     * type argument of the enclosing class, and its name ends in '$'.
     */
    @Test
    public void anObjectNestedInAGenericClassKeepsTheTypeArgument() throws TypeSignatureParseException {
        final var sig = parse("Lp/Outer<TT;>.Inner$;");
        assertThat(sig.getBaseClassName()).isEqualTo("p.Outer");
        assertThat(sig.getTypeArguments()).extracting(Object::toString).containsExactly("T");
        assertThat(sig.getSuffixes()).containsExactly("Inner$");
        assertThat(sig.getSuffixTypeArguments()).containsExactly(List.of());
        assertThat(sig.getFullyQualifiedClassName()).isEqualTo("p.Outer$Inner$");
        assertThat(sig).hasToString("p.Outer<T>$Inner$");
    }

    /** "$$" gives the same fully-qualified class name as the class reference with every '$' kept. */
    @Test
    public void aDoubleDollarSignIsKept() throws TypeSignatureParseException {
        final var sig = parse("Lp/A$$B;");
        assertThat(sig.getFullyQualifiedClassName()).isEqualTo("p.A$$B");
        assertThat(sig).hasToString("p.A$$B");
    }

    /** A class name that ends in '$' can be followed by type arguments, as in {@code Box$<String>}. */
    @Test
    public void typeArgumentsCanFollowATrailingDollarSign() throws TypeSignatureParseException {
        final var sig = parse("Lp1/Box$<Ljava/lang/String;>;");
        assertThat(sig.getBaseClassName()).isEqualTo("p1.Box$");
        assertThat(sig.getTypeArguments()).extracting(Object::toString).containsExactly("java.lang.String");
        assertThat(sig.getSuffixes()).isEmpty();
        assertThat(sig).hasToString("p1.Box$<java.lang.String>");

        final var nested = parse("Lp1/Outer$Box$<Ljava/lang/Integer;>;");
        assertThat(nested.getBaseClassName()).isEqualTo("p1.Outer");
        assertThat(nested.getTypeArguments()).isEmpty();
        assertThat(nested.getSuffixes()).containsExactly("Box$");
        assertThat(nested.getSuffixTypeArguments()).hasSize(1);
        assertThat(nested.getSuffixTypeArguments().get(0)).extracting(Object::toString)
                .containsExactly("java.lang.Integer");
        assertThat(nested).hasToString("p1.Outer$Box$<java.lang.Integer>");

        final var doubleDollar = parse("Lp1/Box$$<Ljava/lang/String;>;");
        assertThat(doubleDollar.getBaseClassName()).isEqualTo("p1.Box$$");
        assertThat(doubleDollar.getTypeArguments()).extracting(Object::toString)
                .containsExactly("java.lang.String");
    }

    /** A name cannot have two lists of type arguments, one before and one after a trailing '$'. */
    @Test
    public void typeArgumentsBeforeAndAfterATrailingDollarSignAreAParseError() {
        assertThatExceptionOfType(TypeSignatureParseException.class).isThrownBy(() -> parse("Lp1/Box<TT;>$<TU;>;"));
        assertThatExceptionOfType(TypeSignatureParseException.class)
                .isThrownBy(() -> parse("Lp1/Outer$Box<TT;>$<TU;>;"));
    }

    /** A '.' must be followed by the name of a nested class. */
    @Test
    public void aDotWithoutANameIsAParseError() {
        assertThatExceptionOfType(TypeSignatureParseException.class).isThrownBy(() -> parse("Lp/Outer<TT;>.;"));
        assertThatExceptionOfType(TypeSignatureParseException.class)
                .isThrownBy(() -> parse("Lp/Outer<TT;>..Inner;"));
    }

    /** After a '.', a name can start with '$', since '$' is a legal first character of a Java identifier. */
    @Test
    public void aNameAfterADotCanStartWithADollarSign() throws TypeSignatureParseException {
        final var sig = parse("Lp/Outer<TT;>.$Inner;");
        assertThat(sig.getSuffixes()).containsExactly("$Inner");
        assertThat(sig.getFullyQualifiedClassName()).isEqualTo("p.Outer$$Inner");
    }

    /**
     * A '$' at the start of a class name has no enclosing class name before it, so it is part of the name, as in a
     * class in the default package named {@code $Foo}.
     */
    @Test
    public void aClassNameCanStartWithADollarSign() throws TypeSignatureParseException {
        final var sig = parse("L$Foo;");
        assertThat(sig.getBaseClassName()).isEqualTo("$Foo");
        assertThat(sig.getSuffixes()).isEmpty();
        assertThat(sig.getFullyQualifiedClassName()).isEqualTo("$Foo");
        assertThat(sig).hasToString("$Foo");
    }

    /**
     * A '$' at the start of the simple name after a package is part of the name, as in AutoValue's generated class
     * names ({@code $AutoValue_Foo} and {@code $$AutoValue_Foo}).
     */
    @Test
    public void aSimpleNameAfterAPackageCanStartWithDollarSigns() throws TypeSignatureParseException {
        final var sig = parse("Lp/$A;");
        assertThat(sig.getBaseClassName()).isEqualTo("p.$A");
        assertThat(sig.getSuffixes()).isEmpty();
        assertThat(sig.toStringWithSimpleNames()).isEqualTo("$A");
        final var sig2 = parse("Lp/$$A;");
        assertThat(sig2.getBaseClassName()).isEqualTo("p.$$A");
        assertThat(sig2.getSuffixes()).isEmpty();
        final var sig3 = parse("Lp/$q/B;");
        assertThat(sig3.getBaseClassName()).isEqualTo("p.$q.B");
        assertThat(sig3.getSuffixes()).isEmpty();
    }

    /** The type arguments of a class whose simple name starts with '$' belong to the base class. */
    @Test
    public void typeArgumentsOfANameStartingWithADollarSignAreKept() throws TypeSignatureParseException {
        final var sig = parse("Lp/$Cons<TA;>;");
        assertThat(sig.getBaseClassName()).isEqualTo("p.$Cons");
        assertThat(sig.getTypeArguments()).extracting(Object::toString).containsExactly("A");
        assertThat(sig.getSuffixes()).isEmpty();
        assertThat(sig.toStringWithSimpleNames()).isEqualTo("$Cons<A>");
    }

    /** A '$' after the first character of a name that starts with '$' still separates a nested class name. */
    @Test
    public void aNestedClassOfANameStartingWithADollarSignIsASuffix() throws TypeSignatureParseException {
        final var sig = parse("L$Foo$Inner;");
        assertThat(sig.getBaseClassName()).isEqualTo("$Foo");
        assertThat(sig.getSuffixes()).containsExactly("Inner");
        assertThat(sig.getFullyQualifiedClassName()).isEqualTo("$Foo$Inner");
    }

    /** The simple name of a class keeps the '$' characters at the start of the name. */
    @Test
    public void theSimpleNameKeepsALeadingDollarSign() {
        assertThat(ClassInfo.getSimpleName("$Foo")).isEqualTo("$Foo");
        assertThat(ClassInfo.getSimpleName("p.$$A")).isEqualTo("$$A");
        assertThat(ClassInfo.getSimpleName("p.$Foo$Inner")).isEqualTo("Inner");
        assertThat(ClassInfo.getSimpleName("p.A$$B")).isEqualTo("B");
        assertThat(ClassInfo.getSimpleName("p.A")).isEqualTo("A");
        assertThat(ClassInfo.getSimpleName("A")).isEqualTo("A");
    }

    /** A "$$" after the start of a name still splits as before: the first '$' joins the preceding name. */
    @Test
    public void aDoubleDollarSignInsideANameSplitsAsBefore() throws TypeSignatureParseException {
        final var sig = parse("Lp/A$$B;");
        assertThat(sig.getBaseClassName()).isEqualTo("p.A$");
        assertThat(sig.getSuffixes()).containsExactly("B");
    }

    /** A type annotation for a use of a type variable. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface UseSite {
    }

    /**
     * A generic class with an inner class, so that a reference to the inner class has a suffix.
     *
     * @param <T>
     *            an unused type parameter.
     */
    public static class GenericOuter<T> {
        /** A non-static inner class. */
        public class Inner {
        }
    }

    /**
     * A class with an annotated use of a type variable, and a field whose type has a suffix.
     *
     * @param <T>
     *            the type variable.
     */
    public static class Holder<T> {
        /** An annotated use of a type variable. */
        public @UseSite T useSite;

        /** A field whose type is a reference to an inner class of a generic class. */
        public GenericOuter<String>.Inner nested;
    }

    /**
     * Substituting a nested class type for an annotated type variable puts the type annotation on the innermost
     * nested class of a new type signature. The annotation is shared with the type variable's type signature, so it
     * must keep its ScanResult; the new type signature has none yet.
     */
    @Test
    public void prependingASuffixTypeAnnotationKeepsItsScanResult() {
        try (var scanResult = new ClassGraph().enableClasspath()
                .acceptClasses(Holder.class.getName(), GenericOuter.class.getName(), UseSite.class.getName())
                .enableClassInfo().enableFieldInfo().enableAnnotationInfo().scan()) {
            final var holder = scanResult.getClassInfo(Holder.class.getName());
            final var useSite = (TypeVariableSignature) holder.getFieldInfo("useSite")
                    .getTypeSignatureOrTypeDescriptor();
            final var annotationInfo = useSite.getTypeAnnotationInfo().get(0);
            final var nested = (ClassRefTypeSignature) holder.getFieldInfo("nested")
                    .getTypeSignatureOrTypeDescriptor();

            final var prepended = nested.withTypeAnnotationsPrepended(List.of(annotationInfo));

            assertThat(prepended.getSuffixes()).last().isEqualTo("Inner");
            final var suffixTypeAnnotations = prepended.getSuffixTypeAnnotationInfo();
            assertThat(suffixTypeAnnotations.get(suffixTypeAnnotations.size() - 1)).containsExactly(annotationInfo);
            assertThat(annotationInfo.scanResult).isSameAs(scanResult);
        }
    }
}
