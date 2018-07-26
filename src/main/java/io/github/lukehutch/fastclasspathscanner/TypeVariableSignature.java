/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.fastclasspathscanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.utils.Parser;
import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;
import io.github.lukehutch.fastclasspathscanner.utils.TypeUtils;

/** A type variable signature. */
public class TypeVariableSignature extends ClassRefOrTypeVariableSignature {
    /** The type variable name. */
    private final String typeVariableName;

    /** The name of the class that this type variable is defined in. */
    String containingClassName;

    /** The method signature that this type variable is part of. */
    MethodTypeSignature containingMethodSignature;

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        // No need to set type signature in containing class or method, they are backlinks
    }

    /**
     * @param typeVariableName
     *            The type variable name.
     */
    public TypeVariableSignature(final String typeVariableName) {
        this.typeVariableName = typeVariableName;
    }

    /**
     * Get the name of the type variable.
     * 
     * @return The type variable name.
     */
    public String getTypeVariableName() {
        return typeVariableName;
    }

    /**
     * Look up a type variable (e.g. "T") in the defining method and/or enclosing class' type parameters, and
     * returns the type parameter with the same name (e.g. "T extends com.xyz.Cls").
     * 
     * @return the type parameter (e.g. "T extends com.xyz.Cls", or simply "T" if the type parameter does not have
     *         any bounds). Returns null if a type parameter with the same name as the type variable could not be
     *         found (this should not in general happen, since type variables in successfully-compiled code should
     *         be able to be linked to the corresponding type parameter).
     */
    public TypeParameter getCorrespondingTypeParameter() {
        if (containingMethodSignature != null) {
            if (containingMethodSignature.typeParameters != null
                    && !containingMethodSignature.typeParameters.isEmpty()) {
                for (final TypeParameter typeParameter : containingMethodSignature.typeParameters) {
                    if (typeParameter.identifier.equals(this.typeVariableName)) {
                        return typeParameter;
                    }
                }
            }
        }
        final ClassInfo containingClassInfo = getClassInfo();
        if (containingClassInfo == null) {
            throw new IllegalArgumentException("Could not find ClassInfo object for " + containingClassName);
        }
        final ClassTypeSignature containingClassSignature = containingClassInfo.getTypeSignature();
        if (containingClassSignature != null) {
            if (containingClassSignature.typeParameters != null
                    && !containingClassSignature.typeParameters.isEmpty()) {
                for (final TypeParameter typeParameter : containingClassSignature.typeParameters) {
                    if (typeParameter.identifier.equals(this.typeVariableName)) {
                        return typeParameter;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void getClassNamesFromTypeDescriptors(final Set<String> classNames) {
    }

    /**
     * Return containingClassName, so that getClassInfo() returns the {@link ClassInfo} object for the containing
     * class.
     */
    @Override
    protected String getClassName() {
        return containingClassName;
    }

    @Override
    public int hashCode() {
        return typeVariableName.hashCode();
    }

    @Override
    public boolean equalsIgnoringTypeParams(final TypeSignature other) {
        if (other instanceof ClassRefTypeSignature) {
            if (((ClassRefTypeSignature) other).className.equals("java.lang.Object")) {
                // java.lang.Object can be reconciled with any type, so it can be reconciled with
                // any type variable
                return true;
            }
            // Compare a type variable to a class reference
            final TypeParameter typeParameter = getCorrespondingTypeParameter();
            // If the corresponding type parameter cannot be resolved
            if (typeParameter == null) {
                // Unknown type variables can always be reconciled with a concrete class
                return true;
            } else {
                if (typeParameter.classBound == null
                        && (typeParameter.interfaceBounds == null || typeParameter.interfaceBounds.isEmpty())) {
                    // If the type parameter has no bounds, just assume the type variable can be reconciled
                    // to the class by type inference
                    return true;
                }
                if (typeParameter.classBound != null) {
                    if (typeParameter.classBound instanceof ClassRefTypeSignature) {
                        if (typeParameter.classBound.equals(other)) {
                            // T extends X, and X == other
                            return true;
                        }
                    } else if (typeParameter.classBound instanceof TypeVariableSignature) {
                        // "X" is reconcilable with "Y extends X"
                        return this.equalsIgnoringTypeParams(typeParameter.classBound);
                    } else /* if (typeParameter.classBound instanceof ArrayTypeSignature) */ {
                        return false;
                    }
                }
                for (final ReferenceTypeSignature interfaceBound : typeParameter.interfaceBounds) {
                    if (interfaceBound instanceof ClassRefTypeSignature) {
                        if (interfaceBound.equals(other)) {
                            // T implements X, and X == other
                            return true;
                        }
                    } else if (interfaceBound instanceof TypeVariableSignature) {
                        // "X" is reconcilable with "Y implements X"
                        return this.equalsIgnoringTypeParams(interfaceBound);
                    } else /* if (interfaceBound instanceof ArrayTypeSignature) */ {
                        return false;
                    }
                }
                // Type variable has a concrete bound that is not reconcilable with 'other'
                // (we don't follow the class hierarchy to compare the bound against the class reference,
                // since the compiler should only use the bound during type erasure, not some other class
                // in the class hierarchy)
                return false;
            }
        }
        // Technically I think type variables are never equal to each other, due to capturing,
        // but just compare the variable name for equality here (this should never get
        // triggered in general, since we only compare type-erased signatures to
        // non-type-erased signatures currently).
        return this.equals(other);
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof TypeVariableSignature)) {
            return false;
        }
        final TypeVariableSignature o = (TypeVariableSignature) obj;
        return o.typeVariableName.equals(this.typeVariableName);
    }

    @Override
    public String toString() {
        return typeVariableName;
    }

    /**
     * Returns the type variable along with its type bound, if available (e.g. "X extends xyz.Cls"). You can get
     * this in structured from by calling {@link #getCorrespondingTypeParameter()}. Returns just the type variable
     * if there is no type bound, or if no type bound is known (i.e. if getCorrespondingTypeParameter() returns
     * null).
     * 
     * @return The string representation.
     */
    public String toStringWithTypeBound() {
        final TypeParameter typeParameter = getCorrespondingTypeParameter();
        if (typeParameter == null) {
            return typeVariableName;
        } else {
            return typeParameter.toString();
        }
    }

    /** Parse a TypeVariableSignature. */
    static TypeVariableSignature parse(final Parser parser) throws ParseException {
        final char peek = parser.peek();
        if (peek == 'T') {
            parser.next();
            if (!TypeUtils.getIdentifierToken(parser)) {
                throw new ParseException(parser, "Could not parse type variable signature");
            }
            parser.expect(';');
            final TypeVariableSignature typeVariableSignature = new TypeVariableSignature(parser.currToken());

            // Save type variable signatures in the parser state, so method and class type signatures can link
            // to type signatures
            @SuppressWarnings("unchecked")
            List<TypeVariableSignature> typeVariableSignatures = (List<TypeVariableSignature>) parser.getState();
            if (typeVariableSignatures == null) {
                parser.setState(typeVariableSignatures = new ArrayList<>());
            }
            typeVariableSignatures.add(typeVariableSignature);

            return typeVariableSignature;
        } else {
            return null;
        }
    }
}