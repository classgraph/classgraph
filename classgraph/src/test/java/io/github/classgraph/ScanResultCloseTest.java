package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * A {@link ScanResult} holds open jarfiles and memory-mapped buffers until it is closed, so closing it is part of
 * the contract of the API: the accessors stop working, and so does any method of an object read from it that has to
 * look up a class through it. {@link ScanResult#closeAll()} closes every {@link ScanResult} that is still open.
 */
public class ScanResultCloseTest {
    /**
     * Scan this test's own package, which is enough to produce classes, packages, resources and a classpath order.
     *
     * @return the scan result.
     */
    private static ScanResult scan() {
        return new ClassGraph().enableClasspath().acceptPackages(ScanResultCloseTest.class.getPackageName())
                .enableClassInfo().enableMethodInfo().scan();
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
        // The varargs annotation queries reach their check through #getClassesWithAnnotation(String), which is not
        // called at all when the array of annotation names is empty
        assertThatIllegalStateException().isThrownBy(() -> scanResult.getClassesWithAllAnnotations(new String[0]))
                .withMessageContaining("after it has been closed");
        assertThatIllegalStateException().isThrownBy(() -> scanResult.getClassesWithAnyAnnotation(new String[0]))
                .withMessageContaining("after it has been closed");
    }

    /**
     * A {@link ClassInfo} read from a {@link ScanResult} still reports what it holds itself after the
     * {@link ScanResult} is closed, but a method that has to look up a class through the closed {@link ScanResult}
     * throws {@link IllegalStateException}. The class graph is not supported after close.
     */
    @Test
    public void aClassInfoReportsOnlyWhatItHoldsAfterClose() {
        final ClassInfo classInfo;
        try (var scanResult = scan()) {
            classInfo = scanResult.getClassInfo(ScanResultCloseTest.class.getName());
            assertThat(classInfo).isNotNull();
        }
        assertThat(classInfo.getName()).isEqualTo(ScanResultCloseTest.class.getName());
        assertThat(classInfo.getPackageName()).isEqualTo(ScanResultCloseTest.class.getPackageName());
        assertThat(classInfo.getMethodInfo()).isNotEmpty();
        assertThat(classInfo.getMethodInfo("closeAllClosesEveryOpenScanResult")).isNotEmpty();
        // getThrownExceptions() looks up the exception classes through the ScanResult the first time it is called
        final var methodInfo = classInfo.getMethodInfo("closingAResourceStreamAfterTheScanResultDoesNotThrow")
                .get(0);
        assertThat(methodInfo.getThrownExceptionNames()).containsExactly(Exception.class.getName());
        assertThatIllegalStateException().isThrownBy(methodInfo::getThrownExceptions)
                .withMessageContaining("after it has been closed");
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
        try (var scanResult = new ClassGraph().enableClasspathEntries("src/test/resources/record.jar")
                .enableClassInfo().scan()) {
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

    /**
     * {@link ScanResult#closeAll()} also closes a {@link ScanResult} that the caller dropped without closing while
     * still holding one of its resources. {@link ScanResult#closeAll()} only finds a {@link ScanResult} through a
     * weak reference, so the resource must keep its {@link ScanResult} reachable: otherwise the {@link ScanResult}
     * could be garbage collected while the jarfile it opened stays open, reachable through the resource.
     *
     * @throws Exception
     *             if the scan could not be run.
     */
    @Test
    public void closeAllClosesAScanResultReachableOnlyFromAResource() throws Exception {
        final var resource = resourceOfDroppedScanResult();
        // Give the garbage collector the chance to collect the
        // ScanResult, if the resource did not keep it reachable
        for (var i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(20);
        }

        ScanResult.closeAll();

        assertThat(resource.getVfsEntry().getRoot().getVfs().isClosed()).isTrue();
        assertThatIllegalStateException().isThrownBy(resource::load)
                .withMessage("Cannot open a resource after the ScanResult is closed");
    }

    /**
     * Scan a jarfile without closing the {@link ScanResult}, and return one of its resources, so that the caller
     * holds no reference to the {@link ScanResult} itself.
     *
     * @return a resource of the jarfile.
     */
    private static Resource resourceOfDroppedScanResult() {
        final var scanResult = new ClassGraph().enableClasspathEntries("src/test/resources/issue286.jar").scan();
        return scanResult.getAllResources().get(0);
    }
}
