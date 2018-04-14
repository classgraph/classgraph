package io.github.lukehutch.fastclasspathscanner.utils;

import java.util.BitSet;

public class GraphvizUtils {

    private static final char NBSP_CHAR = (char) 0x00A0;

    private static final BitSet IS_UNICODE_WHITESPACE = new BitSet(1 << 16);

    static {
        // Valid unicode whitespace chars, see:
        // http://stackoverflow.com/questions/4731055/whitespace-matching-regex-java
        final String wsChars = ""//
                + (char) 0x0009 // CHARACTER TABULATION
                + (char) 0x000A // LINE FEED (LF)
                + (char) 0x000B // LINE TABULATION
                + (char) 0x000C // FORM FEED (FF)
                + (char) 0x000D // CARRIAGE RETURN (CR)
                + (char) 0x0020 // SPACE
                + (char) 0x0085 // NEXT LINE (NEL) 
                + NBSP_CHAR // NO-BREAK SPACE
                + (char) 0x1680 // OGHAM SPACE MARK
                + (char) 0x180E // MONGOLIAN VOWEL SEPARATOR
                + (char) 0x2000 // EN QUAD 
                + (char) 0x2001 // EM QUAD 
                + (char) 0x2002 // EN SPACE
                + (char) 0x2003 // EM SPACE
                + (char) 0x2004 // THREE-PER-EM SPACE
                + (char) 0x2005 // FOUR-PER-EM SPACE
                + (char) 0x2006 // SIX-PER-EM SPACE
                + (char) 0x2007 // FIGURE SPACE
                + (char) 0x2008 // PUNCTUATION SPACE
                + (char) 0x2009 // THIN SPACE
                + (char) 0x200A // HAIR SPACE
                + (char) 0x2028 // LINE SEPARATOR
                + (char) 0x2029 // PARAGRAPH SEPARATOR
                + (char) 0x202F // NARROW NO-BREAK SPACE
                + (char) 0x205F // MEDIUM MATHEMATICAL SPACE
                + (char) 0x3000; // IDEOGRAPHIC SPACE
        for (int i = 0; i < wsChars.length(); i++) {
            IS_UNICODE_WHITESPACE.set(wsChars.charAt(i));
        }
    }

    private static boolean isUnicodeWhitespace(final char c) {
        return IS_UNICODE_WHITESPACE.get(c);
    }

    /**
     * Encode HTML-unsafe characters as HTML entities.
     * 
     * @param unsafeStr
     *            The string to escape to make HTML-safe.
     * @param turnNewlineIntoBreak
     *            If true, turn '\n' into a break element in the output.
     * @return The sanitized/escaped HTML-safe string.
     */
    public static void htmlEncode(final CharSequence unsafeStr, final boolean turnNewlineIntoBreak,
            final StringBuilder buf) {
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            switch (c) {
            case '&':
                buf.append("&amp;");
                break;
            case '<':
                buf.append("&lt;");
                break;
            case '>':
                buf.append("&gt;");
                break;
            case '"':
                buf.append("&quot;");
                break;
            case '\'':
                buf.append("&#x27;"); // See http://goo.gl/FzoP6m
                break;
            case '\\':
                buf.append("&lsol;");
                break;
            case '/':
                buf.append("&#x2F;"); // '/' can be a dangerous char if attr values are not quoted
                break;
            // Encode a few common characters that like to get screwed up in some charset/browser variants
            case '—':
                buf.append("&mdash;");
                break;
            case '–':
                buf.append("&ndash;");
                break;
            case '“':
                buf.append("&ldquo;");
                break;
            case '”':
                buf.append("&rdquo;");
                break;
            case '‘':
                buf.append("&lsquo;");
                break;
            case '’':
                buf.append("&rsquo;");
                break;
            case '«':
                buf.append("&laquo;");
                break;
            case '»':
                buf.append("&raquo;");
                break;
            case '£':
                buf.append("&pound;");
                break;
            case '©':
                buf.append("&copy;");
                break;
            case '®':
                buf.append("&reg;");
                break;
            case NBSP_CHAR:
                buf.append("&nbsp;");
                break;
            case '\n':
                if (turnNewlineIntoBreak) {
                    buf.append("<br>");
                } else {
                    buf.append(' '); // Newlines function as whitespace in HTML text
                }
                break;
            default:
                if (c <= 32 || isUnicodeWhitespace(c)) {
                    buf.append(' ');
                } else {
                    buf.append(c);
                }
                break;
            }
        }
    }

    /**
     * Encode HTML-unsafe characters as HTML entities.
     * 
     * @param unsafeStr
     *            The string to escape to make HTML-safe.
     * @return The sanitized/escaped HTML-safe string.
     */
    public static void htmlEncode(final CharSequence unsafeStr, final StringBuilder buf) {
        htmlEncode(unsafeStr, /* turnNewlineIntoBreak = */ false, buf);
    }

    /** Encode HTML-unsafe characters as HTML entities. */
    public static String htmlEncode(final CharSequence unsafeStr) {
        final StringBuilder buf = new StringBuilder(unsafeStr.length() * 2);
        htmlEncode(unsafeStr, buf);
        return buf.toString();
    }
}
