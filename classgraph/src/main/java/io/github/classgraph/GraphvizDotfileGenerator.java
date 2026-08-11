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

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.CollectionUtils;

/** Builds a class graph visualization in Graphviz .dot file format. */
final class GraphvizDotfileGenerator {
    /** The color for standard classes. */
    private static final String STANDARD_CLASS_COLOR = "fff2b6";

    /** The color for interfaces. */
    private static final String INTERFACE_COLOR = "b6e7ff";

    /** The color for annotations. */
    private static final String ANNOTATION_COLOR = "f3c9ff";

    /** The wrap width for method annotations and method parameters. */
    private static final int WRAP_WIDTH = 40;

    /** Which characters are Unicode whitespace. */
    private static final BitSet IS_UNICODE_WHITESPACE = new BitSet(1 << 16);

    /**
     * Constructor.
     */
    private GraphvizDotfileGenerator() {
        // Cannot be constructed
    }

    static {
        // Valid unicode whitespace chars, see:
        // http://stackoverflow.com/questions/4731055/whitespace-matching-regex-java
        // Also see (for \n and \r -- a real example of Java stupidity):
        // https://stackoverflow.com/a/3866219/3950982
        final var wsChars = "\u0020" // SPACE
                + "\u0009" // CHARACTER TABULATION
                + "\n" // LINE FEED (LF)
                + "\u000B" // LINE TABULATION
                + "\u000C" // FORM FEED (FF)
                + "\r" // CARRIAGE RETURN (CR)
                + "\u0085" // NEXT LINE (NEL)
                + "\u00A0" // NO-BREAK SPACE
                + "\u1680" // OGHAM SPACE MARK
                + "\u180E" // MONGOLIAN VOWEL SEPARATOR
                + "\u2000" // EN QUAD
                + "\u2001" // EM QUAD
                + "\u2002" // EN SPACE
                + "\u2003" // EM SPACE
                + "\u2004" // THREE-PER-EM SPACE
                + "\u2005" // FOUR-PER-EM SPACE
                + "\u2006" // SIX-PER-EM SPACE
                + "\u2007" // FIGURE SPACE
                + "\u2008" // PUNCTUATION SPACE
                + "\u2009" // THIN SPACE
                + "\u200A" // HAIR SPACE
                + "\u2028" // LINE SEPARATOR
                + "\u2029" // PARAGRAPH SEPARATOR
                + "\u202F" // NARROW NO-BREAK SPACE
                + "\u205F" // MEDIUM MATHEMATICAL SPACE
                + "\u3000"; // IDEOGRAPHIC SPACE
        for (var i = 0; i < wsChars.length(); i++) {
            IS_UNICODE_WHITESPACE.set(wsChars.charAt(i));
        }
    }

    /**
     * Checks if a character is Unicode whitespace.
     *
     * @param c
     *            the character
     * @return true if the character is Unicode whitespace
     */
    private static boolean isUnicodeWhitespace(final char c) {
        return IS_UNICODE_WHITESPACE.get(c);
    }

    /**
     * Encode HTML-unsafe characters as HTML entities.
     *
     * @param unsafeStr
     *            The string to escape to make HTML-safe.
     * @param turnNewlineIntoBreak
     *            If true, turn '\n' into a break element in the output.
     * @param buf
     *            the buffer to append to
     */
    private static void htmlEncode(final CharSequence unsafeStr, final boolean turnNewlineIntoBreak,
            final StringBuilder buf) {
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final var c = unsafeStr.charAt(i);
            switch (c) {
            case '&' -> buf.append("&amp;");
            case '<' -> buf.append("&lt;");
            case '>' -> buf.append("&gt;");
            case '"' -> buf.append("&quot;");
            case '\'' -> buf.append("&#x27;"); // See http://goo.gl/FzoP6m
            case '\\' -> buf.append("&lsol;");
            case '/' -> buf.append("&#x2F;"); // '/' can be a dangerous char if attr values are not quoted
            // Encode a few common characters that like to get screwed up in some charset/browser variants
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
            case '\n' -> {
                if (turnNewlineIntoBreak) {
                    buf.append("<br>");
                } else {
                    buf.append(' '); // Newlines function as whitespace in HTML text
                }
            }
            default -> {
                if (c <= 32 || isUnicodeWhitespace(c)) {
                    buf.append(' ');
                } else {
                    buf.append(c);
                }
            }
            }
        }
    }

    /**
     * Encode HTML-unsafe characters as HTML entities.
     *
     * @param unsafeStr
     *            The string to escape to make HTML-safe.
     * @param buf
     *            the buffer to append to
     */
    private static void htmlEncode(final CharSequence unsafeStr, final StringBuilder buf) {
        htmlEncode(unsafeStr, /* turnNewlineIntoBreak = */ false, buf);
    }

    /**
     * Append a space to the buffer, if it does not already end with one, so that the item appended next is
     * separated from the item before it.
     *
     * @param buf
     *            the buffer to append to
     */
    private static void appendSpaceIfNeeded(final StringBuilder buf) {
        if (buf.charAt(buf.length() - 1) != ' ') {
            buf.append(' ');
        }
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
     * modifiers, package name and simple name.
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
     * @param buf
     *            the buffer to append to
     */
    private static void appendClassNodeLabelHeader(final ClassInfo ci, final String shape, final String boxBgColor,
            final String packageNameSuffix, final StringBuilder buf) {
        buf.append("[shape=").append(shape).append(",style=filled,fillcolor=\"#").append(boxBgColor)
                .append("\",label=");
        buf.append('<');
        buf.append("<table border='0' cellborder='0' cellspacing='1'>");

        // Class modifiers
        buf.append("<tr><td><font point-size='12'>").append(ci.getModifiersString()).append(' ')
                .append(ci.isEnum() ? "enum"
                        : ci.isAnnotation() ? "@interface" : ci.isInterface() ? "interface" : "class")
                .append("</font></td></tr>");

        // Package name
        if (ci.getName().contains(".")) {
            buf.append("<tr><td><font point-size='14'><b>");
            htmlEncode(ci.getPackageName() + packageNameSuffix, buf);
            buf.append("</b></font></td></tr>");
        }

        // Class name
        buf.append("<tr><td><font point-size='20'><b>");
        htmlEncode(ci.getSimpleName(), buf);
        buf.append("</b></font></td></tr>");
    }

    /**
     * Get the annotations to list for a class, in sorted order.
     *
     * @param annotationInfo
     *            the annotations on the class
     * @return the annotations to list
     */
    private static AnnotationInfoList annotationsToShow(final AnnotationInfoList annotationInfo) {
        // Meta-annotations are not listed, so the annotations are filtered before the section header is written --
        // otherwise an annotation class, whose only annotations are meta-annotations, would get a section header
        // with nothing under it
        final var annotationInfoSorted = new AnnotationInfoList(annotationInfo.size());
        for (final AnnotationInfo ai : annotationInfo) {
            if (!ai.getName().startsWith("java.lang.annotation.")) {
                annotationInfoSorted.add(ai);
            }
        }
        CollectionUtils.sortIfNotEmpty(annotationInfoSorted);
        return annotationInfoSorted;
    }

    /**
     * Append the ANNOTATIONS section of a class node, if the class has any annotations to list.
     *
     * @param annotationInfo
     *            the annotations on the class
     * @param darkerColor
     *            the background color of the section header
     * @param buf
     *            the buffer to append to
     */
    private static void appendClassAnnotations(final AnnotationInfoList annotationInfo, final String darkerColor,
            final StringBuilder buf) {
        final var annotationInfoSorted = annotationsToShow(annotationInfo);
        if (annotationInfoSorted.isEmpty()) {
            return;
        }
        buf.append("<tr><td colspan='3' bgcolor='").append(darkerColor)
                .append("'><font point-size='12'><b>ANNOTATIONS</b></font></td></tr>");
        for (final AnnotationInfo ai : annotationInfoSorted) {
            buf.append("<tr>");
            buf.append("<td align='center' valign='top'>");
            htmlEncode(ai.toString(), buf);
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
    private static FieldInfoList fieldsToShow(final FieldInfoList fieldInfo) {
        final var fieldInfoSorted = new FieldInfoList(fieldInfo);
        CollectionUtils.sortIfNotEmpty(fieldInfoSorted);
        for (var i = fieldInfoSorted.size() - 1; i >= 0; --i) {
            // Remove serialVersionUID field
            if ("serialVersionUID".equals(fieldInfoSorted.get(i).getName())) {
                fieldInfoSorted.remove(i);
            }
        }
        return fieldInfoSorted;
    }

    /**
     * Append the FIELDS section of a class node, if the class has any fields to list.
     *
     * @param fieldInfo
     *            the fields of the class
     * @param options
     *            the graph options
     * @param scanSpec
     *            the scan spec
     * @param darkerColor
     *            the background color of the section header
     * @param buf
     *            the buffer to append to
     */
    private static void appendFields(final FieldInfoList fieldInfo, final GraphVizDotFileOptions options,
            final ScanSpec scanSpec, final String darkerColor, final StringBuilder buf) {
        final var fieldInfoSorted = fieldsToShow(fieldInfo);
        if (fieldInfoSorted.isEmpty()) {
            return;
        }
        buf.append("<tr><td colspan='3' bgcolor='").append(darkerColor).append("'><font point-size='12'><b>")
                .append(scanSpec.ignoreFieldVisibility ? "" : "PUBLIC ").append("FIELDS</b></font></td></tr>");
        buf.append("<tr><td cellpadding='0'>");
        buf.append("<table border='0' cellborder='0'>");
        for (final FieldInfo fi : fieldInfoSorted) {
            buf.append("<tr>");
            buf.append("<td align='right' valign='top'>");

            // Field annotations
            final var fieldAnnotationInfo = fi.annotationInfo;
            if (options.showAnnotations && fieldAnnotationInfo != null) {
                for (final AnnotationInfo ai : fieldAnnotationInfo) {
                    appendSpaceIfNeeded(buf);
                    htmlEncode(ai.toString(), buf);
                }
            }

            // Field modifiers
            if (scanSpec.ignoreFieldVisibility) {
                appendSpaceIfNeeded(buf);
                buf.append(fi.getModifiersString());
            }

            // Field type
            appendSpaceIfNeeded(buf);
            final var typeSig = Objects.requireNonNull(fi.getTypeSignatureOrTypeDescriptor());
            htmlEncode(options.useSimpleNames ? typeSig.toStringWithSimpleNames() : typeSig.toString(), buf);
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
    private static MethodInfoList methodsToShow(final MethodInfoList methodInfo) {
        final var methodInfoSorted = new MethodInfoList(methodInfo);
        CollectionUtils.sortIfNotEmpty(methodInfoSorted);
        for (var i = methodInfoSorted.size() - 1; i >= 0; --i) {
            // Don't list static initializer blocks or methods of Object
            final var mi = methodInfoSorted.get(i);
            final var name = mi.getName();
            final var numParam = mi.getParameterInfo().size();
            if ("<clinit>".equals(name) || "hashCode".equals(name) && numParam == 0
                    || "toString".equals(name) && numParam == 0 || "equals".equals(name) && numParam == 1
                            && "boolean (java.lang.Object)".equals(mi.getTypeDescriptor().toString())) {
                methodInfoSorted.remove(i);
            }
        }
        return methodInfoSorted;
    }

    /**
     * Append the annotations of a method, wrapping onto a new row of the enclosing table once a row of annotations
     * gets too long.
     *
     * @param annotationInfo
     *            the annotations on the method
     * @param buf
     *            the buffer to append to
     */
    private static void appendMethodAnnotations(final AnnotationInfoList annotationInfo, final StringBuilder buf) {
        var wrapPos = 0;
        for (final AnnotationInfo ai : annotationInfo) {
            final var ais = ai.toString();
            if (wrapPos > WRAP_WIDTH) {
                // Continue the annotations in the same column of a new row, leaving the method name and parameter
                // columns of that row empty
                buf.append("</td><td></td><td></td></tr><tr><td align='right' valign='top'>");
                wrapPos = 0;
            } else if (buf.charAt(buf.length() - 1) != ' ') {
                buf.append(' ');
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
     * @param paramInfo
     *            the parameters of the method
     * @param options
     *            the graph options
     * @param buf
     *            the buffer to append to
     */
    private static void appendMethodParameters(final List<MethodParameterInfo> paramInfo,
            final GraphVizDotFileOptions options, final StringBuilder buf) {
        for (int i = 0, wrapPos = 0; i < paramInfo.size(); i++) {
            if (i > 0) {
                buf.append(", ");
                wrapPos += 2;
            }
            if (wrapPos > WRAP_WIDTH) {
                buf.append("</td></tr><tr><td></td><td></td><td align='left' valign='top'>");
                wrapPos = 0;
            }

            // Parameter annotations
            final var param = paramInfo.get(i);
            final var paramAnnotationInfo = param.annotationInfo;
            if (options.showAnnotations && paramAnnotationInfo != null) {
                for (final AnnotationInfo ai : paramAnnotationInfo) {
                    final var ais = ai.toString();
                    if (!ais.isEmpty()) {
                        appendSpaceIfNeeded(buf);
                        htmlEncode(ais, buf);
                        wrapPos += 1 + ais.length();
                        if (wrapPos > WRAP_WIDTH) {
                            buf.append("</td></tr><tr><td></td><td></td><td align='left' valign='top'>");
                            wrapPos = 0;
                        }
                    }
                }
            }

            // Parameter type
            final var paramTypeSig = Objects.requireNonNull(param.getTypeSignatureOrTypeDescriptor());
            final var paramTypeStr = options.useSimpleNames ? paramTypeSig.toStringWithSimpleNames()
                    : paramTypeSig.toString();
            htmlEncode(paramTypeStr, buf);
            wrapPos += paramTypeStr.length();

            // Parameter name
            final var paramName = param.getName();
            if (paramName != null) {
                buf.append(" <B>");
                htmlEncode(paramName, buf);
                wrapPos += 1 + paramName.length();
                buf.append("</B>");
            }
        }
    }

    /**
     * Append the METHODS section of a class node, if the class has any methods to list.
     *
     * @param ci
     *            the class info
     * @param methodInfo
     *            the methods of the class
     * @param options
     *            the graph options
     * @param scanSpec
     *            the scan spec
     * @param darkerColor
     *            the background color of the section header
     * @param buf
     *            the buffer to append to
     */
    private static void appendMethods(final ClassInfo ci, final MethodInfoList methodInfo,
            final GraphVizDotFileOptions options, final ScanSpec scanSpec, final String darkerColor,
            final StringBuilder buf) {
        final var methodInfoSorted = methodsToShow(methodInfo);
        if (methodInfoSorted.isEmpty()) {
            return;
        }
        buf.append("<tr><td cellpadding='0'>");
        buf.append("<table border='0' cellborder='0'>");
        buf.append("<tr><td colspan='3' bgcolor='").append(darkerColor).append("'><font point-size='12'><b>")
                .append(scanSpec.ignoreMethodVisibility ? "" : "PUBLIC ").append("METHODS</b></font></td></tr>");
        for (final MethodInfo mi : methodInfoSorted) {
            final var isConstructor = "<init>".equals(mi.getName());
            buf.append("<tr>");

            // Method annotations
            buf.append("<td align='right' valign='top'>");
            final var methodAnnotationInfo = mi.annotationInfo;
            if (options.showAnnotations && methodAnnotationInfo != null) {
                appendMethodAnnotations(methodAnnotationInfo, buf);
            }

            // Method modifiers
            if (scanSpec.ignoreMethodVisibility) {
                appendSpaceIfNeeded(buf);
                buf.append(mi.getModifiersString());
            }

            // Method return type -- constructors have none
            appendSpaceIfNeeded(buf);
            if (isConstructor) {
                buf.append("<b>&lt;constructor&gt;</b>");
            } else {
                final var resultTypeSig = mi.getTypeSignatureOrTypeDescriptor().getResultType();
                htmlEncode(
                        options.useSimpleNames ? resultTypeSig.toStringWithSimpleNames() : resultTypeSig.toString(),
                        buf);
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
            appendMethodParameters(mi.getParameterInfo(), options, buf);
            buf.append(')');
            buf.append("</td></tr>");
        }
        buf.append("</table>");
        buf.append("</td></tr>");
    }

    /**
     * Produce HTML label for class node.
     *
     * @param ci
     *            the class info
     * @param shape
     *            the shape to use
     * @param boxBgColor
     *            the box background color
     * @param options
     *            the graph options
     * @param scanSpec
     *            the scan spec
     * @param buf
     *            the buffer to append to
     */
    private static void labelClassNodeHTML(final ClassInfo ci, final String shape, final String boxBgColor,
            final GraphVizDotFileOptions options, final ScanSpec scanSpec, final StringBuilder buf) {
        appendClassNodeLabelHeader(ci, shape, boxBgColor, /* packageNameSuffix = */ ".", buf);

        final var darkerColor = darkerColor(boxBgColor);

        final var annotationInfo = ci.annotationInfo;
        if (options.showAnnotations && annotationInfo != null) {
            appendClassAnnotations(annotationInfo, darkerColor, buf);
        }

        final var fieldInfo = ci.fieldInfo;
        if (options.showFields && fieldInfo != null) {
            appendFields(fieldInfo, options, scanSpec, darkerColor, buf);
        }

        final var methodInfo = ci.methodInfo;
        if (options.showMethods && methodInfo != null) {
            appendMethods(ci, methodInfo, options, scanSpec, darkerColor, buf);
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
        buf.append("  \"").append(fromClassName).append("\" -> \"").append(toClassName).append("\" ")
                .append(attributes).append('\n');
    }

    /**
     * Append a class node for each class in a list.
     *
     * @param classNodes
     *            the classes to append nodes for
     * @param shape
     *            the shape to use
     * @param boxBgColor
     *            the box background color
     * @param options
     *            the graph options
     * @param scanSpec
     *            the scan spec
     * @param buf
     *            the buffer to append to
     */
    private static void appendClassNodes(final ClassInfoList classNodes, final String shape,
            final String boxBgColor, final GraphVizDotFileOptions options, final ScanSpec scanSpec,
            final StringBuilder buf) {
        for (final ClassInfo node : classNodes) {
            buf.append('"').append(node.getName()).append('"');
            labelClassNodeHTML(node, shape, boxBgColor, options, scanSpec, buf);
            buf.append(";\n");
        }
    }

    /**
     * Append the edges from a standard class to its superclass, to the interfaces it implements, and to the types
     * of its fields and methods.
     *
     * @param classNode
     *            the class to append edges for
     * @param allVisibleNodes
     *            the names of the classes that have a node in the graph
     * @param options
     *            the graph options
     * @param buf
     *            the buffer to append to
     */
    private static void appendStandardClassEdges(final ClassInfo classNode, final Set<String> allVisibleNodes,
            final GraphVizDotFileOptions options, final StringBuilder buf) {
        for (final ClassInfo directSuperclassNode : classNode.getAllSuperclasses().directOnly()) {
            if (directSuperclassNode != null && allVisibleNodes.contains(directSuperclassNode.getName())
                    && !"java.lang.Object".equals(directSuperclassNode.getName())) {
                // class --> superclass
                appendEdge(classNode.getName(), directSuperclassNode.getName(), "[arrowsize=2.5]", buf);
            }
        }

        for (final ClassInfo implementedInterfaceNode : classNode.getDirectSuperinterfaces()) {
            if (allVisibleNodes.contains(implementedInterfaceNode.getName())) {
                // class --<> implemented interface
                appendEdge(classNode.getName(), implementedInterfaceNode.getName(),
                        "[arrowhead=diamond, arrowsize=2.5]", buf);
            }
        }

        final var fieldInfo = classNode.fieldInfo;
        if (options.showFieldTypeDependencyEdges && fieldInfo != null) {
            for (final FieldInfo fi : fieldInfo) {
                for (final ClassInfo referencedFieldType : fi.findReferencedClassInfo(/* log = */ null)) {
                    if (allVisibleNodes.contains(referencedFieldType.getName())) {
                        // class --[ ] field type (open box)
                        appendEdge(referencedFieldType.getName(), classNode.getName(),
                                "[arrowtail=obox, arrowsize=2.5, dir=back]", buf);
                    }
                }
            }
        }

        final var methodInfo = classNode.methodInfo;
        if (options.showMethodTypeDependencyEdges && methodInfo != null) {
            for (final MethodInfo mi : methodInfo) {
                for (final ClassInfo referencedMethodType : mi.findReferencedClassInfo(/* log = */ null)) {
                    if (allVisibleNodes.contains(referencedMethodType.getName())) {
                        // class --[#] method type (filled box)
                        appendEdge(referencedMethodType.getName(), classNode.getName(),
                                "[arrowtail=box, arrowsize=2.5, dir=back]", buf);
                    }
                }
            }
        }
    }

    /**
     * Append the edges from an annotation to the classes it annotates, either directly or through one of their
     * fields or methods.
     *
     * @param annotationNode
     *            the annotation to append edges for
     * @param allVisibleNodes
     *            the names of the classes that have a node in the graph
     * @param buf
     *            the buffer to append to
     */
    private static void appendAnnotationEdges(final ClassInfo annotationNode, final Set<String> allVisibleNodes,
            final StringBuilder buf) {
        for (final ClassInfo annotatedClassNode : annotationNode.getClassesWithAnnotationDirectOnly()) {
            if (allVisibleNodes.contains(annotatedClassNode.getName())) {
                // annotated class --o annotation
                appendEdge(annotatedClassNode.getName(), annotationNode.getName(), "[arrowhead=dot, arrowsize=2.5]",
                        buf);
            }
        }
        for (final ClassInfo classWithMethodAnnotationNode : annotationNode
                .getClassesWithMethodAnnotationDirectOnly()) {
            if (allVisibleNodes.contains(classWithMethodAnnotationNode.getName())) {
                // class with method annotation --o method annotation
                appendEdge(classWithMethodAnnotationNode.getName(), annotationNode.getName(),
                        "[arrowhead=odot, arrowsize=2.5]", buf);
            }
        }
        for (final ClassInfo classWithFieldAnnotationNode : annotationNode
                .getClassesWithFieldAnnotationDirectOnly()) {
            if (allVisibleNodes.contains(classWithFieldAnnotationNode.getName())) {
                // class with field annotation --o field annotation
                appendEdge(classWithFieldAnnotationNode.getName(), annotationNode.getName(),
                        "[arrowhead=odot, arrowsize=2.5]", buf);
            }
        }
    }

    /**
     * Generates a .dot file which can be fed into GraphViz for layout and visualization of the class graph.
     *
     * @param classInfoList
     *            the class info list
     * @param options
     *            the graph options
     * @param scanSpec
     *            the scan spec
     * @return the GraphViz file contents.
     */
    static String generateGraphVizDotFile(final ClassInfoList classInfoList, final GraphVizDotFileOptions options,
            final ScanSpec scanSpec) {
        final var buf = new StringBuilder(1024 * 1024);
        appendDotFileHeader(options, buf);

        final var standardClassNodes = classInfoList.getStandardClasses();
        final var interfaceNodes = classInfoList.getInterfaces();
        final var annotationNodes = classInfoList.getAnnotations();

        appendClassNodes(standardClassNodes, "box", STANDARD_CLASS_COLOR, options, scanSpec, buf);
        appendClassNodes(interfaceNodes, "diamond", INTERFACE_COLOR, options, scanSpec, buf);
        appendClassNodes(annotationNodes, "oval", ANNOTATION_COLOR, options, scanSpec, buf);

        final Set<String> allVisibleNodes = new HashSet<>();
        allVisibleNodes.addAll(standardClassNodes.getNames());
        allVisibleNodes.addAll(interfaceNodes.getNames());
        allVisibleNodes.addAll(annotationNodes.getNames());

        buf.append('\n');
        for (final ClassInfo classNode : standardClassNodes) {
            appendStandardClassEdges(classNode, allVisibleNodes, options, buf);
        }
        for (final ClassInfo interfaceNode : interfaceNodes) {
            for (final ClassInfo superinterfaceNode : interfaceNode.getDirectSuperinterfaces()) {
                if (allVisibleNodes.contains(superinterfaceNode.getName())) {
                    // interface --<> superinterface
                    appendEdge(interfaceNode.getName(), superinterfaceNode.getName(),
                            "[arrowhead=diamond, arrowsize=2.5]", buf);
                }
            }
        }
        if (options.showAnnotationDependencyEdges) {
            for (final ClassInfo annotationNode : annotationNodes) {
                appendAnnotationEdges(annotationNode, allVisibleNodes, buf);
            }
        }
        buf.append('}');
        return buf.toString();
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * returned graph shows inter-class dependencies only. You must have called
     * {@link ClassGraph#enableInterClassDependencies()} before scanning to use this method.
     *
     * @param classInfoList
     *            The list of nodes whose dependencies should be plotted in the graph.
     * @param options
     *            the graph options
     * @param scanSpec
     *            the scan spec
     * @return the GraphViz file contents.
     */
    static String generateGraphVizDotFileFromInterClassDependencies(final ClassInfoList classInfoList,
            final GraphVizDotFileOptions options, final ScanSpec scanSpec) {
        // The graph shows external classes if the options ask for them, or, if the options say nothing either way,
        // if they were enabled in the scan
        final var includeExternalClasses = options.includeExternalClasses != null ? options.includeExternalClasses
                : scanSpec.enableExternalClasses;

        final var buf = new StringBuilder(1024 * 1024);
        appendDotFileHeader(options, buf);

        final Set<ClassInfo> allVisibleNodes = new HashSet<>(classInfoList);
        if (includeExternalClasses) {
            for (final ClassInfo ci : classInfoList) {
                allVisibleNodes.addAll(ci.getClassDependencies());
            }
        }

        for (final ClassInfo ci : allVisibleNodes) {
            buf.append('"').append(ci.getName()).append('"');
            appendClassNodeLabelHeader(ci, ci.isAnnotation() ? "oval" : ci.isInterface() ? "diamond" : "box",
                    ci.isAnnotation() ? ANNOTATION_COLOR
                            : ci.isInterface() ? INTERFACE_COLOR : STANDARD_CLASS_COLOR,
                    /* packageNameSuffix = */ "", buf);
            buf.append("</table>");
            buf.append(">];\n");
        }

        buf.append('\n');
        for (final ClassInfo ci : classInfoList) {
            for (final ClassInfo dep : ci.getClassDependencies()) {
                if (includeExternalClasses || allVisibleNodes.contains(dep)) {
                    // class --> dep
                    appendEdge(ci.getName(), dep.getName(), "[arrowsize=2.5]", buf);
                }
            }
        }

        buf.append('}');
        return buf.toString();
    }
}
