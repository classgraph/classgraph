package nonapi.io.github.classgraph.fileslice.reader;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import nonapi.io.github.classgraph.concurrency.InterruptionChecker;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.fileslice.FileSlice;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Tests that a read past the end of the classfile says so, whether or not the length of the classfile is known
 * ahead of time.
 *
 * <p>
 * When the length is known, the buffer is not grown past it, so a read past the end of the classfile used to be
 * reported as {@code "Hit 2GB limit while trying to grow buffer array"} -- a limit that a classfile of a few hundred
 * bytes had come nowhere near.
 */
public class ClassfileReaderPastEndOfClassfileTest {
    /** Eight bytes, with a different value in every one. */
    private static final byte[] PATTERN = { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD,
            (byte) 0xEF };

    /** The message that a read past the end of the classfile is reported with. */
    private static final String PAST_THE_END = "Tried to read past the end of the classfile";

    /**
     * A read past the end of a classfile whose length is known reports the end of the classfile, both on the first
     * such read and once the whole of the classfile has been buffered.
     *
     * @param tempDir
     *            a temporary directory to write the classfile to
     * @throws IOException
     *             if the classfile could not be written or read
     */
    @Test
    public void aReadPastTheEndOfAFileIsReportedAsSuch(@TempDir final File tempDir) throws IOException {
        final File file = new File(tempDir, "Test.class");
        Files.write(file.toPath(), PATTERN);
        final NestedJarHandler nestedJarHandler = new NestedJarHandler(new ScanSpec(), new InterruptionChecker(),
                new ReflectionUtils());
        try {
            final FileSlice slice = new FileSlice(file, nestedJarHandler, /* log = */ null);
            try (ClassfileReader reader = new ClassfileReader(slice, null)) {
                // The first read past the end stops short of the requested position
                assertThatThrownBy(() -> reader.readByte(PATTERN.length)).isInstanceOf(IOException.class)
                        .hasMessage(PAST_THE_END);
                // The second read past the end finds the whole of the classfile already buffered
                assertThatThrownBy(() -> reader.readByte(PATTERN.length)).isInstanceOf(IOException.class)
                        .hasMessage(PAST_THE_END);
            }
        } finally {
            nestedJarHandler.close(/* log = */ null);
        }
    }

    /**
     * A read past the end of a classfile whose length is not known ahead of time reports the end of the classfile.
     *
     * @throws IOException
     *             if the classfile could not be read
     */
    @Test
    public void aReadPastTheEndOfAStreamIsReportedAsSuch() throws IOException {
        try (ClassfileReader reader = new ClassfileReader(new ByteArrayInputStream(PATTERN.clone()), null)) {
            assertThatThrownBy(() -> reader.readByte(PATTERN.length)).isInstanceOf(IOException.class)
                    .hasMessage(PAST_THE_END);
        }
    }
}
