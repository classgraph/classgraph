package io.github.classgraph.features.localrecord;

/**
 * Source of the classfiles in this directory, which LocalRecordTest scans. Local records, enums and interfaces need
 * Java 16, so the classfiles were compiled with {@code javac --release 17 -d src/test/resources LocalRecordHolder.java}
 * rather than with the Java 8 test sources.
 */
public class LocalRecordHolder {
    public Object[] make() {
        // Implicitly static
        record LocalRecord(String s) {
        }
        // Implicitly static
        enum LocalEnum {
            A
        }
        // Implicitly static
        interface LocalInterface {
        }
        // Not static, since it is declared in an instance method
        class LocalClass implements LocalInterface {
        }
        return new Object[] { new LocalRecord(""), LocalEnum.A, new LocalClass() };
    }
}
