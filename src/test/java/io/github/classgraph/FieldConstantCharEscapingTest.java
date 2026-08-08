package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link FieldInfo#toString()} escaped a single quote in a {@code char} constant initializer value with
 * {@code replaceAll("'", "\\'")}. In a {@code replaceAll} replacement string, a backslash escapes the character
 * that follows it, so that replaced a single quote with a single quote, i.e. it did nothing. The {@code String}
 * branch alongside it correctly uses {@link String#replace(CharSequence, CharSequence)}, which is literal.
 */
public class FieldConstantCharEscapingTest {
    /** Constants whose values need escaping when rendered as Java char literals. */
    public static class Constants {
        /** A single quote. */
        public static final char SINGLE_QUOTE = '\'';

        /** A backslash. */
        public static final char BACKSLASH = '\\';
    }

    /** Single quotes and backslashes in char constant initializer values are escaped. */
    @Test
    public void charConstantsAreEscaped() {
        try (ScanResult scanResult = new ClassGraph()
                .acceptPackagesNonRecursive(FieldConstantCharEscapingTest.class.getPackage().getName())
                .enableFieldInfo().enableStaticFinalFieldConstantInitializerValues().scan()) {
            final ClassInfo constants = scanResult.getClassInfo(Constants.class.getName());
            assertThat(constants).isNotNull();
            assertThat(constants.getFieldInfo("SINGLE_QUOTE").toString()).endsWith("SINGLE_QUOTE = '\\''");
            assertThat(constants.getFieldInfo("BACKSLASH").toString()).endsWith("BACKSLASH = '\\\\'");
        }
    }
}
