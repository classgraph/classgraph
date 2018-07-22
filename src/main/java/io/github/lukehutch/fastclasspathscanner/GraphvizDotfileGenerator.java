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
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.ClassInfo.ClassType;
import io.github.lukehutch.fastclasspathscanner.ClassInfo.RelType;

/** Builds a class graph visualization in Graphviz .dot file format. */
class GraphvizDotfileGenerator {

    private static final int PARAM_WRAP_WIDTH = 40;

    private static final char NBSP_CHAR = (char) 0x00A0;

    private static final BitSet IS_UNICODE_WHITESPACE = new BitSet(1 << 16);

    static {
        // Valid unicode whitespace chars, see:
        // http://stackoverflow.com/questions/4731055/whitespace-matching-regex-java
        final String wsChars = ""//
                + (char) 0x0009 // CHARACTER TABULATION
                + (char) 0x000A // LINE FEED (LF)
                + (char) 0x000B // LINE TABULATION
                + (char) 0x000C // FORM FEED (FF)
                + (char) 0x000D // CARRIAGE RETURN (CR)
                + (char) 0x0020 // SPACE
                + (char) 0x0085 // NEXT LINE (NEL) 
                + NBSP_CHAR // NO-BREAK SPACE
                + (char) 0x1680 // OGHAM SPACE MARK
                + (char) 0x180E // MONGOLIAN VOWEL SEPARATOR
                + (char) 0x2000 // EN QUAD 
                + (char) 0x2001 // EM QUAD 
                + (char) 0x2002 // EN SPACE
                + (char) 0x2003 // EM SPACE
                + (char) 0x2004 // THREE-PER-EM SPACE
                + (char) 0x2005 // FOUR-PER-EM SPACE
                + (char) 0x2006 // SIX-PER-EM SPACE
                + (char) 0x2007 // FIGURE SPACE
                + (char) 0x2008 // PUNCTUATION SPACE
                + (char) 0x2009 // THIN SPACE
                + (char) 0x200A // HAIR SPACE
                + (char) 0x2028 // LINE SEPARATOR
                + (char) 0x2029 // PARAGRAPH SEPARATOR
                + (char) 0x202F // NARROW NO-BREAK SPACE
                + (char) 0x205F // MEDIUM MATHEMATICAL SPACE
                + (char) 0x3000; // IDEOGRAPHIC SPACE
        for (int i = 0; i < wsChars.length(); i++) {
            IS_UNICODE_WHITESPACE.set(wsChars.charAt(i));
        }
    }

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
     */
    public static void htmlEncode(final CharSequence unsafeStr, final boolean turnNewlineIntoBreak,
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
            case NBSP_CHAR:
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
     */
    public static void htmlEncode(final CharSequence unsafeStr, final StringBuilder buf) {
        htmlEncode(unsafeStr, /* turnNewlineIntoBreak = */ false, buf);
    }

    /** Encode HTML-unsafe characters as HTML entities. */
    public static String htmlEncode(final CharSequence unsafeStr) {
        final StringBuilder buf = new StringBuilder(unsafeStr.length() * 2);
        htmlEncode(unsafeStr, buf);
        return buf.toString();
    }

    private static void labelClassNodeHTML(final ClassInfo ci, final String shape, final String boxBgColor,
            final boolean showFields, final boolean showMethods, final ScanSpec scanSpec, final StringBuilder buf) {
        buf.append("[shape=" + shape + ",style=filled,fillcolor=\"#" + boxBgColor + "\",label=");
        buf.append("<");
        buf.append("<table border='0' cellborder='0' cellspacing='1'>");

        // Class modifiers
        buf.append("<tr><td>" + ci.getModifiersStr() + " "
                + (ci.isEnum() ? "enum"
                        : ci.isAnnotation() ? "@interface" : ci.isInterface() ? "interface" : "class")
                + "</td></tr>");

        // Package name
        final String className = ci.getClassName();
        final int dotIdx = className.lastIndexOf('.');
        if (dotIdx > 0) {
            buf.append("<tr><td><b>");
            htmlEncode(className.substring(0, dotIdx + 1), buf);
            buf.append("</b></td></tr>");
        }

        // Class name
        buf.append("<tr><td><font point-size='24'><b>");
        htmlEncode(className.substring(dotIdx + 1), buf);
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
        final List<AnnotationInfo> annotationInfo = ci.annotationInfo;
        if (annotationInfo != null && annotationInfo.size() > 0) {
            buf.append("<tr><td colspan='3' bgcolor='" + darkerColor
                    + "'><font point-size='12'><b>ANNOTATIONS</b></font></td></tr>");
            final List<AnnotationInfo> annotationInfoSorted = new ArrayList<>(annotationInfo);
            Collections.sort(annotationInfoSorted);
            for (final AnnotationInfo ai : annotationInfoSorted) {
                String annotationName = ai.getAnnotationName();
                if (!annotationName.startsWith("java.lang.annotation.")) {
                    buf.append("<tr>");
                    buf.append("<td align='center' valign='top'>");
                    htmlEncode(ai.toString(), buf);
                    buf.append("</td></tr>");
                }
            }
        }

        // Fields
        final List<FieldInfo> fieldInfo = ci.fieldInfo;
        if (showFields && fieldInfo != null && fieldInfo.size() > 0) {
            buf.append("<tr><td colspan='3' bgcolor='" + darkerColor + "'><font point-size='12'><b>"
                    + (scanSpec.ignoreFieldVisibility ? "" : "PUBLIC ") + "FIELDS</b></font></td></tr>");
            buf.append("<tr><td cellpadding='0'>");
            buf.append("<table border='0' cellborder='0'>");
            final List<FieldInfo> fieldInfoSorted = new ArrayList<>(fieldInfo);
            Collections.sort(fieldInfoSorted);
            for (final FieldInfo fi : fieldInfoSorted) {
                buf.append("<tr>");
                buf.append("<td align='right' valign='top'>");

                // Field Annotations
                final List<AnnotationInfo> fieldAnnotationInfo = fi.annotationInfo;
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
                htmlEncode(fi.getTypeSignatureOrTypeDescriptor().toString(), buf);
                buf.append("</td>");

                // Field name
                buf.append("<td align='left' valign='top'><b>");
                final String fieldName = fi.getFieldName();
                htmlEncode(fieldName, buf);
                buf.append("</b></td></tr>");
            }
            buf.append("</table>");
            buf.append("</td></tr>");
        }

        // Methods
        final List<MethodInfo> methodInfo = ci.methodInfo;
        if (showMethods && methodInfo != null && methodInfo.size() > 0) {
            buf.append("<tr><td cellpadding='0'>");
            buf.append("<table border='0' cellborder='0'>");
            buf.append("<tr><td colspan='3' bgcolor='" + darkerColor + "'><font point-size='12'><b>"
                    + (scanSpec.ignoreMethodVisibility ? "" : "PUBLIC ") + "METHODS</b></font></td></tr>");
            final List<MethodInfo> methodInfoSorted = new ArrayList<>(methodInfo);
            Collections.sort(methodInfoSorted);
            for (final MethodInfo mi : methodInfoSorted) {
                // Don't list static initializer blocks
                if (!mi.getMethodName().equals("<clinit>")) {
                    buf.append("<tr>");

                    // Method annotations
                    // TODO: wrap this cell if the contents get too long
                    buf.append("<td align='right' valign='top'>");
                    final List<AnnotationInfo> methodAnnotationInfo = mi.annotationInfo;
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
                    if (!mi.getMethodName().equals("<init>")) {
                        // Don't list return type for constructors
                        htmlEncode(mi.getResultType().toString(), buf);
                    } else {
                        buf.append("<b>&lt;constructor&gt;</b>");
                    }
                    buf.append("</td>");

                    // Method name
                    buf.append("<td align='left' valign='top'>");
                    buf.append("<b>");
                    if (mi.getMethodName().equals("<init>")) {
                        // Show class name for constructors
                        htmlEncode(mi.getClassName().substring(mi.getClassName().lastIndexOf('.') + 1), buf);
                    } else {
                        htmlEncode(mi.getMethodName(), buf);
                    }
                    buf.append("</b>&nbsp;");
                    buf.append("</td>");

                    // Method parameters
                    buf.append("<td align='left' valign='top'>");
                    buf.append('(');
                    if (mi.getNumParameters() != 0) {
                        final MethodParameterInfo[] paramInfo = mi.getParameterInfo();
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
                            final String paramTypeStr = paramInfo[i].getTypeSignatureOrTypeDescriptor().toString();
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
            }
            buf.append("</table>");
            buf.append("</td></tr>");
        }
        buf.append("</table>");
        buf.append(">]");
    }

    /**
     * Generates a .dot file which can be fed into GraphViz for layout and visualization of the class graph. The
     * sizeX and sizeY parameters are the image output size to use (in inches) when GraphViz is asked to render the
     * .dot file.
     */
    public static String generateClassGraphDotFile(final ScanResult scanResult, final float sizeX,
            final float sizeY, final boolean showFields, final boolean showMethods, final ScanSpec scanSpec) {
        final StringBuilder buf = new StringBuilder();
        buf.append("digraph {\n");
        buf.append("size=\"" + sizeX + "," + sizeY + "\";\n");
        buf.append("layout=dot;\n");
        buf.append("rankdir=\"BT\";\n");
        buf.append("overlap=false;\n");
        buf.append("splines=true;\n");
        buf.append("pack=true;\n");
        buf.append("graph [fontname = \"Courier, Regular\"]\n");
        buf.append("node [fontname = \"Courier, Regular\"]\n");
        buf.append("edge [fontname = \"Courier, Regular\"]\n");

        final ClassInfoList standardClassNodes = scanResult.getAllStandardClasses();
        final ClassInfoList interfaceNodes = scanResult.getAllInterfaceClasses();
        final ClassInfoList annotationNodes = scanResult.getAllAnnotationClasses();

        for (final ClassInfo node : standardClassNodes) {
            buf.append("\"").append(node.getClassName()).append("\"");
            labelClassNodeHTML(node, "box", "fff2b6", showFields, showMethods, scanSpec, buf);
            buf.append(";\n");
        }

        for (final ClassInfo node : interfaceNodes) {
            buf.append("\"").append(node.getClassName()).append("\"");
            labelClassNodeHTML(node, "diamond", "b6e7ff", showFields, showMethods, scanSpec, buf);
            buf.append(";\n");
        }

        for (final ClassInfo node : annotationNodes) {
            buf.append("\"").append(node.getClassName()).append("\"");
            labelClassNodeHTML(node, "oval", "f3c9ff", showFields, showMethods, scanSpec, buf);
            buf.append(";\n");
        }

        final Set<String> allVisibleNodes = new HashSet<>();
        allVisibleNodes.addAll(standardClassNodes.getClassNames());
        allVisibleNodes.addAll(interfaceNodes.getClassNames());
        allVisibleNodes.addAll(annotationNodes.getClassNames());

        buf.append("\n");
        for (final ClassInfo classNode : standardClassNodes) {
            for (final ClassInfo directSuperclassNode : classNode.getSuperclasses().directOnly()) {
                if (directSuperclassNode != null && allVisibleNodes.contains(directSuperclassNode.getClassName())
                        && !directSuperclassNode.getClassName().equals("java.lang.Object")) {
                    // class --> superclass
                    buf.append("  \"" + classNode.getClassName() + "\" -> \"" + directSuperclassNode.getClassName()
                            + "\" [arrowsize=2.5]\n");
                }
            }

            for (final ClassInfo implementedInterfaceNode : classNode.getImplementedInterfaces().directOnly()) {
                if (allVisibleNodes.contains(implementedInterfaceNode.getClassName())) {
                    // class --<> implemented interface
                    buf.append("  \"" + classNode.getClassName() + "\" -> \""
                            + implementedInterfaceNode.getClassName() + "\" [arrowhead=diamond, arrowsize=2.5]\n");
                }
            }

            final Set<String> referencedFieldTypeNames = new HashSet<>();
            final List<FieldInfo> fieldInfo = classNode.fieldInfo;
            if (fieldInfo != null) {
                for (final FieldInfo fi : fieldInfo) {
                    final TypeSignature fieldSig = fi.getTypeSignature();
                    if (fieldSig != null) {
                        fieldSig.getAllReferencedClassNames(referencedFieldTypeNames);
                    }
                }
            }
            for (final String fieldTypeName : referencedFieldTypeNames) {
                if (allVisibleNodes.contains(fieldTypeName) && !"java.lang.Object".equals(fieldTypeName)) {
                    // class --[ ] field type (open box)
                    buf.append("  \"" + fieldTypeName + "\" -> \"" + classNode.getClassName()
                            + "\" [arrowtail=obox, arrowsize=2.5, dir=back]\n");
                }
            }

            final Set<String> referencedMethodTypeNames = new HashSet<>();
            final List<MethodInfo> methodInfo = classNode.methodInfo;
            if (methodInfo != null) {
                for (final MethodInfo mi : methodInfo) {
                    final MethodTypeSignature methodSig = mi.getTypeSignature();
                    if (methodSig != null) {
                        methodSig.getAllReferencedClassNames(referencedMethodTypeNames);
                    }
                }
            }
            for (final String methodTypeName : referencedMethodTypeNames) {
                if (allVisibleNodes.contains(methodTypeName) && !"java.lang.Object".equals(methodTypeName)) {
                    // class --[#] method type (filled box)
                    buf.append("  \"" + methodTypeName + "\" -> \"" + classNode.getClassName()
                            + "\" [arrowtail=box, arrowsize=2.5, dir=back]\n");
                }
            }
        }
        for (final ClassInfo interfaceNode : interfaceNodes) {
            for (final ClassInfo superinterfaceNode : interfaceNode.getSuperinterfaces().directOnly()) {
                if (allVisibleNodes.contains(superinterfaceNode.getClassName())) {
                    // interface --<> superinterface
                    buf.append("  \"" + interfaceNode.getClassName() + "\" -> \""
                            + superinterfaceNode.getClassName() + "\" [arrowhead=diamond, arrowsize=2.5]\n");
                }
            }
        }
        for (final ClassInfo annotationNode : annotationNodes) {
            for (final ClassInfo annotatedClassNode : annotationNode.getClassesWithAnnotation().directOnly()) {
                if (allVisibleNodes.contains(annotatedClassNode.getClassName())) {
                    // annotated class --o annotation
                    buf.append("  \"" + annotatedClassNode.getClassName() + "\" -> \""
                            + annotationNode.getClassName() + "\" [arrowhead=dot, arrowsize=2.5]\n");
                }
            }
            for (final ClassInfo classWithMethodAnnotationNode : annotationNode
                    .filterClassInfo(RelType.CLASSES_WITH_METHOD_ANNOTATION, ClassType.ALL).directOnly()) {
                if (allVisibleNodes.contains(classWithMethodAnnotationNode.getClassName())) {
                    // class with method annotation --o method annotation
                    buf.append("  \"" + classWithMethodAnnotationNode.getClassName() + "\" -> \""
                            + annotationNode.getClassName() + "\" [arrowhead=odot, arrowsize=2.5]\n");
                }
            }
            for (final ClassInfo classWithMethodAnnotationNode : annotationNode
                    .filterClassInfo(RelType.CLASSES_WITH_FIELD_ANNOTATION, ClassType.ALL).directOnly()) {
                if (allVisibleNodes.contains(classWithMethodAnnotationNode.getClassName())) {
                    // class with field annotation --o method annotation
                    buf.append("  \"" + classWithMethodAnnotationNode.getClassName() + "\" -> \""
                            + annotationNode.getClassName() + "\" [arrowhead=odot, arrowsize=2.5]\n");
                }
            }
        }
        buf.append("}");
        return buf.toString();
    }
}
