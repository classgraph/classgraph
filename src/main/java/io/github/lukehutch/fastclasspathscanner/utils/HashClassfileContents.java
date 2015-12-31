/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.utils;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;

/**
 * An example wrapper for FastClasspathScanner that hashes the content of classfiles encontered on the classpath in
 * order to enable more precise change detection than provided by timestamp checking.
 *
 */
public class HashClassfileContents {
    private final FastClasspathScanner scanner;
    private final HashMap<String, String> classNameToClassfileHash;

    public HashClassfileContents(final String... packagePrefixesToScan) {
        this.classNameToClassfileHash = new HashMap<>();
        this.scanner = new FastClasspathScanner(packagePrefixesToScan)
        // MD5-hash all files ending in ".class"
                .matchFilenameExtension("class", new FileMatchProcessor() {
                    @Override
                    public void processMatch(final String relativePath, final InputStream inputStream,
                            final int length) throws IOException {
                        final MessageDigest digest;
                        try {
                            digest = MessageDigest.getInstance("MD5");
                        } catch (final NoSuchAlgorithmException e) {
                            throw new RuntimeException(e);
                        }
                        final byte[] buffer = new byte[8192];
                        for (int read; (read = inputStream.read(buffer)) > 0;) {
                            digest.update(buffer, 0, read);
                        }
                        final String hash = "0000000000000000000000000000000"
                                + new BigInteger(1, digest.digest()).toString(16);
                        final String className = relativePath.substring(0, relativePath.length() - 6).replace('/',
                                '.');
                        classNameToClassfileHash.put(className, hash.substring(hash.length() - 32));
                    }
                });
    }

    /**
     * Scans the classpath, and updates the mapping from class name to hash of classfile contents.
     */
    public HashClassfileContents scan() {
        classNameToClassfileHash.clear();
        scanner.scan();
        return this;
    }

    /**
     * Returns the mapping from class name to hash of classfile contents after the call to .scan().
     */
    public HashMap<String, String> getClassNameToClassfileHash() {
        return this.classNameToClassfileHash;
    }
}