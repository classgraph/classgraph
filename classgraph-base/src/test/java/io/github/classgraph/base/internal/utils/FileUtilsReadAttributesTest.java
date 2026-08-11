package io.github.classgraph.base.internal.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link FileUtils#readAttributes(Path)}, and the {@link java.nio.file.attribute.BasicFileAttributes}
 * implementation it falls back to when the attributes of a {@link Path} cannot be read.
 */
public class FileUtilsReadAttributesTest {
    /** A path that cannot exist, so that reading its attributes fails and the fallback is used. */
    private static final Path NONEXISTENT_PATH = Path
            .of("this-path-does-not-exist-" + FileUtilsReadAttributesTest.class.getName());

    /** The attributes of a real file are read through the {@code java.nio.file} API. */
    @Test
    public void attributesOfARealFileAreRead(@TempDir final Path tempDir) throws IOException {
        final var file = Files.write(tempDir.resolve("file.txt"), new byte[] { 1, 2, 3 });
        final var attributes = FileUtils.readAttributes(file);
        assertThat(attributes.isRegularFile()).isTrue();
        assertThat(attributes.isDirectory()).isFalse();
        assertThat(attributes.size()).isEqualTo(3);

        final var dirAttributes = FileUtils.readAttributes(tempDir);
        assertThat(dirAttributes.isDirectory()).isTrue();
        assertThat(dirAttributes.isRegularFile()).isFalse();
    }

    /**
     * When the attributes cannot be read, the accessors that the {@link java.io.File} API can answer are answered
     * from it, rather than the whole call failing.
     */
    @Test
    public void fallbackAnswersWhatTheFileApiCanAnswer() {
        final var attributes = FileUtils.readAttributes(NONEXISTENT_PATH);
        assertThat(attributes.isRegularFile()).isFalse();
        assertThat(attributes.isDirectory()).isFalse();
        assertThat(attributes.isSymbolicLink()).isFalse();
        // Neither a file nor a directory
        assertThat(attributes.isOther()).isTrue();
        assertThat(attributes.size()).isZero();
        assertThat(attributes.lastModifiedTime()).isEqualTo(FileTime.fromMillis(0));
        assertThat(attributes.creationTime()).isEqualTo(FileTime.fromMillis(0));
    }

    /** The fallback refuses the two accessors that the {@link java.io.File} API cannot answer. */
    @Test
    public void fallbackRefusesWhatTheFileApiCannotAnswer() {
        final var attributes = FileUtils.readAttributes(NONEXISTENT_PATH);
        assertThatThrownBy(attributes::lastAccessTime).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(attributes::fileKey).isInstanceOf(UnsupportedOperationException.class);
    }

    /** The caching getter reads the attributes of each path once, and returns the same object afterwards. */
    @Test
    public void cachedGetterReadsEachPathOnce(@TempDir final Path tempDir) throws IOException {
        final var file = Files.write(tempDir.resolve("file.txt"), new byte[] { 1 });
        final var getter = FileUtils.createCachedAttributesGetter();
        final var attributes = getter.get(file);
        assertThat(getter.get(file)).isSameAs(attributes);
        assertThat(getter.get(tempDir)).isNotSameAs(attributes);
    }
}
