package io.github.lukehutch.fastclasspathscanner.scanner;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;

import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;

/**
 * Worker thread class that produces a ClassInfo object from each ClassInfoUnlinked object in the classInfoUnlinked
 * queue, stores the ClassInfo object in the classNameToClassInfo map, then links the ClassInfo object with the
 * others in the classNameToClassInfo map. Only one thread should be used to build the classNameToClassInfo map,
 * since linking is not threadsafe.
 */
public class ClassInfoLinkerCaller implements Callable<Void> {
    private int endOfQueueMarkersExpected;
    private LinkedBlockingQueue<ClassInfoUnlinked> classInfoUnlinked;
    private Map<String, ClassInfo> classNameToClassInfo;
    private InterruptionChecker interruptionChecker;

    public ClassInfoLinkerCaller(int endOfQueueMarkersExpected,
            LinkedBlockingQueue<ClassInfoUnlinked> classInfoUnlinked, Map<String, ClassInfo> classNameToClassInfo,
            InterruptionChecker interruptionChecker) {
        super();
        this.endOfQueueMarkersExpected = endOfQueueMarkersExpected;
        this.classInfoUnlinked = classInfoUnlinked;
        this.classNameToClassInfo = classNameToClassInfo;
        this.interruptionChecker = interruptionChecker;
    }

    @Override
    public Void call() {
        // Convert ClassInfoUnlinked to linked ClassInfo objects
        for (int endOfQueueMarkersNeeded = endOfQueueMarkersExpected; endOfQueueMarkersNeeded > 0;) {
            try {
                ClassInfoUnlinked c = classInfoUnlinked.take();
                if (c == ClassInfoUnlinked.END_OF_QUEUE) {
                    --endOfQueueMarkersNeeded;
                } else {
                    // Create ClassInfo object from ClassInfoUnlinked object, and link into class graph
                    c.link(classNameToClassInfo);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            interruptionChecker.check();
        }
        return null;
    }
}
