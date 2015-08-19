package io.github.lukehutch.fastclasspathscanner.utils;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Log {
    public static void log(String msg) {
        System.out.println(FastClasspathScanner.class.getSimpleName() + ": " + msg);
    }
}
