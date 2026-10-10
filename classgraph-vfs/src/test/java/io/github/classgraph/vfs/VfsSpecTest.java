package io.github.classgraph.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

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
                .denyURLScheme("https")).isSameAs(vfsSpec);

        assertThat(vfsSpec.isNestedJarsEnabled()).isFalse();
        assertThat(vfsSpec.isMultiReleaseVersionsEnabled()).isTrue();
        assertThat(vfsSpec.getMaxBufferedJarRAMSize()).isEqualTo(65_536);
        assertThat(vfsSpec.getDeniedURLSchemes()).containsExactly("https");

        assertThat(vfsSpec.enableNestedJars().disableMultiReleaseVersions().allowURLScheme("https"))
                .isSameAs(vfsSpec);
        assertThat(vfsSpec.isNestedJarsEnabled()).isTrue();
        assertThat(vfsSpec.isMultiReleaseVersionsEnabled()).isFalse();
        assertThat(vfsSpec.getDeniedURLSchemes()).isEmpty();
    }

    /**
     * A URL scheme is lowercased, added to the schemes already denied, and published as an unmodifiable set, sorted
     * by scheme name rather than in the order the schemes were denied.
     */
    @Test
    public void deniedURLSchemesAccumulate() {
        final var vfsSpec = new VfsSpec().denyURLScheme("HTTPS").denyURLScheme("http");
        assertThat(vfsSpec.getDeniedURLSchemes()).containsExactly("http", "https");
        assertThatThrownBy(() -> vfsSpec.getDeniedURLSchemes().add("ftp"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** Allowing a scheme takes it back off the denied list, whatever case it is named in. */
    @Test
    public void allowingASchemeUndoesDenyingIt() {
        final var vfsSpec = new VfsSpec().denyURLScheme("http").denyURLScheme("https");
        assertThat(vfsSpec.allowURLScheme("HTTP").getDeniedURLSchemes()).containsExactly("https");
        // Allowing a scheme that was never denied is a no-op, rather than an error
        assertThat(vfsSpec.allowURLScheme("ftp").getDeniedURLSchemes()).containsExactly("https");
    }

    /** An invalid URL scheme is rejected. */
    @Test
    public void anInvalidURLSchemeIsRejected() {
        assertThatThrownBy(() -> new VfsSpec().denyURLScheme("c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VfsSpec().denyURLScheme("http:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VfsSpec().allowURLScheme("c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VfsSpec().allowURLScheme("http:"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A copy has every setting of the original, and changing either one does not change the other. */
    @Test
    public void aCopyIsIndependentOfTheOriginal() {
        final var original = new VfsSpec().disableNestedJars().disableMultiReleaseVersions()
                .setMaxBufferedJarRAMSize(1024).denyURLScheme("https");
        final var copy = original.copy();
        assertThat(copy).isNotSameAs(original).hasToString(original.toString());

        copy.enableNestedJars().enableMultiReleaseVersions().setMaxBufferedJarRAMSize(2048).allowURLScheme("https")
                .denyURLScheme("ftp");
        assertThat(original.isNestedJarsEnabled()).isFalse();
        assertThat(original.isMultiReleaseVersionsEnabled()).isFalse();
        assertThat(original.getMaxBufferedJarRAMSize()).isEqualTo(1024);
        assertThat(original.getDeniedURLSchemes()).containsExactly("https");

        original.denyURLScheme("s3");
        assertThat(copy.getDeniedURLSchemes()).containsExactly("ftp");
    }

    /** The settings that a {@link Vfs} holds cannot be changed, but a copy of them can. */
    @Test
    public void aCopyOfTheSettingsOfAVfsCanBeChanged() {
        final var vfsCopy = new VfsSpec(new VfsSpec().disableNestedJars());
        final var copy = vfsCopy.copy();
        copy.enableNestedJars();
        assertThat(copy.isNestedJarsEnabled()).isTrue();
        assertThat(vfsCopy.isNestedJarsEnabled()).isFalse();
    }

    /** The settings are described in the verbose log. */
    @Test
    public void theSettingsAreDescribed() {
        assertThat(new VfsSpec().denyURLScheme("https").setMaxBufferedJarRAMSize(1024).toString()).contains(
                "nestedJars: true", "multiReleaseVersions: true", "deniedURLSchemes: [https]",
                "maxBufferedJarRAMSize: 1024");
    }

    /**
     * A {@link Vfs} copies the spec it is constructed with, and the copy keeps every setting. The list of fields is
     * checked too, so that a setting added later fails this test until it is copied, and set here to a value other
     * than its default.
     */
    @Test
    public void aVfsCopiesEverySetting() {
        final var vfsSpec = new VfsSpec().disableNestedJars().disableMultiReleaseVersions().denyURLScheme("https")
                .setMaxBufferedJarRAMSize(1024);
        try (var vfs = new Vfs(vfsSpec)) {
            final var copy = vfs.getVfsSpec();
            assertThat(copy).isNotSameAs(vfsSpec);
            assertThat(copy.isNestedJarsEnabled()).isFalse();
            assertThat(copy.isMultiReleaseVersionsEnabled()).isFalse();
            assertThat(copy.getDeniedURLSchemes()).containsExactly("https");
            assertThat(copy.getMaxBufferedJarRAMSize()).isEqualTo(1024);
        }
        assertThat(Arrays.stream(VfsSpec.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                .map(Field::getName)).containsExactlyInAnyOrder("nestedJarsEnabled", "multiReleaseVersionsEnabled",
                        "deniedURLSchemes", "maxBufferedJarRAMSize", "frozen");
    }

    /**
     * The copy that a {@link Vfs} holds cannot be changed, so a {@link Vfs} reads everything it opens with the same
     * settings. Every method that returns a {@link VfsSpec} is tried, so that a setting added later is covered too.
     */
    @Test
    public void theSpecAVfsHoldsCannotBeChanged() {
        try (var vfs = new Vfs()) {
            final var copy = vfs.getVfsSpec();
            // copy() also returns a VfsSpec, but it changes nothing
            final var mutators = Arrays.stream(VfsSpec.class.getMethods())
                    .filter(method -> !Modifier.isStatic(method.getModifiers())
                            && method.getReturnType() == VfsSpec.class && !method.getName().equals("copy"))
                    .toList();
            assertThat(mutators).extracting(Method::getName).contains("enableNestedJars", "disableNestedJars",
                    "enableMultiReleaseVersions", "disableMultiReleaseVersions", "denyURLScheme", "allowURLScheme",
                    "setMaxBufferedJarRAMSize");
            for (final var mutator : mutators) {
                // A valid argument, so that only the spec being frozen can make the call fail
                final var args = Arrays.stream(mutator.getParameterTypes()).map(type -> {
                    if (type == String.class) {
                        return "https";
                    } else if (type == int.class) {
                        return 0;
                    }
                    throw new AssertionError("No test argument for a parameter of type " + type + " of " + mutator);
                }).toArray();
                assertThatThrownBy(() -> mutator.invoke(copy, args)).as(mutator.getName())
                        .isInstanceOf(InvocationTargetException.class)
                        .hasCauseInstanceOf(IllegalStateException.class);
            }
            // Nothing was changed by the calls that were refused
            assertThat(copy.isNestedJarsEnabled()).isEqualTo(VfsSpec.DEFAULT_ENABLE_NESTED_JARS);
            assertThat(copy.isMultiReleaseVersionsEnabled())
                    .isEqualTo(VfsSpec.DEFAULT_ENABLE_MULTI_RELEASE_VERSIONS);
            assertThat(copy.getMaxBufferedJarRAMSize()).isEqualTo(VfsSpec.DEFAULT_MAX_BUFFERED_JAR_RAM_SIZE);
            assertThat(copy.getDeniedURLSchemes()).isEmpty();
        }
    }
}
