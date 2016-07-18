package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.utils.LoggedThread.ThreadLog;

/** Fast parser for jar manifest files. */
public class FastManifestParser {
    public boolean isSystemJar;
    public String classPath;

    /** Parse the manifest file. */
    private void parseManifest(final ZipFile jarFile, final ThreadLog log) throws IOException {
        final ZipEntry zipEntry = jarFile.getEntry("META-INF/MANIFEST.MF");
        if (zipEntry != null) {
            try (InputStream inputStream = jarFile.getInputStream(zipEntry)) {
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
                    if (!classPath.isEmpty()) {
                        this.classPath = classPath;
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
    public FastManifestParser(final ZipFile jarFile, final ThreadLog log) {
        try {
            parseManifest(jarFile, log);
        } catch (final IOException e) {
            if (FastClasspathScanner.verbose) {
                log.log("Exception while opening manifest in jarfile " + jarFile + " : " + e);
            }
        }
    }

    /**
     * Fast parser for jar manifest files. Doesn't intern strings or create Attribute objects like the Manifest
     * class, so has lower overhead. Only extracts a few specific entries from the manifest file, if present.
     * Assumes there is only one of each entry present in the manifest.
     */
    public FastManifestParser(final File jarFile, final ThreadLog log) {
        try (ZipFile zipFile = new ZipFile(jarFile)) {
            parseManifest(zipFile, log);
        } catch (final IOException e) {
            if (FastClasspathScanner.verbose) {
                log.log("Exception while opening jarfile " + jarFile + " : " + e);
            }
        }
    }
}
