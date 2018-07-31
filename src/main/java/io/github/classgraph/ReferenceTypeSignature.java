package io.github.classgraph;

import io.github.classgraph.utils.Parser;
import io.github.classgraph.utils.Parser.ParseException;

/**
 * A type signature for a reference type. Subclasses are {@link ClassRefOrTypeVariableSignature}
 * ({@link ClassRefTypeSignature} or {@link TypeVariableSignature}), and {@link ArrayTypeSignature}.
 */
public abstract class ReferenceTypeSignature extends TypeSignature {
    /**
     * Parse a reference type signature.
     * 
     * @param parser
     *            The parser
     * @param definingClassName
     *            The class containing the type descriptor.
     * @return The parsed type reference type signature.
     * @throws ParseException
     *             If the type signature could not be parsed.
     */
    static ReferenceTypeSignature parseReferenceTypeSignature(final Parser parser, final String definingClassName)
            throws ParseException {
        final ClassRefTypeSignature classTypeSignature = ClassRefTypeSignature.parse(parser, definingClassName);
        if (classTypeSignature != null) {
            return classTypeSignature;
        }
        final TypeVariableSignature typeVariableSignature = TypeVariableSignature.parse(parser, definingClassName);
        if (typeVariableSignature != null) {
            return typeVariableSignature;
        }
        final ArrayTypeSignature arrayTypeSignature = ArrayTypeSignature.parse(parser, definingClassName);
        if (arrayTypeSignature != null) {
            return arrayTypeSignature;
        }
        return null;
    }

    /**
     * Parse a class bound.
     * 
     * @param parser
     *            The parser.
     * @param definingClassName
     *            The class containing the type descriptor.
     * @return The parsed class bound.
     * @throws ParseException
     *             If the type signature could not be parsed.
     */
    static ReferenceTypeSignature parseClassBound(final Parser parser, final String definingClassName)
            throws ParseException {
        parser.expect(':');
        // May return null if there is no signature after ':' (class bound signature may be empty)
        return parseReferenceTypeSignature(parser, definingClassName);
    }
}