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

        // A file named only ".class" has an empty class name, so it is not a classfile, wherever it is found
        assertThat(FileUtils.isClassfile(".class")).isFalse();
        assertThat(FileUtils.isClassfile("com/xyz/.class")).isFalse();
        assertThat(FileUtils.isClassfile("com/xyz/..class")).isFalse();
    }

    /** A classfile is at the path of the class it declares if the two differ only by the extension. */
    @Test
    public void aClassfileIsAtThePathOfTheClassItDeclaresIfTheyDifferOnlyByTheExtension() {
        assertThat(FileUtils.classfilePathMatchesClassName("com/xyz/Widget.class", "com/xyz/Widget")).isTrue();
        assertThat(FileUtils.classfilePathMatchesClassName("com/xyz/Widget.CLASS", "com/xyz/Widget")).isTrue();

        // The class is in a package below the root of the classpath element, so the element is not a package root
        assertThat(FileUtils.classfilePathMatchesClassName("Widget.class", "com/xyz/Widget")).isFalse();
        assertThat(FileUtils.classfilePathMatchesClassName("xyz/Widget.class", "com/xyz/Widget")).isFalse();

        assertThat(FileUtils.classfilePathMatchesClassName("com/xyz/Widget.txt", "com/xyz/Widget")).isFalse();
        assertThat(FileUtils.classfilePathMatchesClassName("com/xyz/Widget", "com/xyz/Widget")).isFalse();
    }

    /** The extension of a classfile path is lower-cased for matching against criteria built from class names. */
    @Test
    public void theExtensionOfAClassfilePathIsLowerCasedForMatching() {
        assertThat(FileUtils.withLowerCaseClassfileExtension("com/xyz/Widget.CLASS"))
                .isEqualTo("com/xyz/Widget.class");
        assertThat(FileUtils.withLowerCaseClassfileExtension("com/xyz/Widget.Class"))
                .isEqualTo("com/xyz/Widget.class");

        // A path that is already in lower case is returned as it is
        final var lowerCase = "com/xyz/Widget.class";
        assertThat(FileUtils.withLowerCaseClassfileExtension(lowerCase)).isSameAs(lowerCase);
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
