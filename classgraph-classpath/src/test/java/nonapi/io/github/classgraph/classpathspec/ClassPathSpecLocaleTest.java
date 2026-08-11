package nonapi.io.github.classgraph.classpathspec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Regression test: enableURLScheme must lower-case the scheme locale-independently.
 */
public class ClassPathSpecLocaleTest {
    @Test
    public void enableURLSchemeIsLocaleIndependent() {
        final var original = Locale.getDefault();
        try {
            // Turkish locale: "I".toLowerCase() -> dotless 'ı', so a naive toLowerCase() turns "FILE" into "fıle"
            // and scheme matching breaks.
            Locale.setDefault(new Locale("tr", "TR"));
            final var classPathSpec = new ClassPathSpec();
            classPathSpec.enableURLScheme("FILE");
            assertTrue(classPathSpec.allowedURLSchemes.contains("file"),
                    "scheme should be stored as ASCII 'file' regardless of default locale");
        } finally {
            Locale.setDefault(original);
        }
    }
}
