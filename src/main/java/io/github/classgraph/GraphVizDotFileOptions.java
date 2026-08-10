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

import org.jspecify.annotations.Nullable;

/**
 * Options for the GraphViz .dot file generators on {@link ClassInfoList}. A freshly constructed instance holds the
 * defaults; each method switches one option away from its default and returns {@code this}, so options can be
 * chained:
 *
 * <pre>
 * classInfoList.generateGraphVizDotFile(new GraphVizDotFileOptions().layoutSize(12, 8).hideFields().hideMethods());
 * </pre>
 *
 * <p>
 * {@link ClassInfoList#generateGraphVizDotFileFromInterClassDependencies(GraphVizDotFileOptions)} draws a different
 * graph, and reads only {@link #layoutSize(float, float)}, {@link #includeExternalClasses()} and
 * {@link #excludeExternalClasses()} — the options that show or hide the contents of a class node have no effect on
 * it.
 */
public class GraphVizDotFileOptions {
    /** The GraphViz layout width, in inches. */
    float sizeX = 10.5f;

    /** The GraphViz layout height, in inches. */
    float sizeY = 8.0f;

    /** Whether to show fields within class nodes. */
    boolean showFields = true;

    /** Whether to show edges between classes and the types of their fields. */
    boolean showFieldTypeDependencyEdges = true;

    /** Whether to show methods within class nodes. */
    boolean showMethods = true;

    /**
     * Whether to show edges between classes and the return types and parameter types of their methods.
     */
    boolean showMethodTypeDependencyEdges = true;

    /** Whether to show annotations within class nodes. */
    boolean showAnnotations = true;

    /** Whether to show edges between classes and the annotations on them. */
    boolean showAnnotationDependencyEdges = true;

    /** Whether to strip the package name from class names in type signatures. */
    boolean useSimpleNames = true;

    /**
     * Whether to show external classes in the inter-class dependency graph, or null to follow the scan's own
     * {@link ClassGraph#enableExternalClasses()} setting.
     */
    @Nullable
    Boolean includeExternalClasses;

    /** Construct a set of GraphViz .dot file options, with every option at its default. */
    public GraphVizDotFileOptions() {
        // Empty
    }

    /**
     * Set the image output size to use (in inches) when GraphViz is asked to render the .dot file. The default is
     * 10.5 by 8 inches.
     *
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout height in inches.
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions layoutSize(final float sizeX, final float sizeY) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        return this;
    }

    /**
     * Do not show fields within class nodes. (Fields are shown by default, if {@link ClassGraph#enableFieldInfo()}
     * was called before scanning.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideFields() {
        showFields = false;
        return this;
    }

    /**
     * Do not show edges between classes and the types of their fields. (These edges are shown by default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideFieldTypeDependencyEdges() {
        showFieldTypeDependencyEdges = false;
        return this;
    }

    /**
     * Do not show methods within class nodes. (Methods are shown by default, if
     * {@link ClassGraph#enableMethodInfo()} was called before scanning.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideMethods() {
        showMethods = false;
        return this;
    }

    /**
     * Do not show edges between classes and the return types and parameter types of their methods. (These edges are
     * shown by default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideMethodTypeDependencyEdges() {
        showMethodTypeDependencyEdges = false;
        return this;
    }

    /**
     * Do not show annotations within class nodes. (Annotations on a class, and on its fields, its methods and their
     * parameters, are shown by default, if {@link ClassGraph#enableAnnotationInfo()} was called before scanning.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideAnnotations() {
        showAnnotations = false;
        return this;
    }

    /**
     * Do not show edges between classes and the annotations on them. (These edges are shown by default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideAnnotationDependencyEdges() {
        showAnnotationDependencyEdges = false;
        return this;
    }

    /**
     * Show class names in method and field type signatures fully qualified. (By default the package name is
     * stripped, leaving the simple name.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions useFullyQualifiedNames() {
        useSimpleNames = false;
        return this;
    }

    /**
     * Show "external classes" (non-accepted classes) in the inter-class dependency graph. This has an effect only
     * if {@link ClassGraph#enableExternalClasses()} was called before scanning. By default the graph follows that
     * setting.
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions includeExternalClasses() {
        includeExternalClasses = Boolean.TRUE;
        return this;
    }

    /**
     * Do not show "external classes" (non-accepted classes) in the inter-class dependency graph, even if
     * {@link ClassGraph#enableExternalClasses()} was called before scanning. By default the graph follows that
     * setting.
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions excludeExternalClasses() {
        includeExternalClasses = Boolean.FALSE;
        return this;
    }
}
