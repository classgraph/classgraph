package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.Test;

import io.github.classgraph.Scanner.ClassfileScanWorkUnit;
import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.vfs.Vfs;

/**
 * A parsed {@link Classfile} waits in a queue until every classfile of the scan has been parsed, and only then is
 * linked. The constant pool is only read while the classfile is parsed, so its index arrays must be released when
 * parsing finishes. Otherwise every queued classfile keeps three {@code int} arrays the length of its constant
 * pool, which can nearly double the peak heap of a scan.
 */
public class ClassfileConstantPoolReleaseTest {
    /** The virtual filesystem that the classpath element reads through. */
    @AutoClose
    private final Vfs vfs = new Vfs();

    /**
     * Once the constructor has returned, the constant pool index arrays are empty.
     *
     * @throws Exception
     *             if the classfile could not be parsed.
     */
    @Test
    public void theConstantPoolIsReleasedWhenParsingFinishes() throws Exception {
        final var classfile = parse(ClassfileConstantPoolReleaseTest.class);
        for (final var fieldName : List.of("entryOffset", "entryTag", "indirectStringRefs")) {
            final var field = Classfile.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            assertThat(((int[]) field.get(classfile)).length).as("length of " + fieldName).isZero();
        }
    }

    /**
     * Parse the classfile of a class in this module's test classes directory, the way the scanner does.
     *
     * @param cls
     *            the class to parse.
     * @return the parsed classfile.
     * @throws Exception
     *             if the classfile could not be parsed.
     */
    private Classfile parse(final Class<?> cls) throws Exception {
        final var classesDir = Path.of(cls.getProtectionDomain().getCodeSource().getLocation().toURI());
        final var scanSpec = new ScanSpec();
        scanSpec.enableClassInfo = true;
        final var classpathElement = new ClasspathElementDir(new ClasspathEntryWorkUnit(classesDir,
                /* classLoader = */ null, /* parentClasspathElement = */ null, /* classpathElementIdx = */ 0,
                /* packageRootPrefix = */ "", ClassLoaderHandler.NO_PACKAGE_ROOT_PREFIXES,
                ClassLoaderHandler.NO_LIB_DIR_PREFIXES), classesDir, vfs, scanSpec);
        final var resource = classpathElement.getResource(cls.getName().replace('.', '/') + ".class");
        assertThat(resource).isNotNull();

        final List<Classfile> parsed = new ArrayList<>();
        try (var executorService = new AutoCloseableExecutorService(1)) {
            // The Classfile constructor needs a work queue, to schedule any external superclasses for scanning
            WorkQueue.runWorkQueue(
                    List.of(new ClassfileScanWorkUnit(classpathElement, resource, /* isExternalClass = */ false)),
                    executorService, new InterruptionChecker(), /* numParallelTasks = */ 1,
                    ClassGraph.DEFAULT_WORKER_TIMEOUT.toNanos(), /* log = */ null, (workUnit, workQueue, log) -> {
                        try {
                            parsed.add(new Classfile(workUnit.classpathElement(), List.of(classpathElement),
                                    new UnscannedModules(List.of(), null, vfs, scanSpec),
                                    ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(),
                                    workUnit.classfileResource().getPath(), workUnit.classfileResource(),
                                    workUnit.isExternalClass(), new ConcurrentHashMap<>(), workQueue, scanSpec,
                                    /* log = */ null));
                        } catch (final IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        assertThat(parsed).hasSize(1);
        return parsed.get(0);
    }
}
