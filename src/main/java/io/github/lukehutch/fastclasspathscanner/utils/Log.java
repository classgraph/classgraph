package io.github.lukehutch.fastclasspathscanner.utils;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class Log {
    public static void log(final String msg) {
        System.err.println(FastClasspathScanner.class.getSimpleName() + ": " + msg);
    }
}
