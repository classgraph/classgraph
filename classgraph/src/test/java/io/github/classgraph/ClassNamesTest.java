package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Tests for {@link ClassNames}. */
public class ClassNamesTest {
    /** A path names a classfile if it ends with a ".class" extension, whatever case the extension is written in. */
    @Test
    public void aPathNamesAClassfileIfItEndsWithAClassExtension() {
        assertThat(ClassNames.isClassfilePath("com/xyz/Widget.class")).isTrue();
        assertThat(ClassNames.isClassfilePath("Widget.class")).isTrue();

        // Some zipfiles are written with an uppercase extension
        assertThat(ClassNames.isClassfilePath("com/xyz/Widget.CLASS")).isTrue();
        assertThat(ClassNames.isClassfilePath("com/xyz/Widget.Class")).isTrue();

        assertThat(ClassNames.isClassfilePath("com/xyz/Widget.classfile")).isFalse();
        assertThat(ClassNames.isClassfilePath("com/xyz/Widget.txt")).isFalse();
        assertThat(ClassNames.isClassfilePath("com/xyz/class")).isFalse();
        assertThat(ClassNames.isClassfilePath("")).isFalse();

        // A file named only ".class" has an empty class name, so it is not a classfile, wherever it is found
        assertThat(ClassNames.isClassfilePath(".class")).isFalse();
        assertThat(ClassNames.isClassfilePath("com/xyz/.class")).isFalse();
        assertThat(ClassNames.isClassfilePath("com/xyz/..class")).isFalse();
    }

    /** A classfile is at the path of the class it declares if the two differ only by the extension. */
    @Test
    public void aClassfileIsAtThePathOfTheClassItDeclaresIfTheyDifferOnlyByTheExtension() {
        assertThat(ClassNames.classfilePathMatchesClassName("com/xyz/Widget.class", "com/xyz/Widget")).isTrue();
        assertThat(ClassNames.classfilePathMatchesClassName("com/xyz/Widget.CLASS", "com/xyz/Widget")).isTrue();

        // The class is in a package below the root of the classpath element, so the element is not a package root
        assertThat(ClassNames.classfilePathMatchesClassName("Widget.class", "com/xyz/Widget")).isFalse();
        assertThat(ClassNames.classfilePathMatchesClassName("xyz/Widget.class", "com/xyz/Widget")).isFalse();

        assertThat(ClassNames.classfilePathMatchesClassName("com/xyz/Widget.txt", "com/xyz/Widget")).isFalse();
        assertThat(ClassNames.classfilePathMatchesClassName("com/xyz/Widget", "com/xyz/Widget")).isFalse();
    }

    /** The extension of a classfile path is lower-cased for matching against criteria built from class names. */
    @Test
    public void theExtensionOfAClassfilePathIsLowerCasedForMatching() {
        assertThat(ClassNames.withLowerCaseClassfileExtension("com/xyz/Widget.CLASS"))
                .isEqualTo("com/xyz/Widget.class");
        assertThat(ClassNames.withLowerCaseClassfileExtension("com/xyz/Widget.Class"))
                .isEqualTo("com/xyz/Widget.class");

        // A path that is already in lower case is returned as it is
        final var lowerCase = "com/xyz/Widget.class";
        assertThat(ClassNames.withLowerCaseClassfileExtension(lowerCase)).isSameAs(lowerCase);
    }

    /** Classfile paths and class names convert to each other. */
    @Test
    public void classfilePathsAndClassNamesConvertToEachOther() {
        assertThat(ClassNames.classfilePathToClassName("java/lang/String.class")).isEqualTo("java.lang.String");
        assertThat(ClassNames.classfilePathToClassName("X.class")).isEqualTo("X");
        assertThat(ClassNames.classNameToClassfilePath("java.lang.String")).isEqualTo("java/lang/String.class");

        // A classfile that has been through a filesystem that upper-cases filenames still names its class
        assertThat(ClassNames.classfilePathToClassName("java/lang/String.CLASS")).isEqualTo("java.lang.String");

        assertThatThrownBy(() -> ClassNames.classfilePathToClassName("java/lang/String"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not the path of a classfile: java/lang/String");
        assertThatThrownBy(() -> ClassNames.classfilePathToClassName("java/lang/.class"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not the path of a classfile: java/lang/.class");
    }

    /**
     * The directories a classfile is stored in have to be able to be the packages that declare the class, so each
     * of them has to be a Java identifier.
     */
    @Test
    public void theDirectoriesOfAClassfilePathHaveToBeAbleToBePackageNames() {
        assertThat(ClassNames.classfilePathHasValidPackage("com/xyz/Widget.class")).isTrue();
        // A classfile in the unnamed package has no directories to check
        assertThat(ClassNames.classfilePathHasValidPackage("Widget.class")).isTrue();
        // Only the directories are checked, not the name of the classfile itself, which is checked against the
        // class name that the classfile declares
        assertThat(ClassNames.classfilePathHasValidPackage("com/xyz/Widget-2.class")).isTrue();
        // '$' and '_' are Java identifier characters, and a digit is legal after the first character
        assertThat(ClassNames.classfilePathHasValidPackage("com/xyz$1/_pkg2/Widget.class")).isTrue();

        // The versioned copies of a multi-release jarfile are stored where no class could be loaded from: a hyphen
        // is not a Java identifier character, and a Java identifier cannot start with a digit
        assertThat(ClassNames.classfilePathHasValidPackage("META-INF/versions/9/mrj/Cls.class")).isFalse();
        assertThat(ClassNames.classfilePathHasValidPackage("versions/9/mrj/Cls.class")).isFalse();
        assertThat(ClassNames.classfilePathHasValidPackage("com/x-yz/Widget.class")).isFalse();

        // An empty directory name is not a package name segment
        assertThat(ClassNames.classfilePathHasValidPackage("com//xyz/Widget.class")).isFalse();
        assertThat(ClassNames.classfilePathHasValidPackage("/com/xyz/Widget.class")).isFalse();
    }

    /** A package name converts to the path of the directory that holds its classes. */
    @Test
    public void aPackageNameConvertsToThePathOfItsDirectory() {
        assertThat(ClassNames.packageNameToPath("com.xyz")).isEqualTo("com/xyz");
        assertThat(ClassNames.packageNameToPath("com")).isEqualTo("com");
        // The unnamed package is the root of the classpath element
        assertThat(ClassNames.packageNameToPath("")).isEmpty();
    }
}
