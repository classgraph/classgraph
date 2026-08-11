package io.github.classgraph.base.internal.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests the path string operations that decide what a path names and where it sits. */
public class FileUtilsPathStringTest {
    /** A path names a classfile if it ends with a ".class" extension, whatever case the extension is written in. */
    @Test
    public void aPathNamesAClassfileIfItEndsWithAClassExtension() {
        assertThat(FileUtils.isClassfile("com/xyz/Widget.class")).isTrue();
        assertThat(FileUtils.isClassfile("Widget.class")).isTrue();

        // Some zipfiles are written with an uppercase extension
        assertThat(FileUtils.isClassfile("com/xyz/Widget.CLASS")).isTrue();
        assertThat(FileUtils.isClassfile("com/xyz/Widget.Class")).isTrue();

        assertThat(FileUtils.isClassfile("com/xyz/Widget.classfile")).isFalse();
        assertThat(FileUtils.isClassfile("com/xyz/Widget.txt")).isFalse();
        assertThat(FileUtils.isClassfile("com/xyz/class")).isFalse();
        assertThat(FileUtils.isClassfile("")).isFalse();

        // A file named only ".class" has an empty class name, so it is not a classfile
        assertThat(FileUtils.isClassfile(".class")).isFalse();
    }

    /** The parent directory of a path is everything up to its last separator. */
    @Test
    public void theParentDirectoryOfAPathIsEverythingUpToItsLastSeparator() {
        assertThat(FileUtils.getParentDirPath("com/xyz/Widget.class")).isEqualTo("com/xyz");
        assertThat(FileUtils.getParentDirPath("com/Widget.class")).isEqualTo("com");

        // A path with no separator, or with a separator only at the start, has no parent directory
        assertThat(FileUtils.getParentDirPath("Widget.class")).isEmpty();
        assertThat(FileUtils.getParentDirPath("/Widget.class")).isEmpty();
        assertThat(FileUtils.getParentDirPath("")).isEmpty();

        // A trailing separator makes the whole path the parent directory of the empty name that follows it
        assertThat(FileUtils.getParentDirPath("com/xyz/")).isEqualTo("com/xyz");

        // Any separator can be used, for paths that were not read from a zipfile
        assertThat(FileUtils.getParentDirPath("C:\\dir\\Widget.class", '\\')).isEqualTo("C:\\dir");
        assertThat(FileUtils.getParentDirPath("com/xyz/Widget.class", '\\')).isEmpty();
    }
}
