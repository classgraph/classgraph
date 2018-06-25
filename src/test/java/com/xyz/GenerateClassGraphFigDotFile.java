package com.xyz;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

public class GenerateClassGraphFigDotFile {
    public static void main(final String[] args) {
        final ScanResult scanResult = new FastClasspathScanner("com.xyz.fig") //
                .ignoreFieldVisibility() //
                .enableFieldInfo() //
                .ignoreMethodVisibility() //
                .enableMethodInfo() //
                .scan();
        System.out.println(scanResult.generateClassGraphDotFile(9.2f, 8.0f));
        // System.out.println(scanResult.toJSON(2));
    }
}
