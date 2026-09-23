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
 * Copyright (c) 2026 Luke Hutchison
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Classfile.TypePathNode;
import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;
import nonapi.io.github.classgraph.types.TypeUtils;

/** A type variable signature. */
public final class TypeVariableSignature extends ClassRefOrTypeVariableSignature {
    /** The type variable name. */
    private final String name;

    /**
     * The name of the class that this type variable is used in, which may be nested in the class that declares it.
     */
    private final String definingClassName;

    /**
     * The type parameter that declares this type variable, if it is declared by the method whose signature this type
     * variable is part of, otherwise null.
     */
    TypeParameter methodTypeParameter;

    /** The resolved type parameter, if any. */
    private TypeParameter typeParameterCached;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param typeVariableName
     *            The type variable name.
     * @param definingClassName
     *            the defining class name.
     */
    private TypeVariableSignature(final String typeVariableName, final String definingClassName) {
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
     * Look up a type variable (e.g. "T") in the type parameters of the method whose signature it is part of, then
     * in those of the class it is used in, then, if that class is an inner member class, in those of the classes it
     * is nested in, and return the type parameter with the same name (e.g. "T extends com.xyz.Cls").
     *
     * <p>
     * A type variable declared by a method that a local or anonymous class is declared in is not found, since the
     * classfile does not record which overload of the method that is.
     *
     * @return the type parameter (e.g. "T extends com.xyz.Cls", or simply "T" if the type parameter does not have
     *         any bounds). If no type parameter of the same name is found, an unbounded type parameter with just
     *         the type variable's name is returned (#706).
     * @throws IllegalArgumentException
     *             if the class that the type variable is used in was not found during the scan.
     */
    public TypeParameter resolve() {
        if (typeParameterCached != null) {
            return typeParameterCached;
        }
        if (methodTypeParameter != null) {
            typeParameterCached = methodTypeParameter;
            return methodTypeParameter;
        }
        if (getClassName() != null) {
            final ClassInfo containingClassInfo = getClassInfo();
            if (containingClassInfo == null) {
                throw new IllegalArgumentException("Could not find ClassInfo object for " + definingClassName);
            }
            // The outer classes are listed innermost first. Only an inner member class can use the type variables
            // of the class it is declared in, and a class that was not read may declare the name too, so stop at a
            // static, local or anonymous class, and at a class that was not read. (A local class declared in an
            // initializer has no defining method name, but an initializer declares no type variables.)
            final ClassInfoList outerClasses = containingClassInfo.getOuterClasses();
            ClassInfo classInfo = containingClassInfo;
            for (int i = 0;; i++) {
                final TypeParameter typeParameter = findClassTypeParameter(classInfo);
                if (typeParameter != null) {
                    typeParameterCached = typeParameter;
                    return typeParameter;
                }
                if (i == outerClasses.size() || classInfo.isStatic() || classInfo.isAnonymousInnerClass()
                        || classInfo.getFullyQualifiedDefiningMethodName() != null) {
                    break;
                }
                classInfo = outerClasses.get(i);
                if (!classInfo.isScannedClass) {
                    break;
                }
            }
        }
        // If that failed, then this is a type variable that cannot be resolved.
        // Return a new TypeParameter that only has the name set, with no class or interface bounds. (#706)
        final TypeParameter typeParameter = new TypeParameter(name, null,
                Collections.<ReferenceTypeSignature> emptyList());
        typeParameter.setScanResult(scanResult);
        typeParameterCached = typeParameter;
        return typeParameter;
    }

    /**
     * Find the type parameter of a class that has the name of this type variable.
     *
     * @param classInfo
     *            the class.
     * @return the type parameter, or null if the class declares no type parameter of that name.
     */
    private TypeParameter findClassTypeParameter(final ClassInfo classInfo) {
        ClassTypeSignature classSignature = null;
        try {
            classSignature = classInfo.getTypeSignature();
        } catch (final Exception e) {
            // The class signature of a corrupt classfile may not parse. Treat the class as declaring no type
            // parameters.
        }
        if (classSignature != null && classSignature.typeParameters != null) {
            for (final TypeParameter typeParameter : classSignature.typeParameters) {
                if (typeParameter.name.equals(name)) {
                    return typeParameter;
                }
            }
        }
        return null;
    }

    /**
     * Look this type variable up in a substitution map built by {@link TypeSignature#resolveTypeVariables(ClassInfo)}
     * (#735).
     *
     * @param substitutions
     *            the substitution map.
     * @return the type argument to substitute for this type variable, or null if this type variable is not
     *         substitutable.
     */
    TypeArgument substitution(final Map<String, TypeArgument> substitutions) {
        // A type variable declared by the method itself shadows any type variable of the same name declared by the
        // enclosing class, and is not bound by the context class
        if (methodTypeParameter != null) {
            return null;
        }
        return substitutions.get(TypeSignature.substitutionKey(definingClassName, name));
    }

    @Override
    TypeSignature substituteTypeVariables(final Map<String, TypeArgument> substitutions) {
        final TypeArgument typeArgument = substitution(substitutions);
        if (typeArgument == null) {
            return this;
        }
        // Outside type argument position there is no way to express "?" or "? super X", so leave the type variable
        // unsubstituted in those cases; "? extends X" is substituted as its upper bound X
        final ReferenceTypeSignature typeSignature = typeArgument.getTypeSignature();
        return typeSignature == null || typeArgument.getWildcard() == TypeArgument.Wildcard.ANY
                || typeArgument.getWildcard() == TypeArgument.Wildcard.SUPER ? this : typeSignature;
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected void addTypeAnnotation(final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
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
    static TypeVariableSignature parse(final Parser parser, final String definingClassName) throws ParseException {
        final char peek = parser.peek();
        if (peek == 'T') {
            parser.next();
            // Scala can contain '$' in type variable names (#495)
            if (!TypeUtils.getIdentifierToken(parser, /* stopAtDollarSign = */ false, /* stopAtDot = */ true)) {
                throw new ParseException(parser, "Could not parse type variable signature");
            }
            parser.expect(';');
            final TypeVariableSignature typeVariableSignature = new TypeVariableSignature(parser.currToken(),
                    definingClassName);

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

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return definingClassName, so that getClassInfo() returns the {@link ClassInfo} object for the containing
     * class.
     *
     * @return the defining class name.
     */
    @Override
    protected String getClassName() {
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
        // Any class names present in resolved type variables have to be present in enclosing method or class,
        // so there's no need to look up class references in resolved type variables
    }

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (typeParameterCached != null) {
            typeParameterCached.setScanResult(scanResult);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return (name.hashCode() * 31 + Objects.hashCode(definingClassName)) * 2 + (methodTypeParameter == null ? 0 : 1);
    }

    /**
     * Two type variables are equal if they have the same name and type annotations, are used in the same class, and
     * are either both declared by the method whose signature they are part of, or both not. So the {@code T} of a
     * method that declares {@code <T>} does not equal a {@code T} declared by the class, but the {@code T}s of two
     * methods that each declare {@code <T>} are equal, as are the signatures of {@code <T> void a(T t)} and
     * {@code <T> void b(T t)}, even though the two {@code T}s may have different bounds.
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof TypeVariableSignature)) {
            return false;
        }
        final TypeVariableSignature other = (TypeVariableSignature) obj;
        return other.name.equals(this.name) && Objects.equals(other.definingClassName, this.definingClassName)
                && (other.methodTypeParameter == null) == (this.methodTypeParameter == null)
                && Objects.equals(other.typeAnnotationInfo, this.typeAnnotationInfo);
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.TypeSignature#equalsIgnoringTypeParams(io.github.classgraph.TypeSignature)
     */
    @Override
    public boolean equalsIgnoringTypeParams(final TypeSignature other) {
        return equalsIgnoringTypeParams(other, null);
    }

    /**
     * Compare this type variable to another type signature, ignoring generic type parameters.
     *
     * @param other
     *            the other type signature to compare to, or null.
     * @param visited
     *            the names of the type variables whose bounds are already being compared, so that a chain of type
     *            variable bounds that loops back on itself is not followed forever, or null if none are.
     * @return true if the two type signatures are equal, ignoring type parameters.
     */
    private boolean equalsIgnoringTypeParams(final TypeSignature other, final Set<String> visited) {
        if (other instanceof ClassRefTypeSignature) {
            final ClassRefTypeSignature otherClassRef = (ClassRefTypeSignature) other;
            if (otherClassRef.className.equals("java.lang.Object")) {
                // java.lang.Object can be reconciled with any type, so it can be reconciled with
                // any type variable
                return true;
            }
            final Set<String> visitedTypeVariableNames = visited == null ? new HashSet<String>() : visited;
            if (!visitedTypeVariableNames.add(name)) {
                // Cyclic type variable bounds ("class C<A extends B, B extends A>") are rejected by javac, but a
                // classfile can still contain them, so stop rather than following the cycle forever
                return false;
            }
            // Resolve the type variable against the containing class' type parameters
            TypeParameter typeParameter;
            try {
                typeParameter = resolve();
            } catch (final IllegalArgumentException e) {
                // If the corresponding type parameter cannot be resolved:
                // unknown type variables can always be reconciled with a concrete class
                return true;
            }
            if (typeParameter.classBound == null
                    && (typeParameter.interfaceBounds == null || typeParameter.interfaceBounds.isEmpty())) {
                // If the type parameter has no bounds, just assume the type variable can be reconciled
                // to the class by type inference
                return true;
            }
            // T extends X, and X can be reconciled with 'other'
            if (typeParameter.classBound != null && boundIsReconcilableWith(typeParameter.classBound, otherClassRef,
                    visitedTypeVariableNames)) {
                return true;
            }
            // T implements X, and X can be reconciled with 'other'
            if (typeParameter.interfaceBounds != null) {
                for (final ReferenceTypeSignature interfaceBound : typeParameter.interfaceBounds) {
                    if (boundIsReconcilableWith(interfaceBound, otherClassRef, visitedTypeVariableNames)) {
                        return true;
                    }
                }
            }
            // Type variable has a concrete bound that is not reconcilable with 'other'
            // (we don't follow the class hierarchy to compare the bound against the class reference,
            // since the compiler should only use the bound during type erasure, not some other class
            // in the class hierarchy)
            return false;
        }
        // 'other' is a type variable too. Compare the names and type annotations, but unlike equals(), not the
        // classes the two type variables are used in, so that the T of a superclass method matches the T of the
        // method that overrides it.
        if (!(other instanceof TypeVariableSignature)) {
            return false;
        }
        final TypeVariableSignature o = (TypeVariableSignature) other;
        return o.name.equals(this.name) && Objects.equals(o.typeAnnotationInfo, this.typeAnnotationInfo);
    }

    /**
     * Test whether one of the bounds of a type variable can be reconciled with a class reference.
     *
     * @param bound
     *            a class bound or interface bound of the type variable.
     * @param other
     *            the class reference.
     * @param visitedTypeVariableNames
     *            the names of the type variables whose bounds are already being compared.
     * @return true if the bound can be reconciled with the class reference.
     */
    private static boolean boundIsReconcilableWith(final ReferenceTypeSignature bound,
            final ClassRefTypeSignature other, final Set<String> visitedTypeVariableNames) {
        if (bound instanceof ClassRefTypeSignature) {
            // A type variable with no bound of its own is written into the classfile with java.lang.Object as its
            // bound, and java.lang.Object can be reconciled with any type. Otherwise the bound is compared with the
            // class reference, ignoring type arguments, as for any other comparison made by this method.
            final ClassRefTypeSignature classRefBound = (ClassRefTypeSignature) bound;
            return classRefBound.className.equals("java.lang.Object")
                    || classRefBound.equalsIgnoringTypeParams(other);
        }
        if (bound instanceof TypeVariableSignature) {
            // "X" is reconcilable with "Y extends X", so compare the bound's own bounds with the class reference
            return ((TypeVariableSignature) bound).equalsIgnoringTypeParams(other, visitedTypeVariableNames);
        }
        // An array bound is not reconcilable with a class reference
        return false;
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
        } catch (final IllegalArgumentException e) {
            // Type parameter could not be resolved
            return name;
        }
    }

    @Override
    protected void toStringInternal(final boolean useSimpleNames, final AnnotationInfoList annotationsToExclude,
            final StringBuilder buf) {
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