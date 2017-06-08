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
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;

public class URLPathEncoder {

    private static boolean[] safe = new boolean[256];

    static {
        for (int i = 'a'; i <= 'z'; i++) {
            safe[i] = true;
        }
        for (int i = 'A'; i <= 'Z'; i++) {
            safe[i] = true;
        }
        for (int i = '0'; i <= '9'; i++) {
            safe[i] = true;
        }
        // "safe" rule
        safe['$'] = safe['-'] = safe['_'] = safe['.'] = safe['+'] = true;
        // "extra" rule
        safe['!'] = safe['*'] = safe['\''] = safe['('] = safe[')'] = safe[','] = true;
        // Only include "/" from "fsegment" and "hsegment" rules (exclude ':', '@', '&' and '=' for safety)
        safe['/'] = true;
    }

    private static final char[] hexadecimal = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C',
            'D', 'E', 'F' };

    /** Encode a URL path using percent-encoding. '/' is not encoded. */
    public static String encodePath(final String path) {
        final StringBuilder encodedPath = new StringBuilder(path.length() * 2);
        final ByteArrayOutputStream utf8bytes = new ByteArrayOutputStream(10);
        OutputStreamWriter writer;
        try {
            writer = new OutputStreamWriter(utf8bytes, "UTF8");
        } catch (final UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        for (int i = 0; i < path.length(); i++) {
            final int c = path.charAt(i);
            if (c < 256 && safe[c]) {
                encodedPath.append((char) c);
            } else {
                try {
                    writer.write(c);
                    writer.flush();
                } catch (final IOException e) {
                    utf8bytes.reset();
                    continue;
                }
                final byte[] charBytes = utf8bytes.toByteArray();
                for (int j = 0; j < charBytes.length; j++) {
                    final byte b = charBytes[j];
                    final int low = (b & 0x0f);
                    final int high = ((b & 0xf0) >> 4);
                    encodedPath.append('%');
                    encodedPath.append(hexadecimal[high]);
                    encodedPath.append(hexadecimal[low]);
                }
                utf8bytes.reset();
            }
        }
        return encodedPath.toString();
    }
}
