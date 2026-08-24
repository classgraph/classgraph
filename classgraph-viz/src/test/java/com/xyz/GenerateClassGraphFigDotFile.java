package com.xyz;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.viz.GraphVizDotFile;
import io.github.classgraph.viz.GraphVizDotFileOptions;

/**
 * GenerateClassGraphFigDotFile.
 */
public class GenerateClassGraphFigDotFile {
    /**
     * The main method.
     *
     * @param args
     *            the arguments
     */
    public static void main(final String[] args) {
        try (var scanResult = new ClassGraph().enableClasspath() //
                .acceptPackages("com.xyz.fig") //
                .ignoreFieldVisibility() //
                .enableFieldInfo() //
                .ignoreMethodVisibility() //
                .enableMethodInfo() //
                .enableAnnotationInfo() //
                .scan()) {
            System.out.println(GraphVizDotFile.generate(scanResult, scanResult.getAllClasses(),
                    new GraphVizDotFileOptions().setLayoutSize(9.2f, 8.0f)));
        }
    }
}
