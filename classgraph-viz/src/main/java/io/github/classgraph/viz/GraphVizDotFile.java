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
package io.github.classgraph.viz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ArrayTypeSignature;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodParameterInfo;
import io.github.classgraph.MethodTypeSignature;
import io.github.classgraph.ReferenceTypeSignature;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeArgument;
import io.github.classgraph.TypeParameter;
import io.github.classgraph.TypeSignature;
import org.jspecify.annotations.Nullable;

/**
 * Renders the class graph found by ClassGraph as a <a href="https://graphviz.org/">GraphViz</a> {@code .dot} file,
 * which GraphViz can then lay out and turn into an image:
 *
 * <pre>
 * dot -Tsvg classgraph.dot &gt; classgraph.svg
 * </pre>
 *
 * <p>
 * There are two kinds of graph. {@link #generate(ScanResult, ClassInfoList, GraphVizDotFileOptions)} draws the
 * classes themselves, showing their fields, methods and annotations inside each class' box, and connecting each
 * class to its superclass and superinterfaces.
 * {@link #generateFromInterClassDependencies(ScanResult, ClassInfoList, GraphVizDotFileOptions)} draws only the
 * names of the classes, connected by "depends upon" edges.
 */
public final class GraphVizDotFile {
    /** The color for standard classes. */
    private static final String STANDARD_CLASS_COLOR = "fff2b6";

    /** The color for interfaces. */
    private static final String INTERFACE_COLOR = "b6e7ff";

    /** The color for annotations. */
    private static final String ANNOTATION_COLOR = "f3c9ff";

    /** The wrap width for method annotations and method parameters. */
    private static final int WRAP_WIDTH = 40;

    /**
     * The number of characters after which an annotation is cut short in a label. GraphViz rejects the whole file
     * if a label has a run of text of 16382 characters or more between two tags, and compilers write annotations
     * far longer than that, such as the {@code ScalaSignature} of a Scala class or the {@code Metadata} of a Kotlin
     * class. Even if every character is escaped as a six-character entity, a cut annotation stays near 1200
     * characters, and an annotation longer than this cannot be read in a graph anyway.
     */
    private static final int MAX_ANNOTATION_LENGTH = 200;

    /** Not instantiable. */
    private GraphVizDotFile() {
        // Empty
    }

    /**
     * Encode HTML-unsafe characters as HTML entities. Characters below U+0020 and Unicode whitespace, including
     * newlines, become a plain space, since a label is laid out by GraphViz as a single line of text unless it is
     * broken up by table cells. The exception is U+00A0 (no-break space), which is written as {@code &nbsp;}. The
     * other characters that XML 1.0 does not allow in text, which are lone surrogates, U+FFFE and U+FFFF, are
     * written as the text {@code \}{@code uXXXX}, since GraphViz parses a label as XML, and since a lone surrogate
     * cannot be written as UTF-8. A classfile name is modified UTF-8, which can hold any of these characters.
     *
     * @param unsafeStr
     *            The string to escape to make HTML-safe.
     * @param buf
     *            the buffer to append to
     */
    static void htmlEncode(final CharSequence unsafeStr, final StringBuilder buf) {
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final var c = unsafeStr.charAt(i);
            switch (c) {
            case '&' -> buf.append("&amp;");
            case '<' -> buf.append("&lt;");
            case '>' -> buf.append("&gt;");
            case '"' -> buf.append("&quot;");
            case '\'' -> buf.append("&#x27;"); // "&apos;" is not an HTML 4 entity
            // GraphViz resolves only the named entities it knows, and there is no name for a backslash --
            // "&lsol;" and "&bsol;" both make it report "undefined entity" and give up on the whole label
            case '\\' -> buf.append("&#x5C;");
            case '/' -> buf.append("&#x2F;"); // '/' can be a dangerous char if attr values are not quoted
            // Characters that some charsets cannot represent, written as their named entities
            case '—' -> buf.append("&mdash;");
            case '–' -> buf.append("&ndash;");
            case '“' -> buf.append("&ldquo;");
            case '”' -> buf.append("&rdquo;");
            case '‘' -> buf.append("&lsquo;");
            case '’' -> buf.append("&rsquo;");
            case '«' -> buf.append("&laquo;");
            case '»' -> buf.append("&raquo;");
            case '£' -> buf.append("&pound;");
            case '©' -> buf.append("&copy;");
            case '®' -> buf.append("&reg;");
            case (char) 0x00A0 -> buf.append("&nbsp;");
            default -> {
                if (Character.isHighSurrogate(c) && i + 1 < n
                        && Character.isLowSurrogate(unsafeStr.charAt(i + 1))) {
                    // A valid surrogate pair
                    buf.append(c).append(unsafeStr.charAt(++i));
                } else if (Character.isSurrogate(c) || c == 0xFFFE || c == 0xFFFF) {
                    // The backslash is written as an entity, as above
                    appendUnicodeEscape(c, "&#x5C;", buf);
                } else if (c <= ' ' || Character.isSpaceChar(c) || c == 0x0085 || c == 0x180E) {
                    // NEL (U+0085) is not a space separator, and U+180E has not been one since Unicode 6.3
                    buf.append(' ');
                } else {
                    buf.append(c);
                }
            }
            }
        }
    }

    /**
     * Append a space to the buffer, so that the item appended next is separated from the item before it. Nothing is
     * appended if there is no item before it in the same table cell or the same parameter list, i.e. if the buffer
     * ends with the tag that opened the cell or with the opening parenthesis of a parameter list, or if the buffer
     * already ends with a space.
     *
     * @param buf
     *            the buffer to append to
     */
    private static void appendSpaceIfNeeded(final StringBuilder buf) {
        if (!buf.isEmpty()) {
            final var lastChar = buf.charAt(buf.length() - 1);
            if (lastChar != ' ' && lastChar != '>' && lastChar != '(') {
                buf.append(' ');
            }
        }
    }

    /**
     * Get the string form of an annotation, using simple or fully qualified names according to the graph options.
     *
     * @param annotationInfo
     *            the annotation
     * @param options
     *            the graph options
     * @return the annotation, as it would be written in source code, cut short after {@link #MAX_ANNOTATION_LENGTH}
     *         characters
     */
    private static String annotationToString(final AnnotationInfo annotationInfo,
            final GraphVizDotFileOptions options) {
        return truncateAnnotation(
                options.useSimpleNames ? annotationInfo.toStringWithSimpleNames() : annotationInfo.toString());
    }

    /**
     * Cut an annotation short after {@link #MAX_ANNOTATION_LENGTH} characters, ending it with "...", unless that
     * would split a surrogate pair, in which case it is cut one character earlier.
     *
     * @param str
     *            the string form of the annotation
     * @return the string, cut short if it is longer than {@link #MAX_ANNOTATION_LENGTH} characters
     */
    static String truncateAnnotation(final String str) {
        if (str.length() <= MAX_ANNOTATION_LENGTH) {
            return str;
        }
        final var end = Character.isHighSurrogate(str.charAt(MAX_ANNOTATION_LENGTH - 1)) ? MAX_ANNOTATION_LENGTH - 1
                : MAX_ANNOTATION_LENGTH;
        // ASCII dots rather than U+2026, which some charsets cannot represent
        return str.substring(0, end) + "...";
    }

    /**
     * Get the annotations directly present on an annotated element, in sorted order, or the empty list if
     * {@link ClassGraph#enableAnnotationInfo()} was not called before scanning.
     *
     * @param scanResult
     *            the scan result the annotated element came from
     * @param allAnnotationInfo
     *            a supplier of the annotations and meta-annotations on the annotated element (called only if
     *            annotation info is available)
     * @return the directly present annotations
     */
    private static List<AnnotationInfo> directAnnotations(final ScanResult scanResult,
            final Supplier<AnnotationInfoList> allAnnotationInfo) {
        return scanResult.isAnnotationInfoEnabled() ? allAnnotationInfo.get().directOnly() : List.of();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a color that matches the background color of a class node's box, but is darker, for use as the background
     * of the section headers within the box.
     *
     * @param boxBgColor
     *            the box background color, as six hex digits
     * @return the darker color, as a Graphviz color literal
     */
    private static String darkerColor(final String boxBgColor) {
        final var darkness = 0.8f;
        final var r = (int) (Integer.parseInt(boxBgColor.substring(0, 2), 16) * darkness);
        final var g = (int) (Integer.parseInt(boxBgColor.substring(2, 4), 16) * darkness);
        final var b = (int) (Integer.parseInt(boxBgColor.substring(4, 6), 16) * darkness);
        return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
    }

    /**
     * Append the start of the HTML label of a class node: the shape and color of the node's box, and the class'
     * modifiers and kind, its package name, and its name relative to the package, followed by the given type
     * parameters.
     *
     * @param ci
     *            the class info
     * @param shape
     *            the shape to use
     * @param boxBgColor
     *            the box background color
     * @param packageNameSuffix
     *            the text to append after the package name, i.e. "." to run the package name into the class name on
     *            the line below it, or "" to show the package name on its own
     * @param typeParameters
     *            the type parameters to show after the class name, such as {@code <E extends Entity>}, or ""
     * @param buf
     *            the buffer to append to
     */
    private static void appendClassNodeLabelHeader(final ClassInfo ci, final String shape, final String boxBgColor,
            final String packageNameSuffix, final String typeParameters, final StringBuilder buf) {
        buf.append("[shape=").append(shape).append(",style=filled,fillcolor=\"#").append(boxBgColor)
                .append("\",label=");
        buf.append('<');
        buf.append("<table border='0' cellborder='0' cellspacing='1'>");

        // Class modifiers and kind. The classfile of a placeholder class was never read, so it is only known to be
        // an annotation, interface or enum if a scanned class referred to it as one; otherwise its kind is unknown,
        // rather than "class".
        final var modifiersAndKind = new StringBuilder(ci.getModifiersString());
        final var kind = ci.isAnnotation() ? "@interface"
                : ci.isInterface() ? "interface"
                        : ci.isEnum() ? "enum" : ci.isRecord() ? "record" : ci.getResource() != null ? "class" : "";
        if (!kind.isEmpty()) {
            if (!modifiersAndKind.isEmpty()) {
                modifiersAndKind.append(' ');
            }
            modifiersAndKind.append(kind);
        }
        // GraphViz rejects an empty <font> element
        if (!modifiersAndKind.isEmpty()) {
            buf.append("<tr><td><font point-size='12'>").append(modifiersAndKind).append("</font></td></tr>");
        }

        // Package name
        final var packageName = ci.getPackageName();
        if (!packageName.isEmpty()) {
            buf.append("<tr><td><font point-size='14'><b>");
            htmlEncode(packageName + packageNameSuffix, buf);
            buf.append("</b></font></td></tr>");
        }

        // Class name, relative to the package, so that two nested classes with the same simple name get different
        // labels, followed by the type parameters of the class
        buf.append("<tr><td><font point-size='20'><b>");
        htmlEncode((packageName.isEmpty() ? ci.getName() : ci.getName().substring(packageName.length() + 1))
                + typeParameters, buf);
        buf.append("</b></font></td></tr>");
    }

    /**
     * Get the type parameters of a class or method as they are declared, such as
     * {@code <K, V extends Comparable<V>>}.
     *
     * @param typeParameters
     *            the type parameters
     * @param useSimpleNames
     *            whether to show the simple names of the classes in the bounds, rather than their fully qualified
     *            names
     * @return the type parameters in angle brackets, or "" if there are none
     */
    private static String typeParametersToString(final List<TypeParameter> typeParameters,
            final boolean useSimpleNames) {
        if (typeParameters.isEmpty()) {
            return "";
        }
        final var buf = new StringBuilder("<");
        for (var i = 0; i < typeParameters.size(); i++) {
            if (i > 0) {
                buf.append(", ");
            }
            final var typeParameter = typeParameters.get(i);
            buf.append(useSimpleNames ? typeParameter.toStringWithSimpleNames() : typeParameter.toString());
        }
        return buf.append('>').toString();
    }

    /**
     * Get the annotations to list for a class, in sorted order.
     *
     * @param annotationInfo
     *            the annotations on the class
     * @return the annotations to list
     */
    private static List<AnnotationInfo> annotationsToShow(final List<AnnotationInfo> annotationInfo) {
        // Meta-annotations are not listed, so the annotations are filtered before the section header is written --
        // otherwise an annotation class, whose only annotations are meta-annotations, would get a section header
        // with nothing under it
        final List<AnnotationInfo> annotationInfoSorted = new ArrayList<>(annotationInfo.size());
        for (final AnnotationInfo ai : annotationInfo) {
            if (!ai.getName().startsWith("java.lang.annotation.")) {
                annotationInfoSorted.add(ai);
            }
        }
        annotationInfoSorted.sort(null);
        return annotationInfoSorted;
    }

    /**
     * Append the ANNOTATIONS section of a class node, if the class has any annotations to list.
     *
     * @param annotationInfo
     *            the annotations on the class
     * @param options
     *            the graph options
     * @param darkerColor
     *            the background color of the section header
     * @param buf
     *            the buffer to append to
     */
    private static void appendClassAnnotations(final List<AnnotationInfo> annotationInfo,
            final GraphVizDotFileOptions options, final String darkerColor, final StringBuilder buf) {
        final var annotationInfoSorted = annotationsToShow(annotationInfo);
        if (annotationInfoSorted.isEmpty()) {
            return;
        }
        buf.append("<tr><td colspan='3' bgcolor='").append(darkerColor)
                .append("'><font point-size='12'><b>ANNOTATIONS</b></font></td></tr>");
        for (final AnnotationInfo ai : annotationInfoSorted) {
            buf.append("<tr>");
            buf.append("<td align='center' valign='top'>");
            htmlEncode(annotationToString(ai, options), buf);
            buf.append("</td></tr>");
        }
    }

    /**
     * Get the fields to list for a class, in sorted order.
     *
     * @param fieldInfo
     *            the fields of the class
     * @return the fields to list
     */
    private static List<FieldInfo> fieldsToShow(final List<FieldInfo> fieldInfo) {
        final List<FieldInfo> fieldInfoSorted = new ArrayList<>(fieldInfo);
        fieldInfoSorted.sort(null);
        for (var i = fieldInfoSorted.size() - 1; i >= 0; --i) {
            // Remove the serialVersionUID field, and fields that the compiler generated rather than the source code
            // declared, such as this$0, $VALUES and $assertionsDisabled
            final var fi = fieldInfoSorted.get(i);
            if ("serialVersionUID".equals(fi.getName()) || fi.isSynthetic()) {
                fieldInfoSorted.remove(i);
            }
        }
        return fieldInfoSorted;
    }

    /**
     * Append the FIELDS section of a class node, if the class has any fields to list.
     *
     * @param scanResult
     *            the scan result the class came from
     * @param fieldInfo
     *            the fields of the class
     * @param options
     *            the graph options
     * @param darkerColor
     *            the background color of the section header
     * @param buf
     *            the buffer to append to
     */
    private static void appendFields(final ScanResult scanResult, final List<FieldInfo> fieldInfo,
            final GraphVizDotFileOptions options, final String darkerColor, final StringBuilder buf) {
        final var fieldInfoSorted = fieldsToShow(fieldInfo);
        if (fieldInfoSorted.isEmpty()) {
            return;
        }
        final var ignoreFieldVisibility = scanResult.isFieldVisibilityIgnored();
        buf.append("<tr><td colspan='3' bgcolor='").append(darkerColor).append("'><font point-size='12'><b>")
                .append(ignoreFieldVisibility ? "" : "PUBLIC ").append("FIELDS</b></font></td></tr>");
        buf.append("<tr><td cellpadding='0'>");
        buf.append("<table border='0' cellborder='0'>");
        for (final FieldInfo fi : fieldInfoSorted) {
            buf.append("<tr>");
            buf.append("<td align='right' valign='top'>");

            // Field annotations
            final var fieldAnnotations = directAnnotations(scanResult, fi::getAllAnnotationInfo);
            if (options.showAnnotations) {
                for (final AnnotationInfo ai : fieldAnnotations) {
                    appendSpaceIfNeeded(buf);
                    htmlEncode(annotationToString(ai, options), buf);
                }
            }

            // Field modifiers
            if (ignoreFieldVisibility) {
                appendSpaceIfNeeded(buf);
                buf.append(fi.getModifiersString());
            }

            // Field type -- without any field annotation that the compiler also recorded as a type annotation, which
            // is either shown above or hidden with the other field annotations
            TypeSignature typeSig = null;
            try {
                typeSig = fi.getTypeSignatureOrTypeDescriptor();
            } catch (final IllegalArgumentException _) {
                // Only a classfile that the JVM rejects has a type descriptor that does not parse. A field with no
                // type signature then has no type to show
            }
            if (typeSig != null) {
                appendSpaceIfNeeded(buf);
                htmlEncode(typeSig.toString(options.useSimpleNames, fieldAnnotations), buf);
            }
            buf.append("</td>");

            // Field name
            buf.append("<td align='left' valign='top'><b>");
            htmlEncode(fi.getName(), buf);
            buf.append("</b></td></tr>");
        }
        buf.append("</table>");
        buf.append("</td></tr>");
    }

    /**
     * Get the methods to list for a class, in sorted order.
     *
     * @param methodInfo
     *            the methods of the class
     * @return the methods to list
     */
    private static List<MethodInfo> methodsToShow(final List<MethodInfo> methodInfo) {
        final List<MethodInfo> methodInfoSorted = new ArrayList<>(methodInfo);
        methodInfoSorted.sort(null);
        for (var i = methodInfoSorted.size() - 1; i >= 0; --i) {
            // Don't list static initializer blocks, methods of Object, or methods that the compiler generated rather
            // than the source code declared, such as lambda bodies, an enum's $values() and bridge methods (the
            // methods an enum must have, values() and valueOf(String), are not marked synthetic, so are listed)
            final var mi = methodInfoSorted.get(i);
            final var name = mi.getName();
            final var numParam = mi.getParameterInfo().size();
            if (mi.isSynthetic() || mi.isBridge() || "<clinit>".equals(name)
                    || "hashCode".equals(name) && numParam == 0 || "toString".equals(name) && numParam == 0
                    || "equals".equals(name) && numParam == 1
                            && "(Ljava/lang/Object;)Z".equals(mi.getTypeDescriptorString())) {
                methodInfoSorted.remove(i);
            }
        }
        return methodInfoSorted;
    }

    /**
     * Get the parameters to list for a method: all but those that the compiler generated rather than the source
     * code declared, such as the name and ordinal parameters of an enum constructor. A parameter is known to be
     * generated only if the classfile records the flags of its parameters. javac 21 and later record them for any
     * method with a generated parameter; earlier versions of javac record them only when run with
     * {@code -parameters}.
     *
     * @param methodInfo
     *            the method
     * @return the parameters to list
     */
    private static List<MethodParameterInfo> parametersToShow(final MethodInfo methodInfo) {
        final List<MethodParameterInfo> paramInfo = new ArrayList<>(methodInfo.getParameterInfo());
        paramInfo.removeIf(MethodParameterInfo::isSynthetic);
        return paramInfo;
    }

    /**
     * Append the annotations of a method, wrapping onto a new row of the enclosing table once a row of annotations
     * gets too long.
     *
     * @param annotationInfo
     *            the annotations on the method
     * @param options
     *            the graph options
     * @param buf
     *            the buffer to append to
     */
    private static void appendMethodAnnotations(final List<AnnotationInfo> annotationInfo,
            final GraphVizDotFileOptions options, final StringBuilder buf) {
        var wrapPos = 0;
        for (final AnnotationInfo ai : annotationInfo) {
            final var ais = annotationToString(ai, options);
            if (wrapPos > WRAP_WIDTH) {
                // Continue the annotations in the same column of a new row, leaving the method name and parameter
                // columns of that row empty
                buf.append("</td><td></td><td></td></tr><tr><td align='right' valign='top'>");
                wrapPos = 0;
            } else {
                appendSpaceIfNeeded(buf);
                wrapPos++;
            }
            htmlEncode(ais, buf);
            wrapPos += ais.length();
        }
    }

    /**
     * Append the parameters of a method, wrapping onto a new row of the enclosing table once a row of parameters
     * gets too long.
     *
     * @param scanResult
     *            the scan result the method came from
     * @param paramInfo
     *            the parameters of the method
     * @param options
     *            the graph options
     * @param buf
     *            the buffer to append to
     */
    private static void appendMethodParameters(final ScanResult scanResult,
            final List<MethodParameterInfo> paramInfo, final GraphVizDotFileOptions options,
            final StringBuilder buf) {
        for (int i = 0, wrapPos = 0; i < paramInfo.size(); i++) {
            if (i > 0) {
                buf.append(", ");
                wrapPos += 2;
            }
            if (wrapPos > WRAP_WIDTH) {
                buf.append("</td></tr><tr><td></td><td></td><td align='left' valign='top'>");
                wrapPos = 0;
            }

            // Parameter annotations -- a row is wrapped before an annotation rather than after it, so that the last
            // annotation of a parameter stays on the same row as the parameter's type
            final var param = paramInfo.get(i);
            final var paramAnnotations = directAnnotations(scanResult, param::getAllAnnotationInfo);
            if (options.showAnnotations) {
                for (final AnnotationInfo ai : paramAnnotations) {
                    if (wrapPos > WRAP_WIDTH) {
                        buf.append("</td></tr><tr><td></td><td></td><td align='left' valign='top'>");
                        wrapPos = 0;
                    }
                    final var ais = annotationToString(ai, options);
                    appendSpaceIfNeeded(buf);
                    htmlEncode(ais, buf);
                    wrapPos += 1 + ais.length();
                }
            }

            // Parameter type -- without the parameter annotations, as for a field type. A parameter has no type if
            // the classfile lists more parameters in its parameter names or parameter annotations than in the type
            // descriptor of the method.
            final var paramTypeSig = param.getTypeSignatureOrTypeDescriptor();
            if (paramTypeSig != null) {
                var paramTypeStr = paramTypeSig.toString(options.useSimpleNames, paramAnnotations);
                // The variadic parameter of a variadic method has an array type, but is shown as "T..." not "T[]".
                // The rendering of an array type always ends in "[]", since an array has at least one dimension.
                if (param.isVarArgs() && paramTypeSig instanceof ArrayTypeSignature) {
                    paramTypeStr = paramTypeStr.substring(0, paramTypeStr.length() - 2) + "...";
                }
                appendSpaceIfNeeded(buf);
                htmlEncode(paramTypeStr, buf);
                wrapPos += paramTypeStr.length();
            }

            // Parameter name
            final var paramName = param.getName();
            if (paramName != null) {
                appendSpaceIfNeeded(buf);
                buf.append("<b>");
                htmlEncode(paramName, buf);
                wrapPos += 1 + paramName.length();
                buf.append("</b>");
            }
        }
    }

    /**
     * Append the METHODS section of a class node, if the class has any methods to list.
     *
     * @param scanResult
     *            the scan result the class came from
     * @param ci
     *            the class info
     * @param methodInfo
     *            the methods of the class
     * @param options
     *            the graph options
     * @param darkerColor
     *            the background color of the section header
     * @param buf
     *            the buffer to append to
     */
    private static void appendMethods(final ScanResult scanResult, final ClassInfo ci,
            final List<MethodInfo> methodInfo, final GraphVizDotFileOptions options, final String darkerColor,
            final StringBuilder buf) {
        final var methodInfoSorted = methodsToShow(methodInfo);
        if (methodInfoSorted.isEmpty()) {
            return;
        }
        final var ignoreMethodVisibility = scanResult.isMethodVisibilityIgnored();
        buf.append("<tr><td colspan='3' bgcolor='").append(darkerColor).append("'><font point-size='12'><b>")
                .append(ignoreMethodVisibility ? "" : "PUBLIC ").append("METHODS</b></font></td></tr>");
        buf.append("<tr><td cellpadding='0'>");
        buf.append("<table border='0' cellborder='0'>");
        for (final MethodInfo mi : methodInfoSorted) {
            final var isConstructor = "<init>".equals(mi.getName());
            buf.append("<tr>");

            // Method annotations
            buf.append("<td align='right' valign='top'>");
            final var methodAnnotations = directAnnotations(scanResult, mi::getAllAnnotationInfo);
            if (options.showAnnotations) {
                appendMethodAnnotations(methodAnnotations, options, buf);
            }

            // Method modifiers
            if (ignoreMethodVisibility) {
                appendSpaceIfNeeded(buf);
                buf.append(mi.getModifiersString());
            }

            MethodTypeSignature methodType = null;
            try {
                methodType = mi.getTypeSignatureOrTypeDescriptor();
            } catch (final IllegalArgumentException _) {
                // Only a classfile that the JVM rejects has a type descriptor that does not parse. A method with no
                // type signature then has no type parameters or return type to show
            }

            // Method type parameters -- a constructor can have them too
            final var typeParametersStr = typeParametersToString(
                    methodType == null ? List.of() : methodType.getTypeParameters(), options.useSimpleNames);
            if (!typeParametersStr.isEmpty()) {
                appendSpaceIfNeeded(buf);
                htmlEncode(typeParametersStr, buf);
            }

            // Method return type -- constructors have none
            if (isConstructor) {
                appendSpaceIfNeeded(buf);
                buf.append("<b>&lt;constructor&gt;</b>");
            } else if (methodType != null) {
                // Without the method annotations, as for a field type
                appendSpaceIfNeeded(buf);
                final var resultTypeSig = methodType.getResultType();
                htmlEncode(resultTypeSig.toString(options.useSimpleNames, methodAnnotations), buf);
            }
            buf.append("</td>");

            // Method name -- constructors are named after their class
            buf.append("<td align='left' valign='top'>");
            buf.append("<b>");
            htmlEncode(isConstructor ? ci.getSimpleName() : mi.getName(), buf);
            buf.append("</b>&nbsp;");
            buf.append("</td>");

            // Method parameters
            buf.append("<td align='left' valign='top'>");
            buf.append('(');
            appendMethodParameters(scanResult, parametersToShow(mi), options, buf);
            buf.append(')');
            buf.append("</td></tr>");
        }
        buf.append("</table>");
        buf.append("</td></tr>");
    }

    /**
     * Produce HTML label for class node.
     *
     * @param scanResult
     *            the scan result the class came from
     * @param ci
     *            the class info
     * @param shape
     *            the shape to use
     * @param boxBgColor
     *            the box background color
     * @param options
     *            the graph options
     * @param buf
     *            the buffer to append to
     */
    private static void labelClassNodeHTML(final ScanResult scanResult, final ClassInfo ci, final String shape,
            final String boxBgColor, final GraphVizDotFileOptions options, final StringBuilder buf) {
        appendClassNodeLabelHeader(ci, shape, boxBgColor, /* packageNameSuffix = */ ".", typeParametersToString(
                ci.getTypeSignatureOrTypeDescriptor().getTypeParameters(), options.useSimpleNames), buf);

        final var darkerColor = darkerColor(boxBgColor);

        if (options.showAnnotations && scanResult.isAnnotationInfoEnabled()) {
            appendClassAnnotations(ci.getAllAnnotationInfo().directOnly(), options, darkerColor, buf);
        }

        if (options.showFields && scanResult.isFieldInfoEnabled()) {
            appendFields(scanResult, ci.getDeclaredFieldInfo(), options, darkerColor, buf);
        }

        if (options.showMethods && scanResult.isMethodInfoEnabled()) {
            appendMethods(scanResult, ci, ci.getDeclaredMethodAndConstructorInfo(), options, darkerColor, buf);
        }

        buf.append("</table>");
        buf.append(">]");
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Append the header of a .dot file: the layout size of the graph, and the layout, direction and font settings
     * that every graph shares.
     *
     * @param options
     *            the graph options
     * @param buf
     *            the buffer to append to
     */
    private static void appendDotFileHeader(final GraphVizDotFileOptions options, final StringBuilder buf) {
        buf.append("digraph {\n");
        buf.append("size=\"").append(options.sizeX).append(',').append(options.sizeY).append("\";\n");
        buf.append("layout=dot;\n");
        buf.append("rankdir=\"BT\";\n");
        buf.append("overlap=false;\n");
        buf.append("splines=true;\n");
        buf.append("pack=true;\n");
        buf.append("graph [fontname = \"Courier, Regular\"]\n");
        buf.append("node [fontname = \"Courier, Regular\"]\n");
        buf.append("edge [fontname = \"Courier, Regular\"]\n");
    }

    /**
     * Append a quoted GraphViz identifier, escaping characters that would terminate or alter the identifier.
     *
     * @param identifier
     *            The identifier to quote and escape.
     * @param buf
     *            The destination buffer.
     */
    static void appendQuotedIdentifier(final CharSequence identifier, final StringBuilder buf) {
        buf.append('"');
        for (int i = 0, n = identifier.length(); i < n; i++) {
            final char c = identifier.charAt(i);
            switch (c) {
            case '"' -> buf.append("\\\"");
            case '\\' -> buf.append("\\\\");
            case '\n' -> buf.append("\\n");
            case '\r' -> buf.append("\\r");
            case '\t' -> buf.append("\\t");
            default -> {
                if (Character.isHighSurrogate(c) && i + 1 < n
                        && Character.isLowSurrogate(identifier.charAt(i + 1))) {
                    // A valid surrogate pair
                    buf.append(c).append(identifier.charAt(++i));
                } else if (c < 0x20 || c == 0x7f || Character.isSurrogate(c)) {
                    // DOT does not define a portable escape for every control character, and a lone surrogate
                    // cannot be written as UTF-8. Encode these into a collision-free printable form (a backslash in
                    // the identifier is doubled above); node identifiers are internal and need not equal the label.
                    appendUnicodeEscape(c, "\\", buf);
                } else {
                    buf.append(c);
                }
            }
            }
        }
        buf.append('"');
    }

    /**
     * Append a character as the text of a Java Unicode escape, i.e. a backslash, the letter {@code u} and four
     * lowercase hex digits.
     *
     * @param c
     *            the character.
     * @param backslash
     *            the text to write for the backslash.
     * @param buf
     *            the buffer to append to.
     */
    private static void appendUnicodeEscape(final char c, final String backslash, final StringBuilder buf) {
        final String hex = Integer.toHexString(c);
        buf.append(backslash).append('u').append("0".repeat(4 - hex.length())).append(hex);
    }

    /**
     * Append an edge between two class nodes.
     *
     * @param fromClassName
     *            the name of the class the edge starts at
     * @param toClassName
     *            the name of the class the edge ends at
     * @param attributes
     *            the Graphviz attributes of the edge, i.e. the shape and size of its arrowhead
     * @param buf
     *            the buffer to append to
     */
    private static void appendEdge(final String fromClassName, final String toClassName, final String attributes,
            final StringBuilder buf) {
        buf.append("  ");
        appendQuotedIdentifier(fromClassName, buf);
        buf.append(" -> ");
        appendQuotedIdentifier(toClassName, buf);
        buf.append(' ').append(attributes).append('\n');
    }

    /**
     * Append a class node for each class in a list.
     *
     * @param scanResult
     *            the scan result the classes came from
     * @param classNodes
     *            the classes to append nodes for
     * @param shape
     *            the shape to use
     * @param boxBgColor
     *            the box background color
     * @param options
     *            the graph options
     * @param buf
     *            the buffer to append to
     */
    private static void appendClassNodes(final ScanResult scanResult, final ClassInfoList classNodes,
            final String shape, final String boxBgColor, final GraphVizDotFileOptions options,
            final StringBuilder buf) {
        for (final ClassInfo node : classNodes) {
            appendQuotedIdentifier(node.getName(), buf);
            labelClassNodeHTML(scanResult, node, shape, boxBgColor, options, buf);
            buf.append(";\n");
        }
    }

    /**
     * Append the edges from a class to its direct superclass and to its direct superinterfaces, i.e. the interfaces
     * that a class implements, or that an interface extends.
     *
     * @param classNode
     *            the class to append edges for
     * @param allVisibleNodes
     *            the names of the classes that have a node in the graph
     * @param buf
     *            the buffer to append to
     */
    private static void appendSupertypeEdges(final ClassInfo classNode, final Set<String> allVisibleNodes,
            final StringBuilder buf) {
        for (final ClassInfo directSuperclassNode : classNode.getAllSuperclasses().directOnly()) {
            // java.lang.Object is skipped: every class descends from it, and it is the recorded superclass of every
            // interface and annotation
            if (allVisibleNodes.contains(directSuperclassNode.getName())
                    && !"java.lang.Object".equals(directSuperclassNode.getName())) {
                // class --> superclass
                appendEdge(classNode.getName(), directSuperclassNode.getName(), "[arrowsize=2.5]", buf);
            }
        }

        for (final ClassInfo superinterfaceNode : classNode.getDirectSuperinterfaces()) {
            if (allVisibleNodes.contains(superinterfaceNode.getName())) {
                // class --<> superinterface
                appendEdge(classNode.getName(), superinterfaceNode.getName(), "[arrowhead=diamond, arrowsize=2.5]",
                        buf);
            }
        }
    }

    /**
     * Add the names of the classes in a type signature to a set: the class of a class reference type, the classes
     * in its type arguments (including the type arguments of an enclosing class, as in {@code Outer<A>.Inner<B>}),
     * and the classes in the element type of an array type. A base type or a type variable names no class.
     *
     * @param typeSignature
     *            the type signature, or null if there is none
     * @param classNames
     *            the set to add the class names to
     */
    private static void addClassNames(final @Nullable TypeSignature typeSignature, final Set<String> classNames) {
        if (typeSignature instanceof final ClassRefTypeSignature classRef) {
            classNames.add(classRef.getFullyQualifiedClassName());
            for (final TypeArgument typeArgument : classRef.getTypeArguments()) {
                addClassNames(typeArgument.getTypeSignature(), classNames);
            }
            for (final List<TypeArgument> suffixTypeArguments : classRef.getSuffixTypeArguments()) {
                for (final TypeArgument typeArgument : suffixTypeArguments) {
                    addClassNames(typeArgument.getTypeSignature(), classNames);
                }
            }
        } else if (typeSignature instanceof final ArrayTypeSignature array) {
            addClassNames(array.getElementTypeSignature(), classNames);
        }
    }

    /**
     * Append one edge from each of the types of a class' members to the class. The class itself, and any type that
     * has no node in the graph, are skipped.
     *
     * @param classNode
     *            the class to append edges for
     * @param typeNames
     *            the names of the classes in the types of the class' members
     * @param allVisibleNodes
     *            the names of the classes that have a node in the graph
     * @param attributes
     *            the Graphviz attributes of the edges
     * @param buf
     *            the buffer to append to
     */
    private static void appendTypeEdges(final ClassInfo classNode, final Set<String> typeNames,
            final Set<String> allVisibleNodes, final String attributes, final StringBuilder buf) {
        for (final String typeName : typeNames) {
            if (!typeName.equals(classNode.getName()) && allVisibleNodes.contains(typeName)) {
                appendEdge(typeName, classNode.getName(), attributes, buf);
            }
        }
    }

    /**
     * Append the edges from the types of a class' fields and methods to the class: one edge for each class named in
     * the type of a field, and one for each class named in the return type, parameter types or type parameter
     * bounds of a method or constructor, however many members name it. The annotations on the members and the
     * exceptions that methods throw are not types of the members, so they get no edge here.
     *
     * <p>
     * Type variables are not resolved, but both the type signature and the type descriptor of a member are read, so
     * a member whose type is a type variable is joined to the erasure of the type variable. A type descriptor that
     * does not parse is skipped.
     *
     * @param scanResult
     *            the scan result the class came from
     * @param classNode
     *            the class to append edges for
     * @param allVisibleNodes
     *            the names of the classes that have a node in the graph
     * @param options
     *            the graph options
     * @param buf
     *            the buffer to append to
     */
    private static void appendMemberTypeEdges(final ScanResult scanResult, final ClassInfo classNode,
            final Set<String> allVisibleNodes, final GraphVizDotFileOptions options, final StringBuilder buf) {
        if (options.showFieldTypeDependencyEdges && scanResult.isFieldInfoEnabled()) {
            final Set<String> fieldTypeNames = new TreeSet<>();
            for (final FieldInfo fi : fieldsToShow(classNode.getDeclaredFieldInfo())) {
                try {
                    addClassNames(fi.getTypeSignatureOrTypeDescriptor(), fieldTypeNames);
                    addClassNames(fi.getTypeDescriptor(), fieldTypeNames);
                } catch (final IllegalArgumentException _) {
                    // Only a classfile that the JVM rejects has a type descriptor that does not parse. The classes
                    // in the type signature, if the field has one, have been added already
                }
            }
            // class --[ ] field type (open box)
            appendTypeEdges(classNode, fieldTypeNames, allVisibleNodes, "[arrowtail=obox, arrowsize=2.5, dir=back]",
                    buf);
        }

        if (options.showMethodTypeDependencyEdges && scanResult.isMethodInfoEnabled()) {
            final Set<String> methodTypeNames = new TreeSet<>();
            for (final MethodInfo mi : methodsToShow(classNode.getDeclaredMethodAndConstructorInfo())) {
                for (final MethodParameterInfo mpi : parametersToShow(mi)) {
                    addClassNames(mpi.getTypeSignature(), methodTypeNames);
                    addClassNames(mpi.getTypeDescriptor(), methodTypeNames);
                }
                try {
                    final var methodType = mi.getTypeSignatureOrTypeDescriptor();
                    for (final TypeParameter typeParameter : methodType.getTypeParameters()) {
                        addClassNames(typeParameter.getClassBound(), methodTypeNames);
                        for (final ReferenceTypeSignature interfaceBound : typeParameter.getInterfaceBounds()) {
                            addClassNames(interfaceBound, methodTypeNames);
                        }
                    }
                    addClassNames(methodType.getResultType(), methodTypeNames);
                    addClassNames(mi.getTypeDescriptor().getResultType(), methodTypeNames);
                } catch (final IllegalArgumentException _) {
                    // Only a classfile that the JVM rejects has a type descriptor that does not parse. The classes
                    // in the type signature, if the method has one, have been added already
                }
            }
            // class --[#] method type (filled box)
            appendTypeEdges(classNode, methodTypeNames, allVisibleNodes, "[arrowtail=box, arrowsize=2.5, dir=back]",
                    buf);
        }
    }

    /**
     * Index the classes that are directly annotated by each annotation, or that have a field, method or method
     * parameter directly annotated by it, so that the annotation edges can be drawn without having to ask each
     * annotation which classes it annotates (which would also return the classes reached through a meta-annotation,
     * or through {@link java.lang.annotation.Inherited @Inherited} -- those classes are still reachable from the
     * annotation along a path of edges in the graph). As for the type edges, only the fields, methods and method
     * parameters that are listed in a class node are read: an annotation on {@code toString()} or
     * {@code serialVersionUID}, for example, does not join the class to the annotation.
     *
     * @param scanResult
     *            the scan result the classes came from
     * @param allVisibleClasses
     *            the classes that have a node in the graph
     * @param classAnnotations
     *            populated with annotation name to the classes it directly annotates
     * @param memberAnnotations
     *            populated with annotation name to the classes with a listed field, method or method parameter it
     *            directly annotates, listing each class once however many of its members the annotation is on
     */
    private static void indexAnnotations(final ScanResult scanResult, final List<ClassInfo> allVisibleClasses,
            final Map<String, List<ClassInfo>> classAnnotations,
            final Map<String, List<ClassInfo>> memberAnnotations) {
        if (!scanResult.isAnnotationInfoEnabled()) {
            return;
        }
        for (final ClassInfo ci : allVisibleClasses) {
            for (final AnnotationInfo ai : ci.getAllAnnotationInfo().directOnly()) {
                classAnnotations.computeIfAbsent(ai.getName(), _ -> new ArrayList<>()).add(ci);
            }
            final Set<String> memberAnnotationNames = new HashSet<>();
            if (scanResult.isFieldInfoEnabled()) {
                for (final FieldInfo fi : fieldsToShow(ci.getDeclaredFieldInfo())) {
                    for (final AnnotationInfo ai : fi.getAllAnnotationInfo().directOnly()) {
                        memberAnnotationNames.add(ai.getName());
                    }
                }
            }
            if (scanResult.isMethodInfoEnabled()) {
                for (final MethodInfo mi : methodsToShow(ci.getDeclaredMethodAndConstructorInfo())) {
                    for (final AnnotationInfo ai : mi.getAllAnnotationInfo().directOnly()) {
                        memberAnnotationNames.add(ai.getName());
                    }
                    for (final MethodParameterInfo mpi : parametersToShow(mi)) {
                        for (final AnnotationInfo ai : mpi.getAllAnnotationInfo().directOnly()) {
                            memberAnnotationNames.add(ai.getName());
                        }
                    }
                }
            }
            for (final String name : memberAnnotationNames) {
                memberAnnotations.computeIfAbsent(name, _ -> new ArrayList<>()).add(ci);
            }
        }
        for (final List<ClassInfo> classes : classAnnotations.values()) {
            classes.sort(null);
        }
        for (final List<ClassInfo> classes : memberAnnotations.values()) {
            classes.sort(null);
        }
    }

    /**
     * Append the edges from the classes an annotation annotates to the annotation: one edge for each class it
     * annotates directly, and one for each class with a listed field, method or method parameter it annotates.
     *
     * @param annotationNode
     *            the annotation to append edges for
     * @param classAnnotations
     *            annotation name to the classes it directly annotates
     * @param memberAnnotations
     *            annotation name to the classes with a field, method or method parameter it directly annotates
     * @param buf
     *            the buffer to append to
     */
    private static void appendAnnotationEdges(final ClassInfo annotationNode,
            final Map<String, List<ClassInfo>> classAnnotations,
            final Map<String, List<ClassInfo>> memberAnnotations, final StringBuilder buf) {
        final var annotationName = annotationNode.getName();
        for (final ClassInfo annotatedClassNode : classAnnotations.getOrDefault(annotationName, List.of())) {
            // annotated class --o annotation
            appendEdge(annotatedClassNode.getName(), annotationName, "[arrowhead=dot, arrowsize=2.5]", buf);
        }
        for (final ClassInfo classWithMemberAnnotationNode : memberAnnotations.getOrDefault(annotationName,
                List.of())) {
            // class with annotated field, method or method parameter --o annotation
            appendEdge(classWithMemberAnnotationNode.getName(), annotationName, "[arrowhead=odot, arrowsize=2.5]",
                    buf);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph.
     *
     * <p>
     * To show non-public classes, call {@link ClassGraph#ignoreClassVisibility()} before scanning.
     *
     * <p>
     * To show fields, call {@link ClassGraph#enableFieldInfo()} before scanning. To show non-public fields, also
     * call {@link ClassGraph#ignoreFieldVisibility()} before scanning.
     *
     * <p>
     * To show methods, call {@link ClassGraph#enableMethodInfo()} before scanning. To show non-public methods, also
     * call {@link ClassGraph#ignoreMethodVisibility()} before scanning.
     *
     * <p>
     * To show annotations, call {@link ClassGraph#enableAnnotationInfo()} before scanning. An annotation class that
     * is not public is drawn as a node of its own only if {@link ClassGraph#ignoreClassVisibility()} was also
     * called, as for any other class.
     *
     * <p>
     * Classes, fields, methods and method parameters that the compiler generated, rather than ones declared in the
     * source code, are left out of the graph.
     *
     * @param scanResult
     *            the {@link ScanResult} the classes came from.
     * @param classes
     *            the classes to plot in the graph, e.g. {@link ScanResult#getAllClasses()}.
     * @param options
     *            the graph options.
     * @return the GraphViz file contents.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableClassInfo()} was not called before scanning (since there would be
     *             nothing to graph).
     */
    public static String generate(final ScanResult scanResult, final ClassInfoList classes,
            final GraphVizDotFileOptions options) {
        Objects.requireNonNull(scanResult, "scanResult must not be null");
        Objects.requireNonNull(classes, "classes must not be null");
        Objects.requireNonNull(options, "options must not be null");
        if (!scanResult.isClassInfoEnabled()) {
            throw new IllegalStateException("Please call ClassGraph#enableClassInfo() before #scan()");
        }

        final var buf = new StringBuilder(1024 * 1024);
        appendDotFileHeader(options, buf);

        // A class that the compiler generated, such as the class javac writes to hold the table of a switch on an
        // enum, is left out, as are the members the compiler generated
        final var declaredClasses = classes.filter(ci -> !ci.isSynthetic());
        final var standardClassNodes = declaredClasses.getStandardClasses();
        final var interfaceNodes = declaredClasses.getInterfaces();
        final var annotationNodes = declaredClasses.getAnnotations();

        appendClassNodes(scanResult, standardClassNodes, "box", STANDARD_CLASS_COLOR, options, buf);
        appendClassNodes(scanResult, interfaceNodes, "diamond", INTERFACE_COLOR, options, buf);
        appendClassNodes(scanResult, annotationNodes, "oval", ANNOTATION_COLOR, options, buf);

        final List<ClassInfo> allVisibleClasses = new ArrayList<>(
                standardClassNodes.size() + interfaceNodes.size() + annotationNodes.size());
        allVisibleClasses.addAll(standardClassNodes);
        allVisibleClasses.addAll(interfaceNodes);
        allVisibleClasses.addAll(annotationNodes);
        final Set<String> allVisibleNodes = new HashSet<>();
        for (final ClassInfo ci : allVisibleClasses) {
            allVisibleNodes.add(ci.getName());
        }

        buf.append('\n');
        for (final ClassInfo classNode : allVisibleClasses) {
            appendSupertypeEdges(classNode, allVisibleNodes, buf);
            appendMemberTypeEdges(scanResult, classNode, allVisibleNodes, options, buf);
        }
        if (options.showAnnotationDependencyEdges && !annotationNodes.isEmpty()) {
            final Map<String, List<ClassInfo>> classAnnotations = new HashMap<>();
            final Map<String, List<ClassInfo>> memberAnnotations = new HashMap<>();
            indexAnnotations(scanResult, allVisibleClasses, classAnnotations, memberAnnotations);
            for (final ClassInfo annotationNode : annotationNodes) {
                appendAnnotationEdges(annotationNode, classAnnotations, memberAnnotations, buf);
            }
        }
        buf.append('}');
        return buf.toString();
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, using
     * the default options.
     *
     * <p>
     * Methods, fields and annotations are shown if enabled, via {@link ClassGraph#enableMethodInfo()},
     * {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()}.
     *
     * <p>
     * Only public classes, methods, and fields are shown, unless {@link ClassGraph#ignoreClassVisibility()},
     * {@link ClassGraph#ignoreMethodVisibility()}, and/or {@link ClassGraph#ignoreFieldVisibility()} has/have been
     * called.
     *
     * @param scanResult
     *            the {@link ScanResult} the classes came from.
     * @param classes
     *            the classes to plot in the graph, e.g. {@link ScanResult#getAllClasses()}.
     * @return the GraphViz file contents.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableClassInfo()} was not called before scanning (since there would be
     *             nothing to graph).
     */
    public static String generate(final ScanResult scanResult, final ClassInfoList classes) {
        return generate(scanResult, classes, new GraphVizDotFileOptions());
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, and save
     * it to a file, in UTF-8.
     *
     * @param scanResult
     *            the {@link ScanResult} the classes came from.
     * @param classes
     *            the classes to plot in the graph, e.g. {@link ScanResult#getAllClasses()}.
     * @param file
     *            the file to save the GraphViz .dot file to.
     * @param options
     *            the graph options.
     * @return the file that was written (for method chaining).
     * @throws IOException
     *             if the file could not be saved.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableClassInfo()} was not called before scanning (since there would be
     *             nothing to graph).
     */
    public static Path write(final ScanResult scanResult, final ClassInfoList classes, final Path file,
            final GraphVizDotFileOptions options) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        return Files.writeString(file, generate(scanResult, classes, options));
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, using
     * the default options, and save it to a file, in UTF-8.
     *
     * <p>
     * Methods, fields and annotations are shown if enabled, via {@link ClassGraph#enableMethodInfo()},
     * {@link ClassGraph#enableFieldInfo()} and {@link ClassGraph#enableAnnotationInfo()}.
     *
     * <p>
     * Only public classes, methods, and fields are shown, unless {@link ClassGraph#ignoreClassVisibility()},
     * {@link ClassGraph#ignoreMethodVisibility()}, and/or {@link ClassGraph#ignoreFieldVisibility()} has/have been
     * called.
     *
     * @param scanResult
     *            the {@link ScanResult} the classes came from.
     * @param classes
     *            the classes to plot in the graph, e.g. {@link ScanResult#getAllClasses()}.
     * @param file
     *            the file to save the GraphViz .dot file to.
     * @return the file that was written (for method chaining).
     * @throws IOException
     *             if the file could not be saved.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableClassInfo()} was not called before scanning (since there would be
     *             nothing to graph).
     */
    public static Path write(final ScanResult scanResult, final ClassInfoList classes, final Path file)
            throws IOException {
        return write(scanResult, classes, file, new GraphVizDotFileOptions());
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * returned graph shows inter-class dependencies only. You must have called
     * {@link ClassGraph#enableInterClassDependencies()} before scanning to use this method.
     *
     * <p>
     * A class that the compiler generated, rather than one declared in the source code, is left out of the graph,
     * whether it is one of {@code classes} or a dependency of one of them.
     *
     * @param scanResult
     *            the {@link ScanResult} the classes came from.
     * @param classes
     *            the classes whose dependencies should be plotted in the graph.
     * @param options
     *            the graph options. Only the layout size and the unlisted-dependency setting have any effect on
     *            this graph.
     * @return the GraphViz file contents.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableInterClassDependencies()} was not called before scanning (since there
     *             would be nothing to graph).
     */
    public static String generateFromInterClassDependencies(final ScanResult scanResult,
            final ClassInfoList classes, final GraphVizDotFileOptions options) {
        Objects.requireNonNull(scanResult, "scanResult must not be null");
        Objects.requireNonNull(classes, "classes must not be null");
        Objects.requireNonNull(options, "options must not be null");
        if (!scanResult.isInterClassDependenciesEnabled()) {
            throw new IllegalStateException("Please call ClassGraph#enableInterClassDependencies() before #scan()");
        }

        final var buf = new StringBuilder(1024 * 1024);
        appendDotFileHeader(options, buf);

        // A class that the compiler generated is left out, whether it is listed or is a dependency of a listed class
        final var declaredClasses = classes.filter(ci -> !ci.isSynthetic());
        // Sorted by class name, so that the output does not depend on hash order
        final Set<ClassInfo> allVisibleNodes = new TreeSet<>(declaredClasses);
        if (options.showUnlistedDependencies) {
            for (final ClassInfo ci : declaredClasses) {
                for (final ClassInfo dep : ci.getClassDependencies()) {
                    if (!dep.isSynthetic()) {
                        allVisibleNodes.add(dep);
                    }
                }
            }
        }

        for (final ClassInfo ci : allVisibleNodes) {
            appendQuotedIdentifier(ci.getName(), buf);
            appendClassNodeLabelHeader(ci, ci.isAnnotation() ? "oval" : ci.isInterface() ? "diamond" : "box",
                    ci.isAnnotation() ? ANNOTATION_COLOR
                            : ci.isInterface() ? INTERFACE_COLOR : STANDARD_CLASS_COLOR,
                    /* packageNameSuffix = */ "", /* typeParameters = */ "", buf);
            buf.append("</table>");
            buf.append(">];\n");
        }

        buf.append('\n');
        for (final ClassInfo ci : declaredClasses) {
            for (final ClassInfo dep : ci.getClassDependencies()) {
                // An edge is only drawn if the dependency has a node of its own -- when unlisted dependencies are
                // shown, every dependency that the compiler did not generate was given a node above
                if (allVisibleNodes.contains(dep)) {
                    // class --> dep
                    appendEdge(ci.getName(), dep.getName(), "[arrowsize=2.5]", buf);
                }
            }
        }

        buf.append('}');
        return buf.toString();
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, using
     * the default options. The returned graph shows inter-class dependencies only. You must have called
     * {@link ClassGraph#enableInterClassDependencies()} before scanning to use this method.
     *
     * @param scanResult
     *            the {@link ScanResult} the classes came from.
     * @param classes
     *            the classes whose dependencies should be plotted in the graph.
     * @return the GraphViz file contents.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableInterClassDependencies()} was not called before scanning (since there
     *             would be nothing to graph).
     */
    public static String generateFromInterClassDependencies(final ScanResult scanResult,
            final ClassInfoList classes) {
        return generateFromInterClassDependencies(scanResult, classes, new GraphVizDotFileOptions());
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, and save
     * it to a file, in UTF-8. The saved graph shows inter-class dependencies only. You must have called
     * {@link ClassGraph#enableInterClassDependencies()} before scanning to use this method.
     *
     * @param scanResult
     *            the {@link ScanResult} the classes came from.
     * @param classes
     *            the classes whose dependencies should be plotted in the graph.
     * @param file
     *            the file to save the GraphViz .dot file to.
     * @param options
     *            the graph options. Only the layout size and the unlisted-dependency setting have any effect on
     *            this graph.
     * @return the file that was written (for method chaining).
     * @throws IOException
     *             if the file could not be saved.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableInterClassDependencies()} was not called before scanning (since there
     *             would be nothing to graph).
     */
    public static Path writeFromInterClassDependencies(final ScanResult scanResult, final ClassInfoList classes,
            final Path file, final GraphVizDotFileOptions options) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        return Files.writeString(file, generateFromInterClassDependencies(scanResult, classes, options));
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph, using
     * the default options, and save it to a file, in UTF-8. The saved graph shows inter-class dependencies only.
     * You must have called {@link ClassGraph#enableInterClassDependencies()} before scanning to use this method.
     *
     * @param scanResult
     *            the {@link ScanResult} the classes came from.
     * @param classes
     *            the classes whose dependencies should be plotted in the graph.
     * @param file
     *            the file to save the GraphViz .dot file to.
     * @return the file that was written (for method chaining).
     * @throws IOException
     *             if the file could not be saved.
     * @throws IllegalStateException
     *             if {@link ClassGraph#enableInterClassDependencies()} was not called before scanning (since there
     *             would be nothing to graph).
     */
    public static Path writeFromInterClassDependencies(final ScanResult scanResult, final ClassInfoList classes,
            final Path file) throws IOException {
        return writeFromInterClassDependencies(scanResult, classes, file, new GraphVizDotFileOptions());
    }
}
