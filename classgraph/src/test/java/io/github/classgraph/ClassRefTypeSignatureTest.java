package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A '$' in a class reference usually separates the name of a nested class from the name of its enclosing class, but
 * it can also be part of a name: Scala names the class of an {@code object} by appending '$' to the object's name,
 * and synthetic classes can contain "$$".
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
}
