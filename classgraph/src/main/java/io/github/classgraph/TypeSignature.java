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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.Classfile.ClassContainment;
import io.github.classgraph.Classfile.TypePathNode;
import io.github.classgraph.base.internal.utils.Assert;

// TODO: once ClassGraph's minimum supported JDK version is 21 or later, each place that tests for more than one
// subclass of TypeSignature in turn, and so could use a pattern switch, has a TODO of its own.

/**
 * A type signature for a reference type or base type. Subclasses are {@link ReferenceTypeSignature} (whose own
 * subclasses are {@link ClassRefTypeSignature}, {@link TypeVariableSignature}, and {@link ArrayTypeSignature}), and
 * {@link BaseTypeSignature}. This corresponds to the {@code JavaTypeSignature} production of the signature grammar
 * in section 4.7.9.1 of the JVM Specification.
 *
 * <p>
 * The hierarchy is sealed, so on Java 21 or later, a pattern switch over a {@link TypeSignature} that has a case
 * for each of {@link BaseTypeSignature}, {@link ArrayTypeSignature}, {@link ClassRefTypeSignature} and
 * {@link TypeVariableSignature} needs no default case.
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
     * subinterface of the class declaring the type variable, if the supertype, or any supertype between it and the
     * context class, is used in raw form, if the type variable is shadowed by a type parameter of the method that
     * declares this type, or if the type argument is a wildcard that has no expressible equivalent outside type
     * argument position ({@code "?"} or {@code "? super X"} -- an upper-bounded wildcard {@code "? extends X"} is
     * substituted as {@code X}). In type argument position, wildcards are substituted verbatim, so {@code List<T>}
     * with {@code T := "? extends Number"} resolves to {@code List<? extends Number>}.
     *
     * <p>
     * Type variables are matched by the class that declares them together with their name, and bindings are
     * collected by walking up the superclass and superinterface chain of the context class. A type variable that an
     * inner class inherits from its enclosing class is matched by the enclosing class, so given {@code class
     * Outer<V> { abstract class Inner implements Base<V> {} }}, the result type of {@code Base#getT()} resolves to
     * {@code java.lang.String} against a subclass of {@code Outer<String>.Inner}, and a type variable used in
     * {@code Inner} resolves against a subclass of {@code Outer<String>}. An inner class of a generic class that is
     * referenced with no type arguments, as in {@code Outer.Inner}, is used in raw form.
     *
     * <p>
     * An array type substituted for the element type of an array type adds its dimensions, so {@code E[]} with
     * {@code E := String[]} resolves to {@code String[][]}. If the result would have more than 255 dimensions, the
     * maximum the JVM allows, the type variable is left unchanged.
     *
     * <p>
     * Type annotations are kept. A type annotation on a use of a type variable, such as the {@code @A} in
     * {@code @A T} or {@code List<@A T>}, is placed on the type that replaces the type variable, before that type's
     * own type annotations: on the innermost class of a nested class type (as in {@code Map.@A Entry}), on the
     * outermost dimension of an array type, and on the wildcard of a wildcard type argument. The type signature
     * that binds the type variable is not changed.
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
        final var classSignature = classInfo.getTypeSignatureIfParseable();
        if (classSignature == null) {
            // The class has no generic signature, or one that does not parse, so it supplies no type arguments --
            // but a class further up the hierarchy may still be generic, so keep walking through each direct
            // supertype that is not generic. A generic direct supertype, or an inner class of a generic class, is
            // used in raw form, which erases every binding above it (JLS 4.8), so the walk stops there.
            final var superclassInfo = classInfo.superclassIncludingExternal();
            if (!hasTypeParametersInScope(superclassInfo)) {
                addSubstitutions(superclassInfo, substitutions, visited);
            }
            for (final ClassInfo interfaceInfo : classInfo.allSuperinterfacesIncludingExternal().directOnly()) {
                if (!hasTypeParametersInScope(interfaceInfo)) {
                    addSubstitutions(interfaceInfo, substitutions, visited);
                }
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
        final var supertypeClassInfo = supertypeSignature.getClassInfo();
        if (supertypeSignature.getTypeArguments().isEmpty() && suffixTypeArguments.stream().allMatch(List::isEmpty)
                && hasTypeParametersInScope(supertypeClassInfo)) {
            // The supertype is used in raw form, which erases every binding above it (JLS 4.8)
            return;
        }
        addSubstitutions(supertypeClassInfo, substitutions, visited);
    }

    /**
     * Check whether a class declares type parameters, or is an inner member class of a class that has type
     * parameters in scope, i.e. whether a reference to the class with no type arguments is a raw type (JLS 4.8).
     *
     * @param classInfo
     *            the class, or null.
     * @return true if the class or a class it is nested in, up to the first static member class, local class or
     *         anonymous class, has a parseable generic signature that declares at least one type parameter.
     */
    private static boolean hasTypeParametersInScope(final @Nullable ClassInfo classInfo) {
        if (classInfo == null) {
            return false;
        }
        // The enclosing classes are listed innermost first, as in TypeVariableSignature.resolve()
        final var enclosingClasses = classInfo.enclosingClassesIncludingExternal();
        var currClassInfo = classInfo;
        for (var i = 0;; i++) {
            final var classSignature = currClassInfo.getTypeSignatureIfParseable();
            if (classSignature != null && !classSignature.getTypeParameters().isEmpty()) {
                return true;
            }
            if (i == enclosingClasses.size() || !currClassInfo.isMemberClass() || currClassInfo.isStatic()) {
                return false;
            }
            currClassInfo = enclosingClasses.get(i);
        }
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
        // A class signature that does not parse names no type parameters to substitute
        final var classSignature = classInfo.getTypeSignatureIfParseable();
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
     * Render this type as {@link #toString()} or {@link #toStringWithSimpleNames()} does, but leave out the given
     * annotations where they are written on the type itself.
     *
     * <p>
     * An annotation whose {@link java.lang.annotation.Target @Target} includes
     * {@link java.lang.annotation.ElementType#TYPE_USE TYPE_USE} as well as a declaration kind, such as
     * {@link java.lang.annotation.ElementType#FIELD FIELD}, is recorded by the compiler both on the declaration and
     * on the declared type. Passing the annotations of a field, method or method parameter here renders its type
     * without repeating them, which is how {@link FieldInfo}, {@link MethodInfo} and {@link MethodParameterInfo}
     * show such an annotation once. An annotation is left out only where the compiler records that copy: on the
     * type as a whole, or for an array type on its element type, or for a static nested class such as
     * {@code Map.Entry} on the nested class. An annotation written inside the type, for example on a type argument
     * as in {@code List<@NonNull String>}, is part of the type and is kept.
     *
     * @param useSimpleNames
     *            if true, use simple class names, as {@link #toStringWithSimpleNames()} does; if false, use fully
     *            qualified class names, as {@link #toString()} does.
     * @param annotationsToExclude
     *            the annotations to leave out, usually the annotations of the field, method or method parameter
     *            that this is the type of.
     * @return this type as a string, without the excluded annotations.
     * @throws NullPointerException
     *             if {@code annotationsToExclude} is null or contains null.
     */
    public String toString(final boolean useSimpleNames, final Collection<AnnotationInfo> annotationsToExclude) {
        Assert.notNull(annotationsToExclude, "annotationsToExclude");
        final var buf = new StringBuilder();
        toStringInternal(useSimpleNames, List.copyOf(annotationsToExclude), buf);
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Compare base types, ignoring generic type parameters and type annotations.
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
     * Add a type annotation to this type, or to the type within it that the type path leads to.
     *
     * @param typePath
     *            The type path, relative to this type.
     * @param annotationInfo
     *            The annotation to add.
     * @param unlinkedInnerClasses
     *            The {@code InnerClasses} entries for inner classes that the classfile the annotation was read from
     *            did not link (see {@link ClassInfo#getUnlinkedInnerClasses()}).
     * @throws IllegalArgumentException
     *             if the type path does not lead to a type within this type.
     */
    abstract void addTypeAnnotation(List<TypePathNode> typePath, AnnotationInfo annotationInfo,
            List<ClassContainment> unlinkedInnerClasses);
}
