package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

import io.github.classgraph.test.internal.InternalExtendsExternal;

/**
 * Tests the behavior of the accessors that report where a class was found, when they are called on a placeholder
 * {@link ClassInfo}, i.e. one for a class that was referenced by a scanned class but whose own classfile was never
 * read. All four should throw {@link IllegalStateException}, as documented.
 */
public class ExternalClassInfoAccessorsTest {
    /** Scan a single package, so that {@link Object} is referenced but never scanned. */
    private static ScanResult scan() {
        return new ClassGraph().acceptPackages(InternalExtendsExternal.class.getPackage().getName())
                .enableExternalClasses().scan();
    }

    /**
     * The classpath element accessors should throw {@link IllegalStateException} for a placeholder class, rather
     * than failing with a {@link NullPointerException}.
     */
    @Test
    public void classpathElementAccessorsThrowIllegalStateExceptionForPlaceholderClass() {
        try (var scanResult = scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(Object.class.getName());
            assertThat(classInfo).isNotNull();
            // A placeholder class has no Resource, because its classfile was never read
            assertThat(classInfo.getResource()).isNull();

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(classInfo::getClasspathElementFile);
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(classInfo::getModuleRef);
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(classInfo::getClasspathElementURI);
            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(classInfo::getClasspathElementURL);
        }
    }

    /**
     * A class whose classfile was read should report its classpath element without throwing, so the test above is
     * checking a property of placeholder classes specifically, not of all classes.
     */
    @Test
    public void scannedClassReportsItsClasspathElement() {
        try (var scanResult = scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(InternalExtendsExternal.class.getName());
            assertThat(classInfo).isNotNull();
            assertThat(classInfo.getResource()).isNotNull();
            assertThat(classInfo.getClasspathElementURI()).isNotNull();
            assertThat(classInfo.getClasspathElementFile()).isNotNull();
        }
    }
}
