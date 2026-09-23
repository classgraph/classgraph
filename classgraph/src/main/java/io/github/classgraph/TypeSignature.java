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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Classfile.TypePathNode;
import io.github.classgraph.base.internal.utils.Assert;
import org.jspecify.annotations.Nullable;

// TODO: once ClassGraph's minimum supported JDK version is 21 or later, this sealed hierarchy lets a pattern switch
// over a TypeSignature list BaseTypeSignature, ArrayTypeSignature, ClassRefTypeSignature and TypeVariableSignature
// with no default case. Mention this in the javadoc below. Each place that tests for more than one of these
// subclasses in turn, and so could use such a switch, has a TODO of its own.

/**
 * A type signature for a reference type or base type. Subclasses are {@link ReferenceTypeSignature} (whose own
 * subclasses are {@link ClassRefTypeSignature}, {@link TypeVariableSignature}, and {@link ArrayTypeSignature}), and
 * {@link BaseTypeSignature}. This corresponds to the {@code JavaTypeSignature} production of the signature grammar
 * in section 4.7.9.1 of the JVM Specification.
 */
public abstract sealed class TypeSignature extends HierarchicalTypeSignature
        permits BaseTypeSignature, ReferenceTypeSignature {
    /** Constructor. */
    TypeSignature() {
        // Empty
    }

    // -------------------------------------------------------------------------------------------------------------
    // Type variable resolution (#735)

    /**
     * Resolve the type variables in this type signature against a context class, i.e. substitute the type arguments
     * that the context class (or a class between the context class and the class declaring this type) supplies for
     * them.
     *
     * <p>
     * For example, given {@code interface Base<T> { T getT(); }} and {@code abstract class Derived implements
     * Base<String> {}}, the result type of {@code Base#getT()} is the type variable {@code T}, but calling this
     * method on that result type with {@code Derived} as the context class returns {@code java.lang.String}.
     *
     * <p>
     * A type variable is left unchanged if it cannot be resolved: if the context class is not a subclass or
     * subinterface of the class declaring the type variable, if the supertype is used in raw form, if the type
     * variable is shadowed by a type parameter of the method that declares this type, or if the type argument is a
     * wildcard that has no expressible equivalent outside type argument position ({@code "?"} or
     * {@code "? super X"} -- an upper-bounded wildcard {@code "? extends X"} is substituted as {@code X}). In type
     * argument position, wildcards are substituted verbatim, so {@code List<T>} with
     * {@code T := "? extends Number"} resolves to {@code List<? extends Number>}.
     *
     * <p>
     * Type variables are matched by the class that declares them together with their name, and bindings are
     * collected by walking up the superclass and superinterface chain of the context class. A type variable that an
     * inner class inherits from its enclosing class is therefore not resolved, since the classfile records it as
     * declared by the inner class, which is not on that chain.
     *
     * @param contextClass
     *            the class to resolve type variables against.
     * @return this type signature with any resolvable type variables substituted, or this type signature itself if
     *         no type variable in it could be resolved.
     * @throws NullPointerException
     *             if {@code contextClass} is null.
     */
    public TypeSignature resolveTypeVariables(final ClassInfo contextClass) {
        Assert.notNull(contextClass, "contextClass");
        final Map<String, TypeArgument> substitutions = new HashMap<>();
        addSubstitutions(contextClass, substitutions, new HashSet<>());
        if (substitutions.isEmpty()) {
            return this;
        }
        final var substituted = substituteTypeVariables(substitutions);
        if (substituted != this) {
            // Any nodes built during substitution have no ScanResult yet, so getClassInfo() etc. would fail on them
            substituted.setScanResult(scanResult);
        }
        return substituted;
    }

    /**
     * Get the key used to look up a type variable in a substitution map. A type variable is identified by the class
     * that declares it as well as by its name, since the same name may be declared by several classes in a class
     * hierarchy.
     *
     * @param definingClassName
     *            the name of the class declaring the type variable.
     * @param typeVariableName
     *            the name of the type variable.
     * @return the substitution map key.
     */
    static String substitutionKey(final @Nullable String definingClassName, final String typeVariableName) {
        return definingClassName + "::" + typeVariableName;
    }

    /**
     * Walk from a class up through its superclasses and superinterfaces, recording the type argument that each
     * level supplies for each type parameter of the level above it.
     *
     * @param classInfo
     *            the class to walk up from.
     * @param substitutions
     *            the substitution map to add to.
     * @param visited
     *            the names of the classes already visited, to terminate on cyclic or diamond hierarchies.
     */
    private static void addSubstitutions(final @Nullable ClassInfo classInfo,
            final Map<String, TypeArgument> substitutions, final Set<String> visited) {
        if (classInfo == null || !visited.add(classInfo.getName())) {
            return;
        }
        final var classSignature = classInfo.getTypeSignature();
        if (classSignature == null) {
            // The class has no generic signature, so it supplies no type arguments -- but a class further up the
            // hierarchy may still be generic, so keep walking
            addSubstitutions(classInfo.superclassIncludingExternal(), substitutions, visited);
            for (final ClassInfo interfaceInfo : classInfo.allSuperinterfacesIncludingExternal()) {
                addSubstitutions(interfaceInfo, substitutions, visited);
            }
            return;
        }
        addSupertypeSubstitutions(classSignature.getSuperclassSignature(), substitutions, visited);
        for (final ClassRefTypeSignature superinterfaceSignature : classSignature.getSuperinterfaceSignatures()) {
            addSupertypeSubstitutions(superinterfaceSignature, substitutions, visited);
        }
    }

    /**
     * Record the type arguments that a class supplies for the type parameters of one of its direct supertypes, then
     * continue walking up from that supertype.
     *
     * @param supertypeSignature
     *            the signature of the supertype, as referenced by the subclass.
     * @param substitutions
     *            the substitution map to add to, which already holds the bindings of the subclass' own type
     *            parameters.
     * @param visited
     *            the names of the classes already visited.
     */
    private static void addSupertypeSubstitutions(final @Nullable ClassRefTypeSignature supertypeSignature,
            final Map<String, TypeArgument> substitutions, final Set<String> visited) {
        if (supertypeSignature == null) {
            return;
        }
        // A reference to a nested class carries a separate list of type arguments for each level of nesting, e.g.
        // "Outer<A>.Inner<B>" (and even for a static nested class, where the type arguments of the reference are
        // all attached to the last level), so bind the type parameters of each level in turn
        final StringBuilder classNameBuf = new StringBuilder(supertypeSignature.getBaseClassName());
        addTypeArgumentSubstitutions(supertypeSignature, classNameBuf.toString(),
                supertypeSignature.getTypeArguments(), substitutions);
        final var suffixes = supertypeSignature.getSuffixes();
        final var suffixTypeArguments = supertypeSignature.getSuffixTypeArguments();
        for (var i = 0; i < suffixes.size(); i++) {
            classNameBuf.append('$').append(suffixes.get(i));
            addTypeArgumentSubstitutions(supertypeSignature, classNameBuf.toString(), suffixTypeArguments.get(i),
                    substitutions);
        }
        addSubstitutions(supertypeSignature.getClassInfo(), substitutions, visited);
    }

    /**
     * Record the type arguments supplied for the type parameters of one class in a supertype reference.
     *
     * @param supertypeSignature
     *            the supertype reference the type arguments came from, used to reach the {@link ScanResult}.
     * @param className
     *            the name of the class that declares the type parameters.
     * @param typeArguments
     *            the type arguments supplied for that class' type parameters.
     * @param substitutions
     *            the substitution map to add to, and to compose the type arguments with.
     */
    private static void addTypeArgumentSubstitutions(final ClassRefTypeSignature supertypeSignature,
            final String className, final List<TypeArgument> typeArguments,
            final Map<String, TypeArgument> substitutions) {
        if (typeArguments.isEmpty() || supertypeSignature.scanResult == null) {
            // The class is referenced in raw form, so there is nothing to substitute
            return;
        }
        final var classInfo = supertypeSignature.scanResult.getClassInfo(className);
        if (classInfo == null) {
            // The class was not encountered during scanning, so its type parameters are unknown
            return;
        }
        final var classSignature = classInfo.getTypeSignature();
        final var typeParameters = classSignature == null ? null : classSignature.getTypeParameters();
        if (typeParameters == null || typeParameters.size() != typeArguments.size()) {
            return;
        }
        for (var i = 0; i < typeParameters.size(); i++) {
            // Compose with the substitutions already collected from the classes below this one, so that "class
            // Derived extends Mid<String>" and "class Mid<U> extends Base<U>" map Base's T to String
            substitutions.put(substitutionKey(className, typeParameters.get(i).getName()),
                    typeArguments.get(i).substituteTypeVariables(substitutions));
        }
    }

    /**
     * Substitute type variables in this type signature, using a substitution map built by
     * {@link #resolveTypeVariables(ClassInfo)}.
     *
     * @param substitutions
     *            the substitution map.
     * @return the substituted type signature, or this type signature itself if nothing was substituted.
     */
    TypeSignature substituteTypeVariables(final Map<String, TypeArgument> substitutions) {
        // Base types contain no type variables
        return this;
    }

    /**
     * Render a type signature back into the type signature string format of JVMS section 4.7.9.1, so that a
     * substituted array type can be given a type signature string that matches its substituted element type. This
     * is implemented in one place using {@code instanceof} rather than as an overridden method in each of the five
     * signature classes, since it is only needed for this one purpose.
     *
     * @param typeSignature
     *            the type signature to render.
     * @param buf
     *            the buffer to append to
     */
    private static void toTypeSignatureStr(final HierarchicalTypeSignature typeSignature, final StringBuilder buf) {
        // TODO: once ClassGraph's minimum supported JDK version is 21 or later, make this a pattern switch
        if (typeSignature instanceof final BaseTypeSignature baseTypeSignature) {
            buf.append(baseTypeSignature.getTypeSignatureChar());
        } else if (typeSignature instanceof final ArrayTypeSignature arrayTypeSignature) {
            buf.append(arrayTypeSignature.getTypeSignatureString());
        } else if (typeSignature instanceof final TypeVariableSignature typeVariableSignature) {
            buf.append('T').append(typeVariableSignature.getName()).append(';');
        } else if (typeSignature instanceof final TypeArgument typeArgument) {
            switch (typeArgument.getWildcard()) {
            case ANY -> {
                buf.append('*');
                return;
            }
            case EXTENDS -> buf.append('+');
            case SUPER -> buf.append('-');
            default -> {
                // Wildcard.NONE -- no prefix character
            }
            }
            toTypeSignatureStr(Objects.requireNonNull(typeArgument.getTypeSignature()), buf);
        } else if (typeSignature instanceof final ClassRefTypeSignature classRefTypeSignature) {
            buf.append('L').append(classRefTypeSignature.getBaseClassName().replace('.', '/'));
            final var typeArguments = classRefTypeSignature.getTypeArguments();
            toTypeArgumentsStr(typeArguments, buf);
            // Like javac, separate a nested class from its enclosing class with '$', as in the class name, until
            // an enclosing class has type arguments, and with '.' from then on
            var enclosingHasTypeArguments = !typeArguments.isEmpty();
            final var suffixes = classRefTypeSignature.getSuffixes();
            final var suffixTypeArguments = classRefTypeSignature.getSuffixTypeArguments();
            for (var i = 0; i < suffixes.size(); i++) {
                buf.append(enclosingHasTypeArguments ? '.' : '$').append(suffixes.get(i));
                toTypeArgumentsStr(suffixTypeArguments.get(i), buf);
                enclosingHasTypeArguments |= !suffixTypeArguments.get(i).isEmpty();
            }
            buf.append(';');
        } else {
            throw new IllegalArgumentException("Unexpected type signature type: " + typeSignature.getClass());
        }
    }

    /**
     * Render a list of type arguments back into type signature string format, if the list is non-empty.
     *
     * @param typeArguments
     *            the type arguments.
     * @param buf
     *            the buffer to append to
     */
    private static void toTypeArgumentsStr(final List<TypeArgument> typeArguments, final StringBuilder buf) {
        if (!typeArguments.isEmpty()) {
            buf.append('<');
            for (final TypeArgument typeArgument : typeArguments) {
                toTypeSignatureStr(typeArgument, buf);
            }
            buf.append('>');
        }
    }

    /**
     * Render a type signature back into type signature string format.
     *
     * @param typeSignature
     *            the type signature to render.
     * @return the type signature string.
     */
    static String toTypeSignatureStr(final TypeSignature typeSignature) {
        final StringBuilder buf = new StringBuilder();
        toTypeSignatureStr(typeSignature, buf);
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the referenced class names.
     */
    @Override
    void findReferencedClassNames(final Set<String> refdClassNames) {
        final var className = getClassName();
        if (className != null && !className.isEmpty()) {
            refdClassNames.add(className);
        }
    }

    /**
     * Compare base types, ignoring generic type parameters.
     *
     * @param other
     *            the other {@link TypeSignature} to compare to, or null.
     * @return True if the two {@link TypeSignature} objects are equal, ignoring type parameters. As with
     *         {@link Object#equals(Object)}, null is not equal to anything, so null returns false rather than
     *         throwing.
     */
    public abstract boolean equalsIgnoringTypeParams(final @Nullable TypeSignature other);

    /**
     * Parse a type signature.
     *
     * @param parser
     *            The parser
     * @param definingClass
     *            The class containing the type descriptor.
     * @return The parsed type descriptor or type signature.
     * @throws TypeSignatureParseException
     *             If the type signature could not be parsed.
     */
    static @Nullable TypeSignature parse(final TypeSignatureParser parser, final @Nullable String definingClass)
            throws TypeSignatureParseException {
        final var referenceTypeSignature = ReferenceTypeSignature.parseReferenceTypeSignature(parser,
                definingClass);
        if (referenceTypeSignature != null) {
            return referenceTypeSignature;
        }
        final var baseTypeSignature = BaseTypeSignature.parse(parser);
        if (baseTypeSignature != null) {
            return baseTypeSignature;
        }
        return null;
    }

    /**
     * Parse a type signature.
     *
     * @param typeDescriptor
     *            The type descriptor or type signature to parse.
     * @param definingClass
     *            The class containing the type descriptor.
     * @return The parsed type descriptor or type signature.
     * @throws TypeSignatureParseException
     *             If the type signature could not be parsed.
     */
    static TypeSignature parse(final String typeDescriptor, final @Nullable String definingClass)
            throws TypeSignatureParseException {
        final TypeSignatureParser parser = new TypeSignatureParser(typeDescriptor);
        final var typeSignature = parse(parser, definingClass);
        if (typeSignature == null) {
            throw new TypeSignatureParseException(parser, "Could not parse type signature");
        }
        if (parser.hasMore()) {
            throw new TypeSignatureParseException(parser, "Extra characters at end of type descriptor");
        }
        return typeSignature;
    }

    /**
     * Add a type annotation to this type.
     *
     * @param typePath
     *            The type path.
     * @param annotationInfo
     *            The annotation to add.
     */
    @Override
    abstract void addTypeAnnotation(List<TypePathNode> typePath, AnnotationInfo annotationInfo);
}
