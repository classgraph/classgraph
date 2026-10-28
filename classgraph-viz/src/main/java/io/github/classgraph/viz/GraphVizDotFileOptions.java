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

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;

/**
 * Options for the GraphViz .dot file generators in {@link GraphVizDotFile}. Methods return {@code this} for method
 * chaining:
 *
 * <pre>
 * GraphVizDotFile.generate(scanResult, scanResult.getAllClasses(),
 *         new GraphVizDotFileOptions().setLayoutSize(12, 8).hideFields().hideMethods());
 * </pre>
 *
 * <p>
 * Note that
 * {@link GraphVizDotFile#generateFromInterClassDependencies(ScanResult, ClassInfoList, GraphVizDotFileOptions)}
 * only uses {@link #setLayoutSize(float, float)}, {@link #showUnlistedDependencies()}, and
 * {@link #hideUnlistedDependencies()}.
 */
public final class GraphVizDotFileOptions {
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
     * Whether to show edges between classes and the return types, parameter types, and type parameter bounds of
     * their methods and constructors.
     */
    boolean showMethodTypeDependencyEdges = true;

    /**
     * Whether to show the annotations on a class and on its fields, methods, and method parameters within its class
     * node.
     */
    boolean showAnnotations = true;

    /**
     * Whether to show edges between classes and the annotations on them, or on their fields, methods, and method
     * parameters.
     */
    boolean showAnnotationDependencyEdges = true;

    /** Whether to strip the package name from class names in type signatures and annotations. */
    boolean useSimpleNames = true;

    /**
     * Whether the inter-class dependency graph also shows classes that the listed classes depend on but that are
     * not in the list.
     */
    boolean showUnlistedDependencies = true;

    /** Construct a set of GraphViz .dot file options with default values. */
    public GraphVizDotFileOptions() {
        // Empty
    }

    /**
     * Set the image output size to use (in inches) when GraphViz renders the .dot file. The default is 10.5 by 8
     * inches.
     *
     * @param sizeX
     *            The GraphViz layout width in inches.
     * @param sizeY
     *            The GraphViz layout height in inches.
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions setLayoutSize(final float sizeX, final float sizeY) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        return this;
    }

    /**
     * Show fields within class nodes, if {@link ClassGraph#enableFieldInfo()} was called before scanning. (This is
     * the default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions showFields() {
        showFields = true;
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
     * Show edges between classes and the types of their displayed fields, including type arguments, array element
     * types, and erased types. Self-edges are omitted. (This is the default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions showFieldTypeDependencyEdges() {
        showFieldTypeDependencyEdges = true;
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
     * Show methods within class nodes, if {@link ClassGraph#enableMethodInfo()} was called before scanning. (This
     * is the default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions showMethods() {
        showMethods = true;
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
     * Show edges between classes and the return types, parameter types, and type parameter bounds of their
     * displayed methods and constructors, including type arguments, array element types, and erased types.
     * Self-edges are omitted. (This is the default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions showMethodTypeDependencyEdges() {
        showMethodTypeDependencyEdges = true;
        return this;
    }

    /**
     * Do not show edges between classes and the return types, parameter types, and type parameter bounds of their
     * methods and constructors. (These edges are shown by default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideMethodTypeDependencyEdges() {
        showMethodTypeDependencyEdges = false;
        return this;
    }

    /**
     * Show the annotations on a class, and on its fields, methods, and method parameters, within the class node, if
     * {@link ClassGraph#enableAnnotationInfo()} was called before scanning. (This is the default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions showAnnotations() {
        showAnnotations = true;
        return this;
    }

    /**
     * Do not show declaration annotations on a class, or on its fields, methods, and method parameters, within the
     * class node. Annotations targeting only {@link java.lang.annotation.ElementType#TYPE_USE TYPE_USE} remain part
     * of the rendered type signature. (Annotations are shown by default, if
     * {@link ClassGraph#enableAnnotationInfo()} was called before scanning.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideAnnotations() {
        showAnnotations = false;
        return this;
    }

    /**
     * Show edges between classes and the annotations directly present on them (with a filled dot) or on their
     * displayed fields, methods, constructors, and method parameters (with a hollow dot). (This is the default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions showAnnotationDependencyEdges() {
        showAnnotationDependencyEdges = true;
        return this;
    }

    /**
     * Do not show edges between classes and the annotations on them, or on their fields, methods, and method
     * parameters. (These edges are shown by default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideAnnotationDependencyEdges() {
        showAnnotationDependencyEdges = false;
        return this;
    }

    /**
     * Strip the package name from class names in method and field type signatures, in the bounds of class type
     * parameters, and in annotations, leaving the simple name. (This is the default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions useSimpleNames() {
        useSimpleNames = true;
        return this;
    }

    /**
     * Show fully qualified class names in method and field type signatures, in the bounds of class type parameters,
     * and in annotations. (By default, simple names are used.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions useFullyQualifiedNames() {
        useSimpleNames = false;
        return this;
    }

    /**
     * In the inter-class dependency graph, include nodes for non-synthetic classes that the listed classes depend
     * on even if they are not in the list of classes to graph. (This is the default.)
     *
     * <p>
     * External classes (see {@link io.github.classgraph.ClassInfo#isExternalClass()}) are recorded as dependencies
     * only if {@link ClassGraph#enableExternalClasses()} was called before scanning.
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions showUnlistedDependencies() {
        showUnlistedDependencies = true;
        return this;
    }

    /**
     * In the inter-class dependency graph, include only the classes in the list of classes to graph and the
     * dependency edges between them. (By default, unlisted dependencies of the listed classes are also included.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideUnlistedDependencies() {
        showUnlistedDependencies = false;
        return this;
    }
}
