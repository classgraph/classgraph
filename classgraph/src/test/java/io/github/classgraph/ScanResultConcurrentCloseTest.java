package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.vfs.Vfs;

/**
 * A {@link ScanResult} may be closed by one thread while another thread is in one of its getters, for example by
 * {@link ScanResult#closeAll()}. The getter must then either throw {@link IllegalStateException} or return the
 * complete result: it must never see a collection emptied under it, which gave a
 * {@link java.util.ConcurrentModificationException}, a {@link NullPointerException}, or a silently shortened
 * result.
 *
 * <p>
 * Each test makes the race deterministic: the collections given to the {@link ScanResult} close it at the moment a
 * getter, which has already read the field and passed its closed check, first reads their contents.
 */
public class ScanResultConcurrentCloseTest {
    /** The temporary directory, holding the file whose timestamp is recorded. */
    @TempDir
    Path tempDir;

    /** An action that is run once, the first time it is fired after being set. */
    private static final class OneShot {
        /** The action, or null once it has run or before it is set. */
        private @Nullable Runnable action;

        /** Run the action, if it is set and has not yet run. */
        void fire() {
            final var curr = action;
            action = null;
            if (curr != null) {
                curr.run();
            }
        }
    }

    /**
     * A map that fires a {@link OneShot} when its contents are first read through a view.
     *
     * @param <K>
     *            the key type
     * @param <V>
     *            the value type
     */
    private static final class CloseOnReadMap<K, V> extends LinkedHashMap<K, V> {
        /** The serial version UID. */
        private static final long serialVersionUID = 1L;

        /** The action fired before the contents are read. */
        final transient OneShot beforeRead = new OneShot();

        @Override
        public Collection<V> values() {
            beforeRead.fire();
            return super.values();
        }

        @Override
        public Set<K> keySet() {
            beforeRead.fire();
            return super.keySet();
        }

        @Override
        public Set<Map.Entry<K, V>> entrySet() {
            beforeRead.fire();
            return super.entrySet();
        }
    }

    /**
     * A list that fires a {@link OneShot} when it is first iterated or copied.
     *
     * @param <E>
     *            the element type
     */
    private static final class CloseOnReadList<E> extends ArrayList<E> {
        /** The serial version UID. */
        private static final long serialVersionUID = 1L;

        /** The action fired before the list is iterated or copied. */
        final transient OneShot beforeRead = new OneShot();

        @Override
        public Iterator<E> iterator() {
            beforeRead.fire();
            return super.iterator();
        }

        @Override
        public Object[] toArray() {
            beforeRead.fire();
            return super.toArray();
        }
    }

    /**
     * Make a classpath element for a directory. Nothing is read from it.
     *
     * @param dir
     *            the directory
     * @param vfs
     *            the virtual filesystem
     * @return the classpath element
     */
    private static ClasspathElement classpathElement(final Path dir, final Vfs vfs) {
        final var workUnit = new ClasspathEntryWorkUnit(dir, /* classLoader = */ null,
                /* parentClasspathElement = */ null, /* classpathElementIdx = */ 0, /* packageRootPrefix = */ "",
                ClassLoaderHandler.NO_PACKAGE_ROOT_PREFIXES, ClassLoaderHandler.NO_LIB_DIR_PREFIXES);
        return new ClasspathElementDir(workUnit, dir, vfs, new ScanSpec());
    }

    /**
     * Make a {@link ScanResult} from the given collections.
     *
     * @param classpathOrder
     *            the classpath order
     * @param packageNameToPackageInfo
     *            the map from package name to package info
     * @param moduleInfoInScanOrder
     *            the module info of every classpath element that holds a module, in scan order
     * @param fileToLastModified
     *            the map from file to its timestamp at scan time, or null
     * @param vfs
     *            the virtual filesystem, which the {@link ScanResult} closes
     * @return the scan result
     */
    private static ScanResult scanResult(final List<ClasspathElement> classpathOrder,
            final Map<String, PackageInfo> packageNameToPackageInfo, final List<ModuleInfo> moduleInfoInScanOrder,
            final @Nullable Map<File, Long> fileToLastModified, final Vfs vfs) {
        final var scanSpec = new ScanSpec();
        scanSpec.enableClassInfo = true;
        return new ScanResult(scanSpec, classpathOrder, /* classNameToClassInfo = */ new HashMap<>(),
                packageNameToPackageInfo, moduleInfoInScanOrder, fileToLastModified, vfs, /* topLevelLog = */ null);
    }

    /** A close that happens after {@link ScanResult#getPackageInfo()} has read the map leaves its result whole. */
    @Test
    public void getPackageInfoReturnsEveryPackageWhenClosedDuringTheCall() {
        final var packages = new CloseOnReadMap<String, PackageInfo>();
        packages.put("com.xyz", new PackageInfo("com.xyz"));
        packages.put("com.xyz.abc", new PackageInfo("com.xyz.abc"));
        final var scanResult = scanResult(new ArrayList<>(), packages, new ArrayList<>(), new HashMap<>(),
                new Vfs());
        packages.beforeRead.action = scanResult::close;
        assertThat(scanResult.getPackageInfo().getNames()).containsExactly("com.xyz", "com.xyz.abc");
        assertThat(scanResult.isClosed()).isTrue();
        assertThatIllegalStateException().isThrownBy(scanResult::getPackageInfo);
    }

    /** A close that happens after {@link ScanResult#getModuleInfo()} has read the list leaves its result whole. */
    @Test
    public void getModuleInfoReturnsEveryModuleWhenClosedDuringTheCall() {
        try (var classpathEltVfs = new Vfs()) {
            final var classpathElement = classpathElement(tempDir, classpathEltVfs);
            final var modules = new CloseOnReadList<ModuleInfo>();
            modules.add(new ModuleInfo(/* moduleRef = */ null, classpathElement, "com.xyz"));
            final var scanResult = scanResult(new ArrayList<>(), new HashMap<>(), modules, new HashMap<>(),
                    new Vfs());
            modules.beforeRead.action = scanResult::close;
            assertThat(scanResult.getModuleInfo().getNames()).containsExactly("com.xyz");
            assertThat(scanResult.isClosed()).isTrue();
        }
    }

    /**
     * A close that happens after {@link ScanResult#getClasspathFiles()} has read the classpath order leaves its
     * result whole.
     */
    @Test
    public void getClasspathFilesReturnsEveryFileWhenClosedDuringTheCall() {
        final var vfs = new Vfs();
        final var classpathOrder = new CloseOnReadList<ClasspathElement>();
        classpathOrder.add(classpathElement(tempDir, vfs));
        final var scanResult = scanResult(classpathOrder, new HashMap<>(), new ArrayList<>(), new HashMap<>(), vfs);
        classpathOrder.beforeRead.action = scanResult::close;
        assertThat(scanResult.getClasspathFiles()).containsExactly(tempDir.toFile());
        assertThat(scanResult.isClosed()).isTrue();
    }

    /**
     * A close that happens after {@link ScanResult#isClasspathContentsModifiedSinceScan()} has read the recorded
     * timestamps still reports the modification.
     *
     * @throws IOException
     *             if the file cannot be written
     */
    @Test
    public void isClasspathContentsModifiedSinceScanSeesEveryFileWhenClosedDuringTheCall() throws IOException {
        final var file = Files.writeString(tempDir.resolve("a.txt"), "a").toFile();
        final var timestamps = new CloseOnReadMap<File, Long>();
        // A timestamp that differs from the file's current one: the file was modified since the scan
        timestamps.put(file, file.lastModified() - 1000);
        final var scanResult = scanResult(new ArrayList<>(), new HashMap<>(), new ArrayList<>(), timestamps,
                new Vfs());
        timestamps.beforeRead.action = scanResult::close;
        assertThat(scanResult.isClasspathContentsModifiedSinceScan()).isTrue();
        assertThat(scanResult.isClosed()).isTrue();
        assertThatIllegalStateException().isThrownBy(scanResult::isClasspathContentsModifiedSinceScan);
    }

    /**
     * A close that happens after {@link ScanResult#getClasspathContentsLastModifiedMillis()} has read the recorded
     * timestamps still reports the latest one.
     *
     * @throws IOException
     *             if the file cannot be written
     */
    @Test
    public void getClasspathContentsLastModifiedMillisSeesEveryFileWhenClosedDuringTheCall() throws IOException {
        final var file = Files.writeString(tempDir.resolve("a.txt"), "a").toFile();
        // Set a timestamp in the past, since a timestamp later than the current time is ignored
        final var timestamp = System.currentTimeMillis() - 60_000;
        assertThat(file.setLastModified(timestamp)).isTrue();
        final var timestamps = new CloseOnReadMap<File, Long>();
        timestamps.put(file, file.lastModified());
        final var scanResult = scanResult(new ArrayList<>(), new HashMap<>(), new ArrayList<>(), timestamps,
                new Vfs());
        timestamps.beforeRead.action = scanResult::close;
        assertThat(scanResult.getClasspathContentsLastModifiedMillis()).isEqualTo(file.lastModified());
        assertThat(scanResult.isClosed()).isTrue();
        assertThatIllegalStateException().isThrownBy(scanResult::getClasspathContentsLastModifiedMillis);
    }

    /**
     * A {@link ScanResult} that recorded no timestamps, as from {@code ClassGraph#getClasspathScanResult}, reports
     * the classpath as modified and its last-modified time as 0, until it is closed.
     */
    @Test
    public void aScanResultWithNoTimestampsReportsModifiedUntilClosed() {
        final var scanResult = scanResult(new ArrayList<>(), new HashMap<>(), new ArrayList<>(),
                /* fileToLastModified = */ null, new Vfs());
        assertThat(scanResult.isClasspathContentsModifiedSinceScan()).isTrue();
        assertThat(scanResult.getClasspathContentsLastModifiedMillis()).isZero();
        scanResult.close();
        assertThatIllegalStateException().isThrownBy(scanResult::isClasspathContentsModifiedSinceScan);
        assertThatIllegalStateException().isThrownBy(scanResult::getClasspathContentsLastModifiedMillis);
    }
}
