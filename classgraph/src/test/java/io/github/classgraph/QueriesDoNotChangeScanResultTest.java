package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;

/**
 * Looking up the classes that a type refers to can need a {@link ClassInfo} object for a class that the scan did
 * not record: an array class, or a class that is named only in a signature. Making one must not change what the
 * {@link ScanResult} returns for a class name or a list of classes, or the result of a query would depend on which
 * queries were made before it.
 */
public class QueriesDoNotChangeScanResultTest {
    /** A class whose method names an array class and a class that is named nowhere else. */
    public static class Target {
        /**
         * A method.
         *
         * @param checksum
         *            a parameter whose type is named only here.
         * @return null.
         */
        public AtomicLong[] method(final CRC32 checksum) {
            return null;
        }
    }

    /**
     * Asking for the dependencies of a method, or for the {@link ArrayClassInfo} of an array type, does not add
     * classes to the scan result.
     */
    @Test
    public void lookingUpATypeDoesNotAddAClass() {
        try (var scanResult = new ClassGraph().enableClasspath().enableClassInfo().enableMethodInfo()
                .enableExternalClasses().acceptClasses(Target.class.getName()).scan()) {
            final var classNamesBefore = scanResult.getAllClasses().getNames();
            final var classMapBefore = scanResult.getAllClassesAsMap().keySet();
            assertThat(scanResult.getClassInfo(CRC32.class.getName())).isNull();

            final var methodInfo = scanResult.getClassInfo(Target.class.getName()).getMethodInfo("method").get(0);
            assertThat(methodInfo.getClassDependencies().getNames()).contains(CRC32.class.getName());
            final var resultType = (ArrayTypeSignature) methodInfo.getTypeSignatureOrTypeDescriptor()
                    .getResultType();
            final var arrayClassInfo = resultType.getArrayClassInfo();
            assertThat(arrayClassInfo.getName()).isEqualTo(AtomicLong.class.getName() + "[]");

            assertThat(scanResult.getAllClasses().getNames()).isEqualTo(classNamesBefore);
            assertThat(scanResult.getAllClassesAsMap().keySet()).isEqualTo(classMapBefore);
            assertThat(scanResult.getClassInfo(CRC32.class.getName())).isNull();
            assertThat(scanResult.getClassInfo(AtomicLong.class.getName() + "[]")).isNull();

            // A second lookup returns the same object
            assertThat(methodInfo.getClassDependencies().get(CRC32.class.getName()))
                    .isSameAs(methodInfo.getClassDependencies().get(CRC32.class.getName()));
            assertThat(((ArrayTypeSignature) methodInfo.getTypeSignatureOrTypeDescriptor().getResultType())
                    .getArrayClassInfo()).isSameAs(arrayClassInfo);
        }
    }
}
