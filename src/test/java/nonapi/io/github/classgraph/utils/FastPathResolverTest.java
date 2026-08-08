package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Tests for {@link FastPathResolver}. */
public class FastPathResolverTest {
    /**
     * A {@code "jrt:"} URL must have its scheme recognized, so that the scheme is
     * normalized to lowercase and the path after it is treated as an absolute path.
     */
    @Test
    public void jrtSchemeIsRecognized() {
        assertThat(FastPathResolver.resolve("jrt:/modules/java.base")).isEqualTo("jrt:/modules/java.base");
        // The scheme is case-insensitive, and is normalized to lowercase
        assertThat(FastPathResolver.resolve("JRT:/modules/java.base")).isEqualTo("jrt:/modules/java.base");
        // (A doubled separator after the scheme, "jrt://modules/java.base", is not
        // tested here: on Windows a
        // path starting with "//" is deliberately kept doubled, since it may be a UNC
        // path (#736))
    }

    /**
     * A doubled {@code "jar:"} prefix (produced by some servlet containers for a
     * jar nested within a WAR file) must not send the scheme-stripping loop round
     * forever.
     */
    @Test
    public void doubledJarSchemeTerminates() {
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThat(FastPathResolver.resolve("jar:jar:file:/a/b.war!/WEB-INF/lib/c.jar!/"))
                        .isEqualTo(FastPathResolver.resolve("jar:file:/a/b.war!/WEB-INF/lib/c.jar!/")));
    }
}
