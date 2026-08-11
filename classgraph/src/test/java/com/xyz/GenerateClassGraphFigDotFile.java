package com.xyz;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.GraphVizDotFileOptions;

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
        try (var scanResult = new ClassGraph() //
                .acceptPackages("com.xyz.fig") //
                .ignoreFieldVisibility() //
                .enableFieldInfo() //
                .ignoreMethodVisibility() //
                .enableMethodInfo() //
                .enableAnnotationInfo() //
                .scan()) {
            System.out.println(scanResult.getAllClasses()
                    .generateGraphVizDotFile(new GraphVizDotFileOptions().layoutSize(9.2f, 8.0f)));
        }
    }
}
