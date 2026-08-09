package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ClassGraph's public API is {@code @NullMarked}, but that is only a
 * compile-time contract, and it protects only callers that run a null checker of
 * their own. Every public API method therefore also checks its arguments at
 * runtime, so that a null fails at the call that passed it, rather than deeper
 * inside ClassGraph or (worse) silently, as an empty "not found" result.
 *
 * <p>
 * This test covers a representative sample of each kind of entry point: the
 * {@link ClassGraph} builder methods (single-value and varargs), the
 * {@link ScanResult} lookups, the {@link ClassInfo} queries, the list classes,
 * and the type signature API.
 */
class NullArgumentTest {
    /** A scan result to run the lookup and query checks against. */
    private static ScanResult scanResult;

    /** Scan the ClassGraph API package itself. */
    @BeforeAll
    static void scan() {
        scanResult = new ClassGraph().acceptPackages(NullArgumentTest.class.getPackage().getName()).enableAllInfo()
                .scan();
    }

    /** Close the scan result. */
    @AfterAll
    static void close() {
        scanResult.close();
    }

    /**
     * Assert that the call throws {@link NullPointerException} with ClassGraph's own
     * message, rather than failing later, failing silently, or throwing a
     * JVM-generated NPE that names some internal variable.
     *
     * @param call the call to make.
     */
    private static void rejectsNull(final ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(NullPointerException.class).hasMessageContaining("must not be null");
    }

    /** Single-value {@link ClassGraph} builder arguments are rejected. */
    @Test
    void classGraphSingleValueArgs() {
        final var classGraph = new ClassGraph();
        rejectsNull(() -> classGraph.overrideClasspath((String) null));
        rejectsNull(() -> classGraph.overrideClasspath((Iterable<?>) null));
        rejectsNull(() -> classGraph.filterClasspathElements(null));
        rejectsNull(() -> classGraph.filterClasspathElementsByURL(null));
        rejectsNull(() -> classGraph.addClassLoader(null));
        rejectsNull(() -> classGraph.addModuleLayer(null));
        // Previously reported the misleading "URL schemes must contain at least two
        // characters"
        rejectsNull(() -> classGraph.enableURLScheme(null));
    }

    /**
     * Both a null varargs array and a null element within one are rejected by the
     * {@link ClassGraph} accept/reject methods.
     */
    @Test
    void classGraphVarargsArgs() {
        final var classGraph = new ClassGraph();
        rejectsNull(() -> classGraph.acceptPackages((String[]) null));
        rejectsNull(() -> classGraph.acceptPackages((String) null));
        rejectsNull(() -> classGraph.acceptPackages("com.example", null));
        rejectsNull(() -> classGraph.rejectPackages((String) null));
        rejectsNull(() -> classGraph.acceptClasses((String) null));
        rejectsNull(() -> classGraph.acceptPaths((String) null));
        rejectsNull(() -> classGraph.acceptJars((String) null));
        rejectsNull(() -> classGraph.acceptModules((String) null));
        rejectsNull(() -> classGraph.overrideClasspath((Object[]) null));
        rejectsNull(() -> classGraph.overrideClassLoaders((ClassLoader) null));
    }

    /** Null scan callbacks are rejected. */
    @Test
    void scanCallbacks() {
        final var classGraph = new ClassGraph().acceptPackages("io.github.classgraph.nonexistent");
        final ExecutorService executorService = Executors.newFixedThreadPool(1);
        try {
            rejectsNull(() -> classGraph.scanAsync(null, 1, ScanResult::close, Throwable::printStackTrace));
            rejectsNull(() -> classGraph.scanAsync(executorService, 1, null, Throwable::printStackTrace));
            rejectsNull(() -> classGraph.scanAsync(executorService, 1, ScanResult::close, null));
        } finally {
            executorService.shutdown();
        }
    }

    /**
     * {@link ScanResult} lookups reject null, rather than returning null as if
     * nothing matched.
     */
    @Test
    void scanResultLookups() {
        rejectsNull(() -> scanResult.getClassInfo(null));
        rejectsNull(() -> scanResult.getPackageInfo(null));
        rejectsNull(() -> scanResult.getModuleInfo(null));
        rejectsNull(() -> scanResult.getAllSubclasses((String) null));
        rejectsNull(() -> scanResult.getAllSubclasses((Class<?>) null));
        rejectsNull(() -> scanResult.getDirectSubclasses((String) null));
        rejectsNull(() -> scanResult.getDirectSubclasses((Class<?>) null));
        rejectsNull(() -> scanResult.getAllSuperclasses((String) null));
        rejectsNull(() -> scanResult.getAllSuperclasses((Class<?>) null));
        rejectsNull(() -> scanResult.getAllInterfaces((String) null));
        rejectsNull(() -> scanResult.getAllInterfaces((Class<?>) null));
        rejectsNull(() -> scanResult.getDirectInterfaces((String) null));
        rejectsNull(() -> scanResult.getDirectInterfaces((Class<?>) null));
        rejectsNull(() -> scanResult.getAllClassesImplementing((String) null));
        rejectsNull(() -> scanResult.getAllClassesImplementing((Class<?>) null));
        rejectsNull(() -> scanResult.getDirectClassesImplementing((String) null));
        rejectsNull(() -> scanResult.getDirectClassesImplementing((Class<?>) null));
        rejectsNull(() -> scanResult.getAllSubinterfaces((String) null));
        rejectsNull(() -> scanResult.getAllSubinterfaces((Class<?>) null));
        rejectsNull(() -> scanResult.getDirectSubinterfaces((String) null));
        rejectsNull(() -> scanResult.getDirectSubinterfaces((Class<?>) null));
        rejectsNull(() -> scanResult.getAllAnnotationsOnClass((String) null));
        rejectsNull(() -> scanResult.getAllAnnotationsOnClass((Class<?>) null));
        rejectsNull(() -> scanResult.getDirectAnnotationsOnClass((String) null));
        rejectsNull(() -> scanResult.getDirectAnnotationsOnClass((Class<?>) null));
        rejectsNull(() -> scanResult.getClassesWithAnnotation((String) null));
        rejectsNull(() -> scanResult.getResourcesWithLeafName(null));
        rejectsNull(() -> scanResult.getResourcesWithPath(null));
        rejectsNull(() -> scanResult.loadClass(null, false));
        rejectsNull(() -> scanResult.loadClass("java.lang.String", null, false));
    }

    /**
     * {@link ClassInfo} queries reject null, rather than returning false or null as
     * if nothing matched.
     */
    @Test
    void classInfoQueries() {
        final var classInfo = scanResult.getClassInfo(ClassInfoList.class.getName());
        rejectsNull(() -> classInfo.hasAnnotation((String) null));
        rejectsNull(() -> classInfo.hasAnnotation((Class<? extends Annotation>) null));
        rejectsNull(() -> classInfo.extendsSuperclass((String) null));
        rejectsNull(() -> classInfo.implementsInterface((String) null));
        rejectsNull(() -> classInfo.hasDeclaredMethod(null));
        rejectsNull(() -> classInfo.hasField(null));
        rejectsNull(() -> classInfo.getMethodInfo(null));
        rejectsNull(() -> classInfo.getFieldInfo(null));
        rejectsNull(() -> classInfo.getAllAnnotationInfo((String) null));
        // Previously wrapped as "IllegalArgumentException: Could not load class ..."
        rejectsNull(() -> classInfo.loadClass((Class<?>) null));
    }

    /** The list classes reject null, in their constructors and their methods. */
    @Test
    void listClasses() {
        final var classInfoList = scanResult.getAllClasses();
        rejectsNull(() -> new ClassInfoList((List<ClassInfo>) null));
        rejectsNull(() -> classInfoList.get(null));
        rejectsNull(() -> classInfoList.containsName(null));
        rejectsNull(() -> classInfoList.filter(null));
        rejectsNull(() -> classInfoList.exclude(null));
        rejectsNull(() -> classInfoList.union((ClassInfoList) null));
        rejectsNull(() -> classInfoList.intersect((ClassInfoList) null));
        rejectsNull(() -> classInfoList.getAssignableTo(null));
        rejectsNull(() -> classInfoList.loadClasses(null, false));
        rejectsNull(() -> classInfoList.generateGraphVizDotFile(null));
        rejectsNull(() -> classInfoList.generateGraphVizDotFileFromInterClassDependencies(null));
        rejectsNull(() -> classInfoList.writeGraphVizDotFile(null));
        rejectsNull(() -> classInfoList.writeGraphVizDotFile(null, new GraphVizDotFileOptions()));
        rejectsNull(() -> classInfoList.writeGraphVizDotFileFromInterClassDependencies(null));
        rejectsNull(
                () -> classInfoList.writeGraphVizDotFileFromInterClassDependencies(null, new GraphVizDotFileOptions()));

        final var classInfo = scanResult.getClassInfo(ClassInfoList.class.getName());
        rejectsNull(() -> classInfo.getMethodInfo().get(null));
        rejectsNull(() -> classInfo.getMethodInfo().filter(null));
        rejectsNull(() -> classInfo.getFieldInfo().filter(null));
        rejectsNull(() -> scanResult.getAllResources().get(null));
        rejectsNull(() -> scanResult.getAllResources().filter(null));
    }

    /** The type signature API rejects null. */
    @Test
    void typeSignatures() {
        final var typeSignature = scanResult.getClassInfo(ClassInfoList.class.getName())
                .getMethodInfo("getAssignableTo").get(0).getParameterInfo()[0].getTypeSignatureOrTypeDescriptor();
        rejectsNull(() -> typeSignature.resolveTypeVariables(null));
    }

    /**
     * The exception to the rule: a comparison accepts null and answers it, rather
     * than throwing, since that is what {@link Object#equals(Object)} does and what
     * a caller comparing two possibly-absent signatures expects.
     */
    @Test
    void comparisonsAcceptNull() {
        final var typeSignature = scanResult.getClassInfo(ClassInfoList.class.getName())
                .getMethodInfo("getAssignableTo").get(0).getParameterInfo()[0].getTypeSignatureOrTypeDescriptor();
        assertThat(typeSignature.equalsIgnoringTypeParams(null)).isFalse();
        assertThat(typeSignature.equals(null)).isFalse();
    }
}
