package io.github.classgraph.test.parameterannotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;

/**
 * This class tests for function parameter annotations with different retention policies.
 *
 * @author Tony Nguyen
 * @version 4.8.22
 * @see <a href=
 *      "https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/annotation/RetentionPolicy.html">
 *      RetentionPolicy</a>
 */
public class RetentionPolicyForFunctionParameterAnnotationsTest {
    private static ScanResult scanResult;
    private static ClassInfo classInfo;

    private final static String RETENTION_CLASS = "retention_class";
    private final static String RETENTION_RUNTIME = "retention_runtime";
    private final static String RETENTION_SOURCE = "retention_source";

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface ParamAnnoRuntime {
        String value() default RETENTION_RUNTIME;
    }

    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.PARAMETER)
    public @interface ParamAnnoClass {
        String value() default RETENTION_CLASS;
    }

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.PARAMETER)
    public @interface ParamAnnoSource {
        String value() default RETENTION_SOURCE;
    }

    @BeforeClass
    public static void beforeClass() {
        scanResult = new ClassGraph().enableAllInfo().scan();
        classInfo = scanResult.getClassInfo(RetentionPolicyForFunctionParameterAnnotationsTest.class.getName());
    }

    @AfterClass
    public static void afterClass() {
        scanResult.close();
    }

    /*------------------------------------------------------------------------*/

    /**
     * Must be able to detect parameter annotation with RUNTIME retention.
     */
    @Test
    public void canDetect_ParameterAnnotation_WithRuntimeRetention() {
        final MethodInfo methodInfo = classInfo.getMethodInfo()
                .getSingleMethod("parameterAnnotation_WithRuntimeRetention");

        assertThat(methodInfo.hasParameterAnnotation(ParamAnnoRuntime.class.getName())).isTrue();
    }

    public void parameterAnnotation_WithRuntimeRetention(@ParamAnnoRuntime final int input) {
    }

    /*------------------------------------------------------------------------*/

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface SecondParamAnnoRuntime {
    }

    /**
     * Should be able to detect multiple annotations with RUNTIME retention for a single function parameter.
     */
    @Test
    public void canDetect_TwoAnnotations_WithRuntimeRetention_ForSingleParam() {
        final MethodInfo methodInfo = classInfo.getMethodInfo()
                .getSingleMethod("twoAnnotations_WithRuntimeRetention_ForSingleParam");

        assertThat(methodInfo.hasParameterAnnotation(ParamAnnoRuntime.class.getName())).isTrue();

        assertThat(methodInfo.hasParameterAnnotation(SecondParamAnnoRuntime.class.getName())).isTrue();
    }

    public void twoAnnotations_WithRuntimeRetention_ForSingleParam(
            @ParamAnnoRuntime @SecondParamAnnoRuntime final int input) {
    }

    /*------------------------------------------------------------------------*/

    /**
     * Annotations with CLASS retention does not need to be retained by vm at run time, but annotations with RUNTIME
     * retention should still be detectable.
     */
    @Test
    public void canDetect_ParameterAnnotation_OneRuntimeRetention_OneClassRetention() {
        final MethodInfo methodInfo = classInfo.getMethodInfo()
                .getSingleMethod("oneRuntimeRetention_OneClassRetention");

        assertThat(methodInfo.hasParameterAnnotation(ParamAnnoRuntime.class.getName())).isTrue();
    }

    public void oneRuntimeRetention_OneClassRetention(@ParamAnnoRuntime @ParamAnnoClass final int input) {
    }

    /*------------------------------------------------------------------------*/

    /**
     * Annotations with CLASS retention does not need to be retained by vm at run time, but annotations with RUNTIME
     * retention should still be detectable.
     *
     * This tests a changed ordering of the annotations with different retention policies.
     */
    @Test
    public void canDetect_ParameterAnnotation_OneRuntimeRetention_OneClassRetention_ChangedAnnotationOrder() {
        final MethodInfo methodInfo = classInfo.getMethodInfo()
                .getSingleMethod("oneRuntimeRetention_OneClassRetention_ChangedAnnotationOrder");

        assertThat(methodInfo.hasParameterAnnotation(ParamAnnoRuntime.class.getName())).isTrue();
    }

    public void oneRuntimeRetention_OneClassRetention_ChangedAnnotationOrder(
            @ParamAnnoClass @ParamAnnoRuntime final int input) {
    }

    /*------------------------------------------------------------------------*/

    /**
     * Annotations with SOURCE retention are discarded on compilation, but annotations with RUNTIME retention should
     * still be detectable.
     */
    @Test
    public void canDetect_ParameterAnnotation_OneRuntimeRetention_OneSourceRetention() {
        final MethodInfo methodInfo = classInfo.getMethodInfo()
                .getSingleMethod("oneRuntimeRetention_OneSourceRetention");

        assertThat(methodInfo.hasParameterAnnotation(ParamAnnoRuntime.class.getName())).isTrue();
    }

    public void oneRuntimeRetention_OneSourceRetention(@ParamAnnoRuntime @ParamAnnoSource final int input) {
    }
}