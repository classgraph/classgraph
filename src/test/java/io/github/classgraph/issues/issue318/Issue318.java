package io.github.classgraph.issues.issue318;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Unit test.
 */
public class Issue318 {
    /**
     * The Interface MyAnn.
     */
    @Repeatable(MyAnnRepeating.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE })
    @interface MyAnn {
    }

    /**
     * The Interface MyAnnRepeating.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE })
    @interface MyAnnRepeating {
        /**
         * Value.
         *
         * @return the my ann[]
         */
        MyAnn[] value();
    }

    /**
     * The Class With0MyAnn.
     */
    class With0MyAnn {
    }

    /**
     * The Class With1MyAnn.
     */
    @MyAnn
    class With1MyAnn {
    }

    /**
     * The Class With2MyAnn.
     */
    @MyAnn
    @MyAnn
    class With2MyAnn {
    }

    /**
     * The Class With3MyAnn.
     */
    @MyAnn
    @MyAnn
    @MyAnn
    class With3MyAnn {
    }

    /**
     * Issue 318.
     */
    @Test
    public void issue318() {
        try (final ScanResult scanResult = new ClassGraph().whitelistPackages(Issue318.class.getPackage().getName())
                .enableAnnotationInfo().enableClassInfo().ignoreClassVisibility() //
                //.verbose() //
                .scan()) {
            assertThat(scanResult.getClassesWithAnnotation(MyAnn.class.getName()).getNames()).containsOnly(
                    With1MyAnn.class.getName(), With2MyAnn.class.getName(), With3MyAnn.class.getName());
            assertThat(scanResult.getClassInfo(With3MyAnn.class.getName())
                    .getAnnotationInfoRepeatable(MyAnn.class.getName()).size()).isEqualTo(3);
        }
    }
}
