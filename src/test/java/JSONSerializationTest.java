import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.json.JSONDeserializer;
import io.github.lukehutch.fastclasspathscanner.scanner.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

public class JSONSerializationTest {

    private static class AA<X, Y> {
        X xInAA;
        Y yInAA;
    }

    private static class BBParent<Q> {
        Q bbParentField;

        public BBParent(final Q bbParentField) {
            this.bbParentField = bbParentField;
        }

    }

    private static class BB<B> extends BBParent<B> {
        AA<List<B>, String> aaInBB = new AA<>();

        public BB(final B bbParentField) {
            super(bbParentField);
        }
    }

    private static class CCParent<T> {
        T z;
        BB<T> q;
        Map<T, T> map = new HashMap<>();

        public CCParent(final T obj) {
            q = new BB<>(obj);
        }
    }

    private static class CC extends CCParent<Double> {
        BB<Integer> bbInCC = new BB<>(Integer.valueOf(5));
        int z;

        public CC(final Double obj) {
            super(obj);
        }
    }

    private static class CC2 extends CCParent<Float> {
        int wxy;

        public CC2(final Float obj) {
            super(obj);
        }
    }

    private static class DD {
        CC cc = new CC(Double.valueOf(10));
    }

    private static class ListHolder {
        Map<String, ClassInfo> classNameToClassInfo;
    }

    public static void main(final String[] args) throws Exception {
        final long time0 = System.nanoTime();
        final ScanResult scanResult = new FastClasspathScanner().enableFieldInfo().enableMethodInfo().scan();
        final long time1 = System.nanoTime();
        final String json = scanResult.toJSON(2);
        final long time2 = System.nanoTime();

        // System.out.println(json);

        final ListHolder listHolder = new ListHolder();
        JSONDeserializer.deserializeToField(listHolder, "classNameToClassInfo", json);
        final long time3 = System.nanoTime();

        System.out.println(json.length());
        System.out.println((time1 - time0) * 1.0e-9);
        System.out.println((time2 - time1) * 1.0e-9);
        System.out.println((time3 - time2) * 1.0e-9);
        System.out.println();

        // System.out.println(listHolder.classNameToClassInfo);
        
        // System.out.println(json);

        //        try (PrintWriter w = new PrintWriter("/tmp/3")) {
        //            w.print(json);
        //        }

        // TODO:
        // - Look closely at field names before pushing out new version (e.g. className -> name etc.), since
        //   serialization format should not change.
        // - Run all unit tests through serialize->deserialize to test them
        // - Come up with new ScanSpec config enum, and serialize ScanSpec to JSON too? 
    }
}
