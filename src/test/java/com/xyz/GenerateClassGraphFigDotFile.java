package com.xyz;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class GenerateClassGraphFigDotFile {
    public static void main(final String[] args) {
        System.out.println(new FastClasspathScanner("com.xyz.fig").scan().generateClassGraphDotFile(9.2f, 8.0f));
    }
}
