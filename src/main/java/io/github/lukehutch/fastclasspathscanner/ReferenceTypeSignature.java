package io.github.lukehutch.fastclasspathscanner;

import io.github.lukehutch.fastclasspathscanner.utils.Parser;
import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;

/**
 * A type signature for a reference type. Subclasses are {@link ClassRefTypeSignature},
 * {@link TypeVariableSignature}, and {@link ArrayTypeSignature}.
 */
public abstract class ReferenceTypeSignature extends TypeSignature {
    static ReferenceTypeSignature parseReferenceTypeSignature(final Parser parser) throws ParseException {
        final ClassRefTypeSignature classTypeSignature = ClassRefTypeSignature.parse(parser);
        if (classTypeSignature != null) {
            return classTypeSignature;
        }
        final TypeVariableSignature typeVariableSignature = TypeVariableSignature.parse(parser);
        if (typeVariableSignature != null) {
            return typeVariableSignature;
        }
        final ArrayTypeSignature arrayTypeSignature = ArrayTypeSignature.parse(parser);
        if (arrayTypeSignature != null) {
            return arrayTypeSignature;
        }
        return null;
    }

    static ReferenceTypeSignature parseClassBound(final Parser parser) throws ParseException {
        parser.expect(':');
        // May return null if there is no signature after ':' (class bound signature may be empty)
        return parseReferenceTypeSignature(parser);
    }
}