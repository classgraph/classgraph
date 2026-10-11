package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@link ScanResult} keeps the configuration it was scanned with, even if the {@link ClassGraph} that produced it
 * is configured further and used for another scan.
 */
public class ScanSpecCopyTest {
    /** A class with a field and an external superclass. */
    public static class Target extends ArrayList<String> {
        /** Serial version UID. */
        private static final long serialVersionUID = 1L;

        /** A field. */
        public int field;
    }

    /**
     * Configuring the {@link ClassGraph} after a scan does not change the answers of the {@link ScanResult} that
     * the scan returned.
     */
    @Test
    public void configuringAfterAScanDoesNotChangeTheEarlierResult() {
        final var classGraph = new ClassGraph().enableClasspath().enableClassInfo()
                .acceptClasses(Target.class.getName());
        final var targetPath = Target.class.getName().replace('.', '/') + ".class";
        try (var scanResult = classGraph.scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(Target.class.getName());
            assertThat(scanResult.getResourcesWithPathIgnoringAccept(targetPath)).hasSize(1);

            classGraph.enableFieldInfo().enableExternalClasses().rejectPackages(Target.class.getPackageName());

            assertThat(scanResult.isFieldInfoEnabled()).isFalse();
            // The fields were never read, so asking for them must still fail, rather than reporting none
            assertThatIllegalStateException()
                    .isThrownBy(() -> scanResult.getClassInfo(Target.class.getName()).getDeclaredFieldInfo())
                    .withMessageContaining("enableFieldInfo");
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(Target.class.getName());
            assertThat(scanResult.getResourcesWithPathIgnoringAccept(targetPath)).hasSize(1);
        }
    }

    /**
     * Changing the {@link ClassGraph}'s storage settings after a scan does not change the answers of the
     * {@link ScanResult} that the scan returned. Whether multi-release versioning is honored is read again each
     * time the {@link ScanResult} looks up a resource by path, so it is the setting to change here.
     *
     * @param dir
     *            a temporary directory to scan.
     * @throws IOException
     *             if a file could not be written.
     */
    @Test
    public void reconfiguringTheVfsSettingsAfterAScanDoesNotChangeTheEarlierResult(@TempDir final Path dir)
            throws IOException {
        final var path = "p/a.txt";
        final var versionedPath = "META-INF/versions/9/" + path;
        for (final var relativePath : new String[] { path, versionedPath }) {
            final var file = dir.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, relativePath);
        }
        final var classGraph = new ClassGraph().enableClasspathEntries(dir.toString());
        try (var scanResult = classGraph.scan()) {
            assertThat(scanResult.getResourcesWithPathIgnoringAccept(versionedPath)).isEmpty();

            classGraph.disableMultiReleaseVersions();

            assertThat(scanResult.getResourcesWithPathIgnoringAccept(versionedPath)).isEmpty();
            assertThat(scanResult.getResourcesWithPathIgnoringAccept(path)).hasSize(1);
        }
        // A scan started after the change does use it
        try (var scanResult = classGraph.scan()) {
            assertThat(scanResult.getResourcesWithPathIgnoringAccept(versionedPath)).hasSize(1);
        }
    }

    /**
     * Every field of a {@link ScanSpec} is copied, and every field that can be changed in place is copied into a
     * new object. Every field is set to a value other than its default first, so a field that is added to
     * {@link ScanSpec} later fails this test until the test sets it, and then fails it again if
     * {@link ScanSpec#copy()} does not copy it.
     *
     * @throws ReflectiveOperationException
     *             if a field cannot be read.
     */
    @Test
    public void everyFieldIsCopied() throws ReflectiveOperationException {
        final var original = new ScanSpec();
        original.classpathSpec.allowURLScheme("s3");
        original.classpathSpec.filterClasspathElements(path -> true);
        original.classpathSpec.enableSystemModules();
        original.vfsSpec.disableNestedJars().denyURLScheme("https");
        original.packageAcceptReject.addToAccept("com.a");
        original.packagePrefixAcceptReject.addToAccept("com.a.");
        original.pathAcceptReject.addToAccept("com/a");
        original.pathPrefixAcceptReject.addToAccept("com/a/");
        original.classAcceptReject.addToReject("com.a.B");
        original.classfilePathAcceptReject.addToReject("com/a/B.class");
        original.classPackageAcceptReject.addToAccept("com.c");
        original.classPackagePathAcceptReject.addToAccept("com/c");
        original.jarAcceptReject.addToReject("bad-*.jar");
        original.classpathElementResourcePathAcceptReject.addToAccept("META-INF/x");
        original.moduleAcceptReject.addToReject("java.desktop");
        original.scanJars = false;
        original.scanDirs = false;
        original.enableSystemJars = true;
        original.enableClassInfo = true;
        original.enableFieldInfo = true;
        original.enableMethodInfo = true;
        original.enableAnnotationInfo = true;
        original.enableStaticFinalFieldConstantInitializerValues = true;
        original.enableInterClassDependencies = true;
        original.enableExternalClasses = true;
        original.ignoreClassVisibility = true;
        original.ignoreFieldVisibility = true;
        original.ignoreMethodVisibility = true;
        original.disableRuntimeInvisibleAnnotations = true;
        original.workerTimeout = Duration.ofSeconds(7);

        final var defaults = new ScanSpec();
        final var copy = original.copy();
        for (final Field field : ScanSpec.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            final var originalValue = field.get(original);
            final var copyValue = field.get(copy);
            final var defaultValue = field.get(defaults);
            if (field.getType().isPrimitive() || field.getType() == Duration.class) {
                assertThat(originalValue).as("%s was set by the test", field.getName()).isNotEqualTo(defaultValue);
                assertThat(copyValue).as(field.getName()).isEqualTo(originalValue);
            } else {
                assertThat(originalValue).as("%s was set by the test", field.getName()).usingRecursiveComparison()
                        .withEqualsForType(Object::equals, Pattern.class).isNotEqualTo(defaultValue);
                assertThat(copyValue).as(field.getName()).isNotSameAs(originalValue).usingRecursiveComparison()
                        .withEqualsForType(Object::equals, Pattern.class).isEqualTo(originalValue);
            }
        }
    }
}
