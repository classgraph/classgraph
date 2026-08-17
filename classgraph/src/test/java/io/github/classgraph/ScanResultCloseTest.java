package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * A {@link ScanResult} holds open jarfiles and memory-mapped buffers until it is closed, so closing it is part of
 * the contract of the API: the accessors stop working, but the {@link ClassInfo} objects that were already read
 * from it keep working. {@link ScanResult#closeAll()} closes every {@link ScanResult} that is still open.
 */
public class ScanResultCloseTest {
    /**
     * Scan this test's own package, which is enough to produce classes, packages, resources and a classpath order.
     *
     * @return the scan result.
     */
    private static ScanResult scan() {
        return new ClassGraph().acceptPackages(ScanResultCloseTest.class.getPackageName()).enableClassInfo()
                .enableMethodInfo().scan();
    }

    /** A {@link ScanResult} reports whether it has been closed, and closing it twice is not an error. */
    @Test
    public void closingIsIdempotent() {
        final var scanResult = scan();
        assertThat(scanResult.isClosed()).isFalse();
        scanResult.close();
        assertThat(scanResult.isClosed()).isTrue();
        // The second close is a no-op rather than an error, so try-with-resources can be used around an explicit
        // close
        scanResult.close();
        assertThat(scanResult.isClosed()).isTrue();
    }

    /** Every accessor of a closed {@link ScanResult} throws {@link IllegalStateException}. */
    @Test
    public void theAccessorsOfAClosedScanResultThrow() {
        final var scanResult = scan();
        scanResult.close();
        assertThatIllegalStateException().isThrownBy(scanResult::getAllClasses)
                .withMessageContaining("after it has been closed");
        assertThatIllegalStateException().isThrownBy(scanResult::getAllResources);
        assertThatIllegalStateException().isThrownBy(scanResult::getPackageInfo);
        assertThatIllegalStateException().isThrownBy(scanResult::getModuleInfo);
        assertThatIllegalStateException().isThrownBy(scanResult::getClasspathFiles);
        assertThatIllegalStateException().isThrownBy(scanResult::getClasspathURIs);
        assertThatIllegalStateException().isThrownBy(scanResult::getClasspathContentsLastModifiedMillis);
    }

    /**
     * The {@link ClassInfo} objects read from a {@link ScanResult} keep working after it is closed, so a program
     * can close the scan result as soon as the scan is over and go on using the class graph it read.
     */
    @Test
    public void theClassInfoOfAClosedScanResultStillWorks() {
        final ClassInfo classInfo;
        try (var scanResult = scan()) {
            classInfo = scanResult.getClassInfo(ScanResultCloseTest.class.getName());
            assertThat(classInfo).isNotNull();
        }
        assertThat(classInfo.getName()).isEqualTo(ScanResultCloseTest.class.getName());
        assertThat(classInfo.getPackageName()).isEqualTo(ScanResultCloseTest.class.getPackageName());
        assertThat(classInfo.getMethodInfo()).isNotEmpty();
        assertThat(classInfo.getMethodInfo("closeAllClosesEveryOpenScanResult")).isNotEmpty();
    }

    /**
     * Closing the stream of a resource that the {@link ScanResult} did not close for itself does not throw, even
     * though closing the {@link ScanResult} force-closed the inflater recycler that the stream hands its inflater
     * back to.
     *
     * @throws Exception
     *             if the resource could not be read.
     */
    @Test
    public void closingAResourceStreamAfterTheScanResultDoesNotThrow() throws Exception {
        final InputStream inputStream;
        // A jarfile, so that the classfile is deflated and reading it needs an inflater
        try (var scanResult = new ClassGraph().overrideClasspath("src/test/resources/record.jar").enableClassInfo()
                .scan()) {
            // close() closes the resources that the ScanResult cached for itself, but the classfile resource is
            // only cached if the resource list was fetched, and this scan never fetches it
            final var classfileResource = scanResult.getAllClasses().get(0).getResource();
            assertThat(classfileResource).isNotNull();
            inputStream = classfileResource.open();
            // Read a byte, so that the inflater is really in use
            assertThat(inputStream.read()).isEqualTo(0xca);
        }
        assertThatCode(inputStream::close).doesNotThrowAnyException();
    }

    /** {@link ScanResult#closeAll()} closes every {@link ScanResult} that has not been closed yet. */
    @Test
    public void closeAllClosesEveryOpenScanResult() {
        final var open = scan();
        final var alsoOpen = scan();
        final var alreadyClosed = scan();
        alreadyClosed.close();

        ScanResult.closeAll();

        assertThat(open.isClosed()).isTrue();
        assertThat(alsoOpen.isClosed()).isTrue();
        assertThat(alreadyClosed.isClosed()).isTrue();

        // A ScanResult that was already closed is not registered any more, so a second closeAll() has nothing left
        // to do
        ScanResult.closeAll();
        assertThat(open.isClosed()).isTrue();
    }
}
