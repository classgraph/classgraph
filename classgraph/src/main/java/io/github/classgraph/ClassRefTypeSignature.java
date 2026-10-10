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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Classfile.TypePathNode;
import io.github.classgraph.base.internal.utils.CollectionUtils;
import org.jspecify.annotations.Nullable;

/**
 * A reference to a class type, with any type arguments it was parameterized with. This corresponds to the
 * {@code ClassTypeSignature} production of the signature grammar in section 4.7.9.1 of the JVM Specification. (Note
 * that ClassGraph's {@link ClassTypeSignature} models the {@code ClassSignature} production instead, i.e. the
 * signature of a class declaration.)
 */
public final class ClassRefTypeSignature extends ClassRefOrTypeVariableSignature {
    /** The class name. */
    final String className;

    /** The class type arguments. */
    private final List<TypeArgument> typeArguments;

    /** Type suffixes. */
    private final List<String> suffixes;

    /** The suffix type arguments. */
    private final List<List<TypeArgument>> suffixTypeArguments;

    /** The suffix type annotations, in the order they were read, one list per suffix. */
    private @Nullable List<List<AnnotationInfo>> suffixTypeAnnotations;

    /** The value returned by {@link #getSuffixTypeAnnotationInfo()}, or null if it has not been built yet. */
    private @Nullable List<AnnotationInfoList> suffixTypeAnnotationInfoRef;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param className
     *            The class name.
     * @param typeArguments
     *            The class type arguments.
     * @param suffixes
     *            The class suffixes (for nested classes)
     * @param suffixTypeArguments
     *            The suffix type arguments.
     */
    private ClassRefTypeSignature(final String className, final List<TypeArgument> typeArguments,
            final List<String> suffixes, final List<List<TypeArgument>> suffixTypeArguments) {
        super();
        this.className = className;
        this.typeArguments = typeArguments;
        this.suffixes = suffixes;
        this.suffixTypeArguments = suffixTypeArguments;
    }

    /**
     * Constructor for a reference to a class by its whole name, with no type arguments or suffixes.
     *
     * @param className
     *            The class name.
     */
    ClassRefTypeSignature(final String className) {
        this(className, List.of(), List.of(), List.of());
    }

    /**
     * Create a class reference with no type arguments for a class name, splitting it into a base class name and
     * suffixes as a type signature would.
     *
     * @param className
     *            The class name, with '.' between package segments.
     * @param definingClassName
     *            The name of the defining class (for resolving type variables).
     * @return The class reference. If the name cannot be written as a class reference in a type signature (e.g. it
     *         contains ':', which the JVM allows in a class name), it is the whole name as the base class name,
     *         with no suffixes.
     */
    static ClassRefTypeSignature ofClassName(final String className, final @Nullable String definingClassName) {
        try {
            if (TypeSignature.parse("L" + className.replace('.', '/') + ";",
                    definingClassName) instanceof final ClassRefTypeSignature classRefTypeSignature) {
                return classRefTypeSignature;
            }
        } catch (final TypeSignatureParseException e) {
            // The name cannot be written as a class reference in a type signature
        }
        return new ClassRefTypeSignature(className);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the name of the class, without any suffixes.
     *
     * @see #getFullyQualifiedClassName()
     * @return The name of the class.
     */
    public String getBaseClassName() {
        return className;
    }

    /**
     * Get the name of the class, formed from the base name and any suffixes (suffixes are for nested classes, and
     * are separated by '$'), but without any type arguments. For example,
     * {@code "xyz.Cls<String>.InnerCls<Integer>"} is returned as {@code "xyz.Cls$InnerCls"}. The intent of this
     * method is that if you replace '.' with '/', and then add the suffix ".class", you end up with the path of the
     * classfile relative to the package root.
     *
     * <p>
     * For comparison, {@link #toString()} also uses '$' to separate suffixes, but includes type arguments, e.g.
     * {@code "xyz.Cls<java.lang.String>$InnerCls<java.lang.Integer>"}.
     *
     * @return The fully-qualified name of the class, including suffixes but without type arguments.
     */
    public String getFullyQualifiedClassName() {
        if (suffixes.isEmpty()) {
            return className;
        } else {
            final StringBuilder buf = new StringBuilder();
            buf.append(className);
            for (final String suffix : suffixes) {
                buf.append('$');
                buf.append(suffix);
            }
            return buf.toString();
        }
    }

    /**
     * Get any type arguments of the base class.
     *
     * @return The type arguments for the base class.
     */
    public List<TypeArgument> getTypeArguments() {
        return Collections.unmodifiableList(typeArguments);
    }

    /**
     * Get the suffixes of the class, which are the simple names of nested classes.
     *
     * @return The class suffixes (for nested classes), or the empty list if none.
     */
    public List<String> getSuffixes() {
        return Collections.unmodifiableList(suffixes);
    }

    /**
     * Get a list of type arguments for all nested suffixes of the class, one list per suffix.
     *
     * @return The list of type arguments for the suffixes (nested classes), one list per suffix, or the empty list
     *         if none.
     */
    public List<List<TypeArgument>> getSuffixTypeArguments() {
        final List<List<TypeArgument>> unmodifiableSuffixTypeArguments = new ArrayList<>(
                suffixTypeArguments.size());
        for (final List<TypeArgument> typeArgs : suffixTypeArguments) {
            unmodifiableSuffixTypeArguments.add(Collections.unmodifiableList(typeArgs));
        }
        return Collections.unmodifiableList(unmodifiableSuffixTypeArguments);
    }

    @Override
    TypeSignature substituteTypeVariables(final Map<String, TypeArgument> substitutions) {
        final var substitutedTypeArguments = TypeArgument.substituteTypeVariables(typeArguments, substitutions);
        List<List<TypeArgument>> substitutedSuffixTypeArguments = null;
        for (var i = 0; i < suffixTypeArguments.size(); i++) {
            final var suffixTypeArgs = suffixTypeArguments.get(i);
            final var substitutedSuffixTypeArgs = TypeArgument.substituteTypeVariables(suffixTypeArgs,
                    substitutions);
            if (substitutedSuffixTypeArgs != suffixTypeArgs && substitutedSuffixTypeArguments == null) {
                substitutedSuffixTypeArguments = new ArrayList<>(suffixTypeArguments.subList(0, i));
            }
            if (substitutedSuffixTypeArguments != null) {
                substitutedSuffixTypeArguments.add(substitutedSuffixTypeArgs);
            }
        }
        if (substitutedTypeArguments == typeArguments && substitutedSuffixTypeArguments == null) {
            return this;
        }
        return copy(substitutedTypeArguments,
                substitutedSuffixTypeArguments == null ? suffixTypeArguments : substitutedSuffixTypeArguments);
    }

    @Override
    ClassRefTypeSignature withTypeAnnotationsPrepended(final List<AnnotationInfo> annotations) {
        final var copy = copy(typeArguments, suffixTypeArguments);
        if (suffixes.isEmpty()) {
            copy.prependTypeAnnotations(annotations);
        } else {
            // A type annotation on the type as a whole is on the innermost nested class, as in "Map.@A Entry", and
            // the type annotations of the base class apply only to the outermost class. The annotations are not
            // given the copy's ScanResult, which is not set yet: they are shared with the type signature that the
            // substitution was made in, and keep its ScanResult.
            copy.suffixTypeAnnotationLists().get(suffixes.size() - 1).addAll(0, annotations);
        }
        return copy;
    }

    /**
     * Make a copy of this class reference with different type arguments, and with copies of its type annotations.
     *
     * @param newTypeArguments
     *            the type arguments of the base class.
     * @param newSuffixTypeArguments
     *            the type arguments of the suffixes.
     * @return the copy.
     */
    private ClassRefTypeSignature copy(final List<TypeArgument> newTypeArguments,
            final List<List<TypeArgument>> newSuffixTypeArguments) {
        final var copy = new ClassRefTypeSignature(className, newTypeArguments, suffixes, newSuffixTypeArguments);
        copy.copyTypeAnnotationsFrom(this);
        final var suffixAnnotations = suffixTypeAnnotations;
        if (suffixAnnotations != null) {
            final List<List<AnnotationInfo>> suffixAnnotationsCopy = new ArrayList<>(suffixAnnotations.size());
            for (final List<AnnotationInfo> annotations : suffixAnnotations) {
                suffixAnnotationsCopy.add(new ArrayList<>(annotations));
            }
            copy.suffixTypeAnnotations = suffixAnnotationsCopy;
        }
        return copy;
    }

    /**
     * Get a list of lists of type annotations for all nested suffixes of the class, one list per suffix.
     *
     * @return The list of lists of type annotations for the suffixes (nested classes), one list per suffix, with an
     *         empty list for a suffix that has no type annotations. The result is empty if there are no suffixes.
     */
    public List<AnnotationInfoList> getSuffixTypeAnnotationInfo() {
        synchronized (this) {
            if (suffixTypeAnnotationInfoRef == null) {
                final var typeAnnotations = suffixTypeAnnotations;
                if (typeAnnotations == null) {
                    // Not cached, so that a type annotation added later is still seen
                    return Collections.nCopies(suffixes.size(), AnnotationInfoList.EMPTY_LIST);
                }
                final List<AnnotationInfoList> suffixTypeAnnotationInfo = new ArrayList<>(typeAnnotations.size());
                for (final List<AnnotationInfo> annotations : typeAnnotations) {
                    // The order of type annotations in a classfile is not specified, so sort them by name
                    suffixTypeAnnotationInfo.add(new AnnotationInfoList(CollectionUtils.sortCopy(annotations)));
                }
                suffixTypeAnnotationInfoRef = Collections.unmodifiableList(suffixTypeAnnotationInfo);
            }
            return suffixTypeAnnotationInfoRef;
        }
    }

    /**
     * Get the per-suffix type annotation lists of this class reference, one list per suffix (nested class),
     * allocating them if this class reference has no suffix type annotations yet.
     *
     * @return the per-suffix type annotation lists.
     */
    private List<List<AnnotationInfo>> suffixTypeAnnotationLists() {
        var typeAnnotations = suffixTypeAnnotations;
        if (typeAnnotations == null) {
            suffixTypeAnnotations = typeAnnotations = new ArrayList<>(suffixes.size());
            for (var i = 0; i < suffixes.size(); i++) {
                typeAnnotations.add(new ArrayList<>(1));
            }
        }
        return typeAnnotations;
    }

    /**
     * Add a type annotation to one of the suffixes (nested classes) of this class reference.
     *
     * @param suffixIdx
     *            the index of the suffix that the annotation applies to
     * @param annotationInfo
     *            the type annotation
     */
    private void addSuffixTypeAnnotation(final int suffixIdx, final AnnotationInfo annotationInfo) {
        // Set the ScanResult as the annotation is added, for the same reason as in
        // HierarchicalTypeSignature#addTypeAnnotation
        annotationInfo.setScanResult(scanResult);
        suffixTypeAnnotationLists().get(suffixIdx).add(annotationInfo);
    }

    @Override
    void addTypeAnnotation(final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
        // Find how many deeper nested levels to descend to
        var numDeeperNestedLevels = 0;
        var nextTypeArgIdx = -1;
        for (final TypePathNode typePathNode : typePath) {
            if (typePathNode.typePathKind() == 1) {
                // Annotation is deeper in a nested type (can handle this iteratively)
                numDeeperNestedLevels++;
            } else if (typePathNode.typePathKind() == 3) {
                // Annotation is on a type argument of a parameterized type (need to handle this recursively)
                nextTypeArgIdx = typePathNode.typeArgumentIdx();
                break;
            } else {
                // Not valid here: 0 => Annotation is deeper in an array type 2 => Annotation is on the bound of a
                // wildcard type argument of a parameterized type
                throw new IllegalArgumentException("Bad typePathKind: " + typePathNode.typePathKind());
            }
        }

        // Figure out whether to index the base type or a suffix, skipping over non-nested class pairs
        var suffixIdx = -1;
        var nestingLevel = -1;
        var typePrefix = className;
        for (;;) {
            final boolean skipSuffix;
            if (suffixIdx >= suffixes.size()) {
                throw new IllegalArgumentException("Ran out of nested types while trying to add type annotation");
            } else if (suffixIdx == suffixes.size() - 1) {
                // The suffix to the right cannot be static, because there are no suffixes to the right, so this
                // suffix doesn't need to be skipped
                skipSuffix = false;
            } else {
                // For suffix path X.Y, classes are not nested if Y is static
                final var outerClassInfo = scanResult().getClassInfo(typePrefix);
                typePrefix = typePrefix + '$' + suffixes.get(suffixIdx + 1);
                final var innerClassInfo = scanResult().getClassInfo(typePrefix);
                skipSuffix = outerClassInfo == null || innerClassInfo == null
                        || outerClassInfo.isInterfaceOrAnnotation() //
                        || innerClassInfo.isInterfaceOrAnnotation() //
                        || innerClassInfo.isStatic() //
                        || !outerClassInfo.nestedClassesIncludingExternal().contains(innerClassInfo);
            }
            if (!skipSuffix) {
                // Found nested classes
                nestingLevel++;
                if (nestingLevel >= numDeeperNestedLevels) {
                    break;
                }
            }
            suffixIdx++;
        }

        if (nextTypeArgIdx == -1) {
            // Reached end of path -- add type annotation
            if (suffixIdx == -1) {
                // Add type annotation to base type
                addTypeAnnotation(annotationInfo);
            } else {
                // Add type annotation to suffix type
                addSuffixTypeAnnotation(suffixIdx, annotationInfo);
            }
        } else {
            final var typeArgumentList = suffixIdx == -1 ? typeArguments : suffixTypeArguments.get(suffixIdx);
            // For type descriptors (as opposed to type signatures), typeArguments is the empty list, so need to
            // bounds-check nextTypeArgIdx
            if (nextTypeArgIdx < typeArgumentList.size()) {
                // type_path_kind == 3 can be followed by type_path_kind == 2, for an annotation on the bound of a
                // nested type, and this has to be handled recursively on the remaining part of the type path
                final var remainingTypePath = typePath.subList(numDeeperNestedLevels + 1, typePath.size());
                // Add type annotation to type argument
                typeArgumentList.get(nextTypeArgIdx).addTypeAnnotation(remainingTypePath, annotationInfo);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** @return the fully-qualified class name. */
    @Override
    protected String getClassName() {
        return getFullyQualifiedClassName();
    }

    /**
     * Get the {@link ClassInfo} object for the referenced class.
     *
     * @return The {@link ClassInfo} object for the referenced class, or null if the referenced class was not
     *         encountered during scanning (i.e. if no ClassInfo object was created for the class during scanning).
     */
    @Override
    public @Nullable ClassInfo getClassInfo() {
        return super.getClassInfo();
    }

    @Override
    void setScanResult(final @Nullable ScanResult scanResult) {
        super.setScanResult(scanResult);
        for (final TypeArgument typeArgument : typeArguments) {
            typeArgument.setScanResult(scanResult);
        }
        for (final List<TypeArgument> typeArgumentList : suffixTypeArguments) {
            for (final TypeArgument typeArgument : typeArgumentList) {
                typeArgument.setScanResult(scanResult);
            }
        }
        final var suffixAnnotations = suffixTypeAnnotations;
        if (suffixAnnotations != null) {
            for (final List<AnnotationInfo> annotations : suffixAnnotations) {
                for (final AnnotationInfo annotationInfo : annotations) {
                    annotationInfo.setScanResult(scanResult);
                }
            }
        }
    }

    /**
     * Get the names of any classes referenced in the type signature.
     *
     * @param refdClassNames
     *            the referenced class names.
     */
    @Override
    void findReferencedClassNames(final Set<String> refdClassNames) {
        refdClassNames.add(getFullyQualifiedClassName());
        for (final TypeArgument typeArgument : typeArguments) {
            typeArgument.findReferencedClassNames(refdClassNames);
        }
        for (final List<TypeArgument> typeArgumentList : suffixTypeArguments) {
            for (final TypeArgument typeArgument : typeArgumentList) {
                typeArgument.findReferencedClassNames(refdClassNames);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int hashCode() {
        return className.hashCode() + 7 * typeArguments.hashCode() + 15 * suffixTypeArguments.hashCode()
                + 31 * (typeAnnotations == null ? 0 : typeAnnotations.hashCode())
                + 64 * (suffixTypeAnnotations == null ? 0 : suffixTypeAnnotations.hashCode());
    }

    /**
     * Test whether two class references have the same nested class suffixes, with the same suffix type arguments
     * and suffix type annotations.
     *
     * @param a
     *            the first class reference
     * @param b
     *            the second class reference
     * @return true if the suffixes of the two class references match
     */
    private static boolean suffixesMatch(final ClassRefTypeSignature a, final ClassRefTypeSignature b) {
        return a.suffixes.equals(b.suffixes) //
                && a.suffixTypeArguments.equals(b.suffixTypeArguments) //
                && Objects.equals(a.suffixTypeAnnotations, b.suffixTypeAnnotations);
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final ClassRefTypeSignature o)) {
            return false;
        }
        return o.className.equals(this.className) && o.typeArguments.equals(this.typeArguments)
                && Objects.equals(this.typeAnnotations, o.typeAnnotations) && suffixesMatch(o, this);
    }

    @Override
    public boolean equalsIgnoringTypeParams(final @Nullable TypeSignature other) {
        if (other instanceof TypeVariableSignature) {
            // Compare class type signature to type variable -- the logic for this is implemented in
            // TypeVariableSignature, and is not duplicated here
            return other.equalsIgnoringTypeParams(this);
        }
        if (!(other instanceof final ClassRefTypeSignature o)) {
            return false;
        }
        // The type arguments of the classes in the suffixes are type parameters too, and type annotations are not
        // compared here by any other kind of type signature
        return o.className.equals(this.className) && o.suffixes.equals(this.suffixes);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the type annotations to leave out when rendering this class reference. The compiler records a
     * declaration annotation that is also a type annotation on the outermost part of the class reference that can
     * be annotated: the base class, or for a static nested class such as {@code Map.Entry}, the nested class, since
     * the enclosing class of a static nested class cannot be annotated. So the first occurrence of each excluded
     * annotation, searching from the base class inward, is the one to leave out; the same annotation written again
     * on a part further in is kept.
     *
     * @param annotationsToExclude
     *            the annotations to exclude
     * @return the excluded type annotations of this class reference, compared by identity
     */
    private Set<AnnotationInfo> findExcludedTypeAnnotations(final List<AnnotationInfo> annotationsToExclude) {
        final Set<AnnotationInfo> excluded = Collections.newSetFromMap(new IdentityHashMap<>());
        final var typeAnnotationList = typeAnnotations;
        final var suffixTypeAnnotationLists = suffixTypeAnnotations;
        for (final AnnotationInfo annotationToExclude : annotationsToExclude) {
            final var idx = typeAnnotationList == null ? -1 : typeAnnotationList.indexOf(annotationToExclude);
            if (typeAnnotationList != null && idx >= 0) {
                excluded.add(typeAnnotationList.get(idx));
            } else if (suffixTypeAnnotationLists != null) {
                for (final List<AnnotationInfo> suffixTypeAnnotationList : suffixTypeAnnotationLists) {
                    final var suffixIdx = suffixTypeAnnotationList.indexOf(annotationToExclude);
                    if (suffixIdx >= 0) {
                        excluded.add(suffixTypeAnnotationList.get(suffixIdx));
                        break;
                    }
                }
            }
        }
        return excluded;
    }

    @Override
    protected void toStringInternal(final boolean useSimpleNames,
            final @Nullable List<AnnotationInfo> annotationsToExclude, final StringBuilder buf) {
        final Set<AnnotationInfo> excluded = annotationsToExclude == null || annotationsToExclude.isEmpty()
                ? Set.of()
                : findExcludedTypeAnnotations(annotationsToExclude);
        // Only render the base class if not using simple names, or if there are no suffixes
        if (!useSimpleNames || suffixes.isEmpty()) {
            // Append type annotations
            final var typeAnnotationList = typeAnnotations;
            if (typeAnnotationList != null) {
                for (final AnnotationInfo annotationInfo : typeAnnotationList) {
                    if (!excluded.contains(annotationInfo)) {
                        annotationInfo.toString(useSimpleNames, buf);
                        buf.append(' ');
                    }
                }
            }
            // Append base class name
            buf.append(useSimpleNames ? ClassInfo.getSimpleName(className) : className);
            // Append base class type arguments
            if (!typeArguments.isEmpty()) {
                buf.append('<');
                for (var i = 0; i < typeArguments.size(); i++) {
                    if (i > 0) {
                        buf.append(", ");
                    }
                    typeArguments.get(i).toString(useSimpleNames, buf);
                }
                buf.append('>');
            }
        }

        // Append suffixes
        if (!suffixes.isEmpty()) {
            for (var i = useSimpleNames ? suffixes.size() - 1 : 0; i < suffixes.size(); i++) {
                if (!useSimpleNames) {
                    // Use '$' rather than '.' as separator for suffixes, since that is what Class.getName() does.
                    buf.append('$');
                }
                final var typeAnnotations = suffixTypeAnnotations == null ? null : suffixTypeAnnotations.get(i);
                // Append type annotations for this suffix
                if (typeAnnotations != null && !typeAnnotations.isEmpty()) {
                    for (final AnnotationInfo annotationInfo : typeAnnotations) {
                        if (!excluded.contains(annotationInfo)) {
                            annotationInfo.toString(useSimpleNames, buf);
                            buf.append(' ');
                        }
                    }
                }
                // Append suffix name
                buf.append(suffixes.get(i));
                // Append suffix type arguments
                final var suffixTypeArgumentsList = suffixTypeArguments.get(i);
                if (!suffixTypeArgumentsList.isEmpty()) {
                    buf.append('<');
                    for (var j = 0; j < suffixTypeArgumentsList.size(); j++) {
                        if (j > 0) {
                            buf.append(", ");
                        }
                        suffixTypeArgumentsList.get(j).toString(useSimpleNames, buf);
                    }
                    buf.append('>');
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Parse a class type signature.
     *
     * @param parser
     *            The parser.
     * @param definingClassName
     *            The name of the defining class (for resolving type variables).
     * @return The class type signature.
     * @throws TypeSignatureParseException
     *             If the type signature could not be parsed.
     */
    static @Nullable ClassRefTypeSignature parse(final TypeSignatureParser parser,
            final @Nullable String definingClassName) throws TypeSignatureParseException {
        if (parser.peek() == 'L') {
            parser.next();
            // A '$' at the start of the class name or of a package segment has no name before it, so it is part of
            // the name (e.g. "$Foo", or AutoValue's "p/$AutoValue_Foo"), not a nested class separator
            if (!TypeUtils.getIdentifierToken(parser, /* stopAtDollarSign = */ true,
                    /* dollarAtSegmentStartIsPartOfName = */ true, /* stopAtDot = */ true)) {
                throw new TypeSignatureParseException(parser, "Could not parse identifier token");
            }
            var className = parser.currToken();
            var typeArguments = TypeArgument.parseList(parser, definingClassName);
            List<String> suffixes;
            List<List<TypeArgument>> suffixTypeArguments;
            if (parser.peek() == '.' || parser.peek() == '$') {
                suffixes = new ArrayList<>();
                suffixTypeArguments = new ArrayList<>();
                while (parser.peek() == '.' || parser.peek() == '$') {
                    final var separator = parser.getc();
                    // A '$' is taken to separate nested class names, since before the first type arguments the
                    // signature gives no other way to tell nested classes apart. After a '.', which javac writes
                    // once an enclosing class has type arguments, the whole simple name of the nested class
                    // follows, and a '$' in it is part of that name (e.g. "Outer<TT;>.Inner$Name").
                    if (TypeUtils.getIdentifierToken(parser, /* stopAtDollarSign = */ separator == '$',
                            /* dollarAtSegmentStartIsPartOfName = */ false, /* stopAtDot = */ true)) {
                        suffixes.add(parser.currToken());
                        suffixTypeArguments.add(TypeArgument.parseList(parser, definingClassName));
                    } else if (separator == '$') {
                        // A '$' that is not followed by a name is part of the preceding name, as in "$$", or in a
                        // name that ends in '$' (which Scala uses for the class of an object, e.g. "Outer$Inner$").
                        // Type arguments may follow such a name, as in "Box$<Ljava/lang/String;>".
                        final var typeArgumentsAfterDollar = TypeArgument.parseList(parser, definingClassName);
                        if (suffixes.isEmpty()) {
                            className += '$';
                            if (!typeArgumentsAfterDollar.isEmpty()) {
                                if (!typeArguments.isEmpty()) {
                                    throw new TypeSignatureParseException(parser,
                                            "Type arguments both before and after '$'");
                                }
                                typeArguments = typeArgumentsAfterDollar;
                            }
                        } else {
                            final var last = suffixes.size() - 1;
                            suffixes.set(last, suffixes.get(last) + '$');
                            if (!typeArgumentsAfterDollar.isEmpty()) {
                                if (!suffixTypeArguments.get(last).isEmpty()) {
                                    throw new TypeSignatureParseException(parser,
                                            "Type arguments both before and after '$'");
                                }
                                suffixTypeArguments.set(last, typeArgumentsAfterDollar);
                            }
                        }
                    } else {
                        throw new TypeSignatureParseException(parser, "Missing class name after '.'");
                    }
                }
            } else {
                suffixes = List.of();
                suffixTypeArguments = List.of();
            }
            parser.expect(';');
            return new ClassRefTypeSignature(className, typeArguments, suffixes, suffixTypeArguments);
        } else {
            return null;
        }
    }
}
