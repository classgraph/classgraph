package com.xyz;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.ScanResult;

public class GenerateClassGraphFigDotFile {
    public static void main(final String[] args) {
        final ScanResult scanResult = new FastClasspathScanner() //
                .whitelistPackages("com.xyz.fig") //
                .ignoreFieldVisibility() //
                .enableFieldInfo() //
                .ignoreMethodVisibility() //
                .enableMethodInfo() //
                .enableAnnotationInfo() //
                .scan();
        System.out.println(scanResult.getAllClasses().generateGraphVizDotFile(9.2f, 8.0f));
        // System.out.println(scanResult.toJSON(2));
    }
}
