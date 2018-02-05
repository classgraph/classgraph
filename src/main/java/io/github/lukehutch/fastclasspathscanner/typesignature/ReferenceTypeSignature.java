package io.github.lukehutch.fastclasspathscanner.typesignature;

import io.github.lukehutch.fastclasspathscanner.typesignature.TypeUtils.ParseException;
import io.github.lukehutch.fastclasspathscanner.typesignature.TypeUtils.ParseState;

/**
 * A type signature for a reference type. Subclasses are ClassTypeSignature, TypeVariableSignature, and
 * ArrayTypeSignature.
 */
public abstract class ReferenceTypeSignature extends TypeSignature {
    static ReferenceTypeSignature parseReferenceTypeSignature(final ParseState parseState) throws ParseException {
        final ClassTypeSignature classTypeSignature = ClassTypeSignature.parse(parseState);
        if (classTypeSignature != null) {
            return classTypeSignature;
        }
        final TypeVariableSignature typeVariableSignature = TypeVariableSignature.parse(parseState);
        if (typeVariableSignature != null) {
            return typeVariableSignature;
        }
        final ArrayTypeSignature arrayTypeSignature = ArrayTypeSignature.parse(parseState);
        if (arrayTypeSignature != null) {
            return arrayTypeSignature;
        }
        return null;
    }

    static ReferenceTypeSignature parseClassBound(final ParseState parseState) throws ParseException {
        parseState.expect(':');
        // May return null if there is no signature after ':' (class bound signature may be empty)
        return parseReferenceTypeSignature(parseState);
    }
}