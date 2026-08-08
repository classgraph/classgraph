package nonapi.io.github.classgraph.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests that a map whose keys are not {@link Comparable} is serialized with each key still paired with its own
 * value.
 *
 * <p>
 * Map keys are sorted so that the serialized form is deterministic. When the keys are {@link Comparable} they are
 * sorted before the values are looked up, so keys and values stay in step. When they are not (a {@code Class<?>} key
 * is a legal map key, but {@link Class} does not implement {@link Comparable}), the serializer used to sort the
 * array of stringified keys on its own, after the values had already been collected in the map's own iteration
 * order -- so each key ended up paired with a different key's value.
 */
public class JSONSerializerMapKeyOrderTest {
    /** A class with a map field keyed by {@code Class<?>}, i.e. by a non-Comparable basic value type. */
    public static class ClassKeyedMapHolder {
        /** A map with non-Comparable keys. */
        public Map<Class<?>, String> map = new LinkedHashMap<>();
    }

    /** Each key must be paired with its own value, and the keys must still come out in sorted order. */
    @Test
    public void nonComparableMapKeysStayPairedWithTheirValues() {
        final ClassKeyedMapHolder holder = new ClassKeyedMapHolder();
        // Insert in an order whose stringified form is not already sorted, so that sorting actually reorders
        holder.map.put(Short.class, "value-for-Short");
        holder.map.put(Integer.class, "value-for-Integer");
        holder.map.put(Boolean.class, "value-for-Boolean");

        final String json = JSONSerializer.serializeObject(holder, 0, false);

        assertThat(json).contains("\"class java.lang.Boolean\":\"value-for-Boolean\"");
        assertThat(json).contains("\"class java.lang.Integer\":\"value-for-Integer\"");
        assertThat(json).contains("\"class java.lang.Short\":\"value-for-Short\"");
        // Keys must still be in sorted order, so that serialization is deterministic
        assertThat(json.indexOf("java.lang.Boolean")).isLessThan(json.indexOf("java.lang.Integer"));
        assertThat(json.indexOf("java.lang.Integer")).isLessThan(json.indexOf("java.lang.Short"));
    }

    /** A class with a map field keyed by String, i.e. by a Comparable basic value type. */
    public static class StringKeyedMapHolder {
        /** A map with Comparable keys. */
        public Map<String, String> map = new LinkedHashMap<>();
    }

    /** Comparable keys must still be sorted, and still paired with their own values. */
    @Test
    public void comparableMapKeysAreStillSortedAndPaired() {
        final StringKeyedMapHolder holder = new StringKeyedMapHolder();
        holder.map.put("c", "value-for-c");
        holder.map.put("a", "value-for-a");
        holder.map.put("b", "value-for-b");

        final String json = JSONSerializer.serializeObject(holder, 0, false);

        assertThat(json).contains("\"a\":\"value-for-a\"");
        assertThat(json).contains("\"b\":\"value-for-b\"");
        assertThat(json).contains("\"c\":\"value-for-c\"");
        assertThat(json.indexOf("\"a\"")).isLessThan(json.indexOf("\"b\""));
        assertThat(json.indexOf("\"b\"")).isLessThan(json.indexOf("\"c\""));
    }
}
