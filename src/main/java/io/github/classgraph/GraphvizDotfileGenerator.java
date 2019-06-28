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

    /** The wrap width for method parameters. */
    private static final int PARAM_WRAP_WIDTH = 40;

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
        final String wsChars = "\u0020" // SPACE
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
        for (int i = 0; i < wsChars.length(); i++) {
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
     *            the buf
     */
    private static void htmlEncode(final CharSequence unsafeStr, final boolean turnNewlineIntoBreak,
            final StringBuilder buf) {
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            switch (c) {
            case '&':
                buf.append("&amp;");
                break;
            case '<':
                buf.append("&lt;");
                break;
            case '>':
                buf.append("&gt;");
                break;
            case '"':
                buf.append("&quot;");
                break;
            case '\'':
                buf.append("&#x27;"); // See http://goo.gl/FzoP6m
                break;
            case '\\':
                buf.append("&lsol;");
                break;
            case '/':
                buf.append("&#x2F;"); // '/' can be a dangerous char if attr values are not quoted
                break;
            // Encode a few common characters that like to get screwed up in some charset/browser variants
            case '—':
                buf.append("&mdash;");
                break;
            case '–':
                buf.append("&ndash;");
                break;
            case '“':
                buf.append("&ldquo;");
                break;
            case '”':
                buf.append("&rdquo;");
                break;
            case '‘':
                buf.append("&lsquo;");
                break;
            case '’':
                buf.append("&rsquo;");
                break;
            case '«':
                buf.append("&laquo;");
                break;
            case '»':
                buf.append("&raquo;");
                break;
            case '£':
                buf.append("&pound;");
                break;
            case '©':
                buf.append("&copy;");
                break;
            case '®':
                buf.append("&reg;");
                break;
            case (char) 0x00A0:
                buf.append("&nbsp;");
                break;
            case '\n':
                if (turnNewlineIntoBreak) {
                    buf.append("<br>");
                } else {
                    buf.append(' '); // Newlines function as whitespace in HTML text
                }
                break;
            default:
                if (c <= 32 || isUnicodeWhitespace(c)) {
                    buf.append(' ');
                } else {
                    buf.append(c);
                }
                break;
            }
        }
    }

    /**
     * Encode HTML-unsafe characters as HTML entities.
     *
     * @param unsafeStr
     *            The string to escape to make HTML-safe.
     * @param buf
     *            the buf
     */
    private static void htmlEncode(final CharSequence unsafeStr, final StringBuilder buf) {
        htmlEncode(unsafeStr, /* turnNewlineIntoBreak = */ false, buf);
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
     * @param showFields
     *            whether to show fields
     * @param showMethods
     *            whether to show methods
     * @param useSimpleNames
     *            whether to use simple names for classes in type signatures
     * @param scanSpec
     *            the scan spec
     * @param buf
     *            the buf
     */
    private static void labelClassNodeHTML(final ClassInfo ci, final String shape, final String boxBgColor,
            final boolean showFields, final boolean showMethods, final boolean useSimpleNames,
            final ScanSpec scanSpec, final StringBuilder buf) {
        buf.append("[shape=").append(shape).append(",style=filled,fillcolor=\"#").append(boxBgColor)
                .append("\",label=");
        buf.append('<');
        buf.append("<table border='0' cellborder='0' cellspacing='1'>");

        // Class modifiers
        buf.append("<tr><td><font point-size='12'>").append(ci.getModifiersStr()).append(' ')
                .append(ci.isEnum() ? "enum"
                        : ci.isAnnotation() ? "@interface" : ci.isInterface() ? "interface" : "class")
                .append("</font></td></tr>");

        if (ci.getName().contains(".")) {
            buf.append("<tr><td><font point-size='14'><b>");
            htmlEncode(ci.getPackageName() + ".", buf);
            buf.append("</b></font></td></tr>");
        }

        // Class name
        buf.append("<tr><td><font point-size='20'><b>");
        htmlEncode(ci.getSimpleName(), buf);
        buf.append("</b></font></td></tr>");

        // Create a color that matches the box background color, but is darker
        final float darkness = 0.8f;
        final int r = (int) (Integer.parseInt(boxBgColor.substring(0, 2), 16) * darkness);
        final int g = (int) (Integer.parseInt(boxBgColor.substring(2, 4), 16) * darkness);
        final int b = (int) (Integer.parseInt(boxBgColor.substring(4, 6), 16) * darkness);
        final String darkerColor = String.format("#%s%s%s%s%s%s", Integer.toString(r >> 4, 16),
                Integer.toString(r & 0xf, 16), Integer.toString(g >> 4, 16), Integer.toString(g & 0xf, 16),
                Integer.toString(b >> 4, 16), Integer.toString(b & 0xf, 16));

        // Class annotations
        final AnnotationInfoList annotationInfo = ci.annotationInfo;
        if (annotationInfo != null && !annotationInfo.isEmpty()) {
            buf.append("<tr><td colspan='3' bgcolor='").append(darkerColor)
                    .append("'><font point-size='12'><b>ANNOTATIONS</b></font></td></tr>");
            final AnnotationInfoList annotationInfoSorted = new AnnotationInfoList(annotationInfo);
            CollectionUtils.sortIfNotEmpty(annotationInfoSorted);
            for (final AnnotationInfo ai : annotationInfoSorted) {
                final String annotationName = ai.getName();
                if (!annotationName.startsWith("java.lang.annotation.")) {
                    buf.append("<tr>");
                    buf.append("<td align='center' valign='top'>");
                    htmlEncode(ai.toString(), buf);
                    buf.append("</td></tr>");
                }
            }
        }

        // Fields
        final FieldInfoList fieldInfo = ci.fieldInfo;
        if (showFields && fieldInfo != null && !fieldInfo.isEmpty()) {
            final FieldInfoList fieldInfoSorted = new FieldInfoList(fieldInfo);
            CollectionUtils.sortIfNotEmpty(fieldInfoSorted);
            for (int i = fieldInfoSorted.size() - 1; i >= 0; --i) {
                // Remove serialVersionUID field
                if (fieldInfoSorted.get(i).getName().equals("serialVersionUID")) {
                    fieldInfoSorted.remove(i);
                }
            }
            if (!fieldInfoSorted.isEmpty()) {
                buf.append("<tr><td colspan='3' bgcolor='").append(darkerColor)
                        .append("'><font point-size='12'><b>")
                        .append(scanSpec.ignoreFieldVisibility ? "" : "PUBLIC ")
                        .append("FIELDS</b></font></td></tr>");
                buf.append("<tr><td cellpadding='0'>");
                buf.append("<table border='0' cellborder='0'>");
                for (final FieldInfo fi : fieldInfoSorted) {
                    buf.append("<tr>");
                    buf.append("<td align='right' valign='top'>");

                    // Field Annotations
                    final AnnotationInfoList fieldAnnotationInfo = fi.annotationInfo;
                    if (fieldAnnotationInfo != null) {
                        for (final AnnotationInfo ai : fieldAnnotationInfo) {
                            if (buf.charAt(buf.length() - 1) != ' ') {
                                buf.append(' ');
                            }
                            htmlEncode(ai.toString(), buf);
                        }
                    }

                    // Field modifiers
                    if (scanSpec.ignoreFieldVisibility) {
                        if (buf.charAt(buf.length() - 1) != ' ') {
                            buf.append(' ');
                        }
                        buf.append(fi.getModifierStr());
                    }

                    // Field type
                    if (buf.charAt(buf.length() - 1) != ' ') {
                        buf.append(' ');
                    }
                    final TypeSignature typeSig = fi.getTypeSignatureOrTypeDescriptor();
                    htmlEncode(useSimpleNames ? typeSig.toStringWithSimpleNames() : typeSig.toString(), buf);
                    buf.append("</td>");

                    // Field name
                    buf.append("<td align='left' valign='top'><b>");
                    final String fieldName = fi.getName();
                    htmlEncode(fieldName, buf);
                    buf.append("</b></td></tr>");
                }
                buf.append("</table>");
                buf.append("</td></tr>");
            }
        }

        // Methods
        final MethodInfoList methodInfo = ci.methodInfo;
        if (showMethods && methodInfo != null) {
            final MethodInfoList methodInfoSorted = new MethodInfoList(methodInfo);
            CollectionUtils.sortIfNotEmpty(methodInfoSorted);
            for (int i = methodInfoSorted.size() - 1; i >= 0; --i) {
                // Don't list static initializer blocks or methods of Object
                final MethodInfo mi = methodInfoSorted.get(i);
                final String name = mi.getName();
                final int numParam = mi.getParameterInfo().length;
                if (name.equals("<clinit>") || name.equals("hashCode") && numParam == 0
                        || name.equals("toString") && numParam == 0 || name.equals("equals") && numParam == 1
                                && mi.getTypeDescriptor().toString().equals("boolean (java.lang.Object)")) {
                    methodInfoSorted.remove(i);
                }
            }
            if (!methodInfoSorted.isEmpty()) {
                buf.append("<tr><td cellpadding='0'>");
                buf.append("<table border='0' cellborder='0'>");
                buf.append("<tr><td colspan='3' bgcolor='").append(darkerColor)
                        .append("'><font point-size='12'><b>")
                        .append(scanSpec.ignoreMethodVisibility ? "" : "PUBLIC ")
                        .append("METHODS</b></font></td></tr>");
                for (final MethodInfo mi : methodInfoSorted) {
                    buf.append("<tr>");

                    // Method annotations
                    // TODO: wrap this cell if the contents get too long
                    buf.append("<td align='right' valign='top'>");
                    final AnnotationInfoList methodAnnotationInfo = mi.annotationInfo;
                    if (methodAnnotationInfo != null) {
                        for (final AnnotationInfo ai : methodAnnotationInfo) {
                            if (buf.charAt(buf.length() - 1) != ' ') {
                                buf.append(' ');
                            }
                            htmlEncode(ai.toString(), buf);
                        }
                    }

                    // Method modifiers
                    if (scanSpec.ignoreMethodVisibility) {
                        if (buf.charAt(buf.length() - 1) != ' ') {
                            buf.append(' ');
                        }
                        buf.append(mi.getModifiersStr());
                    }

                    // Method return type
                    if (buf.charAt(buf.length() - 1) != ' ') {
                        buf.append(' ');
                    }
                    if (!mi.getName().equals("<init>")) {
                        // Don't list return type for constructors
                        final TypeSignature resultTypeSig = mi.getTypeSignatureOrTypeDescriptor().getResultType();
                        htmlEncode(
                                useSimpleNames ? resultTypeSig.toStringWithSimpleNames() : resultTypeSig.toString(),
                                buf);
                    } else {
                        buf.append("<b>&lt;constructor&gt;</b>");
                    }
                    buf.append("</td>");

                    // Method name
                    buf.append("<td align='left' valign='top'>");
                    buf.append("<b>");
                    if (mi.getName().equals("<init>")) {
                        // Show class name for constructors
                        htmlEncode(ci.getSimpleName(), buf);
                    } else {
                        htmlEncode(mi.getName(), buf);
                    }
                    buf.append("</b>&nbsp;");
                    buf.append("</td>");

                    // Method parameters
                    buf.append("<td align='left' valign='top'>");
                    buf.append('(');
                    final MethodParameterInfo[] paramInfo = mi.getParameterInfo();
                    if (paramInfo.length != 0) {
                        for (int i = 0, wrapPos = 0; i < paramInfo.length; i++) {
                            if (i > 0) {
                                buf.append(", ");
                                wrapPos += 2;
                            }
                            if (wrapPos > PARAM_WRAP_WIDTH) {
                                buf.append("</td></tr><tr><td></td><td></td><td align='left' valign='top'>");
                                wrapPos = 0;
                            }

                            // Param annotation
                            final AnnotationInfo[] paramAnnotationInfo = paramInfo[i].annotationInfo;
                            if (paramAnnotationInfo != null) {
                                for (final AnnotationInfo ai : paramAnnotationInfo) {
                                    final String ais = ai.toString();
                                    if (!ais.isEmpty()) {
                                        if (buf.charAt(buf.length() - 1) != ' ') {
                                            buf.append(' ');
                                        }
                                        htmlEncode(ais, buf);
                                        wrapPos += 1 + ais.length();
                                        if (wrapPos > PARAM_WRAP_WIDTH) {
                                            buf.append("</td></tr><tr><td></td><td></td>"
                                                    + "<td align='left' valign='top'>");
                                            wrapPos = 0;
                                        }
                                    }
                                }
                            }

                            // Param type
                            final TypeSignature paramTypeSig = paramInfo[i].getTypeSignatureOrTypeDescriptor();
                            final String paramTypeStr = useSimpleNames ? paramTypeSig.toStringWithSimpleNames()
                                    : paramTypeSig.toString();
                            htmlEncode(paramTypeStr, buf);
                            wrapPos += paramTypeStr.length();

                            // Param name
                            final String paramName = paramInfo[i].getName();
                            if (paramName != null) {
                                buf.append(" <B>");
                                htmlEncode(paramName, buf);
                                wrapPos += 1 + paramName.length();
                                buf.append("</B>");
                            }
                        }
                    }
                    buf.append(')');
                    buf.append("</td></tr>");
                }
                buf.append("</table>");
                buf.append("</td></tr>");
            }
        }
        buf.append("</table>");
        buf.append(">]");
    }

    /**
     * Generates a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * sizeX and sizeY parameters are the image output size to use (in inches) when GraphViz is asked to render the
     * .dot file.
     *
     * @param classInfoList
     *            the class info list
     * @param sizeX
     *            the size X
     * @param sizeY
     *            the size Y
     * @param showFields
     *            whether to show fields
     * @param showFieldTypeDependencyEdges
     *            whether to show field type dependency edges
     * @param showMethods
     *            whether to show methods
     * @param showMethodTypeDependencyEdges
     *            whether to show method type dependency edges
     * @param showAnnotations
     *            whether to show annotations
     * @param useSimpleNames
     *            whether to use simple names for classes
     * @param scanSpec
     *            the scan spec
     * @return the string
     */
    static String generateGraphVizDotFile(final ClassInfoList classInfoList, final float sizeX, final float sizeY,
            final boolean showFields, final boolean showFieldTypeDependencyEdges, final boolean showMethods,
            final boolean showMethodTypeDependencyEdges, final boolean showAnnotations,
            final boolean useSimpleNames, final ScanSpec scanSpec) {
        final StringBuilder buf = new StringBuilder(1024 * 1024);
        buf.append("digraph {\n");
        buf.append("size=\"").append(sizeX).append(',').append(sizeY).append("\";\n");
        buf.append("layout=dot;\n");
        buf.append("rankdir=\"BT\";\n");
        buf.append("overlap=false;\n");
        buf.append("splines=true;\n");
        buf.append("pack=true;\n");
        buf.append("graph [fontname = \"Courier, Regular\"]\n");
        buf.append("node [fontname = \"Courier, Regular\"]\n");
        buf.append("edge [fontname = \"Courier, Regular\"]\n");

        final ClassInfoList standardClassNodes = classInfoList.getStandardClasses();
        final ClassInfoList interfaceNodes = classInfoList.getInterfaces();
        final ClassInfoList annotationNodes = classInfoList.getAnnotations();

        for (final ClassInfo node : standardClassNodes) {
            buf.append('"').append(node.getName()).append('"');
            labelClassNodeHTML(node, "box", STANDARD_CLASS_COLOR, showFields, showMethods, useSimpleNames, scanSpec,
                    buf);
            buf.append(";\n");
        }

        for (final ClassInfo node : interfaceNodes) {
            buf.append('"').append(node.getName()).append('"');
            labelClassNodeHTML(node, "diamond", INTERFACE_COLOR, showFields, showMethods, useSimpleNames, scanSpec,
                    buf);
            buf.append(";\n");
        }

        for (final ClassInfo node : annotationNodes) {
            buf.append('"').append(node.getName()).append('"');
            labelClassNodeHTML(node, "oval", ANNOTATION_COLOR, showFields, showMethods, useSimpleNames, scanSpec,
                    buf);
            buf.append(";\n");
        }

        final Set<String> allVisibleNodes = new HashSet<>();
        allVisibleNodes.addAll(standardClassNodes.getNames());
        allVisibleNodes.addAll(interfaceNodes.getNames());
        allVisibleNodes.addAll(annotationNodes.getNames());

        buf.append('\n');
        for (final ClassInfo classNode : standardClassNodes) {
            for (final ClassInfo directSuperclassNode : classNode.getSuperclasses().directOnly()) {
                if (directSuperclassNode != null && allVisibleNodes.contains(directSuperclassNode.getName())
                        && !directSuperclassNode.getName().equals("java.lang.Object")) {
                    // class --> superclass
                    buf.append("  \"").append(classNode.getName()).append("\" -> \"")
                            .append(directSuperclassNode.getName()).append("\" [arrowsize=2.5]\n");
                }
            }

            for (final ClassInfo implementedInterfaceNode : classNode.getInterfaces().directOnly()) {
                if (allVisibleNodes.contains(implementedInterfaceNode.getName())) {
                    // class --<> implemented interface
                    buf.append("  \"").append(classNode.getName()).append("\" -> \"")
                            .append(implementedInterfaceNode.getName())
                            .append("\" [arrowhead=diamond, arrowsize=2.5]\n");
                }
            }

            if (showFieldTypeDependencyEdges && classNode.fieldInfo != null) {
                for (final FieldInfo fi : classNode.fieldInfo) {
                    for (final ClassInfo referencedFieldType : fi.findReferencedClassInfo()) {
                        if (allVisibleNodes.contains(referencedFieldType.getName())) {
                            // class --[ ] field type (open box)
                            buf.append("  \"").append(referencedFieldType.getName()).append("\" -> \"")
                                    .append(classNode.getName())
                                    .append("\" [arrowtail=obox, arrowsize=2.5, dir=back]\n");
                        }
                    }
                }
            }

            if (showMethodTypeDependencyEdges && classNode.methodInfo != null) {
                for (final MethodInfo mi : classNode.methodInfo) {
                    for (final ClassInfo referencedMethodType : mi.findReferencedClassInfo()) {
                        if (allVisibleNodes.contains(referencedMethodType.getName())) {
                            // class --[#] field type (open box)
                            buf.append("  \"").append(referencedMethodType.getName()).append("\" -> \"")
                                    .append(classNode.getName())
                                    .append("\" [arrowtail=box, arrowsize=2.5, dir=back]\n");
                        }
                    }
                }
            }
        }
        for (final ClassInfo interfaceNode : interfaceNodes) {
            for (final ClassInfo superinterfaceNode : interfaceNode.getInterfaces().directOnly()) {
                if (allVisibleNodes.contains(superinterfaceNode.getName())) {
                    // interface --<> superinterface
                    buf.append("  \"").append(interfaceNode.getName()).append("\" -> \"")
                            .append(superinterfaceNode.getName()).append("\" [arrowhead=diamond, arrowsize=2.5]\n");
                }
            }
        }
        if (showAnnotations) {
            for (final ClassInfo annotationNode : annotationNodes) {
                for (final ClassInfo annotatedClassNode : annotationNode.getClassesWithAnnotationDirectOnly()) {
                    if (allVisibleNodes.contains(annotatedClassNode.getName())) {
                        // annotated class --o annotation
                        buf.append("  \"").append(annotatedClassNode.getName()).append("\" -> \"")
                                .append(annotationNode.getName()).append("\" [arrowhead=dot, arrowsize=2.5]\n");
                    }
                }
                for (final ClassInfo classWithMethodAnnotationNode : annotationNode
                        .getClassesWithMethodAnnotationDirectOnly()) {
                    if (allVisibleNodes.contains(classWithMethodAnnotationNode.getName())) {
                        // class with method annotation --o method annotation
                        buf.append("  \"").append(classWithMethodAnnotationNode.getName()).append("\" -> \"")
                                .append(annotationNode.getName()).append("\" [arrowhead=odot, arrowsize=2.5]\n");
                    }
                }
                for (final ClassInfo classWithMethodAnnotationNode : annotationNode
                        .getClassesWithFieldAnnotationDirectOnly()) {
                    if (allVisibleNodes.contains(classWithMethodAnnotationNode.getName())) {
                        // class with field annotation --o method annotation
                        buf.append("  \"").append(classWithMethodAnnotationNode.getName()).append("\" -> \"")
                                .append(annotationNode.getName()).append("\" [arrowhead=odot, arrowsize=2.5]\n");
                    }
                }
            }
        }
        buf.append('}');
        return buf.toString();
    }

    /**
     * Generate a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * returned graph shows inter-class dependencies only. The sizeX and sizeY parameters are the image output size
     * to use (in inches) when GraphViz is asked to render the .dot file. You must have called
     * {@link ClassGraph#enableInterClassDependencies()} before scanning to use this method.
     *
     * @param classInfoList
     *            The list of nodes whose dependencies should be plotted in the graph.
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout width in inches.
     * @param includeExternalClasses
     *            If true, include any dependency nodes in the graph that are not themselves in classInfoList.
     * @return the GraphViz file contents.
     * @throws IllegalArgumentException
     *             if this {@link ClassInfoList} is empty or {@link ClassGraph#enableInterClassDependencies()} was
     *             not called before scanning (since there would be nothing to graph).
     */
    static String generateGraphVizDotFileFromInterClassDependencies(final ClassInfoList classInfoList,
            final float sizeX, final float sizeY, final boolean includeExternalClasses) {

        final StringBuilder buf = new StringBuilder(1024 * 1024);
        buf.append("digraph {\n");
        buf.append("size=\"").append(sizeX).append(',').append(sizeY).append("\";\n");
        buf.append("layout=dot;\n");
        buf.append("rankdir=\"BT\";\n");
        buf.append("overlap=false;\n");
        buf.append("splines=true;\n");
        buf.append("pack=true;\n");
        buf.append("graph [fontname = \"Courier, Regular\"]\n");
        buf.append("node [fontname = \"Courier, Regular\"]\n");
        buf.append("edge [fontname = \"Courier, Regular\"]\n");

        final Set<ClassInfo> allVisibleNodes = new HashSet<>(classInfoList);
        if (includeExternalClasses) {
            for (final ClassInfo ci : classInfoList) {
                allVisibleNodes.addAll(ci.getClassDependencies());
            }
        }

        for (final ClassInfo ci : allVisibleNodes) {
            buf.append('"').append(ci.getName()).append('"');
            buf.append("[shape=").append(ci.isAnnotation() ? "oval" : ci.isInterface() ? "diamond" : "box")
                    .append(",style=filled,fillcolor=\"#").append(ci.isAnnotation() ? ANNOTATION_COLOR
                            : ci.isInterface() ? INTERFACE_COLOR : STANDARD_CLASS_COLOR)
                    .append("\",label=");
            buf.append('<');
            buf.append("<table border='0' cellborder='0' cellspacing='1'>");

            // Class modifiers
            buf.append("<tr><td><font point-size='12'>").append(ci.getModifiersStr()).append(' ')
                    .append(ci.isEnum() ? "enum"
                            : ci.isAnnotation() ? "@interface" : ci.isInterface() ? "interface" : "class")
                    .append("</font></td></tr>");

            if (ci.getName().contains(".")) {
                buf.append("<tr><td><font point-size='14'><b>");
                htmlEncode(ci.getPackageName(), buf);
                buf.append("</b></font></td></tr>");
            }

            // Class name
            buf.append("<tr><td><font point-size='20'><b>");
            htmlEncode(ci.getSimpleName(), buf);
            buf.append("</b></font></td></tr>");
            buf.append("</table>");
            buf.append(">];\n");
        }

        buf.append('\n');
        for (final ClassInfo ci : classInfoList) {
            for (final ClassInfo dep : ci.getClassDependencies()) {
                if (includeExternalClasses || allVisibleNodes.contains(dep)) {
                    // class --> dep
                    buf.append("  \"").append(ci.getName()).append("\" -> \"").append(dep.getName())
                            .append("\" [arrowsize=2.5]\n");
                }
            }
        }

        buf.append('}');
        return buf.toString();
    }
}
