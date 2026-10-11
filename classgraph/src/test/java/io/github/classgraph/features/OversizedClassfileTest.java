package io.github.classgraph.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * A classfile is read into a buffer up to the furthest point the scanner reads or skips to, so a classfile that
 * declares a huge attribute that the scanner skips makes the scanner buffer all of it. Deflated, a classfile of
 * hundreds of megabytes of zeros takes up well under a megabyte of a jar, so the size of the buffer has to be
 * limited for a crafted jar not to exhaust the heap.
 */
public class OversizedClassfileTest {
    /** The length of the attribute that the scanner skips: far more than any real classfile holds. */
    private static final int SKIPPED_ATTRIBUTE_LENGTH = 256 * 1024 * 1024;

    /** The most that scanning the jar may allocate: far less than {@link #SKIPPED_ATTRIBUTE_LENGTH}. */
    private static final long MAX_ALLOCATED_BYTES = 96L * 1024 * 1024;

    /**
     * Write the start of a classfile for a public class with one private field, up to and including the length of
     * the one attribute of that field. The attribute's content, and the rest of the classfile (the method count and
     * the class attribute count, both zero), have to follow.
     *
     * @param className
     *            the name of the class.
     * @param attributeLength
     *            the length of the field's attribute.
     * @return the bytes of the start of the classfile.
     * @throws IOException
     *             if the classfile could not be written.
     */
    private static byte[] classfileHeader(final String className, final int attributeLength) throws IOException {
        final var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(byteArrayOutputStream)) {
            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // Minor version
            out.writeShort(52); // Major version (Java 8)

            out.writeShort(8); // Constant pool count (one greater than the number of entries)
            // #1
            out.writeByte(1);
            out.writeUTF(className);
            // #2
            out.writeByte(7); // class ref to #1 (this_class)
            out.writeShort(1);
            // #3
            out.writeByte(1);
            out.writeUTF("java/lang/Object");
            // #4
            out.writeByte(7); // class ref to #3 (super_class)
            out.writeShort(3);
            // #5
            out.writeByte(1);
            out.writeUTF("field");
            // #6
            out.writeByte(1);
            out.writeUTF("I");
            // #7
            out.writeByte(1);
            out.writeUTF("Padding"); // An attribute name the JVM and the scanner do not know, so both skip it

            out.writeShort(0x0021); // ACC_PUBLIC | ACC_SUPER
            out.writeShort(2); // this_class
            out.writeShort(4); // super_class
            out.writeShort(0); // Number of interfaces

            out.writeShort(1); // Number of fields
            out.writeShort(0x0002); // ACC_PRIVATE, so that the scanner skips the field and its attributes
            out.writeShort(5);
            out.writeShort(6);
            out.writeShort(1); // attributes_count
            out.writeShort(7); // "Padding"
            out.writeInt(attributeLength);
        }
        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Write a deflated classfile entry to a jar, streaming the attribute's zeros through the deflater so that the
     * whole classfile is never held in memory.
     *
     * @param zipOutputStream
     *            the jar to write the entry to.
     * @param className
     *            the name of the class.
     * @param attributeLength
     *            the length of the field's attribute.
     * @throws IOException
     *             if the entry could not be written.
     */
    private static void writeClassfile(final ZipOutputStream zipOutputStream, final String className,
            final int attributeLength) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(className + ".class"));
        zipOutputStream.write(classfileHeader(className, attributeLength));
        final var zeros = new byte[1024 * 1024];
        for (var remaining = attributeLength; remaining > 0; remaining -= zeros.length) {
            zipOutputStream.write(zeros, 0, Math.min(remaining, zeros.length));
        }
        zipOutputStream.write(new byte[4]); // methods_count, attributes_count
        zipOutputStream.closeEntry();
    }

    /**
     * Get the number of bytes allocated by all threads since the JVM started, or skip the test if the JVM cannot
     * report it.
     *
     * @return the number of bytes allocated.
     */
    private static long allocatedBytes() {
        final var threadMXBean = ManagementFactory.getThreadMXBean();
        assumeTrue(threadMXBean instanceof com.sun.management.ThreadMXBean);
        final var sunThreadMXBean = (com.sun.management.ThreadMXBean) threadMXBean;
        assumeTrue(sunThreadMXBean.isThreadAllocatedMemorySupported());
        sunThreadMXBean.setThreadAllocatedMemoryEnabled(true);
        return sunThreadMXBean.getTotalThreadAllocatedBytes();
    }

    /**
     * A classfile that would have to be buffered far beyond the size of any real classfile is skipped, without
     * being buffered, and the other classfiles of the jar are still scanned.
     *
     * @param tempDir
     *            the directory to write the jar to.
     * @throws IOException
     *             if the jar could not be written.
     */
    @Test
    public void anOversizedClassfileIsSkippedWithoutBeingBuffered(@TempDir final Path tempDir) throws IOException {
        final var jarFile = tempDir.resolve("oversized.jar");
        try (var zipOutputStream = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            zipOutputStream.setLevel(Deflater.BEST_SPEED);
            writeClassfile(zipOutputStream, "Oversized", SKIPPED_ATTRIBUTE_LENGTH);
            writeClassfile(zipOutputStream, "Small", 16);
        }
        assertThat(Files.size(jarFile)).isLessThan(SKIPPED_ATTRIBUTE_LENGTH / 100);

        final var allocatedBefore = allocatedBytes();
        try (var scanResult = new ClassGraph().enableClassInfo().enableClasspathEntries(jarFile.toFile()).scan()) {
            assertThat(allocatedBytes() - allocatedBefore).isLessThan(MAX_ALLOCATED_BYTES);
            assertThat(scanResult.getClassInfo("Small")).isNotNull();
            assertThat(scanResult.getClassInfo("Oversized")).isNull();
        }
    }
}
