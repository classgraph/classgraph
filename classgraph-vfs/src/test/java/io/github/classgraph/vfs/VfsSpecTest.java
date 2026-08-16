package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The settings a {@link Vfs} is constructed with. */
public class VfsSpecTest {
    /** Every setting starts at its documented default. */
    @Test
    public void everySettingStartsAtItsDefault() {
        final var vfsSpec = new VfsSpec();
        assertThat(vfsSpec.isNestedJarsEnabled()).isEqualTo(VfsSpec.DEFAULT_ENABLE_NESTED_JARS);
        assertThat(vfsSpec.isMultiReleaseVersionsEnabled())
                .isEqualTo(VfsSpec.DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS);
        assertThat(vfsSpec.getMaxBufferedJarRAMSize()).isEqualTo(VfsSpec.DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE);
        assertThat(vfsSpec.getAllowedURLSchemes()).isEmpty();
    }

    /** Every setter returns the same object, so that settings can be chained. */
    @Test
    public void settersChain() {
        final var vfsSpec = new VfsSpec();
        assertThat(vfsSpec.disableNestedJars().enableMultiReleaseVersions().setMaxBufferedJarRAMSize(65_536)
                .enableURLScheme("https")).isSameAs(vfsSpec);

        assertThat(vfsSpec.isNestedJarsEnabled()).isFalse();
        assertThat(vfsSpec.isMultiReleaseVersionsEnabled()).isTrue();
        assertThat(vfsSpec.getMaxBufferedJarRAMSize()).isEqualTo(65_536);
        assertThat(vfsSpec.getAllowedURLSchemes()).containsExactly("https");

        assertThat(vfsSpec.enableNestedJars().disableMultiReleaseVersions()).isSameAs(vfsSpec);
        assertThat(vfsSpec.isNestedJarsEnabled()).isTrue();
        assertThat(vfsSpec.isMultiReleaseVersionsEnabled()).isFalse();
    }

    /** A URL scheme is lowercased, added to the schemes already allowed, and published as an unmodifiable set. */
    @Test
    public void allowedURLSchemesAccumulate() {
        final var vfsSpec = new VfsSpec().enableURLScheme("HTTPS").enableURLScheme("http");
        assertThat(vfsSpec.getAllowedURLSchemes()).containsExactlyInAnyOrder("https", "http");
        assertThatThrownBy(() -> vfsSpec.getAllowedURLSchemes().add("ftp"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** An invalid URL scheme is rejected. */
    @Test
    public void anInvalidURLSchemeIsRejected() {
        assertThatThrownBy(() -> new VfsSpec().enableURLScheme("c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VfsSpec().enableURLScheme("http:"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The settings are described in the verbose log. */
    @Test
    public void theSettingsAreDescribed() {
        assertThat(new VfsSpec().enableURLScheme("https").setMaxBufferedJarRAMSize(1024).toString()).contains(
                "nestedJars: true", "multiReleaseVersions: false", "allowedURLSchemes: [https]",
                "maxBufferedJarRAMSize: 1024");
    }
}
