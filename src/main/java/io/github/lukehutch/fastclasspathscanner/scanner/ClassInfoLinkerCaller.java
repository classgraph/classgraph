package io.github.lukehutch.fastclasspathscanner.scanner;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;

import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo;

/**
 * Worker thread class that produces a ClassInfo object from each ClassInfoUnlinked object in the classInfoUnlinked
 * queue, stores the ClassInfo object in the classNameToClassInfo map, then links the ClassInfo object with the
 * others in the classNameToClassInfo map. Only one thread should be used to build the classNameToClassInfo map,
 * since linking is not threadsafe.
 */
public class ClassInfoLinkerCaller implements Callable<Void> {
    private final int numWorkerThreads;
    private final LinkedBlockingQueue<ClassInfoUnlinked> classInfoUnlinked;
    private final Map<String, ClassInfo> classNameToClassInfo;

    public ClassInfoLinkerCaller(final LinkedBlockingQueue<ClassInfoUnlinked> classInfoUnlinked,
            final Map<String, ClassInfo> classNameToClassInfo, final int numWorkerThreads) {
        this.classInfoUnlinked = classInfoUnlinked;
        this.classNameToClassInfo = classNameToClassInfo;
        this.numWorkerThreads = numWorkerThreads;
    }

    @Override
    public Void call() {
        // Convert ClassInfoUnlinked to linked ClassInfo objects
        for (int endOfQueueMarkersNeeded = numWorkerThreads; endOfQueueMarkersNeeded > 0
                && !Thread.currentThread().isInterrupted();) {
            try {
                final ClassInfoUnlinked c = classInfoUnlinked.take();
                if (c == ClassInfoUnlinked.END_OF_QUEUE) {
                    --endOfQueueMarkersNeeded;
                } else {
                    // Create ClassInfo object from ClassInfoUnlinked object, and link into class graph
                    c.link(classNameToClassInfo);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }
}
