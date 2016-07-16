package io.github.lukehutch.fastclasspathscanner.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/** Fast parser for jar manifest files. */
public class FastManifestParser {
    /**
     * Fast parser for jar manifest files. Doesn't intern strings or create Attribute objects like the Manifest
     * class, so has lower overhead. Note that only the first value for a given key is returned (manifest files can
     * have multiple entries with the same key in different sections).
     */
    public static Map<String, String> parseManifest(File jarFile) {
        final String manifestUrlStr = "jar:" + jarFile.toURI() + "!/META-INF/MANIFEST.MF";
        try (InputStream inputStream = new URL(manifestUrlStr).openStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[16384];
            for (int bytesRead; (bytesRead = inputStream.read(data, 0, data.length)) != -1;) {
                buffer.write(data, 0, bytesRead);
            }
            buffer.flush();
            String manifest = buffer.toString("UTF-8");
            int manifestLen = manifest.length();
            StringBuilder buf = new StringBuilder(manifestLen);
            for (int i = 0; i < manifestLen; i++) {
                char c0 = manifest.charAt(i);
                char c1 = i < manifestLen - 1 ? manifest.charAt(i + 1) : '\n';
                char c2 = i < manifestLen - 2 ? manifest.charAt(i + 2) : '\n';
                if (c0 == '\r' && c1 == '\n') {
                    if (c2 == ' ') {
                        i += 2;
                    } else {
                        buf.append('\n');
                    }
                } else if (c0 == '\r') {
                    if (c1 == ' ') {
                        i += 1;
                    } else {
                        buf.append('\n');
                    }
                } else if (c0 == '\n') {
                    if (c1 == ' ') {
                        i += 1;
                    } else {
                        buf.append('\n');
                    }
                } else {
                    buf.append(c0);
                }
            }
            Map<String, String> manifestContent = new HashMap<>();
            int bufLen = buf.length();
            int curr = 0;
            for (;;) {
                while (curr < bufLen && buf.charAt(curr) == '\n') {
                    curr++;
                }
                if (curr >= bufLen) {
                    break;
                }
                int colonIdx = buf.indexOf(":", curr);
                if (colonIdx < 0) {
                    break;
                }
                char charAfterColon = colonIdx < bufLen - 1 ? buf.charAt(colonIdx + 1) : '\n';
                int idxAfterColonSeparator = charAfterColon == ' ' ? colonIdx + 2 : colonIdx + 1;
                int endIdx = buf.indexOf("\n", idxAfterColonSeparator);
                if (endIdx < 0) {
                    endIdx = bufLen;
                }
                String key = buf.substring(curr, colonIdx);
                String val = buf.substring(idxAfterColonSeparator, endIdx);
                if (!manifestContent.containsKey(key)) {
                    manifestContent.put(key, val);
                }
                curr = endIdx + 1;
            }
            return manifestContent;

        } catch (final IOException e) {
            // Jar does not contain a manifest
            return null;
        }
    }
}
