package io.github.classgraph.classpath.internal.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/** Tests for {@link ClasspathSpec}. */
public class ClasspathSpecTest {
    /** A valid URL scheme is accepted, in any case, and stored in lowercase. */
    @Test
    public void aValidURLSchemeIsEnabled() {
        final var classpathSpec = new ClasspathSpec();
        classpathSpec.enableURLScheme("s3");
        classpathSpec.enableURLScheme("HTTP");
        classpathSpec.enableURLScheme("view-source");
        classpathSpec.enableURLScheme("ms-help");
        classpathSpec.enableURLScheme("z39.50r");
        assertThat(classpathSpec.allowedURLSchemes).containsExactlyInAnyOrder("s3", "http", "view-source",
                "ms-help", "z39.50r");
    }

    /** A string that is not a URL scheme is rejected, rather than being stored where it can never match. */
    @Test
    public void aStringThatIsNotAURLSchemeIsRejected() {
        final var classpathSpec = new ClasspathSpec();
        // The commonest mistake: including the scheme's trailing ':'
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.enableURLScheme("s3:"));
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.enableURLScheme("s3://"));
        // A scheme has to start with a letter
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.enableURLScheme("3s"));
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.enableURLScheme("-s3"));
        // A scheme cannot contain a space or a path separator
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.enableURLScheme("my scheme"));
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.enableURLScheme("a/b"));
        // A one-character scheme is indistinguishable from a Windows drive letter
        assertThatIllegalArgumentException().isThrownBy(() -> classpathSpec.enableURLScheme("s"));
        assertThat(classpathSpec.allowedURLSchemes).isNull();
    }
}
