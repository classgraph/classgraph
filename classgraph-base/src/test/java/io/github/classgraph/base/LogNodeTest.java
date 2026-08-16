package io.github.classgraph.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

/** Tests for {@link LogNode}. */
public class LogNodeTest {
    /**
     * The logger that {@link LogNode} writes to. Named here as a string, since {@link LogNode} names it that way.
     */
    private static final Logger LOGGER = Logger.getLogger("io.github.classgraph.ClassGraph");

    /** A {@link Handler} that records the messages logged to it, rather than printing them. */
    private static class RecordingHandler extends Handler {
        /** The messages that were logged. */
        final List<String> messages = new ArrayList<>();

        @Override
        public void publish(final LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
            // Nothing to flush
        }

        @Override
        public void close() {
            // Nothing to close
        }
    }

    /**
     * Run a task with the messages logged by {@link LogNode} recorded rather than printed, so that the tests
     * neither print to the console nor depend on the logging configuration.
     *
     * @param task
     *            The task to run.
     * @return the messages that were logged while the task ran.
     */
    private static List<String> recordLogOutput(final Runnable task) {
        final var handler = new RecordingHandler();
        final var useParentHandlers = LOGGER.getUseParentHandlers();
        LOGGER.setUseParentHandlers(false);
        LOGGER.addHandler(handler);
        try {
            task.run();
        } finally {
            LOGGER.removeHandler(handler);
            LOGGER.setUseParentHandlers(useParentHandlers);
        }
        return handler.messages;
    }

    /**
     * Strip the timestamp and logger name from each line of the log output, leaving the indentation and the
     * message, so that the tests can assert on the log's structure.
     *
     * @param logNode
     *            The log node to render.
     * @return the message part of each line of the log output.
     */
    private static List<String> messageLines(final LogNode logNode) {
        // Only the first two tab-separated fields are stripped, since a stacktrace line contains tabs of its own
        return logNode.toString().lines().map(line -> line.split("\t", 3)[2]).toList();
    }

    /** The toplevel log node names the ClassGraph version and the environment the scan is running in. */
    @Test
    public void aToplevelLogNodeRecordsTheVersionAndTheEnvironment() {
        assertThat(messageLines(new LogNode())).hasSize(4)
                .anySatisfy(line -> assertThat(line).startsWith("ClassGraph version "))
                .anySatisfy(line -> assertThat(line).startsWith("Operating system: "))
                .anySatisfy(line -> assertThat(line).startsWith("Java version: "))
                .anySatisfy(line -> assertThat(line).startsWith("Java home: "));
    }

    /** Each line is prefixed with a timestamp and the name of the library, so that log lines can be recognized. */
    @Test
    public void everyLineNamesTheTimeAndTheLibrary() {
        final var logNode = new LogNode();
        logNode.log("a message");
        assertThat(logNode.toString().lines()).allSatisfy(line -> assertThat(line)
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[-+]\\d{4}\tClassGraph\t.*"));
    }

    /** Child entries are indented by two dashes per level of the tree, so that the structure can be read. */
    @Test
    public void entriesAreIndentedByTheirDepthInTheTree() {
        final var logNode = new LogNode();
        final var child = logNode.log("child");
        final var grandchild = child.log("grandchild");
        grandchild.log("great-grandchild");

        assertThat(messageLines(logNode)).endsWith("child", "-- grandchild", "---- great-grandchild");
    }

    /**
     * Entries added with a sort key are ordered by that key rather than by the order they were added in, so that
     * the output is deterministic even though entries are added from many threads.
     */
    @Test
    public void entriesWithASortKeyAreOrderedByIt() {
        final var logNode = new LogNode();
        logNode.log("3", "third");
        logNode.log("1", "first");
        logNode.log("2", "second");

        assertThat(messageLines(logNode)).endsWith("first", "second", "third");
    }

    /**
     * Entries with the same sort key are ordered by the order they were added in, and an entry with no sort key is
     * ordered before any entry that has one, since the empty sort key sorts first.
     */
    @Test
    public void entriesWithTheSameSortKeyAreOrderedChronologically() {
        final var logNode = new LogNode();
        logNode.log("same", "added first");
        logNode.log("same", "added second");
        logNode.log("no sort key");

        assertThat(messageLines(logNode)).endsWith("no sort key", "added first", "added second");
    }

    /** An empty message is not printed, so that a log node can be used purely to group its children. */
    @Test
    public void emptyMessagesAreNotPrinted() {
        final var logNode = new LogNode();
        logNode.log("").log("only the child is printed");

        // The child of the empty node is still indented by its own depth in the tree
        assertThat(messageLines(logNode)).endsWith("-- only the child is printed");
    }

    /** The elapsed time is shown after the message, whether it was passed in or measured by the log node. */
    @Test
    public void theElapsedTimeIsShownAfterTheMessage() {
        final var logNode = new LogNode();
        logNode.log("half a second", 500_000_000L);
        // A negative elapsed time means "not measured", and is not shown
        logNode.log("not timed", -1L);
        logNode.log("timed by the log node").addElapsedTime();

        assertThat(messageLines(logNode)).contains("half a second (took 0.500000 sec)", "not timed")
                .anySatisfy(line -> assertThat(line).startsWith("timed by the log node (took 0."));
    }

    /** A logged exception is printed as its stacktrace, under the entry it was logged against. */
    @Test
    public void anExceptionIsPrintedAsItsStacktrace() {
        final var logNode = new LogNode();
        logNode.log("something failed", new IllegalStateException("the reason"));

        assertThat(messageLines(logNode)).contains("-- java.lang.IllegalStateException: the reason")
                .anySatisfy(line -> assertThat(line).startsWith("-- \tat " + getClass().getName()));
    }

    /** Every way of logging an entry adds a child node, and returns it so that sub-entries can be added to it. */
    @Test
    public void everyLogOverloadAddsAChildEntry() {
        final var logNode = new LogNode();
        final var exception = new IllegalArgumentException("failed");
        logNode.log("sort key", "sort key, message, elapsed time, exception", 1L, exception);
        logNode.log("sort key", "sort key, message, elapsed time", 1L);
        logNode.log("sort key", "sort key, message, exception", exception);
        logNode.log("sort key", "sort key, message");
        logNode.log("message, elapsed time, exception", 1L, exception);
        logNode.log("message, elapsed time", 1L);
        logNode.log("message, exception", exception);
        logNode.log("message");
        // A collection of messages adds one entry per message, and the last of them is returned
        final var lastOfSeveral = logNode.log(List.of("first of several messages", "second of several messages"));
        assertThat(lastOfSeveral).isNotNull();
        assertThat(lastOfSeveral.toString()).contains("second of several messages");
        // An empty collection adds nothing, so there is no last entry to return
        assertThat(logNode.log(List.of())).isNull();
        logNode.log(exception);

        assertThat(messageLines(logNode)).contains("sort key, message, elapsed time, exception (took 0.000000 sec)",
                "sort key, message, elapsed time (took 0.000000 sec)", "sort key, message, exception",
                "sort key, message", "message, elapsed time, exception (took 0.000000 sec)",
                "message, elapsed time (took 0.000000 sec)", "message, exception", "message",
                "first of several messages", "second of several messages", "Exception thrown");
        // The four overloads that take an exception, and log(Throwable), each print its stacktrace under the entry
        assertThat(messageLines(logNode))
                .filteredOn(line -> line.endsWith("java.lang.IllegalArgumentException: failed")).hasSize(5);
    }

    /** Flushing writes the log out and empties it, and is rejected on any node but the toplevel one. */
    @Test
    public void flushingWritesTheLogOutAndEmptiesIt() {
        final var logNode = new LogNode();
        final var child = logNode.log("a message");
        assertThatThrownBy(child::flush).isInstanceOf(IllegalStateException.class)
                .hasMessage("Only flush the toplevel LogNode");

        final var logged = recordLogOutput(logNode::flush);
        assertThat(logged).singleElement().asString().contains("a message");
        assertThat(logNode.toString()).isEmpty();

        // Flushing an empty log writes nothing at all, rather than writing a blank line
        assertThat(recordLogOutput(logNode::flush)).isEmpty();
    }

    /**
     * When realtime logging is turned on, each entry is written out as it is added, as well as being added to the
     * tree, so that entries are not lost if the scan never reaches the point where the log is flushed.
     */
    @Test
    public void realtimeLoggingWritesEachEntryAsItIsAdded() {
        final var logNode = new LogNode();
        final var logged = recordLogOutput(() -> {
            LogNode.logInRealtime(true);
            try {
                logNode.log("logged in realtime");
            } finally {
                LogNode.logInRealtime(false);
            }
        });
        assertThat(logged).singleElement().asString().contains("logged in realtime");
    }

    /** Once realtime logging is turned off again, entries are only added to the tree. */
    @Test
    public void entriesAreNotWrittenOutWhenRealtimeLoggingIsOff() {
        assertThat(recordLogOutput(() -> new LogNode().log("not logged in realtime"))).isEmpty();
    }

    /** The log is safe to add to from many threads at once, and no entry is lost or overwritten. */
    @Test
    public void entriesCanBeAddedFromManyThreadsAtOnce() throws InterruptedException {
        final var logNode = new LogNode();
        final var threads = new ArrayList<Thread>();
        for (var i = 0; i < 8; i++) {
            final var threadIdx = i;
            final var thread = new Thread(() -> {
                for (var j = 0; j < 100; j++) {
                    logNode.log("entry " + threadIdx + "." + j);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (final Thread thread : threads) {
            thread.join();
        }
        // The four entries the toplevel log node writes when it is created are in the log as well
        assertThat(messageLines(logNode)).hasSize(8 * 100 + 4);
    }
}
