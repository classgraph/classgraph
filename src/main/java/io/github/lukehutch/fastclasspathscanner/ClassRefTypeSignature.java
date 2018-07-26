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
import java.util.Collections;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.utils.Parser;
import io.github.lukehutch.fastclasspathscanner.utils.Parser.ParseException;
import io.github.lukehutch.fastclasspathscanner.utils.TypeUtils;

/** A class reference type signature (called "ClassTypeSignature" in the classfile documentation). */
public class ClassRefTypeSignature extends ClassRefOrTypeVariableSignature {
    /** The class name. */
    final String className;

    /** The class name and suffixes, without type arguments. */
    private String fullyQualifiedClassName;

    /** The class type arguments. */
    private final List<TypeArgument> typeArguments;

    /** The class type signature suffix(es), or the empty list if no suffixes. */
    private final List<String> suffixes;

    /**
     * The suffix type arguments, one per suffix, or the empty list if no suffixes. The element value will be the
     * empty list if there is no type argument for a given suffix.
     */
    private final List<List<TypeArgument>> suffixTypeArguments;

    @Override
    void setScanResult(final ScanResult scanResult) {
        super.setScanResult(scanResult);
        if (typeArguments != null) {
            for (final TypeArgument typeArgument : typeArguments) {
                typeArgument.setScanResult(scanResult);
            }
        }
        if (suffixTypeArguments != null) {
            for (final List<TypeArgument> list : suffixTypeArguments) {
                for (final TypeArgument typeArgument : list) {
                    typeArgument.setScanResult(scanResult);
                }
            }
        }
    }

    /**
     * @param className
     *            The class name.
     * @param typeArguments
     *            The class type arguments.
     * @param suffixes
     *            The class suffixes (for inner classes)
     * @param suffixTypeArguments
     *            The suffix type arguments.
     */
    ClassRefTypeSignature(final String className, final List<TypeArgument> typeArguments,
            final List<String> suffixes, final List<List<TypeArgument>> suffixTypeArguments) {
        this.className = className;
        this.typeArguments = typeArguments;
        this.suffixes = suffixes;
        this.suffixTypeArguments = suffixTypeArguments;
    }

    /**
     * Get the name of the base class.
     * 
     * @return The name of the base class.
     */
    public String getBaseClassName() {
        return className;
    }

    /**
     * Get the name of the class, formed from the base name and any suffixes (suffixes are for inner class nesting,
     * and are separated by '$'), but without any type arguments. For example,
     * {@code "xyz.Cls<String>$InnerCls<Integer>"} is returned as {@code "xyz.Cls$InnerCls"}.
     * 
     * @return The fully-qualified name of the class, including suffixes but without type arguments.
     */
    public String getFullyQualifiedClassName() {
        if (fullyQualifiedClassName == null) {
            final StringBuilder buf = new StringBuilder();
            buf.append(className);
            for (int i = 0; i < suffixes.size(); i++) {
                buf.append('$');
                buf.append(suffixes.get(i));
            }
            fullyQualifiedClassName = buf.toString();
        }
        return fullyQualifiedClassName;
    }

    /**
     * Get the fully qualified class name (used by {@link #getClassInfo()} and {@link #loadClass()}.
     * 
     * @return The fully qualified name of the class.
     */
    @Override
    protected String getClassName() {
        return getFullyQualifiedClassName();
    }

    /**
     * Get the {@link ClassInfo} object for the referenced class, or null if the referenced class was not
     * encountered during scanning (i.e. no ClassInfo object was created for the class during scanning). N.B. even
     * if this method returns null, {@link #loadClass()} may be able to load the referenced class by name.
     * 
     * @return The {@link ClassInfo} object for the referenced class.
     */
    @Override
    public ClassInfo getClassInfo() {
        return super.getClassInfo();
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

    /**
     * Get any type arguments of the base class.
     * 
     * @return The type arguments for the base class.
     */
    public List<TypeArgument> getTypeArguments() {
        return typeArguments;
    }

    /**
     * Get any suffixes of the class (typically nested inner class names).
     * 
     * @return The class suffixes (for inner classes).
     */
    public List<String> getSuffixes() {
        return suffixes;
    }

    /**
     * Get any type arguments for any suffixes of the class, one list per suffix.
     * 
     * @return The type arguments for the inner classes, one list per suffix.
     */
    public List<List<TypeArgument>> getSuffixTypeArguments() {
        return suffixTypeArguments;
    }

    @Override
    public void getClassNamesFromTypeDescriptors(final Set<String> classNameListOut) {
        classNameListOut.add(className);
        classNameListOut.add(getFullyQualifiedClassName());
        for (final TypeArgument typeArgument : typeArguments) {
            typeArgument.getClassNamesFromTypeDescriptors(classNameListOut);
        }
    }

    @Override
    public int hashCode() {
        return className.hashCode() + 7 * typeArguments.hashCode() + 15 * suffixes.hashCode();
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof ClassRefTypeSignature)) {
            return false;
        }
        final ClassRefTypeSignature o = (ClassRefTypeSignature) obj;
        return o.className.equals(this.className) && o.typeArguments.equals(this.typeArguments)
                && o.suffixes.equals(this.suffixes);
    }

    @Override
    public boolean equalsIgnoringTypeParams(final TypeSignature other) {
        if (other instanceof TypeVariableSignature) {
            // Compare class type signature to type variable -- the logic for this
            // is implemented in TypeVariableSignature, and is not duplicated here
            return ((TypeVariableSignature) other).equalsIgnoringTypeParams(this);
        }
        if (!(other instanceof ClassRefTypeSignature)) {
            return false;
        }
        final ClassRefTypeSignature o = (ClassRefTypeSignature) other;
        if (o.suffixes.equals(this.suffixes)) {
            return o.className.equals(this.className);
        } else {
            return o.getFullyQualifiedClassName().equals(this.getFullyQualifiedClassName());
        }
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder();
        buf.append(className);
        if (!typeArguments.isEmpty()) {
            buf.append('<');
            for (int i = 0; i < typeArguments.size(); i++) {
                if (i > 0) {
                    buf.append(", ");
                }
                buf.append(typeArguments.get(i).toString());
            }
            buf.append('>');
        }
        for (int i = 0; i < suffixes.size(); i++) {
            // Use '$' before each suffix
            buf.append('$');
            buf.append(suffixes.get(i));
            final List<TypeArgument> suffixTypeArgs = suffixTypeArguments.get(i);
            if (!suffixTypeArgs.isEmpty()) {
                buf.append('<');
                for (int j = 0; j < suffixTypeArgs.size(); j++) {
                    if (j > 0) {
                        buf.append(", ");
                    }
                    buf.append(suffixTypeArgs.get(j).toString());
                }
                buf.append('>');
            }
        }
        return buf.toString();
    }

    /** Parse a class type signature. */
    static ClassRefTypeSignature parse(final Parser parser) throws ParseException {
        if (parser.peek() == 'L') {
            parser.next();
            if (!TypeUtils.getIdentifierToken(parser, /* separator = */ '/', /* separatorReplace = */ '.')) {
                throw new ParseException(parser, "Could not parse identifier token");
            }
            final String className = parser.currToken();
            final List<TypeArgument> typeArguments = TypeArgument.parseList(parser);
            List<String> suffixes;
            List<List<TypeArgument>> suffixTypeArguments;
            if (parser.peek() == '.') {
                suffixes = new ArrayList<>();
                suffixTypeArguments = new ArrayList<>();
                while (parser.peek() == '.') {
                    parser.expect('.');
                    if (!TypeUtils.getIdentifierToken(parser, /* separator = */ '/',
                            /* separatorReplace = */ '.')) {
                        throw new ParseException(parser, "Could not parse identifier token");
                    }
                    suffixes.add(parser.currToken());
                    suffixTypeArguments.add(TypeArgument.parseList(parser));
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