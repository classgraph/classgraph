package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import nonapi.io.github.classgraph.types.ParseException;

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
     * @throws ParseException
     *             if the class reference could not be parsed.
     */
    private static ClassRefTypeSignature parse(final String typeSignatureStr) throws ParseException {
        final TypeSignature typeSignature = TypeSignature.parse(typeSignatureStr, /* definingClass = */ null);
        assertThat(typeSignature).isInstanceOf(ClassRefTypeSignature.class);
        return (ClassRefTypeSignature) typeSignature;
    }

    /**
     * A class name that ends in '$' keeps the '$'.
     *
     * @throws ParseException
     *             if the class reference could not be parsed.
     */
    @Test
    public void aTrailingDollarSignIsPartOfTheName() throws ParseException {
        final ClassRefTypeSignature sig = parse("Lp/Obj$;");
        assertThat(sig.getBaseClassName()).isEqualTo("p.Obj$");
        assertThat(sig.getSuffixes()).isEmpty();
        assertThat(sig.getFullyQualifiedClassName()).isEqualTo("p.Obj$");
        assertThat(sig).hasToString("p.Obj$");
    }

    /**
     * A Scala {@code object} nested in a generic class, e.g. {@code class Outer[T] { object Inner }}, keeps the
     * type argument of the enclosing class, and its name ends in '$'.
     *
     * @throws ParseException
     *             if the class reference could not be parsed.
     */
    @Test
    public void anObjectNestedInAGenericClassKeepsTheTypeArgument() throws ParseException {
        final ClassRefTypeSignature sig = parse("Lp/Outer<TT;>.Inner$;");
        assertThat(sig.getBaseClassName()).isEqualTo("p.Outer");
        assertThat(sig.getTypeArguments()).extracting(Object::toString).containsExactly("T");
        assertThat(sig.getSuffixes()).containsExactly("Inner$");
        assertThat(sig.getSuffixTypeArguments()).containsExactly(Collections.<TypeArgument> emptyList());
        assertThat(sig.getFullyQualifiedClassName()).isEqualTo("p.Outer$Inner$");
        assertThat(sig).hasToString("p.Outer<T>$Inner$");
    }

    /**
     * "$$" gives the same fully-qualified class name as the class reference with every '$' kept.
     *
     * @throws ParseException
     *             if the class reference could not be parsed.
     */
    @Test
    public void aDoubleDollarSignIsKept() throws ParseException {
        final ClassRefTypeSignature sig = parse("Lp/A$$B;");
        assertThat(sig.getFullyQualifiedClassName()).isEqualTo("p.A$$B");
        assertThat(sig).hasToString("p.A$$B");
    }

    /** A '.' must be followed by the name of a nested class. */
    @Test
    public void aDotWithoutANameIsAParseError() {
        assertThatThrownBy(() -> parse("Lp/Outer.$Inner;")).isInstanceOf(ParseException.class);
    }
}
