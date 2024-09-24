package java.awt;

/**
 * An interface providing access to an external event pump.
 */

public interface EventPump {

    /**
     * Indicate whether the current thread is the dispatch thread used by this event pump
     * This method should be thread safe.
     * @return true if and only if the current thread is the dispatch thread.
     */
    boolean isDispatchThread();

    /**
     * Perform the specified code on the dispatch thread.
     * @param r The code to execute.
     */
    void scheduleDispatch(Runnable r);

    /**
     * Create a secondary loop.
     * @param filter An optional filter that may indicate the purpose of the secondary loop.
     * @return the run loop.
     */
    SecondaryLoop createSecondaryLoop(EventFilter filter);

    /**
     * Notify the event pump that one or more events have been posted to the event queue.
     * This method should be thread safe.
     */
    void eventsPosted();
}
