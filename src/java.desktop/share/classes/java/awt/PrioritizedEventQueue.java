package java.awt;

import java.util.function.*;
import sun.awt.*;

/**
 * A basic representation of a collection of AWTEvents with associated priorities.
 */

class PrioritizedEventQueue {

    private final int NUM_PRIORITIES;

    /*
     * We maintain one Queue for each priority that the EventQueue supports.
     * That is, the queue of events is actually implemented as
     * NUM_PRIORITIES queues and all Events on a particular internal queue
     * have identical priority. Events are pulled off the EventQueue starting
     * with the Queue of highest priority. We progress in decreasing order
     * across all Queues.
     */
    private final Queue[] queues;

    /**
     * Create a prioritized event queue.
     * @param nPriorities The number of priorities.
     */

    public PrioritizedEventQueue(int nPriorities) {
        this.NUM_PRIORITIES = nPriorities;
        queues = new Queue[nPriorities];
        for (int i = 0; i < NUM_PRIORITIES; i++) {
            queues[i] = new Queue();
        }
    }

    /**
     * Add an event to the queue.
     * @param event The event.
     * @param priority The event priority.
     * @return the new queue item.
     */

    public EventQueueItem add(AWTEvent event, int priority) {
        EventQueueItem newItem = new EventQueueItem(event);
        add(newItem, priority);
        return newItem;
    }

    private void add(EventQueueItem item, int priority) {
        if (queues[priority].head == null) {
            queues[priority].head = queues[priority].tail = item;
        } else {
            queues[priority].tail.next = item;
            queues[priority].tail = item;
        }
    }

    /**
     * Remove and return the next available event.
     * @return the next event, or null if the queue is empty.
     */
    public EventQueueItem getNextItem() {
        for (int i = NUM_PRIORITIES - 1; i >= 0; i--) {
            if (queues[i].head != null) {
                EventQueueItem entry = queues[i].head;
                queues[i].head = entry.next;
                if (entry.next == null) {
                    queues[i].tail = null;
                }
                return entry;
            }
        }
        return null;
    }

    /**
     * Remove and return the next available event with the specified event ID.
     * @param id The event ID.
     * @return the next event, or null if the queue does not contain an event with the specified ID.
     */
    public EventQueueItem getNextItemWithId(int id) {
        for (int i = 0; i < NUM_PRIORITIES; i++) {
            for (EventQueueItem entry = queues[i].head, prev = null;
                 entry != null; prev = entry, entry = entry.next)
            {
                if (entry.event.getID() == id) {
                    if (prev == null) {
                        queues[i].head = entry.next;
                    } else {
                        prev.next = entry.next;
                    }
                    if (queues[i].tail == entry) {
                        queues[i].tail = prev;
                    }
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * Return the next available event without removing it.
     * @return the next event, or null if the queue is empty.
     */
    public EventQueueItem peek() {
        for (int i = NUM_PRIORITIES - 1; i >= 0; i--) {
            if (queues[i].head != null) {
                return queues[i].head;
            }
        }
        return null;
    }

    /**
     * Returns the first event with the specified id without removing it.
     * @param id the id of the type of event desired
     * @return the first event of the specified id or {@code null}
     *    if there is no such event
     */
    public EventQueueItem peekEvent(int id) {
        for (int i = NUM_PRIORITIES - 1; i >= 0; i--) {
            EventQueueItem q = queues[i].head;
            for (; q != null; q = q.next) {
                if (q.event.getID() == id) {
                    return q;
                }
            }
        }
        return null;
    }

    /**
     * Indicate whether this queue is empty.
     * @return true if and only if there are no events in this queue.
     */
    public boolean isEmpty() {
        for (int i = 0; i < NUM_PRIORITIES; i++) {
            if (queues[i].head != null) {
                return false;
            }
        }

        return true;
    }

    /**
     * Remove events from the queue that match the specified predicate.
     * @param p The predicate.
     * @param c If not null, each queue item that is removed is passed to this consumer.
     */
    public void removeEvents(Predicate<AWTEvent> p, Consumer<EventQueueItem> c) {
        for (int i = 0; i < NUM_PRIORITIES; i++) {
            EventQueueItem entry = queues[i].head;
            EventQueueItem prev = null;
            while (entry != null) {
                if (p.test(entry.event)) {
                    if (prev == null) {
                        queues[i].head = entry.next;
                    } else {
                        prev.next = entry.next;
                    }
                    if (c != null) {
                        c.accept(entry);
                    }
                } else {
                    prev = entry;
                }
                entry = entry.next;
            }
            queues[i].tail = prev;
        }
    }

    /**
     * Attach the events in the specified queue at the end of this queue.
     * @param other The queue containing the events to be appended. This queue should not be used henceforth.
     */
    public void spliceAtEnd(PrioritizedEventQueue other) {
        for (int i = 0; i < NUM_PRIORITIES; i++) {
            EventQueueItem otherHead = other.queues[i].head;
            if (otherHead != null) {
                EventQueueItem otherTail = other.queues[i].tail;
                EventQueueItem tail = queues[i].tail;
                if (tail == null) {
                    queues[i].head = otherHead;
                    queues[i].tail = otherTail;
                } else {
                    tail.next = otherHead;
                    queues[i].tail = otherTail;
                }
            }
        }
    }

    /**
     * Coalesce an event based on its source and ID.
     * @param e The event.
     * @param priority The event priority.
     * @return true if the event was coalesced.
     */
    public boolean coalesceOtherEvent(AWTEvent e, int priority) {
        int id = e.getID();
        Component source = (Component)e.getSource();
        for (EventQueueItem entry = queues[priority].head;
             entry != null; entry = entry.next)
        {
            // Give Component.coalesceEvents a chance
            if (entry.event.getSource() == source && entry.event.getID() == id) {
                AWTEvent coalescedEvent = source.coalesceEvents(
                        entry.event, e);
                if (coalescedEvent != null) {
                    entry.event = coalescedEvent;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The Queue object holds pointers to the beginning and end of one internal
     * queue. A PrioritizedEventQueue object is composed of multiple internal Queues, one
     * for each priority supported by the EventQueue. All Events on a particular
     * internal Queue have identical priority.
     */
    private static class Queue {
        EventQueueItem head;
        EventQueueItem tail;
    }
}
