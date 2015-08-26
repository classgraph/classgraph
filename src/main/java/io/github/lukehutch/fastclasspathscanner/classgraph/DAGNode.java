/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison <luke .dot. hutch .at. gmail .dot. com>
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.classgraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

/**
 * An object to hold class or interface interrelatedness information in a tree or DAG structure.
 */
class DAGNode {
    /** Class or interface name. */
    String name;

    /** Direct superclass (there can be only one) / direct superinterface(s). */
    ArrayList<DAGNode> directSuperNodes = new ArrayList<>(4);

    /** Direct subclass(es) / superinterface(s). */
    ArrayList<DAGNode> directSubNodes = new ArrayList<>(4);

    /** All superclasses, including java.lang.Object / all superinterfaces. */
    HashSet<DAGNode> allSuperNodes = new HashSet<>(4);

    /** All subclasses / subinterfaces. */
    HashSet<DAGNode> allSubNodes = new HashSet<>(4);

    /** This class or interface was encountered on the classpath. */
    public DAGNode(final String name) {
        this.name = name;
    }

    /** This class/interface was referenced as a superclass/superinterface of the given subclass/subinterface. */
    public DAGNode(final String name, final DAGNode subNode) {
        this.name = name;
        addSubNode(subNode);
    }

    /** Connect this node to a subnode. */
    public void addSubNode(final DAGNode subNode) {
        subNode.directSuperNodes.add(this);
        this.directSubNodes.add(subNode);
    }

    /** Topological sort DFS recursion */
    protected <N extends DAGNode> void topoSortRec(final HashSet<N> visited, final ArrayList<N> topoOrder) {
        @SuppressWarnings("unchecked")
        final N thisGeneric = (N) this;
        if (visited.add(thisGeneric)) {
            for (final DAGNode subNode : directSubNodes) {
                subNode.topoSortRec(visited, topoOrder);
            }
            topoOrder.add(thisGeneric);
        }
    }

    /** Perform topological sort on DAG. */
    public static <N extends DAGNode> ArrayList<N> topoSort(final Collection<N> nodes) {
        final ArrayList<N> topoOrder = new ArrayList<>(nodes.size());
        final HashSet<N> visited = new HashSet<>();
        for (final N node : nodes) {
            if (node.directSuperNodes.isEmpty()) {
                // Start the topo sort at each least upper bound
                node.topoSortRec(visited, topoOrder);
            }
        }
        // Reverse the postorder traversal node ordering to get the topological ordering
        for (int i = 0, n = topoOrder.size(), n2 = n / 2; i < n2; i++) {
            final N tmp = topoOrder.get(i);
            topoOrder.set(i, topoOrder.get(n - 1 - i));
            topoOrder.set(n - 1 - i, tmp);
        }
        return topoOrder;
    }

    @Override
    public String toString() {
        return name;
    }
}
