package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.vfsspec.VfsScanSpec;

/**
 * A {@link ModuleInfo} is created as soon as any classfile is read from a module, including a
 * {@code module-info.class} file, which does not itself contribute a {@link ClassInfo}. A {@link ModuleInfo} with
 * no accepted classes therefore has a null class set, which used to make {@link ModuleInfo#getClassInfo()} and
 * {@link ModuleInfo#getClassInfo(String)} throw {@link NullPointerException}. The sibling package accessors,
 * {@link ModuleInfo#getPackageInfo()} and {@link ModuleInfo#getPackageInfo(String)}, have always handled this.
 */
public class ModuleInfoNoClassesTest {
    /**
     * A {@link ModuleInfo} with no classes returns an empty list rather than throwing.
     */
    @Test
    public void moduleWithNoAcceptedClasses() {
        final var workUnit = new ClasspathEntryWorkUnit(Path.of("."), /* classLoader = */ null,
                /* parentClasspathElement = */ null, /* classpathElementIdx = */ 0, /* packageRootPrefix = */ "",
                ClassLoaderHandlerRegistry.NO_PACKAGE_ROOT_PREFIXES);
        final var classpathElement = new ClasspathElementDir(workUnit, /* scanResources = */ null, new ScanSpec(),
                new VfsScanSpec());
        final var moduleInfo = new ModuleInfo(/* moduleRef = */ null, classpathElement, "com.xyz.mymodule");
        assertThat(moduleInfo.getClassInfo()).isEmpty();
        assertThat(moduleInfo.getClassInfo("com.xyz.Foo")).isNull();
        // The package accessors already behaved this way
        assertThat(moduleInfo.getPackageInfo()).isEmpty();
        assertThat(moduleInfo.getPackageInfo("com.xyz")).isNull();
    }
}
