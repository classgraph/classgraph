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
 * Options for the GraphViz .dot file generators in {@link GraphVizDotFile}. A freshly constructed instance holds
 * the defaults; each method sets one option and returns {@code this}, so options can be chained:
 *
 * <pre>
 * GraphVizDotFile.generate(scanResult, scanResult.getAllClasses(),
 *         new GraphVizDotFileOptions().setLayoutSize(12, 8).hideFields().hideMethods());
 * </pre>
 *
 * <p>
 * Every option can be set either way round, so an instance handed on from elsewhere can be moved to whichever
 * setting is wanted, rather than only away from the default.
 *
 * <p>
 * {@link GraphVizDotFile#generateFromInterClassDependencies(ScanResult, ClassInfoList, GraphVizDotFileOptions)}
 * draws a different graph, and reads only {@link #setLayoutSize(float, float)}, {@link #showUnlistedDependencies()}
 * and {@link #hideUnlistedDependencies()} -- the options that show or hide the contents of a class node have no
 * effect on it.
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
     * Whether to show edges between classes and the return types, parameter types and type parameter bounds of
     * their methods and constructors.
     */
    boolean showMethodTypeDependencyEdges = true;

    /**
     * Whether to show the annotations on a class and on its fields, methods and method parameters within its class
     * node. Annotations that apply only to types, which are part of a field, parameter or result type, are shown
     * either way.
     */
    boolean showAnnotations = true;

    /**
     * Whether to show edges between classes and the annotations on them, or on their fields, methods and method
     * parameters.
     */
    boolean showAnnotationDependencyEdges = true;

    /** Whether to strip the package name from class names in type signatures and annotations. */
    boolean useSimpleNames = true;

    /**
     * Whether the inter-class dependency graph also shows the classes that the listed classes depend on but that
     * are not in the list.
     */
    boolean showUnlistedDependencies = true;

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
     * Show edges between classes and the types of their fields. (This is the default.)
     *
     * <p>
     * Each class, interface or annotation is joined by one edge to each class named in the type of any of its
     * fields, including in type arguments and array element types, if that class has a node in the graph. The
     * fields that are not listed in a class node -- {@code serialVersionUID}, and the fields that the compiler
     * generated, such as {@code this$0} -- are skipped. Type variables are not resolved, but the erased type of
     * each field is read as well, so a field of type {@code E}, where {@code E extends Entity}, is joined to
     * {@code Entity}. The annotations on a field are not part of its type (see
     * {@link #showAnnotationDependencyEdges()}). A class is not joined to itself.
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
     * Show edges between classes and the return types, parameter types and type parameter bounds of their methods
     * and constructors. (This is the default.)
     *
     * <p>
     * Each class, interface or annotation is joined by one edge to each class named in those types of any of its
     * methods and constructors, including in type arguments and array element types, if that class has a node in
     * the graph. The methods and parameters that are not listed in a class node -- {@code hashCode()},
     * {@code toString()}, {@code equals(Object)}, and the methods and parameters that the compiler generated, such
     * as bridge methods and the name and ordinal parameters of an enum constructor -- are skipped. Type variables
     * are not resolved, but the erased types of each method are read as well, so a method returning {@code E},
     * where {@code E extends Entity}, is joined to {@code Entity}. The exceptions a method throws, and the
     * annotations on a method and on its parameters, are not among its types (see
     * {@link #showAnnotationDependencyEdges()}). A class is not joined to itself.
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions showMethodTypeDependencyEdges() {
        showMethodTypeDependencyEdges = true;
        return this;
    }

    /**
     * Do not show edges between classes and the return types, parameter types and type parameter bounds of their
     * methods and constructors. (These edges are shown by default.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideMethodTypeDependencyEdges() {
        showMethodTypeDependencyEdges = false;
        return this;
    }

    /**
     * Show the annotations on a class, and on its fields, its methods and their parameters, within the class node,
     * if {@link ClassGraph#enableAnnotationInfo()} was called before scanning. (This is the default.)
     *
     * <p>
     * An annotation that applies both to a declaration and to the declared type (one whose
     * {@link java.lang.annotation.Target @Target} includes {@link java.lang.annotation.ElementType#TYPE_USE
     * TYPE_USE} as well as, for example, {@link java.lang.annotation.ElementType#FIELD FIELD}) is shown once, as an
     * annotation of the field, method or parameter.
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions showAnnotations() {
        showAnnotations = true;
        return this;
    }

    /**
     * Do not show the annotations on a class, or on its fields, its methods and their parameters, within the class
     * node. (They are shown by default, if {@link ClassGraph#enableAnnotationInfo()} was called before scanning.)
     *
     * <p>
     * This also hides an annotation that applies both to a declaration and to the declared type, such as
     * {@code @NotNull} from Jakarta Bean Validation. An annotation that applies only to types (whose
     * {@link java.lang.annotation.Target @Target} is {@link java.lang.annotation.ElementType#TYPE_USE TYPE_USE}
     * alone, such as {@code @NonNull} from JSpecify) is part of the type it is written on, whether that is the
     * whole field type, as in {@code @NonNull String}, or a part of it, as in {@code List<@NonNull String>}, and is
     * still shown.
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideAnnotations() {
        showAnnotations = false;
        return this;
    }

    /**
     * Show edges between classes and the annotations on them, or on their fields, methods and method parameters.
     * (This is the default.)
     *
     * <p>
     * Each class, interface or annotation is joined by one edge (with a filled dot) to each annotation directly on
     * it, and by one edge (with a hollow dot) to each annotation directly on any of its fields, methods,
     * constructors or method parameters, if the annotation has a node in the graph.
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions showAnnotationDependencyEdges() {
        showAnnotationDependencyEdges = true;
        return this;
    }

    /**
     * Do not show edges between classes and the annotations on them, or on their fields, methods and method
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
     * Show class names in method and field type signatures, in the bounds of class type parameters, and in
     * annotations fully qualified. (By default the package name is stripped, leaving the simple name.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions useFullyQualifiedNames() {
        useSimpleNames = false;
        return this;
    }

    /**
     * In the inter-class dependency graph, also give a node to each class that a listed class depends on but that
     * is not in the list of classes to graph, so that every dependency edge of the listed classes is drawn. (This
     * is the default.)
     *
     * <p>
     * An external class (see {@link io.github.classgraph.ClassInfo#isExternalClass()}) is a dependency only if
     * {@link ClassGraph#enableExternalClasses()} was called before scanning. To leave the external classes out of
     * the graph, leave them out of the list of classes to graph, and call {@link #hideUnlistedDependencies()}.
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions showUnlistedDependencies() {
        showUnlistedDependencies = true;
        return this;
    }

    /**
     * In the inter-class dependency graph, draw only the classes in the list of classes to graph, and only the
     * dependency edges between them. (By default, the classes that the listed classes depend on are drawn too.)
     *
     * @return this {@link GraphVizDotFileOptions}, for method chaining.
     */
    public GraphVizDotFileOptions hideUnlistedDependencies() {
        showUnlistedDependencies = false;
        return this;
    }
}
