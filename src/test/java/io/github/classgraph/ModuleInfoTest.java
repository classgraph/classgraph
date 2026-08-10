package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * A {@link ModuleInfo} is created for every module that a scan reads at least one classfile from, whether the
 * module was found on the module path or as a modular jar or directory on the traditional classpath. The module
 * used here is compiled at test time rather than checked in, so that its {@code module-info.class} is always in
 * step with the JDK the tests are running on.
 */
public class ModuleInfoTest {
    /** The name of the module that is compiled and scanned. */
    private static final String MODULE_NAME = "com.xyz.mymodule";

    /** The name of the annotation on the module declaration. */
    private static final String ANNOTATION_NAME = "com.xyz.mod.OnModule";

    /** The name of the only class in the module, apart from the annotation. */
    private static final String CLASS_NAME = "com.xyz.mod.ClassInModule";

    /** The directory holding the compiled module. */
    private static Path moduleDir;

    /**
     * Compile a module with an annotated module declaration, one annotation type and one ordinary class.
     *
     * @param tempDir
     *            the temporary directory to compile in.
     * @throws IOException
     *             if the sources could not be written.
     */
    @BeforeAll
    public static void compileModule(@TempDir final Path tempDir) throws IOException {
        final var srcDir = tempDir.resolve("src");
        final var packageDir = srcDir.resolve("com/xyz/mod");
        Files.createDirectories(packageDir);

        final var moduleInfoSrc = srcDir.resolve("module-info.java");
        Files.writeString(moduleInfoSrc, """
                @com.xyz.mod.OnModule("scanned")
                module com.xyz.mymodule {
                    exports com.xyz.mod;
                }
                """);
        final var annotationSrc = packageDir.resolve("OnModule.java");
        Files.writeString(annotationSrc, """
                package com.xyz.mod;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.MODULE)
                public @interface OnModule {
                    String value();
                }
                """);
        final var classSrc = packageDir.resolve("ClassInModule.java");
        Files.writeString(classSrc, """
                package com.xyz.mod;

                public class ClassInModule {
                }
                """);

        moduleDir = tempDir.resolve("classes");
        Files.createDirectories(moduleDir);
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the tests must be run on a JDK, not a JRE").isNotNull();
        final var exitCode = compiler.run(/* in = */ null, /* out = */ null, /* err = */ null, "-d",
                moduleDir.toString(), moduleInfoSrc.toString(), annotationSrc.toString(), classSrc.toString());
        assertThat(exitCode).as("javac exit code").isZero();
    }

    /**
     * Scan the compiled module as a directory on the traditional classpath.
     *
     * @param enableAnnotationInfo
     *            whether to call {@link ClassGraph#enableAnnotationInfo()}.
     * @return the scan result.
     */
    private static ScanResult scan(final boolean enableAnnotationInfo) {
        final var classGraph = new ClassGraph().overrideClasspath(moduleDir.toString()).enableClassInfo();
        return (enableAnnotationInfo ? classGraph.enableAnnotationInfo() : classGraph).scan();
    }

    /**
     * Get the {@link ModuleInfo} for the compiled module.
     *
     * @param scanResult
     *            the scan result.
     * @return the {@link ModuleInfo}.
     */
    private static ModuleInfo moduleInfo(final ScanResult scanResult) {
        final var moduleInfo = scanResult.getModuleInfo(MODULE_NAME);
        assertThat(moduleInfo).as("ModuleInfo for " + MODULE_NAME).isNotNull();
        return moduleInfo;
    }

    /** A modular directory on the traditional classpath is reported as a module, and named after its descriptor. */
    @Test
    public void aModularClasspathElementIsReportedAsAModule() {
        try (var scanResult = scan(/* enableAnnotationInfo = */ false)) {
            final var moduleInfo = moduleInfo(scanResult);
            assertThat(moduleInfo.getName()).isEqualTo(MODULE_NAME);
            assertThat(moduleInfo.toString()).isEqualTo("module " + MODULE_NAME);
            assertThat(scanResult.getModuleInfo().getNames()).containsExactly(MODULE_NAME);
        }
    }

    /** A module read from the traditional classpath has no {@link ModuleRef}, but does have a location. */
    @Test
    public void aModuleOnTheClasspathHasNoModuleRefButHasALocation() {
        try (var scanResult = scan(/* enableAnnotationInfo = */ false)) {
            final var moduleInfo = moduleInfo(scanResult);
            // A ModuleRef is only present for modules read from the module path
            assertThat(moduleInfo.getModuleRef()).isNull();
            // The location falls back to the URI of the classpath element the module descriptor was read from
            assertThat(Path.of(moduleInfo.getLocationURI())).isEqualTo(moduleDir);
            // The location is cached after the first call
            assertThat(moduleInfo.getLocationURI()).isEqualTo(moduleInfo.getLocationURI());
        }
    }

    /** The classes and packages of a module are listed in the {@link ModuleInfo}, and are reachable by name. */
    @Test
    public void theClassesAndPackagesOfAModuleAreListed() {
        try (var scanResult = scan(/* enableAnnotationInfo = */ false)) {
            final var moduleInfo = moduleInfo(scanResult);
            assertThat(moduleInfo.getClassInfo().getNames()).containsExactly(CLASS_NAME, ANNOTATION_NAME);
            assertThat(moduleInfo.getClassInfo(CLASS_NAME)).isNotNull();
            assertThat(moduleInfo.getClassInfo("com.xyz.mod.NoSuchClass")).isNull();
            assertThat(moduleInfo.getPackageInfo().getNames()).containsExactly("com.xyz.mod");
            assertThat(moduleInfo.getPackageInfo("com.xyz.mod")).isNotNull();
            assertThat(moduleInfo.getPackageInfo("com.xyz.nosuchpackage")).isNull();
            // The class knows which module it is in
            assertThat(scanResult.getClassInfo(CLASS_NAME).getModuleInfo()).isSameAs(moduleInfo);
        }
    }

    /** Annotations on the module declaration are read from {@code module-info.class}. */
    @Test
    public void annotationsOnTheModuleDeclarationAreRead() {
        try (var scanResult = scan(/* enableAnnotationInfo = */ true)) {
            final var moduleInfo = moduleInfo(scanResult);
            final var annotationInfo = moduleInfo.getAllAnnotationInfo();
            assertThat(annotationInfo.getNames()).contains(ANNOTATION_NAME);
            assertThat(annotationInfo.get(ANNOTATION_NAME).getParameterValues().getValue("value"))
                    .isEqualTo("scanned");
            // The list is cached after the first call
            assertThat(moduleInfo.getAllAnnotationInfo()).isSameAs(annotationInfo);
        }
    }

    /** Reading module annotations without {@link ClassGraph#enableAnnotationInfo()} is an error. */
    @Test
    public void readingModuleAnnotationsRequiresAnnotationInfoToBeEnabled() {
        try (var scanResult = scan(/* enableAnnotationInfo = */ false)) {
            final var moduleInfo = moduleInfo(scanResult);
            assertThatIllegalStateException().isThrownBy(moduleInfo::getAllAnnotationInfo)
                    .withMessageContaining("enableAnnotationInfo");
        }
    }

    /** Modules are ordered by name, and two {@link ModuleInfo} objects for the same module are equal. */
    @Test
    public void modulesAreOrderedAndComparedByNameAndLocation() {
        try (var scanResult = scan(/* enableAnnotationInfo = */ false);
                var scanResult2 = scan(/* enableAnnotationInfo = */ false)) {
            final var moduleInfo = moduleInfo(scanResult);
            // The same module scanned twice produces two equal ModuleInfo objects
            final var sameModule = moduleInfo(scanResult2);
            assertThat(sameModule).isNotSameAs(moduleInfo).isEqualTo(moduleInfo).hasSameHashCodeAs(moduleInfo);
            assertThat(moduleInfo).isEqualTo(moduleInfo).isNotEqualTo(MODULE_NAME).isNotEqualTo(null);
            assertThat(moduleInfo.compareTo(sameModule)).isZero();

            // Modules with different names sort by name
            final var earlierName = new ModuleInfo(/* moduleRef = */ null, sameModuleDirClasspathElement(),
                    "com.xyz.aaa");
            assertThat(moduleInfo.compareTo(earlierName)).isPositive();
            assertThat(earlierName.compareTo(moduleInfo)).isNegative();
            assertThat(moduleInfo).isNotEqualTo(earlierName);
        }
    }

    /**
     * Modules of the JDK itself are read from the module path, so they carry a {@link ModuleRef}, and their
     * location comes from the {@link ModuleRef} rather than from a classpath element.
     */
    @Test
    public void aModuleOnTheModulePathCarriesAModuleRef() {
        try (var scanResult = new ClassGraph().enableSystemJarsAndModules().enableClassInfo()
                .acceptPackagesNonRecursive("java.util.function").scan()) {
            final var moduleInfo = scanResult.getModuleInfo("java.base");
            assertThat(moduleInfo).as("ModuleInfo for java.base").isNotNull();
            final var moduleRef = moduleInfo.getModuleRef();
            assertThat(moduleRef).isNotNull();
            assertThat(moduleRef.getName()).isEqualTo("java.base");
            assertThat(moduleInfo.getLocationURI()).isEqualTo(moduleRef.getLocationURI());
            assertThat(moduleInfo.getClassInfo().getNames()).contains("java.util.function.Function");
            assertThat(moduleInfo.getPackageInfo().getNames()).containsExactly("java.util.function");
        }
    }

    /**
     * Get a classpath element for the directory the module was compiled into, for constructing a {@link ModuleInfo}
     * directly.
     *
     * @return a classpath element for the module directory.
     */
    private static ClasspathElement sameModuleDirClasspathElement() {
        final var workUnit = new ClasspathEntryWorkUnit(moduleDir, /* classLoader = */ null,
                /* parentClasspathElement = */ null, /* classpathElementIdx = */ 0, /* packageRootPrefix = */ "",
                ClassLoaderHandlerRegistry.NO_PACKAGE_ROOT_PREFIXES);
        return new ClasspathElementDir(workUnit, /* nestedJarHandler = */ null, new ScanSpec());
    }
}
