package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/** Manifest dependency depth does not consume the scanner's Java call stack. */
public class DeepManifestClasspathTest {
    /**
     * A long manifest chain preserves preorder even on a small-stack scan thread.
     *
     * @param tempDir
     *            the directory in which the fixture jars are written.
     * @throws Exception
     *             if fixture creation or the scan fails.
     */
    @Test
    public void deepManifestChainIsScannedIteratively(@TempDir final Path tempDir) throws Exception {
        final int depth = 1200;
        final List<File> expected = new ArrayList<>();
        for (int i = 0; i < depth; i++) {
            final Path file = tempDir.resolve("jar" + i + ".jar");
            expected.add(file.toFile().getCanonicalFile());
            final Manifest manifest = new Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            if (i + 1 < depth) {
                manifest.getMainAttributes().putValue("Class-Path", "jar" + (i + 1) + ".jar");
            }
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
                out.finish();
            }
        }
        final ClassGraph scanner = new ClassGraph().enableClasspathEntries(expected.get(0));
        final FutureTask<List<File>> task = new FutureTask<>(() -> {
            try (ScanResult result = scanner.scan(1)) {
                return new ArrayList<>(result.getClasspathFiles());
            }
        });
        final Thread thread = new Thread(null, task, "manifest-small-stack", 256 * 1024);
        thread.setDaemon(true);
        thread.start();
        try {
            assertThat(task.get(60, TimeUnit.SECONDS)).containsExactlyElementsOf(expected);
        } finally {
            thread.interrupt();
            thread.join(5000);
        }
    }
}
