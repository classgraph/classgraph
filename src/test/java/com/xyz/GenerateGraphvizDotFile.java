package com.xyz;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class GenerateGraphvizDotFile {
    public static void main(String[] args) {
        System.out.println(new FastClasspathScanner("com.xyz.fig").scan().generateClassGraphDotFile());
    }
}
