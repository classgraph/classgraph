/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
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
package io.github.classgraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Classfile.TypePathNode;
import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;
import nonapi.io.github.classgraph.types.TypeUtils;
import org.jspecify.annotations.Nullable;

/** A type variable signature. */
public final class TypeVariableSignature extends ClassRefOrTypeVariableSignature {
    /** The type variable name. */
    private final String name;

    /** The name of the class that this type variable is defined in. */
    private final @Nullable String definingClassName;

    /** The method signature that this type variable is part of. */
    @Nullable
    MethodTypeSignature containingMethodSignature;

    /** The resolved type parameter, if any. */
    private @Nullable TypeParameter typeParameterCached;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param typeVariableName
     *            The type variable name.
     * @param definingClassName
     *            the defining class name.
     */
    private TypeVariableSignature(final String typeVariableName, final @Nullable String definingClassName) {
        super();
        this.name = typeVariableName;
        this.definingClassName = definingClassName;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the name of the type variable.
     *
     * @return The type variable name.
     */
    public String getName() {
        return name;
    }

    /**
     * Look up a type variable (e.g. "T") in the defining method and/or enclosing class' type parameters, and return
     * the type parameter with the same name (e.g. "T extends com.xyz.Cls").
     *
     * @return the type parameter (e.g. "T extends com.xyz.Cls", or simply "T" if the type parameter does not have
     *         any bounds). If no type parameter of the same name is declared by the defining method or the
     *         enclosing class, an unbounded type parameter with just the type variable's name is returned.
     * @throws IllegalStateException
     *             if the enclosing class was not found during the scan.
     */
    // #706
    public TypeParameter resolve() {
        final var cached = typeParameterCached;
        if (cached != null) {
            return cached;
        }
        // Try resolving the type variable against the containing method
        final var methodSignature = containingMethodSignature;
        if (methodSignature != null && methodSignature.typeParameters != null
                && !methodSignature.typeParameters.isEmpty()) {
            for (final TypeParameter typeParameter : methodSignature.typeParameters) {
                if (typeParameter.name.equals(this.name)) {
                    typeParameterCached = typeParameter;
                    return typeParameter;
                }
            }
        }
        // If that failed, try resolving the type variable against the containing class
        if (getClassName() != null) {
            final var containingClassInfo = getClassInfo();
            if (containingClassInfo == null) {
                throw new IllegalStateException("Could not find ClassInfo object for " + definingClassName);
            }
            ClassTypeSignature containingClassSignature = null;
            try {
                containingClassSignature = containingClassInfo.getTypeSignature();
            } catch (final Exception e) {
                // Ignore
            }
            if (containingClassSignature != null && containingClassSignature.typeParameters != null
                    && !containingClassSignature.typeParameters.isEmpty()) {
                for (final TypeParameter typeParameter : containingClassSignature.typeParameters) {
                    if (typeParameter.name.equals(this.name)) {
                        typeParameterCached = typeParameter;
                        return typeParameter;
                    }
                }
            }
        }
        // If that failed, then this is a type variable that cannot be resolved. Return a new TypeParameter that
        // only has the name set, with no class or interface bounds. (#706)
        final TypeParameter typeParameter = new TypeParameter(name, null, List.of());
        typeParameter.setScanResult(scanResult);
        typeParameterCached = typeParameter;
        return typeParameter;
    }

    /**
     * Look this type variable up in a substitution map built by
     * {@link TypeSignature#resolveTypeVariables(ClassInfo)}.
     *
     * @param substitutions
     *            the substitution map.
     * @return the type argument to substitute for this type variable, or null if this type variable is not
     *         substitutable.
     */
    // #735
    @Nullable
    TypeArgument substitution(final Map<String, TypeArgument> substitutions) {
        // A type variable declared by the method itself shadows any type variable of the same name declared by the
        // enclosing class, and is not bound by the context class
        final var methodSignature = containingMethodSignature;
        if (methodSignature != null && methodSignature.typeParameters != null) {
            for (final TypeParameter typeParameter : methodSignature.typeParameters) {
                if (typeParameter.getName().equals(name)) {
                    return null;
                }
            }
        }
        return substitutions.get(TypeSignature.substitutionKey(definingClassName, name));
    }

    @Override
    TypeSignature substituteTypeVariables(final Map<String, TypeArgument> substitutions) {
        final var typeArgument = substitution(substitutions);
        if (typeArgument == null) {
            return this;
        }
        // Outside type argument position there is no way to express "?" or "? super X", so leave the type variable
        // unsubstituted in those cases; "? extends X" is substituted as its upper bound X
        final var typeSignature = typeArgument.getTypeSignature();
        return typeSignature == null || typeArgument.getWildcard() == TypeArgument.Wildcard.ANY
                || typeArgument.getWildcard() == TypeArgument.Wildcard.SUPER ? this : typeSignature;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    void addTypeAnnotation(final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
        if (typePath.isEmpty()) {
            addTypeAnnotation(annotationInfo);
        } else {
            throw new IllegalArgumentException("Type variable should have empty typePath");
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parse a TypeVariableSignature.
     *
     * @param parser
     *            the parser
     * @param definingClassName
     *            the defining class name
     * @return the type variable signature
     * @throws ParseException
     *             if parsing fails
     */
    static @Nullable TypeVariableSignature parse(final Parser parser, final @Nullable String definingClassName)
            throws ParseException {
        final var peek = parser.peek();
        if (peek == 'T') {
            parser.next();
            // Scala can contain '$' in type variable names (#495)
            if (!TypeUtils.getIdentifierToken(parser, /* stopAtDollarSign = */ false, /* stopAtDot = */ true)) {
                throw new ParseException(parser, "Could not parse type variable signature");
            }
            parser.expect(';');
            final TypeVariableSignature typeVariableSignature = new TypeVariableSignature(parser.currToken(),
                    definingClassName);

            // Save type variable signatures in the parser state, so method and class type signatures can link to
            // type signatures
            @SuppressWarnings("unchecked")
            var typeVariableSignatures = (List<TypeVariableSignature>) parser.getState();
            if (typeVariableSignatures == null) {
                parser.setState(typeVariableSignatures = new ArrayList<>());
            }
            typeVariableSignatures.add(typeVariableSignature);

            return typeVariableSignature;
        } else {
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return definingClassName, so that getClassInfo() returns the {@link ClassInfo} object for the containing
     * class.
     *
     * @return the defining class name.
     */
    @Override
    protected @Nullable String getClassName() {
        return definingClassName;
    }

    /**
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the referenced class names.
     */
    @Override
    protected void findReferencedClassNames(final Set<String> refdClassNames) {
        // Any class names present in resolved type variables have to be present in enclosing method or class, so
        // there's no need to look up class references in resolved type variables
    }

    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        super.setScanResult(scanResult);
        final var cached = typeParameterCached;
        if (cached != null) {
            cached.setScanResult(scanResult);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return name.hashCode();
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final TypeVariableSignature other)) {
            return false;
        }
        return other.name.equals(this.name) && Objects.equals(other.typeAnnotationInfo, this.typeAnnotationInfo);
    }

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.TypeSignature#equalsIgnoringTypeParams(io.github.
     * classgraph.TypeSignature)
     */
    @Override
    public boolean equalsIgnoringTypeParams(final @Nullable TypeSignature other) {
        if (other instanceof final ClassRefTypeSignature otherClassRef) {
            if ("java.lang.Object".equals(otherClassRef.className)) {
                // java.lang.Object can be reconciled with any type, so it can be reconciled with any type variable
                return true;
            }
            // Resolve the type variable against the containing class' type parameters
            TypeParameter typeParameter;
            try {
                typeParameter = resolve();
            } catch (final IllegalStateException e) {
                // If the corresponding type parameter cannot be resolved: unknown type variables can always be
                // reconciled with a concrete class
                return true;
            }
            if (typeParameter.classBound == null
                    && (typeParameter.interfaceBounds == null || typeParameter.interfaceBounds.isEmpty())) {
                // If the type parameter has no bounds, just assume the type variable can be reconciled to the class
                // by type inference
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
            if (typeParameter.interfaceBounds != null) {
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
            }
            // Type variable has a concrete bound that is not reconcilable with 'other' (we don't follow the class
            // hierarchy to compare the bound against the class reference, since the compiler should only use the
            // bound during type erasure, not some other class in the class hierarchy)
            return false;
        }
        // Technically I think type variables are never equal to each other, due to capturing, but just compare the
        // variable name for equality here (this should never get triggered in general, since we only compare
        // type-erased signatures to non-type-erased signatures currently).
        return this.equals(other);
    }

    /**
     * Returns the type variable along with its type bound, if available (e.g. "X extends xyz.Cls"). You can get
     * this in structured form by calling {@link #resolve()}. Returns just the type variable if there is no type
     * bound, or if no type bound is known (i.e. if {@link #resolve()} throws).
     *
     * @return The string representation.
     */
    public String toStringWithTypeBound() {
        try {
            return resolve().toString();
        } catch (final IllegalStateException e) {
            // Type parameter could not be resolved
            return name;
        }
    }

    @Override
    protected void toStringInternal(final boolean useSimpleNames,
            final @Nullable AnnotationInfoList annotationsToExclude, final StringBuilder buf) {
        if (typeAnnotationInfo != null) {
            for (final AnnotationInfo annotationInfo : typeAnnotationInfo) {
                if (annotationsToExclude == null || !annotationsToExclude.contains(annotationInfo)) {
                    annotationInfo.toString(useSimpleNames, buf);
                    buf.append(' ');
                }
            }
        }
        buf.append(name);
    }
}