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
 * A node representing classes, interfaces or annotations a tree or DAG structure.
 */
class DAGNode {
    /** Class, interface or annotation name. */
    final String name;

    /** Direct super-nodes. */
    ArrayList<DAGNode> directSuperNodes = new ArrayList<>(4);

    /** Direct sub-nodes. */
    ArrayList<DAGNode> directSubNodes = new ArrayList<>(4);

    /** All super-nodes. */
    HashSet<DAGNode> allSuperNodes = new HashSet<>(4);

    /** All sub-nodes. */
    HashSet<DAGNode> allSubNodes = new HashSet<>(4);

    /**
     * For annotation classes: the nodes corresponding to classes annotated by this annotation.
     * 
     * For standard classes: the nodes corresponding to the interfaces that the class implements.
     */
    ArrayList<DAGNode> crossLinkedNodes = new ArrayList<>(2);

    // -------------------------------------------------------------------------------------------------------------

    /** A node representing a class, interface or annotation. */
    public DAGNode(final String name) {
        this.name = name;
    }

    /**
     * Connect this node to a sub-node and vice versa.
     */
    public DAGNode addSubNode(final DAGNode subNode) {
        subNode.directSuperNodes.add(this);
        this.directSubNodes.add(subNode);
        return this;
    }

    /**
     * Connect this node to a different node type (for annotations, the cross-linked class is a class annotated by
     * this annotation; for regular classes, the cross-linked class is an interface that the class implements).
     */
    public DAGNode addCrossLink(final DAGNode crossLinkedNode) {
        this.crossLinkedNodes.add(crossLinkedNode);
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the upwards and downwards transitive closure for each node in a graph. Assumes the graph is a DAG in
     * general, but handles cycles (which may occur in the case of meta-annotations). Updates the allSubNodes and
     * allSuperNodes fields of each node based on the downwards and upwards transitive closures respectively.
     */
    public static void findTransitiveClosure(final Collection<? extends DAGNode> nodes) {
        // Find top nodes as initial active set
        HashSet<DAGNode> activeTopDownNodes = new HashSet<>();
        for (final DAGNode node : nodes) {
            if (node.directSuperNodes.isEmpty()) {
                activeTopDownNodes.addAll(node.directSubNodes);
            }
        }
        // Use DP-style "wavefront" to find top-down transitive closure, even if there are cycles
        while (!activeTopDownNodes.isEmpty()) {
            final HashSet<DAGNode> activeTopDownNodesNext = new HashSet<>(activeTopDownNodes.size());
            for (final DAGNode node : activeTopDownNodes) {
                boolean changed = node.allSuperNodes.addAll(node.directSuperNodes);
                for (final DAGNode superNode : node.directSuperNodes) {
                    changed |= node.allSuperNodes.addAll(superNode.allSuperNodes);
                }
                if (changed) {
                    for (final DAGNode subNode : node.directSubNodes) {
                        activeTopDownNodesNext.add(subNode);
                    }
                }
            }
            activeTopDownNodes = activeTopDownNodesNext;
        }

        // Find bottom nodes as initial active set
        HashSet<DAGNode> activeBottomUpNodes = new HashSet<>();
        for (final DAGNode node : nodes) {
            if (node.directSubNodes.isEmpty()) {
                activeBottomUpNodes.addAll(node.directSuperNodes);
            }
        }
        // Use DP-style "wavefront" to find bottom-up transitive closure, even if there are cycles
        while (!activeBottomUpNodes.isEmpty()) {
            final HashSet<DAGNode> activeBottomUpNodesNext = new HashSet<>(activeBottomUpNodes.size());
            for (final DAGNode node : activeBottomUpNodes) {
                boolean changed = node.allSubNodes.addAll(node.directSubNodes);
                for (final DAGNode subNode : node.directSubNodes) {
                    changed |= node.allSubNodes.addAll(subNode.allSubNodes);
                }
                if (changed) {
                    for (final DAGNode superNode : node.directSuperNodes) {
                        activeBottomUpNodesNext.add(superNode);
                    }
                }
            }
            activeBottomUpNodes = activeBottomUpNodesNext;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    @Override
    public String toString() {
        return name;
    }
}
