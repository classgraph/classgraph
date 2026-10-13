package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.ToolProvider;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.vfs.Vfs;

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

    /** The virtual filesystem that the classpath element for the module directory reads through. */
    @AutoClose
    private static final Vfs VFS = new Vfs();

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
        final var classGraph = new ClassGraph().enableClasspathEntries(moduleDir.toString()).enableClassInfo();
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

    /**
     * A module read from the traditional classpath has no {@link java.lang.module.ModuleReference}, but does have a
     * location.
     *
     * @throws IOException
     *             if the module directory could not be canonicalized.
     */
    @Test
    public void aModuleOnTheClasspathHasNoModuleReferenceButHasALocation() throws IOException {
        try (var scanResult = scan(/* enableAnnotationInfo = */ false)) {
            final var moduleInfo = moduleInfo(scanResult);
            // A ModuleReference is only present for modules read from the module path
            assertThat(moduleInfo.getModuleReference()).isNull();
            // The location falls back to the URI of the classpath element the module descriptor was read from.
            // ClassGraph canonicalizes a directory classpath element, so the location is the real path of the
            // module directory, which differs from the path the temp directory was handed out as on macOS
            // (/var is a symlink to /private/var) and on Windows (a path can be handed out in 8.3 short form).
            assertThat(Path.of(moduleInfo.getLocationURI())).isEqualTo(moduleDir.toRealPath());
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
     * A module with no known location sorts before a module of the same name that has one. It used to compare equal
     * to every module of the same name, so the ordering was not transitive: with locations {@code null}, {@code a}
     * and {@code b}, the first compared equal to the other two, which did not compare equal to each other.
     */
    @Test
    public void aModuleWithNoLocationSortsBeforeOneWithALocation() {
        final var noLocation = moduleWithLocation(null);
        final var locationA = moduleWithLocation(URI.create("file:/a"));
        final var locationB = moduleWithLocation(URI.create("file:/b"));
        assertThat(noLocation.getLocationURI()).isNull();

        assertThat(noLocation.compareTo(locationA)).isNegative();
        assertThat(locationA.compareTo(noLocation)).isPositive();
        assertThat(noLocation.compareTo(locationB)).isNegative();
        assertThat(locationA.compareTo(locationB)).isNegative();
        assertThat(noLocation.compareTo(moduleWithLocation(null))).isZero();
        assertThat(noLocation).isNotEqualTo(locationA).isEqualTo(moduleWithLocation(null));
    }

    /**
     * Construct a {@link ModuleInfo} for a module named {@code com.xyz.located}, with the given location.
     *
     * @param location
     *            the module location, or null for none.
     * @return the {@link ModuleInfo}.
     */
    private static ModuleInfo moduleWithLocation(final URI location) {
        final var moduleReference = new ModuleReference(ModuleDescriptor.newModule("com.xyz.located").build(),
                location) {
            @Override
            public ModuleReader open() {
                throw new UnsupportedOperationException();
            }
        };
        // The classpath element is never opened, so the module is never read
        final var classpathElement = new ClasspathElementModule(moduleReference, VFS, /* classLoaderStr = */ null,
                /* isLookupOnly = */ false, new ScanSpec());
        return new ModuleInfo(moduleReference, classpathElement, "com.xyz.located");
    }

    /**
     * Modules of the JDK itself are read from the module path, so they carry a
     * {@link java.lang.module.ModuleReference}, and their location comes from that rather than from a classpath
     * element.
     */
    @Test
    public void aModuleOnTheModulePathCarriesAModuleReference() {
        try (var scanResult = new ClassGraph().enableSystemJars().enableSystemModules().enableClassInfo()
                .acceptPackagesNonRecursive("java.util.function").scan()) {
            final var moduleInfo = scanResult.getModuleInfo("java.base");
            assertThat(moduleInfo).as("ModuleInfo for java.base").isNotNull();
            final var moduleReference = moduleInfo.getModuleReference();
            assertThat(moduleReference).isNotNull();
            assertThat(moduleReference.descriptor().name()).isEqualTo("java.base");
            assertThat(moduleInfo.getLocationURI()).isEqualTo(moduleReference.location().orElse(null));
            assertThat(moduleInfo.getClassInfo().getNames()).contains("java.util.function.Function");
            assertThat(moduleInfo.getPackageInfo().getNames()).containsExactly("java.util.function");
        }
    }

    /** The modules found by a scan are listed in name order. */
    @Test
    public void theModulesOfAScanAreListedInNameOrder() {
        try (var scanResult = new ClassGraph().enableSystemJars().enableSystemModules().enableClassInfo()
                .acceptPackagesNonRecursive("javax.xml.parsers", "java.sql", "java.util.function").scan()) {
            assertThat(scanResult.getModuleInfo().getNames()).contains("java.base", "java.sql", "java.xml")
                    .isSorted();
        }
    }

    /**
     * Compile one empty public class for each of the given class names into {@code classesDir}.
     *
     * @param tempDir
     *            the directory to write the sources to.
     * @param classesDir
     *            the directory to compile the classes into.
     * @param classNames
     *            the fully-qualified class names.
     * @throws IOException
     *             if the sources could not be written.
     */
    private static void compileClasses(final Path tempDir, final Path classesDir, final String... classNames)
            throws IOException {
        final List<String> args = new ArrayList<>(List.of("-d", classesDir.toString()));
        for (final String className : classNames) {
            final var lastDot = className.lastIndexOf('.');
            final var src = tempDir.resolve("src").resolve(className.replace('.', '/') + ".java");
            Files.createDirectories(src.getParent());
            Files.writeString(src, "package " + className.substring(0, lastDot) + "; public class "
                    + className.substring(lastDot + 1) + " {}\n");
            args.add(src.toString());
        }
        final var exitCode = ToolProvider.getSystemJavaCompiler().run(/* in = */ null, /* out = */ null,
                /* err = */ null, args.toArray(new String[0]));
        assertThat(exitCode).as("javac exit code").isZero();
    }

    /**
     * Write a jar holding the classfiles of the given classes, taken from {@code classesDir}.
     *
     * @param jar
     *            the jar to write.
     * @param automaticModuleName
     *            the {@code Automatic-Module-Name} to put in the manifest, or null for no manifest.
     * @param classesDir
     *            the directory the classes were compiled into.
     * @param classNames
     *            the fully-qualified class names.
     * @return the jar.
     * @throws IOException
     *             if the jar could not be written.
     */
    private static Path writeJar(final Path jar, final @Nullable String automaticModuleName, final Path classesDir,
            final String... classNames) throws IOException {
        final var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (automaticModuleName != null) {
            manifest.getMainAttributes().putValue("Automatic-Module-Name", automaticModuleName);
        }
        try (var out = automaticModuleName == null ? new JarOutputStream(Files.newOutputStream(jar))
                : new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (final String className : classNames) {
                final var path = className.replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(path));
                out.write(Files.readAllBytes(classesDir.resolve(path)));
                out.closeEntry();
            }
        }
        return jar;
    }

    /**
     * Two classpath jars whose names differ only in a version suffix derive the same automatic module name. Each is
     * listed as a module of its own, located at its own jar, and looking the name up returns the first in classpath
     * order, both from the {@link ScanResult} and from the module list, which is sorted by name. They used to be
     * merged into one {@link ModuleInfo}, located at whichever jar's classfile was linked first, so a class from
     * the other jar reported the wrong module location.
     *
     * @param tempDir
     *            the temporary directory to build the jars in.
     * @throws IOException
     *             if the jars could not be built.
     */
    @Test
    public void jarsWithTheSameDerivedModuleNameAreListedSeparately(@TempDir final Path tempDir)
            throws IOException {
        final var classesDir = tempDir.resolve("classes");
        compileClasses(tempDir, classesDir, "q.C", "q.D", "q.E");
        final var jar1 = writeJar(tempDir.resolve("foo-1.0.jar"), null, classesDir, "q.C");
        final var jar2 = writeJar(tempDir.resolve("foo-2.0.jar"), null, classesDir, "q.D");
        final var jar3 = writeJar(tempDir.resolve("aaa.jar"), null, classesDir, "q.E");
        // foo-2.0.jar comes first on the classpath, and aaa.jar, whose name sorts first, comes last
        try (var scanResult = new ClassGraph()
                .enableClasspathEntries(jar2.toString(), jar1.toString(), jar3.toString()).enableClassInfo()
                .acceptPackages("q").scan()) {
            for (final String className : List.of("q.C", "q.D")) {
                final var classInfo = scanResult.getClassInfo(className);
                final var moduleInfo = classInfo.getModuleInfo();
                assertThat(moduleInfo.getName()).isEqualTo("foo");
                assertThat(moduleInfo.getLocationURI()).as(className).isEqualTo(classInfo.getClasspathElementURI());
                assertThat(moduleInfo.getClassInfo().getNames()).containsExactly(className);
            }
            final var modules = scanResult.getModuleInfo();
            assertThat(modules.getNames()).containsExactly("aaa", "foo", "foo");
            final var firstFoo = scanResult.getClassInfo("q.D").getModuleInfo();
            assertThat(scanResult.getModuleInfo("foo")).isSameAs(firstFoo);
            assertThat(modules.get("foo")).isSameAs(firstFoo);
        }
    }

    /**
     * A classpath jar whose derived module name is the name of a system module is not merged into that module: its
     * classes have a module with no {@link ModuleReference}, located at the jar, while looking the name up still
     * returns the system module, which comes first in the scan order.
     *
     * @param tempDir
     *            the temporary directory to build the jar in.
     * @throws IOException
     *             if the jar could not be built.
     */
    @Test
    public void aClasspathJarIsNotMergedIntoTheSystemModuleOfTheSameName(@TempDir final Path tempDir)
            throws IOException {
        final var classesDir = tempDir.resolve("classes");
        compileClasses(tempDir, classesDir, "q.Z");
        final var jar = writeJar(tempDir.resolve("java.sql.jar"), null, classesDir, "q.Z");
        try (var scanResult = new ClassGraph().enableSystemJars().enableSystemModules()
                .enableClasspathEntries(jar.toString()).enableClassInfo()
                .acceptPackagesNonRecursive("q", "java.sql").scan()) {
            final var classInfo = scanResult.getClassInfo("q.Z");
            final var jarModule = classInfo.getModuleInfo();
            assertThat(jarModule.getName()).isEqualTo("java.sql");
            assertThat(jarModule.getModuleReference()).isNull();
            assertThat(jarModule.getLocationURI()).isEqualTo(classInfo.getClasspathElementURI());

            final var systemModule = scanResult.getModuleInfo("java.sql");
            assertThat(systemModule).isNotSameAs(jarModule);
            assertThat(systemModule.getModuleReference()).isNotNull();
            assertThat(systemModule.getClassInfo().getNames()).doesNotContain("q.Z");
            assertThat(scanResult.getModuleInfo().filter(mi -> mi.getName().equals("java.sql"))).hasSize(2);
            // The jar's location (file:) sorts before the system module's (jrt:), but the list still maps the name
            // to the system module, the first in scan order
            assertThat(scanResult.getModuleInfo().get("java.sql")).isSameAs(systemModule);
        }
    }

    /**
     * When a package is split across two modules, each module's {@link ModuleInfo#getPackageInfo(String)} returns
     * the one {@link PackageInfo} that the whole scan shares for that package, so its classes are those of both
     * modules, and the module's own classes in the package are found by filtering its class list.
     *
     * @param tempDir
     *            the temporary directory to build the jars in.
     * @throws IOException
     *             if the jars could not be built.
     */
    @Test
    public void aSplitPackageIsSharedByItsModules(@TempDir final Path tempDir) throws IOException {
        final var classesDir = tempDir.resolve("classes");
        compileClasses(tempDir, classesDir, "p.A", "p.B");
        final var jarA = writeJar(tempDir.resolve("a.jar"), "mod.a", classesDir, "p.A");
        final var jarB = writeJar(tempDir.resolve("b.jar"), "mod.b", classesDir, "p.B");
        try (var scanResult = new ClassGraph().enableClasspathEntries(jarA.toString(), jarB.toString())
                .enableClassInfo().acceptPackages("p").scan()) {
            final var moduleA = scanResult.getModuleInfo("mod.a");
            final var packageInfo = moduleA.getPackageInfo("p");
            assertThat(packageInfo).isSameAs(scanResult.getPackageInfo("p"))
                    .isSameAs(scanResult.getModuleInfo("mod.b").getPackageInfo("p"));
            assertThat(packageInfo.getClassInfo().getNames()).containsExactly("p.A", "p.B");
            assertThat(moduleA.getClassInfo().filter(ci -> ci.getPackageName().equals("p")).getNames())
                    .containsExactly("p.A");
        }
    }

    /**
     * Get a classpath element for the directory the module was compiled into, for constructing a {@link ModuleInfo}
     * directly.
     *
     * @return a classpath element for the module directory.
     */
    private static ClasspathElement sameModuleDirClasspathElement() {
        final var workUnit = new ClasspathEntryWorkUnit(moduleDir, /* classLoaderStr = */ null,
                /* parentClasspathElement = */ null, /* classpathElementIdxWithinParent = */ 0,
                /* packageRootPrefix = */ "", ClassLoaderHandler.NO_PACKAGE_ROOT_PREFIXES,
                ClassLoaderHandler.NO_LIB_DIR_PREFIXES);
        return new ClasspathElementDir(workUnit, moduleDir, VFS, new ScanSpec());
    }
}
