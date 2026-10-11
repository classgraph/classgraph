package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** Runs a child JVM for a test, with a time limit, so that a child JVM that hangs fails the test. */
public final class ChildJvm {
    /** How long a child JVM may run, in minutes. */
    private static final long TIMEOUT_MINUTES = 5;

    /** Not instantiable. */
    private ChildJvm() {
    }

    /**
     * Start a child JVM, wait for it to exit, and check that it exited with status 0. The child JVM is killed if it
     * runs for longer than the time limit.
     *
     * @param processBuilder
     *            the child JVM to start. Its standard error is merged into its standard output, and its output is
     *            sent to a temporary file.
     * @return everything the child JVM wrote to stdout and stderr.
     * @throws IOException
     *             if the child JVM could not be started, or its output could not be read.
     * @throws InterruptedException
     *             if the thread was interrupted while waiting for the child JVM.
     */
    public static String run(final ProcessBuilder processBuilder) throws IOException, InterruptedException {
        // The output goes to a file rather than a pipe, so that a child JVM that hangs cannot block the read, and
        // the timeout still applies
        final var outputFile = Files.createTempFile("child-jvm", ".out");
        try {
            final var process = processBuilder.redirectErrorStream(true).redirectOutput(outputFile.toFile())
                    .start();
            final var finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly().waitFor();
            }
            final var output = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
            assertThat(finished).as("Child JVM did not finish in %d minutes. Output:%n%s", TIMEOUT_MINUTES, output)
                    .isTrue();
            assertThat(process.exitValue()).as("Child JVM output:%n%s", output).isZero();
            return output;
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }
}
