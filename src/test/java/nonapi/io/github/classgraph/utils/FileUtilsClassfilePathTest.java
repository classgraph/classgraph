package nonapi.io.github.classgraph.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests the path string operations that decide whether a path names a classfile, and which class it declares. */
public class FileUtilsClassfilePathTest {
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
        final String lowerCase = "com/xyz/Widget.class";
        assertThat(FileUtils.withLowerCaseClassfileExtension(lowerCase)).isSameAs(lowerCase);
    }
}
