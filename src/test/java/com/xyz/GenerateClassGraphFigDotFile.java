package com.xyz;

import io.github.classgraph.ClassGraph;

/**
 * GenerateClassGraphFigDotFile.
 */
public class GenerateClassGraphFigDotFile {
    /**
     * The main method.
     *
     * @param args the arguments
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
            System.out.println(scanResult.getAllClasses().generateGraphVizDotFile(9.2f, 8.0f));
            // System.out.println(scanResult.toJSON(2));
        }
    }
}
