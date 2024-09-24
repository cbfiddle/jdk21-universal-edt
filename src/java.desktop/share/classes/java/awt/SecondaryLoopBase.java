package java.awt;

import sun.util.logging.PlatformLogger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This base class is used to suspend execution on a thread while a nested external event pump is running. The API
 * methods of the class are thread-safe.
 */
public abstract class SecondaryLoopBase implements SecondaryLoop
{
    private static final PlatformLogger log = PlatformLogger.getLogger("java.awt.event.RunLoop");

    private final EventPump eventPump;

    private final Object LOCK = new Object();

    // true for AWT created secondary loops that are used only once (Dialog)
    private boolean isEnterExpected;
    private boolean isExitedBeforeEnter;

    // true between a successful enter and termination of the loop
    private AtomicBoolean loopIsActive = new AtomicBoolean(false);

    // true when the secondary loop is running, is reset if the run loop terminates
    private AtomicBoolean loopIsRunning = new AtomicBoolean(false);

    // true when an invocation on a thread other than the dispatch thread is waiting
    private AtomicBoolean isWaiting = new AtomicBoolean(false);

    /**
     * Initialize this base class.
     * @param eventPump The event pump.
     * @param isEnterExpected True if the secondary loop is expected to be entered exactly once. This option enables
     * support for exit being called before enter.
     */
    public SecondaryLoopBase(EventPump eventPump, boolean isEnterExpected)
    {
        this.eventPump = eventPump;
        this.isEnterExpected = isEnterExpected;
    }

    @Override
    public boolean enter() {

        synchronized (LOCK) {
            if (isEnterExpected) {
                isEnterExpected = false;
                if (isExitedBeforeEnter) {
                    isExitedBeforeEnter = false;
                    log.fine("Entering a secondary loop that has already been exited, aborting");
                    return false;
                }
            }

            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                String message = "enter: " + getMessage();
                log.fine(message);
            }

            if (!loopIsActive.compareAndSet(false, true)) {
                log.fine("A secondary loop has been entered, aborting");
                return false;
            }
        }

        // If we get here, loopIsActive has been set to true, preventing other attempts to enter

        try {
            if (eventPump.isDispatchThread()) {
                fixup();
                loopIsRunning.set(true);
                runSecondaryLoop(loopIsRunning);  // blocks until loop terminates or is canceled
            } else {
                // The motivation for entering a secondary loop on a thread other than the dispatch thread is unclear,
                // other than it being an efficient way to block until some other thread calls exit. Creating a
                // nested run loop seems to have no purpose.

                isWaiting.set(true);
                synchronized (LOCK) {
                    // check for a concurrent call to exit
                    if (!loopIsActive.get()) {
                        return false;
                    }
                    try {
                        while (isWaiting.get()) {
                            LOCK.wait();
                        }
                        if (log.isLoggable(PlatformLogger.Level.FINE)) {
                            log.fine("waitDone");
                        }
                    } catch (InterruptedException e) {
                        if (log.isLoggable(PlatformLogger.Level.FINE)) {
                            log.fine("Exception caught while waiting: " + e);
                        }
                    }
                }
            }
            return true;
        } finally {
            loopIsRunning.set(false);
            isWaiting.set(false);
            loopIsActive.set(false);
        }
    }

    private static void fixup() {
        // Dispose SequencedEvent we are dispatching on the current
        // AppContext, to prevent us from hang - see 4531693 for details
        SequencedEvent currentSE
                = KeyboardFocusManager.getCurrentKeyboardFocusManager().getCurrentSequencedEvent();
        if (currentSE != null) {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("Dispose current SequencedEvent: " + currentSE);
            }
            currentSE.dispose();
        }
    }

    @Override
    public boolean exit() {

        synchronized (LOCK) {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                String message = "exit: " + getMessage();
                log.fine(message);
            }
            if (isEnterExpected) {
                isExitedBeforeEnter = true;
                return false;
            }
            if (!loopIsActive.get()) {
                return false;
            }
            if (loopIsRunning.get()) {
                internalCancelSecondaryLoop();
                isWaiting.set(false);
                LOCK.notifyAll();
                return true;
            }
            if (isWaiting.getAndSet(false)) {
                LOCK.notifyAll();
                return true;
            }
            return false;
        }
    }

    private String getMessage() {
        String message = "isRunning=" + loopIsRunning.get()
                + ", isActive=" + loopIsActive.get()
                + ", isWaiting=" + isWaiting.get();
        if (eventPump.isDispatchThread()) {
            message = message + " on dispatch thread";
        } else {
            message = message + " on thread " + Thread.currentThread();
        }
        return message;
    }

    /**
     * Subclass implementation method for running a secondary loop. This method runs on the dispatch thread.
     * @param isRunning This atomic boolean should be cleared if and when the loop terminates on its own.
     */
    protected abstract void runSecondaryLoop(AtomicBoolean isRunning);

    private void internalCancelSecondaryLoop()
    {
        if (eventPump.isDispatchThread()) {
            cancelSecondaryLoop();
        } else {
            eventPump.scheduleDispatch(this::cancelSecondaryLoop);
        }
    }

    /**
     * Subclass implementation method to cancel the secondary loop. This method runs on the dispatch thread.
     */
    protected abstract void cancelSecondaryLoop();
}
