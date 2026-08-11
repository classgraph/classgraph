package io.github.classgraph.classpath.internal.spec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Regression test: enableURLScheme must lower-case the scheme locale-independently.
 */
public class ClasspathSpecLocaleTest {
    @Test
    public void enableURLSchemeIsLocaleIndependent() {
        final var original = Locale.getDefault();
        try {
            // Turkish locale: "I".toLowerCase() -> dotless 'ı', so a naive toLowerCase() turns "FILE" into "fıle"
            // and scheme matching breaks.
            Locale.setDefault(new Locale("tr", "TR"));
            final var classpathSpec = new ClasspathSpec();
            classpathSpec.enableURLScheme("FILE");
            assertTrue(classpathSpec.allowedURLSchemes.contains("file"),
                    "scheme should be stored as ASCII 'file' regardless of default locale");
        } finally {
            Locale.setDefault(original);
        }
    }
}
