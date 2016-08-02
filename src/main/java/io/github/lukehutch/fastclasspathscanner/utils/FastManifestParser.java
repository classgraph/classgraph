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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Fast parser for jar manifest files. */
public class FastManifestParser {
    public boolean isSystemJar;
    public ArrayList<String> classPath;

    public static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";

    /** Parse the manifest file. */
    private void parseManifest(final ZipFile zipFile, final ZipEntry manifestEntry) throws IOException {
        if (manifestEntry != null) {
            try (InputStream inputStream = zipFile.getInputStream(manifestEntry)) {
                final ByteArrayOutputStream byteBuf = new ByteArrayOutputStream();
                byteBuf.write('\n');
                final byte[] data = new byte[16384];
                for (int bytesRead; (bytesRead = inputStream.read(data, 0, data.length)) != -1;) {
                    byteBuf.write(data, 0, bytesRead);
                }
                byteBuf.flush();
                final String manifest = byteBuf.toString("UTF-8");
                final int len = manifest.length();
                this.isSystemJar = manifest.indexOf("\nImplementation-Title: Java Runtime Environment") > 0
                        || manifest.indexOf("\nSpecification-Title: Java Platform API Specification") > 0;
                // Manifest files support three different line terminator types, and entries can be split
                // across lines with a line terminator followed by a space.
                final int classPathIdx = manifest.indexOf("\nClass-Path:");
                if (classPathIdx >= 0) {
                    final StringBuilder buf = new StringBuilder();
                    int curr = classPathIdx + 12;
                    if (curr < len && manifest.charAt(curr) == ' ') {
                        curr++;
                    }
                    for (; curr < len; curr++) {
                        final char c = manifest.charAt(curr);
                        if (c == '\r' && (curr < len - 1 ? manifest.charAt(curr + 1) : '\n') == '\n') {
                            if ((curr < len - 2 ? manifest.charAt(curr + 2) : '\n') == ' ') {
                                curr += 2;
                            } else {
                                break;
                            }
                        } else if (c == '\r') {
                            if ((curr < len - 1 ? manifest.charAt(curr + 1) : '\n') == ' ') {
                                curr += 1;
                            } else {
                                break;
                            }
                        } else if (c == '\n') {
                            if ((curr < len - 1 ? manifest.charAt(curr + 1) : '\n') == ' ') {
                                curr += 1;
                            } else {
                                break;
                            }
                        } else {
                            buf.append(c);
                        }
                    }
                    final String classPath = buf.toString();
                    for (final String classPathEntry : classPath.split(" ")) {
                        if (!classPathEntry.isEmpty()) {
                            if (this.classPath == null) {
                                this.classPath = new ArrayList<>();
                            }
                            this.classPath.add(classPathEntry);
                        }
                    }
                }
            }
        }
    }

    /**
     * Fast parser for jar manifest files. Doesn't intern strings or create Attribute objects like the Manifest
     * class, so has lower overhead. Only extracts a few specific entries from the manifest file, if present.
     * Assumes there is only one of each entry present in the manifest.
     */
    public FastManifestParser(final ZipFile zipFile, final ZipEntry manifestEntry, final LogNode log) {
        try {
            parseManifest(zipFile, manifestEntry);
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception while opening manifest in jarfile " + zipFile, e);
            }
        }
    }

    /**
     * Fast parser for jar manifest files. Doesn't intern strings or create Attribute objects like the Manifest
     * class, so has lower overhead. Only extracts a few specific entries from the manifest file, if present.
     * Assumes there is only one of each entry present in the manifest.
     */
    public FastManifestParser(final ZipFile zipFile, final LogNode log) {
        this(zipFile, zipFile.getEntry(MANIFEST_PATH), log);
    }

    /**
     * Fast parser for jar manifest files. Doesn't intern strings or create Attribute objects like the Manifest
     * class, so has lower overhead. Only extracts a few specific entries from the manifest file, if present.
     * Assumes there is only one of each entry present in the manifest.
     */
    public FastManifestParser(final File jarFile, final LogNode log) {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            parseManifest(zipFile, zipFile.getEntry(MANIFEST_PATH));
        } catch (final IOException e) {
            if (log != null) {
                log.log("Exception while opening jarfile " + jarFile, e);
            }
        }
    }
}
