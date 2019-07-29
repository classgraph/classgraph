package io.github.classgraph.issues.issue339;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import io.github.classgraph.AnnotationParameterValueList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Unit test.
 */
public class Issue339 {
    /**
     * Grade.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @Documented
    //@Repeatable(Grades.class)
    public @interface Grade {
        /**
         * Points.
         *
         * @return the double
         */
        double points();

        /**
         * Max points.
         *
         * @return the double
         */
        double maxPoints() default 0.0;
    }

    /** The Class Cls. */
    public class Cls {
        /** Method with annotation. */
        @Grade(points = 0.4, maxPoints = 0.4)
        public void method() {
        }
    }

    /** Test. */
    @Test
    public void test() {
        try (ScanResult scanResult = new ClassGraph().enableAllInfo().enableExternalClasses()
                .whitelistClasses(Cls.class.getName()).scan()) {
            final ClassInfo classInfo = scanResult.getClassInfo(Cls.class.getName());
            final AnnotationParameterValueList annotationParamVals = classInfo.getMethodInfo("method").get(0)
                    .getAnnotationInfo().get(0).getParameterValues();
            assertThat(Math.abs((Double) annotationParamVals.get("points").getValue() - 0.4)).isLessThan(1.0e-12);
            assertThat(Math.abs((Double) annotationParamVals.get("maxPoints").getValue() - 0.4))
                    .isLessThan(1.0e-12);
        }
    }
}
