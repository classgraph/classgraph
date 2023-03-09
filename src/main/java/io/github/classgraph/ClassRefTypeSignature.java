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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Classfile.TypePathNode;
import nonapi.io.github.classgraph.types.ParseException;
import nonapi.io.github.classgraph.types.Parser;
import nonapi.io.github.classgraph.types.TypeUtils;

/** A class reference type signature (called "ClassTypeSignature" in the classfile documentation). */
public final class ClassRefTypeSignature extends ClassRefOrTypeVariableSignature {
    /** The class name. */
    final String className;

    /** The class type arguments. */
    private final List<TypeArgument> typeArguments;

    /** Type suffixes. */
    private final List<String> suffixes;

    /** The suffix type arguments. */
    private final List<List<TypeArgument>> suffixTypeArguments;

    /** The suffix type annotations. */
    private List<AnnotationInfoList> suffixTypeAnnotations;

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     *
     * @param className
     *            The class name.
     * @param typeArguments
     *            The class type arguments.
     * @param suffixes
     *            The class suffixes (for inner classes)
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
     * Get the name of the class, formed from the base name and any suffixes (suffixes are for inner class nesting,
     * and are separated by '$'), but without any type arguments. For example,
     * {@code "xyz.Cls<String>.InnerCls<Integer>"} is returned as {@code "xyz.Cls$InnerCls"}. The intent of this
     * method is that if you replace '.' with '/', and then add the suffix ".class", you end up with the path of the
     * classfile relative to the package root.
     * 
     * <p>
     * For comparison, {@link #toString()} uses '.' to separate suffixes, and includes type parameters, whereas this
     * method uses '$' to separate suffixes, and does not include type parameters.
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
        return typeArguments;
    }

    /**
     * Get all nested suffixes of the class (typically nested inner class names).
     * 
     * @return The class suffixes (for inner classes), or the empty list if none.
     */
    public List<String> getSuffixes() {
        return suffixes;
    }

    /**
     * Get a list of type arguments for all nested suffixes of the class, one list per suffix.
     * 
     * @return The list of type arguments for the suffixes (nested inner classes), one list per suffix, or the empty
     *         list if none.
     */
    public List<List<TypeArgument>> getSuffixTypeArguments() {
        return suffixTypeArguments;
    }

    /**
     * Get a list of lists of type annotations for all nested suffixes of the class, one list per suffix.
     * 
     * @return The list of lists of type annotations for the suffixes (nested inner classes), one list per suffix,
     *         or null if none.
     */
    public List<AnnotationInfoList> getSuffixTypeAnnotationInfo() {
        return suffixTypeAnnotations;
    }

    private void addSuffixTypeAnnotation(final int suffixIdx, final AnnotationInfo annotationInfo) {
        if (suffixTypeAnnotations == null) {
            suffixTypeAnnotations = new ArrayList<>(suffixes.size());
            for (int i = 0; i < suffixes.size(); i++) {
                suffixTypeAnnotations.add(new AnnotationInfoList(1));
            }
        }
        suffixTypeAnnotations.get(suffixIdx).add(annotationInfo);
    }

    @Override
    protected void addTypeAnnotation(final List<TypePathNode> typePath, final AnnotationInfo annotationInfo) {
        // Find how many deeper nested levels to descend to
        int numDeeperNestedLevels = 0;
        int nextTypeArgIdx = -1;
        for (final TypePathNode typePathNode : typePath) {
            if (typePathNode.typePathKind == 1) {
                // Annotation is deeper in a nested type
                // (can handle this iteratively)
                numDeeperNestedLevels++;
            } else if (typePathNode.typePathKind == 3) {
                // Annotation is on a type argument of a parameterized type
                // (need to handle this recursively)
                nextTypeArgIdx = typePathNode.typeArgumentIdx;
                break;
            } else {
                // Not valid here:
                // 0 => Annotation is deeper in an array type
                // 2 => Annotation is on the bound of a wildcard type argument of a parameterized type
                throw new IllegalArgumentException("Bad typePathKind: " + typePathNode.typePathKind);
            }
        }

        // Figure out whether to index the base type or a suffix, skipping over non-nested class pairs
        int suffixIdx = -1;
        int nestingLevel = -1;
        String typePrefix = className;
        for (;;) {
            boolean skipSuffix;
            if (suffixIdx >= suffixes.size()) {
                throw new IllegalArgumentException("Ran out of nested types while trying to add type annotation");
            } else if (suffixIdx == suffixes.size() - 1) {
                // The suffix to the right cannot be static, because there are no suffixes to the right,
                // so this suffix doesn't need to be skipped
                skipSuffix = false;
            } else {
                // For suffix path X.Y, classes are not nested if Y is static
                final ClassInfo outerClassInfo = scanResult.getClassInfo(typePrefix);
                typePrefix = typePrefix + '$' + suffixes.get(suffixIdx + 1);
                final ClassInfo innerClassInfo = scanResult.getClassInfo(typePrefix);
                skipSuffix = outerClassInfo == null || innerClassInfo == null
                        || outerClassInfo.isInterfaceOrAnnotation() //
                        || innerClassInfo.isInterfaceOrAnnotation() //
                        || innerClassInfo.isStatic() //
                        || !outerClassInfo.getInnerClasses().contains(innerClassInfo);
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
            final List<TypeArgument> typeArgumentList = suffixIdx == -1 ? typeArguments
                    : suffixTypeArguments.get(suffixIdx);
            // For type descriptors (as opposed to type signatures), typeArguments is the empty list,
            // so need to bounds-check nextTypeArgIdx
            if (nextTypeArgIdx < typeArgumentList.size()) {
                // type_path_kind == 3 can be followed by type_path_kind == 2, for an annotation on the
                // bound of a nested type, and this has to be handled recursively on the remaining
                // part of the type path
                final List<TypePathNode> remainingTypePath = typePath.subList(numDeeperNestedLevels + 1,
                        typePath.size());
                // Add type annotation to type argument  
                typeArgumentList.get(nextTypeArgIdx).addTypeAnnotation(remainingTypePath, annotationInfo);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Load the referenced class, if not already loaded, returning a {@code Class<?>} reference for the referenced
     * class. (Called by {@link AnnotationClassRef#loadClass()}.)
     * 
     * @param ignoreExceptions
     *            if true, ignore exceptions and instead return null if the class could not be loaded.
     * @return The {@code Class<?>} reference for the referenced class.
     * @throws IllegalArgumentException
     *             if the class could not be loaded and ignoreExceptions was false.
     */
    @Override
    public Class<?> loadClass(final boolean ignoreExceptions) {
        return super.loadClass(ignoreExceptions);
    }

    /**
     * Load the referenced class, if not already loaded, returning a {@code Class<?>} reference for the referenced
     * class. (Called by {@link AnnotationClassRef#loadClass()}.)
     * 
     * @return The {@code Class<?>} reference for the referenced class.
     * @throws IllegalArgumentException
     *             if the class could not be loaded.
     */
    @Override
    public Class<?> loadClass() {
        return super.loadClass();
    }

    // -------------------------------------------------------------------------------------------------------------

    /** @return the fully-qualified class name, for classloading. */
    @Override
    protected String getClassName() {
        return getFullyQualifiedClassName();
    }

    /**
     * Get the {@link ClassInfo} object for the referenced class.
     *
     * @return The {@link ClassInfo} object for the referenced class, or null if the referenced class was not
     *         encountered during scanning (i.e. if no ClassInfo object was created for the class during scanning).
     *         N.B. even if this method returns null, {@link #loadClass()} may be able to load the referenced class
     *         by name.
     */
    @Override
    public ClassInfo getClassInfo() {
        return super.getClassInfo();
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ScanResultObject#setScanResult(io.github.classgraph.ScanResult)
     */
    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        for (final TypeArgument typeArgument : typeArguments) {
            typeArgument.setScanResult(scanResult);
        }
        for (final List<TypeArgument> typeArgumentList : suffixTypeArguments) {
            for (final TypeArgument typeArgument : typeArgumentList) {
                typeArgument.setScanResult(scanResult);
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
    protected void findReferencedClassNames(final Set<String> refdClassNames) {
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

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return className.hashCode() + 7 * typeArguments.hashCode() + 15 * suffixTypeArguments.hashCode()
                + 31 * (typeAnnotationInfo == null ? 0 : typeAnnotationInfo.hashCode())
                + 64 * (suffixTypeAnnotations == null ? 0 : suffixTypeAnnotations.hashCode());
    }

    private static boolean suffixesMatch(final ClassRefTypeSignature a, final ClassRefTypeSignature b) {
        return a.suffixes.equals(b.suffixes) //
                && a.suffixTypeArguments.equals(b.suffixTypeArguments) //
                && Objects.equals(a.suffixTypeAnnotations, b.suffixTypeAnnotations);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ClassRefTypeSignature)) {
            return false;
        }
        final ClassRefTypeSignature o = (ClassRefTypeSignature) obj;
        return o.className.equals(this.className) && o.typeArguments.equals(this.typeArguments)
                && Objects.equals(this.typeAnnotationInfo, o.typeAnnotationInfo) && suffixesMatch(o, this);
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.TypeSignature#equalsIgnoringTypeParams(io.github.classgraph.TypeSignature)
     */
    @Override
    public boolean equalsIgnoringTypeParams(final TypeSignature other) {
        if (other instanceof TypeVariableSignature) {
            // Compare class type signature to type variable -- the logic for this
            // is implemented in TypeVariableSignature, and is not duplicated here
            return other.equalsIgnoringTypeParams(this);
        }
        if (!(other instanceof ClassRefTypeSignature)) {
            return false;
        }
        final ClassRefTypeSignature o = (ClassRefTypeSignature) other;
        return o.className.equals(this.className) && Objects.equals(this.typeAnnotationInfo, o.typeAnnotationInfo)
                && suffixesMatch(o, this);
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    protected void toStringInternal(final boolean useSimpleNames, final AnnotationInfoList annotationsToExclude,
            final StringBuilder buf) {
        // Only render the base class if not using simple names, or if there are no suffixes
        if (!useSimpleNames || suffixes.isEmpty()) {
            // Append type annotations
            if (typeAnnotationInfo != null) {
                for (final AnnotationInfo annotationInfo : typeAnnotationInfo) {
                    if (annotationsToExclude == null || !annotationsToExclude.contains(annotationInfo)) {
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
                for (int i = 0; i < typeArguments.size(); i++) {
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
            for (int i = useSimpleNames ? suffixes.size() - 1 : 0; i < suffixes.size(); i++) {
                if (!useSimpleNames) {
                    // Use '$' rather than '.' as separator for suffixes, since that is what Class.getName() does.
                    buf.append('$');
                }
                final AnnotationInfoList typeAnnotations = suffixTypeAnnotations == null ? null
                        : suffixTypeAnnotations.get(i);
                // Append type annotations for this suffix
                if (typeAnnotations != null && !typeAnnotations.isEmpty()) {
                    for (final AnnotationInfo annotationInfo : typeAnnotations) {
                        annotationInfo.toString(useSimpleNames, buf);
                        buf.append(' ');
                    }
                }
                // Append suffix name
                buf.append(suffixes.get(i));
                // Append suffix type arguments
                final List<TypeArgument> suffixTypeArgumentsList = suffixTypeArguments.get(i);
                if (!suffixTypeArgumentsList.isEmpty()) {
                    buf.append('<');
                    for (int j = 0; j < suffixTypeArgumentsList.size(); j++) {
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
     * @throws ParseException
     *             If the type signature could not be parsed.
     */
    static ClassRefTypeSignature parse(final Parser parser, final String definingClassName) throws ParseException {
        if (parser.peek() == 'L') {
            parser.next();
            final int startParserPosition = parser.getPosition();
            if (!TypeUtils.getIdentifierToken(parser, /* stopAtDollarSign = */ true)) {
                throw new ParseException(parser, "Could not parse identifier token");
            }
            String className = parser.currToken();
            final List<TypeArgument> typeArguments = TypeArgument.parseList(parser, definingClassName);
            List<String> suffixes;
            List<List<TypeArgument>> suffixTypeArguments;
            boolean dropSuffixes = false;
            if (parser.peek() == '.' || parser.peek() == '$') {
                suffixes = new ArrayList<>();
                suffixTypeArguments = new ArrayList<>();
                while (parser.peek() == '.' || parser.peek() == '$') {
                    parser.advance(1);
                    if (!TypeUtils.getIdentifierToken(parser, /* stopAtDollarSign = */ true)) {
                        // Got the empty string as the next token after '$', i.e. found an empty suffix.
                        suffixes.add("");
                        suffixTypeArguments.add(Collections.<TypeArgument> emptyList());
                        dropSuffixes = true;
                    } else {
                        suffixes.add(parser.currToken());
                        suffixTypeArguments.add(TypeArgument.parseList(parser, definingClassName));
                    }
                }
                if (dropSuffixes) {
                    // Got an empty suffix -- either "$$", or a class name ending in a '$' (which Scala uses).
                    // In this case, take the whole class reference as a single class name without suffixes.
                    className = parser.getSubstring(startParserPosition, parser.getPosition()).replace('/', '.');
                    suffixes = Collections.emptyList();
                    suffixTypeArguments = Collections.emptyList();
                }
            } else {
                suffixes = Collections.emptyList();
                suffixTypeArguments = Collections.emptyList();
            }
            parser.expect(';');
            return new ClassRefTypeSignature(className, typeArguments, suffixes, suffixTypeArguments);
        } else {
            return null;
        }
    }
}
