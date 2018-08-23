package io.github.classgraph.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

@SuppressWarnings("unused")
public class JSONSerializationTest {

    private static class A<X, Y> {
        X x;
        Y y;

        public A() {
        }

        public A(final X x, final Y y) {
            this.x = x;
            this.y = y;
        }
    }

    private static class B<V> {
        V b;

        public B() {
        }

        public B(final V q) {
            this.b = q;
        }
    }

    private static class C<T> extends B<T> {
        A<List<T>, String> a;
        T[] arr;

        public C() {
        }

        public C(final T t) {
            super(t);
            final List<T> tList = new ArrayList<>();
            tList.add(t);
            a = new A<>(tList, "x");
            final List<T> ts = new ArrayList<>();
            ts.add(t);
            ts.add(t);
            ts.add(t);
            @SuppressWarnings("unchecked")
            final T[] a = (T[]) ts.toArray();
            arr = a;
        }
    }

    private static class D<T> {
        T z;
        C<T> q;
        Map<T, T> map = new HashMap<>();
        List<T> list;

        public D() {
        }

        public D(final T obj) {
            z = obj;
            q = new C<>(obj);
            map.put(obj, obj);
            list = new ArrayList<>();
            list.add(obj);
            list.add(obj);
            list.add(obj);
        }
    }

    private static class E extends D<Short> {
        C<Integer> c = new C<>(Integer.valueOf(5));
        int z = 42;

        public E() {
        }

        public E(final Short obj) {
            super(obj);
        }
    }

    private static class F extends D<Float> {
        int wxy = 123;

        public F() {
        }

        public F(final Float f) {
            super(f);
        }
    }

    private static class G {
        E e = new E(Short.valueOf((short) 3));
        F f = new F(1.5f);
    }

    private static class H {
        G g;
    }

    @Test
    public void testJSON() {
        final H h = new H();
        h.g = new G();

        final String json0 = JSONSerializer.serializeFromField(h, "g", 0, false);

        final String expected = //
                "{\"e\":{\"q\":{\"b\":3,\"a\":{\"x\":[3],\"y\":\"x\"},\"arr\":[3,3,3]},\"map\":{\"3\":3},"
                        + "\"list\":[3,3,3],\"c\":{\"b\":5,\"a\":{\"x\":[5],\"y\":\"x\"},\"arr\":[5,5,5]},"
                        + "\"z\":42},\"f\":{\"z\":1.5,\"q\":{\"b\":1.5,\"a\":{\"x\":[1.5],\"y\":\"x\"},"
                        + "\"arr\":[1.5,1.5,1.5]},\"map\":{\"1.5\":1.5},\"list\":[1.5,1.5,1.5],\"wxy\":123}}";

        assertThat(json0).isEqualTo(expected);

        final G obj = JSONDeserializer.deserializeObject(G.class, json0);

        final String json1 = JSONSerializer.serializeObject(obj, 0, false);

        assertThat(json0).isEqualTo(json1);
    }

    @Test
    public void testSerializeThenDeserializeScanResult() {
        try (ScanResult scanResult = new ClassGraph()
                .whitelistPackagesNonRecursive(JSONSerializationTest.class.getPackage().getName())
                .ignoreClassVisibility().scan()) {
            final int indent = 2;
            final String scanResultJSON = scanResult.toJSON(indent);
            final ScanResult scanResultDeserialized = ScanResult.fromJSON(scanResultJSON);
            final String scanResultReserializedJSON = scanResultDeserialized.toJSON(indent);
            assertThat(scanResultReserializedJSON).isEqualTo(scanResultJSON);
        }
    }
}
