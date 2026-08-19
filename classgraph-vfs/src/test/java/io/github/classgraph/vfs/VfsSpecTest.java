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
        assertThat(vfsSpec.getDeniedURLSchemes()).isEmpty();
    }

    /** Every setter returns the same object, so that settings can be chained. */
    @Test
    public void settersChain() {
        final var vfsSpec = new VfsSpec();
        assertThat(vfsSpec.disableNestedJars().enableMultiReleaseVersions().setMaxBufferedJarRAMSize(65_536)
                .disableURLScheme("https")).isSameAs(vfsSpec);

        assertThat(vfsSpec.isNestedJarsEnabled()).isFalse();
        assertThat(vfsSpec.isMultiReleaseVersionsEnabled()).isTrue();
        assertThat(vfsSpec.getMaxBufferedJarRAMSize()).isEqualTo(65_536);
        assertThat(vfsSpec.getDeniedURLSchemes()).containsExactly("https");

        assertThat(vfsSpec.enableNestedJars().disableMultiReleaseVersions().enableURLScheme("https"))
                .isSameAs(vfsSpec);
        assertThat(vfsSpec.isNestedJarsEnabled()).isTrue();
        assertThat(vfsSpec.isMultiReleaseVersionsEnabled()).isFalse();
        assertThat(vfsSpec.getDeniedURLSchemes()).isEmpty();
    }

    /** A URL scheme is lowercased, added to the schemes already denied, and published as an unmodifiable set. */
    @Test
    public void deniedURLSchemesAccumulate() {
        final var vfsSpec = new VfsSpec().disableURLScheme("HTTPS").disableURLScheme("http");
        assertThat(vfsSpec.getDeniedURLSchemes()).containsExactlyInAnyOrder("https", "http");
        assertThatThrownBy(() -> vfsSpec.getDeniedURLSchemes().add("ftp"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** Enabling a scheme takes it back off the denied list, whatever case it is named in. */
    @Test
    public void enablingASchemeUndoesDisablingIt() {
        final var vfsSpec = new VfsSpec().disableURLScheme("http").disableURLScheme("https");
        assertThat(vfsSpec.enableURLScheme("HTTP").getDeniedURLSchemes()).containsExactly("https");
        // Enabling a scheme that was never denied is a no-op, rather than an error
        assertThat(vfsSpec.enableURLScheme("ftp").getDeniedURLSchemes()).containsExactly("https");
    }

    /** An invalid URL scheme is rejected. */
    @Test
    public void anInvalidURLSchemeIsRejected() {
        assertThatThrownBy(() -> new VfsSpec().disableURLScheme("c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VfsSpec().disableURLScheme("http:"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VfsSpec().enableURLScheme("c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VfsSpec().enableURLScheme("http:"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The settings are described in the verbose log. */
    @Test
    public void theSettingsAreDescribed() {
        assertThat(new VfsSpec().disableURLScheme("https").setMaxBufferedJarRAMSize(1024).toString()).contains(
                "nestedJars: true", "multiReleaseVersions: false", "deniedURLSchemes: [https]",
                "maxBufferedJarRAMSize: 1024");
    }
}
