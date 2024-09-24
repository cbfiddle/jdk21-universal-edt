/*
 * Copyright (c) 1996, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.awt;

import java.awt.event.*;

import java.awt.peer.ComponentPeer;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;

import java.security.AccessController;
import java.security.PrivilegedAction;

import java.util.EmptyStackException;

import java.util.function.*;
import sun.awt.*;
import sun.awt.dnd.SunDropTargetEvent;
import sun.util.logging.PlatformLogger;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.atomic.AtomicInteger;

import java.security.AccessControlContext;

import jdk.internal.access.SharedSecrets;
import jdk.internal.access.JavaSecurityAccess;

/**
 * {@code EventQueue} is a platform-independent class
 * that queues events, both from the underlying peer classes
 * and from trusted application classes.
 * <p>
 * It encapsulates asynchronous event dispatch machinery which
 * extracts events from the queue and dispatches them by calling
 * {@link #dispatchEvent(AWTEvent) dispatchEvent(AWTEvent)} method
 * on this {@code EventQueue} with the event to be dispatched
 * as an argument.  The particular behavior of this machinery is
 * implementation-dependent.  The only requirements are that events
 * which were actually enqueued to this queue (note that events
 * being posted to the {@code EventQueue} can be coalesced)
 * are dispatched:
 * <dl>
 *   <dt> Sequentially.
 *   <dd> That is, it is not permitted that several events from
 *        this queue are dispatched simultaneously.
 *   <dt> In the same order as they are enqueued.
 *   <dd> That is, if {@code AWTEvent}&nbsp;A is enqueued
 *        to the {@code EventQueue} before
 *        {@code AWTEvent}&nbsp;B then event B will not be
 *        dispatched before event A.
 * </dl>
 * <p>
 * Some browsers partition applets in different code bases into
 * separate contexts, and establish walls between these contexts.
 * In such a scenario, there will be one {@code EventQueue}
 * per context. Other browsers place all applets into the same
 * context, implying that there will be only a single, global
 * {@code EventQueue} for all applets. This behavior is
 * implementation-dependent.  Consult your browser's documentation
 * for more information.
 * <p>
 * For information on the threading issues of the event dispatch
 * machinery, see <a href="doc-files/AWTThreadIssues.html#Autoshutdown"
 * >AWT Threading Issues</a>.
 *
 * @author Thomas Ball
 * @author Fred Ecks
 * @author David Mendenhall
 *
 * @since       1.1
 */
public class EventQueue {
    private static final AtomicInteger threadInitNumber = new AtomicInteger();

    private static final int LOW_PRIORITY = 0;
    private static final int NORM_PRIORITY = 1;
    private static final int HIGH_PRIORITY = 2;
    private static final int ULTIMATE_PRIORITY = 3;

    private static final int NUM_PRIORITIES = ULTIMATE_PRIORITY + 1;

    /*
     * We maintain one Queue for each priority that the EventQueue supports.
     * That is, the EventQueue object is actually implemented as
     * NUM_PRIORITIES queues and all Events on a particular internal Queue
     * have identical priority. Events are pulled off the EventQueue starting
     * with the Queue of highest priority. We progress in decreasing order
     * across all Queues.
     */
    private PrioritizedEventQueue currentEvents = new PrioritizedEventQueue(NUM_PRIORITIES);

    /*
     * Events deferred until the next event pump, when an external run loop is used.
     */
    private PrioritizedEventQueue deferredEvents = null;

    /*
     * The next EventQueue on the stack, or null if this EventQueue is
     * on the top of the stack.  If nextQueue is non-null, requests to post
     * an event are forwarded to nextQueue.
     */
    private EventQueue nextQueue;

    /*
     * The previous EventQueue on the stack, or null if this is the
     * "base" EventQueue.
     */
    private EventQueue previousQueue;

    /*
     * A single lock to synchronize the push()/pop() and related operations with
     * all the EventQueues from the AppContext. Synchronization on any particular
     * event queue(s) is not enough: we should lock the whole stack.
     */
    private final Lock pushPopLock;
    private final Condition pushPopCond;

    /*
     * Dummy runnable to wake up EDT from getNextEvent() after
     push/pop is performed
     */
    private static final Runnable dummyRunnable = () -> {};

    private EventDispatchThread dispatchThread;

    private final ThreadGroup threadGroup =
        Thread.currentThread().getThreadGroup();
    private final ClassLoader classLoader =
        Thread.currentThread().getContextClassLoader();

    /*
     * The time stamp of the last dispatched InputEvent or ActionEvent.
     */
    private long mostRecentEventTime = System.currentTimeMillis();

    /*
     * The time stamp of the last KeyEvent .
     */
    private long mostRecentKeyEventTime = System.currentTimeMillis();

    /**
     * The modifiers field of the current event, if the current event is an
     * InputEvent or ActionEvent.
     */
    private WeakReference<AWTEvent> currentEvent;

    /*
     * Non-zero if a thread is waiting in getNextEvent(int) for an event of
     * a particular ID to be posted to the queue.
     */
    private volatile int waitForID;

    /*
     * AppContext corresponding to the queue.
     */
    private final AppContext appContext;

    private final String name = "AWT-EventQueue-" + threadInitNumber.getAndIncrement();

    private EventPump eventPump;

    private static volatile PlatformLogger eventLog;

    private static final PlatformLogger getEventLog() {
        if(eventLog == null) {
            eventLog = PlatformLogger.getLogger("java.awt.event.EventQueue");
        }
        return eventLog;
    }

    static {
        AWTAccessor.setEventQueueAccessor(
                new AWTAccessor.EventQueueAccessor() {
                    @Override
                    public EventQueue createEventQueue(AppContext appContext) {
                        return new EventQueue(appContext);
                    }
                    public Thread getDispatchThread(EventQueue eventQueue) {
                        return eventQueue.getDispatchThread();
                    }
                    public boolean isDispatchThreadImpl(EventQueue eventQueue) {
                        return eventQueue.isDispatchThreadImpl();
                    }
                    public void pumpEvents(EventQueue eventQueue, EventFilter filter) { eventQueue.getDispatchThread().pumpEvents(filter); }
                    public void removeSourceEvents(EventQueue eventQueue,
                                                   Object source,
                                                   boolean removeAllEvents)
                    {
                        eventQueue.removeSourceEvents(source, removeAllEvents);
                    }
                    public boolean noEvents(EventQueue eventQueue) {
                        return eventQueue.noEvents();
                    }
                    public void wakeup(EventQueue eventQueue, boolean isShutdown) {
                        eventQueue.wakeup(isShutdown);
                    }
                    public void invokeAndWait(Object source, Runnable r)
                            throws InterruptedException, InvocationTargetException
                    {
                        EventQueue.invokeAndWait(source, r);
                    }
                    public void setEventPump(EventQueue eventQueue, EventPump pump) {
                        eventQueue.setEventPump(pump);
                    }
                    public long getMostRecentEventTime(EventQueue eventQueue) {
                        return eventQueue.getMostRecentEventTimeImpl();
                    }
                });
    }

    @SuppressWarnings("removal")
    private static boolean fxAppThreadIsDispatchThread =
            AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
                public Boolean run() {
                    return "true".equals(System.getProperty("javafx.embed.singleThread"));
                }
            });

    /**
     * Initializes a new instance of {@code EventQueue}.
     */
    public EventQueue() {
        /*
         * NOTE: if you ever have to start the associated event dispatch
         * thread at this point, be aware of the following problem:
         * If this EventQueue instance is created in
         * SunToolkit.createNewAppContext() the started dispatch thread
         * may call AppContext.getAppContext() before createNewAppContext()
         * completes thus causing mess in thread group to appcontext mapping.
         */

        appContext = AppContext.getAppContext();
        pushPopLock = (Lock)appContext.get(AppContext.EVENT_QUEUE_LOCK_KEY);
        pushPopCond = (Condition)appContext.get(AppContext.EVENT_QUEUE_COND_KEY);
    }

    private EventQueue(AppContext ac) {
        appContext = ac;
        pushPopLock = (Lock)appContext.get(AppContext.EVENT_QUEUE_LOCK_KEY);
        pushPopCond = (Condition)appContext.get(AppContext.EVENT_QUEUE_COND_KEY);
    }

    /**
     * Defer all events posted in the future until activateDeferredEvents() is called.
     * Deferred events are not returned by getNextEvent() or peekEvent() or similar methods.
     * Deferred events are considered for noEvents() and removeSourceEvents().
     */
    public void deferFutureEvents() {
        pushPopLock.lock();
        try {
            if (deferredEvents == null) {
                if (getEventLog().isLoggable(PlatformLogger.Level.FINE)) {
                    getEventLog().fine("Deferring future events");
                }
                deferredEvents = new PrioritizedEventQueue(NUM_PRIORITIES);
            }
        } finally {
            pushPopLock.unlock();
        }
    }

    /**
     * If posted events have been deferred, enable them for processing. Events posted in the future will not be deferred
     * until deferFutureEvents() is called.
     * @return true if and only if some deferred events were enabled.
     */
    public boolean enableDeferredEvents() {
        boolean foundEvents = false;
        pushPopLock.lock();
        try {
            if (deferredEvents != null && !deferredEvents.isEmpty()) {
                if (getEventLog().isLoggable(PlatformLogger.Level.FINE)) {
                    getEventLog().fine("Enabling deferred events");
                }
                currentEvents.spliceAtEnd(deferredEvents);
                foundEvents = true;
            }
            deferredEvents = null;
        } finally {
            pushPopLock.unlock();
        }
        return foundEvents;
    }

    /**
     * Posts a 1.1-style event to the {@code EventQueue}.
     * If there is an existing event on the queue with the same ID
     * and event source, the source {@code Component}'s
     * {@code coalesceEvents} method will be called.
     *
     * @param theEvent an instance of {@code java.awt.AWTEvent},
     *          or a subclass of it
     * @throws NullPointerException if {@code theEvent} is {@code null}
     */
    public void postEvent(AWTEvent theEvent) {
        SunToolkit.flushPendingEvents(appContext);
        postEventPrivate(theEvent);
    }

    /**
     * Posts a 1.1-style event to the {@code EventQueue}.
     * If there is an existing event on the queue with the same ID
     * and event source, the source {@code Component}'s
     * {@code coalesceEvents} method will be called.
     *
     * @param theEvent an instance of {@code java.awt.AWTEvent},
     *          or a subclass of it
     */
    private void postEventPrivate(AWTEvent theEvent) {
        theEvent.isPosted = true;
        pushPopLock.lock();
        try {
            if (nextQueue != null) {
                // Forward the event to the top of EventQueue stack
                nextQueue.postEventPrivate(theEvent);
                return;
            }
            if (dispatchThread == null) {
                if (AWTAutoShutdown.isShutdownEvent(theEvent)) {
                    return;
                } else if (eventPump == null) {
                    initDispatchThread();
                }
            }
            postEvent(theEvent, getPriority(theEvent));
        } finally {
            pushPopLock.unlock();
        }
    }

    private static int getPriority(AWTEvent theEvent) {
        if (theEvent instanceof PeerEvent) {
            PeerEvent peerEvent = (PeerEvent)theEvent;
            if ((peerEvent.getFlags() & PeerEvent.ULTIMATE_PRIORITY_EVENT) != 0) {
                return ULTIMATE_PRIORITY;
            }
            if ((peerEvent.getFlags() & PeerEvent.PRIORITY_EVENT) != 0) {
                return HIGH_PRIORITY;
            }
            if ((peerEvent.getFlags() & PeerEvent.LOW_PRIORITY_EVENT) != 0) {
                return LOW_PRIORITY;
            }
        }
        int id = theEvent.getID();
        if ((id >= PaintEvent.PAINT_FIRST) && (id <= PaintEvent.PAINT_LAST)) {
            return LOW_PRIORITY;
        }
        return NORM_PRIORITY;
    }

    /**
     * Posts the event to the internal Queue of specified priority,
     * coalescing as appropriate.
     *
     * @param theEvent an instance of {@code java.awt.AWTEvent},
     *          or a subclass of it
     * @param priority  the desired priority of the event
     */
    private void postEvent(AWTEvent theEvent, int priority) {

        boolean isExternal = dispatchThread != null && dispatchThread.isUsingExternalEventPump();

        if (coalesceEvent(theEvent, priority)) {
            if (isExternal) {
                // notify the existence of a new event
                AWTEvent cachedEvent = getCachedEvent(theEvent);
                if (cachedEvent != null) {
                    dispatchThread.eventPosted(cachedEvent);
                }
            }
            return;
        }

        boolean isQueueEmpty = noEvents();

        // Add the event to the appropriate queue

        EventQueueItem newItem;
        pushPopLock.lock();
        try {
            if (deferredEvents != null) {
                newItem = deferredEvents.add(theEvent, priority);
            } else {
                newItem = currentEvents.add(theEvent, priority);
            }
        } finally {
            pushPopLock.unlock();
        }
        cacheEQItem(newItem);

        // Notify the existence of a new event.

        if (isExternal) {
            dispatchThread.eventPosted(theEvent);
        } else {
            if (isQueueEmpty) {
                AWTAutoShutdown.getInstance().notifyThreadBusy(dispatchThread);
            }
            if (isQueueEmpty || (theEvent.getID() == this.waitForID)) {
                pushPopCond.signalAll();
            }
        }
    }

    private boolean coalescePaintEvent(PaintEvent e) {
        ComponentPeer sourcePeer = ((Component)e.getSource()).peer;
        if (sourcePeer != null) {
            sourcePeer.coalescePaintEvent(e);
        }
        EventQueueItem[] cache = ((Component)e.getSource()).eventCache;
        if (cache == null) {
            return false;
        }
        int index = eventToCacheIndex(e);

        if (index != -1 && cache[index] != null) {
            PaintEvent merged = mergePaintEvents(e, (PaintEvent)cache[index].event);
            if (merged != null) {
                cache[index].event = merged;
                return true;
            }
        }
        return false;
    }

    private PaintEvent mergePaintEvents(PaintEvent a, PaintEvent b) {
        Rectangle aRect = a.getUpdateRect();
        Rectangle bRect = b.getUpdateRect();
        if (bRect.contains(aRect)) {
            return b;
        }
        if (aRect.contains(bRect)) {
            return a;
        }
        return null;
    }

    private boolean coalesceMouseEvent(MouseEvent e) {
        EventQueueItem[] cache = ((Component)e.getSource()).eventCache;
        if (cache == null) {
            return false;
        }
        int index = eventToCacheIndex(e);
        if (index != -1 && cache[index] != null) {
            cache[index].event = e;
            return true;
        }
        return false;
    }

    private boolean coalescePeerEvent(PeerEvent e) {
        EventQueueItem[] cache = ((Component)e.getSource()).eventCache;
        if (cache == null) {
            return false;
        }
        int index = eventToCacheIndex(e);
        if (index != -1 && cache[index] != null) {
            e = e.coalesceEvents((PeerEvent)cache[index].event);
            if (e != null) {
                cache[index].event = e;
                return true;
            } else {
                cache[index] = null;
            }
        }
        return false;
    }

    /*
     * Should avoid of calling this method by any means
     * as it's working time is dependent on EQ length.
     * In the worst case this method alone can slow down the entire application
     * 10 times by stalling the Event processing.
     * Only here by backward compatibility reasons.
     */
    private boolean coalesceOtherEvent(AWTEvent e, int priority) {
        pushPopLock.lock();
        try {
            boolean b = currentEvents.coalesceOtherEvent(e, priority);
            if (b) {
                return true;
            }
            if (deferredEvents != null) {
                return deferredEvents.coalesceOtherEvent(e, priority);
            }
            return false;
        } finally {
            pushPopLock.unlock();
        }
    }

    private boolean coalesceEvent(AWTEvent e, int priority) {
        if (!(e.getSource() instanceof Component)) {
            return false;
        }
        if (e instanceof PeerEvent) {
            return coalescePeerEvent((PeerEvent)e);
        }
        // The worst case
        if (((Component)e.getSource()).isCoalescingEnabled()
                && coalesceOtherEvent(e, priority))
        {
            return true;
        }
        if (e instanceof PaintEvent) {
            return coalescePaintEvent((PaintEvent)e);
        }
        if (e instanceof MouseEvent) {
            return coalesceMouseEvent((MouseEvent)e);
        }
        return false;
    }

    private AWTEvent getCachedEvent(AWTEvent original) {
        int index = eventToCacheIndex(original);
        if (index != -1 && original.getSource() instanceof Component) {
            Component source = (Component)original.getSource();
            return source.eventCache != null ? source.eventCache[index].event : null;
        }
        return null;
    }

    private void cacheEQItem(EventQueueItem entry) {
        int index = eventToCacheIndex(entry.event);
        if (index != -1 && entry.event.getSource() instanceof Component) {
            Component source = (Component)entry.event.getSource();
            if (source.eventCache == null) {
                source.eventCache = new EventQueueItem[CACHE_LENGTH];
            }
            source.eventCache[index] = entry;
        }
    }

    private void uncacheEQItem(EventQueueItem entry) {
        int index = eventToCacheIndex(entry.event);
        if (index != -1 && entry.event.getSource() instanceof Component) {
            Component source = (Component)entry.event.getSource();
            if (source.eventCache == null) {
                return;
            }
            source.eventCache[index] = null;
        }
    }

    private static final int PAINT = 0;
    private static final int UPDATE = 1;
    private static final int MOVE = 2;
    private static final int DRAG = 3;
    private static final int PEER = 4;
    private static final int CACHE_LENGTH = 5;

    private static int eventToCacheIndex(AWTEvent e) {
        switch(e.getID()) {
        case PaintEvent.PAINT:
            return PAINT;
        case PaintEvent.UPDATE:
            return UPDATE;
        case MouseEvent.MOUSE_MOVED:
            return MOVE;
        case MouseEvent.MOUSE_DRAGGED:
            // Return -1 for SunDropTargetEvent since they are usually synchronous
            // and we don't want to skip them by coalescing with MouseEvent or other drag events
            return e instanceof SunDropTargetEvent ? -1 : DRAG;
        default:
            return e instanceof PeerEvent ? PEER : -1;
        }
    }

    /**
     * Returns whether an event is pending on any of the separate
     * Queues.
     * @return whether an event is pending on any of the separate Queues
     */
    private boolean noEvents() {
        pushPopLock.lock();
        try {
            return currentEvents.isEmpty() && (deferredEvents == null || deferredEvents.isEmpty());
        } finally {
            pushPopLock.unlock();
        }
    }

    /**
     * Removes an event from the {@code EventQueue} and
     * returns it.  This method will block until an event has
     * been posted by another thread.
     * @return the next {@code AWTEvent}
     * @throws InterruptedException
     *            if any thread has interrupted this thread
     */
    public AWTEvent getNextEvent() throws InterruptedException {
        do {
            /*
             * SunToolkit.flushPendingEvents must be called outside
             * of the synchronized block to avoid deadlock when
             * event queues are nested with push()/pop().
             */
            SunToolkit.flushPendingEvents(appContext);
            pushPopLock.lock();
            try {
                AWTEvent event = getNextEventPrivate();
                if (event != null) {
                    return event;
                }
                if (dispatchThread == null || dispatchThread.isUsingExternalEventPump()) {
                    return null;
                } else {
                    AWTAutoShutdown.getInstance().notifyThreadFree(dispatchThread);
                }
                pushPopCond.await();
            } finally {
                pushPopLock.unlock();
            }
        } while(true);
    }

    /*
     * Must be called under the lock. Doesn't call flushPendingEvents()
     */
    AWTEvent getNextEventPrivate() throws InterruptedException {
        EventQueueItem item = currentEvents.getNextItem();
        if (item != null) {
            uncacheEQItem(item);
            return item.event;
        }
        return null;
    }

    AWTEvent getNextEvent(int id) throws InterruptedException {
        do {
            /*
             * SunToolkit.flushPendingEvents must be called outside
             * of the synchronized block to avoid deadlock when
             * event queues are nested with push()/pop().
             */
            SunToolkit.flushPendingEvents(appContext);
            pushPopLock.lock();
            try {
                EventQueueItem entry = currentEvents.getNextItemWithId(id);
                if (entry != null) {
                    uncacheEQItem(entry);
                    return entry.event;
                }
                if (dispatchThread == null || dispatchThread.isUsingExternalEventPump()) {
                    return null;
                }
                waitForID = id;
                pushPopCond.await();
                waitForID = 0;
            } finally {
                pushPopLock.unlock();
            }
        } while(true);
    }

    /**
     * Returns the first event on the {@code EventQueue}
     * without removing it.
     * @return the first event
     */
    public AWTEvent peekEvent() {
        pushPopLock.lock();
        try {
            EventQueueItem entry = currentEvents.peek();
            if (entry != null) {
                return entry.event;
            }
        } finally {
            pushPopLock.unlock();
        }

        return null;
    }

    /**
     * Returns the first event with the specified id, if any.
     * @param id the id of the type of event desired
     * @return the first event of the specified id or {@code null}
     *    if there is no such event
     */
    public AWTEvent peekEvent(int id) {
        pushPopLock.lock();
        try {
            EventQueueItem entry = currentEvents.peekEvent(id);
            return entry != null ? entry.event : null;
        } finally {
            pushPopLock.unlock();
        }
    }

    private static final JavaSecurityAccess javaSecurityAccess =
        SharedSecrets.getJavaSecurityAccess();

    /**
     * Dispatches an event. The manner in which the event is
     * dispatched depends upon the type of the event and the
     * type of the event's source object:
     *
     * <table class="striped">
     * <caption>Event types, source types, and dispatch methods</caption>
     * <thead>
     *   <tr>
     *     <th scope="col">Event Type
     *     <th scope="col">Source Type
     *     <th scope="col">Dispatched To
     * </thead>
     * <tbody>
     *   <tr>
     *     <th scope="row">ActiveEvent
     *     <td>Any
     *     <td>event.dispatch()
     *   <tr>
     *     <th scope="row">Other
     *     <td>Component
     *     <td>source.dispatchEvent(AWTEvent)
     *   <tr>
     *     <th scope="row">Other
     *     <td>MenuComponent
     *     <td>source.dispatchEvent(AWTEvent)
     *   <tr>
     *     <th scope="row">Other
     *     <td>Other
     *     <td>No action (ignored)
     * </tbody>
     * </table>
     *
     * @param event an instance of {@code java.awt.AWTEvent},
     *          or a subclass of it
     * @throws NullPointerException if {@code event} is {@code null}
     * @since           1.2
     */
    protected void dispatchEvent(final AWTEvent event) {
        final Object src = event.getSource();
        final PrivilegedAction<Void> action = new PrivilegedAction<Void>() {
            public Void run() {
                // In case fwDispatcher is installed and we're already on the
                // dispatch thread (e.g. performing DefaultKeyboardFocusManager.sendMessage),
                // dispatch the event straight away.
                if (eventPump == null || isDispatchThreadImpl()) {
                    dispatchEventImpl(event, src);
                } else {
                    eventPump.scheduleDispatch(new Runnable() {
                        @Override
                        public void run() {
                            if (dispatchThread.filterAndCheckEvent(event)) {
                                dispatchEventImpl(event, src);
                            }
                        }
                    });
                }
                return null;
            }
        };

        @SuppressWarnings("removal")
        final AccessControlContext stack = AccessController.getContext();
        @SuppressWarnings("removal")
        final AccessControlContext srcAcc = getAccessControlContextFrom(src);
        @SuppressWarnings("removal")
        final AccessControlContext eventAcc = event.getAccessControlContext();
        if (srcAcc == null) {
            javaSecurityAccess.doIntersectionPrivilege(action, stack, eventAcc);
        } else {
            javaSecurityAccess.doIntersectionPrivilege(
                new PrivilegedAction<Void>() {
                    public Void run() {
                        javaSecurityAccess.doIntersectionPrivilege(action, eventAcc);
                        return null;
                    }
                }, stack, srcAcc);
        }
    }

    @SuppressWarnings("removal")
    private static AccessControlContext getAccessControlContextFrom(Object src) {
        return src instanceof Component ?
            ((Component)src).getAccessControlContext() :
            src instanceof MenuComponent ?
                ((MenuComponent)src).getAccessControlContext() :
                src instanceof TrayIcon ?
                    ((TrayIcon)src).getAccessControlContext() :
                    null;
    }

    /**
     * Called from dispatchEvent() under a correct AccessControlContext
     */
    private void dispatchEventImpl(final AWTEvent event, final Object src) {

        if (getEventLog().isLoggable(PlatformLogger.Level.FINEST)) {
            getEventLog().finest("Dispatching event: " + event);
        }

        event.isPosted = true;
        if (event instanceof ActiveEvent) {
            // This could become the sole method of dispatching in time.
            setCurrentEventAndMostRecentTimeImpl(event);
            ((ActiveEvent)event).dispatch();
        } else if (src instanceof Component) {
            ((Component)src).dispatchEvent(event);
            event.dispatched();
        } else if (src instanceof MenuComponent) {
            ((MenuComponent)src).dispatchEvent(event);
        } else if (src instanceof TrayIcon) {
            ((TrayIcon)src).dispatchEvent(event);
        } else if (AWTAutoShutdown.isShutdownEvent(event)) {
            if (noEvents() && dispatchThread != null) {
                dispatchThread.stopDispatching();
            }
        } else {
            if (getEventLog().isLoggable(PlatformLogger.Level.FINE)) {
                getEventLog().fine("Unable to dispatch event: " + event);
            }
        }
    }

    /**
     * Returns the timestamp of the most recent event that had a timestamp, and
     * that was dispatched from the {@code EventQueue} associated with the
     * calling thread. If an event with a timestamp is currently being
     * dispatched, its timestamp will be returned. If no events have yet
     * been dispatched, the EventQueue's initialization time will be
     * returned instead.In the current version of
     * the JDK, only {@code InputEvent}s,
     * {@code ActionEvent}s, and {@code InvocationEvent}s have
     * timestamps; however, future versions of the JDK may add timestamps to
     * additional event types. Note that this method should only be invoked
     * from an application's {@link #isDispatchThread event dispatching thread}.
     * If this method is
     * invoked from another thread, the current system time (as reported by
     * {@code System.currentTimeMillis()}) will be returned instead.
     *
     * @return the timestamp of the last {@code InputEvent},
     *         {@code ActionEvent}, or {@code InvocationEvent} to be
     *         dispatched, or {@code System.currentTimeMillis()} if this
     *         method is invoked on a thread other than an event dispatching
     *         thread
     * @see java.awt.event.InputEvent#getWhen
     * @see java.awt.event.ActionEvent#getWhen
     * @see java.awt.event.InvocationEvent#getWhen
     * @see #isDispatchThread
     *
     * @since 1.4
     */
    public static long getMostRecentEventTime() {
        return Toolkit.getEventQueue().getMostRecentEventTimeImpl();
    }
    private long getMostRecentEventTimeImpl() {
        pushPopLock.lock();
        try {
            return (isDispatchThreadImpl())
                    ? mostRecentEventTime
                    : System.currentTimeMillis();
        } finally {
            pushPopLock.unlock();
        }
    }

    /**
     * @return most recent event time on all threads.
     */
    long getMostRecentEventTimeEx() {
        pushPopLock.lock();
        try {
            return mostRecentEventTime;
        } finally {
            pushPopLock.unlock();
        }
    }

    /**
     * Returns the event currently being dispatched by the
     * {@code EventQueue} associated with the calling thread. This is
     * useful if a method needs access to the event, but was not designed to
     * receive a reference to it as an argument. Note that this method should
     * only be invoked from an application's event dispatching thread. If this
     * method is invoked from another thread, null will be returned.
     *
     * @return the event currently being dispatched, or null if this method is
     *         invoked on a thread other than an event dispatching thread
     * @since 1.4
     */
    public static AWTEvent getCurrentEvent() {
        return Toolkit.getEventQueue().getCurrentEventImpl();
    }
    private AWTEvent getCurrentEventImpl() {
        pushPopLock.lock();
        try {
            if (dispatchThread != null && dispatchThread.isDispatchThread() || fxAppThreadIsDispatchThread) {
                return (currentEvent != null)
                        ? currentEvent.get()
                        : null;
            }
            return null;
        } finally {
            pushPopLock.unlock();
        }
    }

    /**
     * Replaces the existing {@code EventQueue} with the specified one.
     * Any pending events are transferred to the new {@code EventQueue}
     * for processing by it.
     *
     * @param newEventQueue an {@code EventQueue}
     *          (or subclass thereof) instance to be use
     * @see      java.awt.EventQueue#pop
     * @throws NullPointerException if {@code newEventQueue} is {@code null}
     * @since           1.2
     */
    public void push(EventQueue newEventQueue) {

        if (eventPump != null) {
            throw new UnsupportedOperationException("This toolkit does not support event queue push and pop");
        }

        if (getEventLog().isLoggable(PlatformLogger.Level.FINE)) {
            getEventLog().fine("EventQueue.push(" + newEventQueue + ")");
        }

        pushPopLock.lock();
        try {
            EventQueue topQueue = this;
            while (topQueue.nextQueue != null) {
                topQueue = topQueue.nextQueue;
            }
            if (topQueue.eventPump != null) {
                throw new RuntimeException("push() to queue with event pump");
            }
            if ((topQueue.dispatchThread != null) &&
                (topQueue.dispatchThread.getEventQueue() == this))
            {
                newEventQueue.dispatchThread = topQueue.dispatchThread;
                topQueue.dispatchThread.setEventQueue(newEventQueue);
            }

            // Transfer all events forward to new EventQueue.
            while (topQueue.peekEvent() != null) {
                try {
                    // Use getNextEventPrivate() as it doesn't call flushPendingEvents()
                    newEventQueue.postEventPrivate(topQueue.getNextEventPrivate());
                } catch (InterruptedException ie) {
                    if (getEventLog().isLoggable(PlatformLogger.Level.FINE)) {
                        getEventLog().fine("Interrupted push", ie);
                    }
                }
            }

            if (topQueue.dispatchThread != null) {
                // Wake up EDT waiting in getNextEvent(), so it can
                // pick up a new EventQueue. Post the waking event before
                // topQueue.nextQueue is assigned, otherwise the event would
                // go newEventQueue
                topQueue.postEventPrivate(new InvocationEvent(topQueue, dummyRunnable));
            }

            newEventQueue.previousQueue = topQueue;
            topQueue.nextQueue = newEventQueue;

            if (appContext.get(AppContext.EVENT_QUEUE_KEY) == topQueue) {
                appContext.put(AppContext.EVENT_QUEUE_KEY, newEventQueue);
            }

            signalAll();
        } finally {
            pushPopLock.unlock();
        }
    }

    /**
     * Stops dispatching events using this {@code EventQueue}.
     * Any pending events are transferred to the previous
     * {@code EventQueue} for processing.
     * <p>
     * Warning: To avoid deadlock, do not declare this method
     * synchronized in a subclass.
     *
     * @throws EmptyStackException if no previous push was made
     *  on this {@code EventQueue}
     * @see      java.awt.EventQueue#push
     * @since           1.2
     */
    protected void pop() throws EmptyStackException {

        if (eventPump != null) {
            throw new UnsupportedOperationException("This toolkit does not support event queue push and pop");
        }

        if (getEventLog().isLoggable(PlatformLogger.Level.FINE)) {
            getEventLog().fine("EventQueue.pop(" + this + ")");
        }

        pushPopLock.lock();
        try {
            EventQueue topQueue = this;
            while (topQueue.nextQueue != null) {
                topQueue = topQueue.nextQueue;
            }
            EventQueue prevQueue = topQueue.previousQueue;
            if (prevQueue == null) {
                throw new EmptyStackException();
            }

            topQueue.previousQueue = null;
            prevQueue.nextQueue = null;

            // Transfer all events back to previous EventQueue.
            while (topQueue.peekEvent() != null) {
                try {
                    prevQueue.postEventPrivate(topQueue.getNextEventPrivate());
                } catch (InterruptedException ie) {
                    if (getEventLog().isLoggable(PlatformLogger.Level.FINE)) {
                        getEventLog().fine("Interrupted pop", ie);
                    }
                }
            }

            if ((topQueue.dispatchThread != null) &&
                (topQueue.dispatchThread.getEventQueue() == this))
            {
                prevQueue.dispatchThread = topQueue.dispatchThread;
                topQueue.dispatchThread.setEventQueue(prevQueue);
            }

            if (appContext.get(AppContext.EVENT_QUEUE_KEY) == this) {
                appContext.put(AppContext.EVENT_QUEUE_KEY, prevQueue);
            }

            // Wake up EDT waiting in getNextEvent(), so it can
            // pick up a new EventQueue
            topQueue.postEventPrivate(new InvocationEvent(topQueue, dummyRunnable));

            signalAll();
        } finally {
            pushPopLock.unlock();
        }
    }

    /**
     * Creates a new {@code secondary loop} associated with this
     * event queue. Use the {@link SecondaryLoop#enter} and
     * {@link SecondaryLoop#exit} methods to start and stop the
     * event loop and dispatch the events from this queue.
     *
     * @return secondaryLoop A new secondary loop object, which can
     *                       be used to launch a new nested event
     *                       loop and dispatch events from this queue
     *
     * @see SecondaryLoop#enter
     * @see SecondaryLoop#exit
     *
     * @since 1.7
     */
    public SecondaryLoop createSecondaryLoop() {
        return createSecondaryLoop(null, null, 0);
    }

    /**
     * Create a {@code secondary loop} associated with this event queue for a modal lightweight container.
     * This method is for use only by external event pumps.
     * @param c The lightweight model container.
     * @return the secondary loop.
     */

    SecondaryLoop createLightweightSecondaryLoop(Container c) {
        if (eventPump == null) {
            throw new Error("Lightweight secondary loops are supported only for external event pumps");
        }
        EventFilter filter = EventDispatchThread.createLightweightModalEventFilter(c);
        return eventPump.createSecondaryLoop(filter);
    }

    private class FwSecondaryLoopWrapper implements SecondaryLoop {
        private final SecondaryLoop loop;
        private final EventFilter filter;

        public FwSecondaryLoopWrapper(SecondaryLoop loop, EventFilter filter) {
            this.loop = loop;
            this.filter = filter;
        }

        @Override
        public boolean enter() {
            if (filter != null) {
                dispatchThread.addEventFilter(filter);
            }
            boolean wasEntered = loop.enter();
            if (filter != null && !wasEntered) {
                dispatchThread.removeEventFilter(filter);
            }
            return wasEntered;
        }

        @Override
        public boolean exit() {
            if (filter != null) {
                dispatchThread.removeEventFilter(filter);
            }
            return loop.exit();
        }
    }

    SecondaryLoop createSecondaryLoop(Conditional cond, EventFilter filter, long interval) {
        pushPopLock.lock();
        try {
            if (nextQueue != null) {
                // Forward the request to the top of EventQueue stack
                return nextQueue.createSecondaryLoop(cond, filter, interval);
            }
            if (eventPump != null) {
                return new FwSecondaryLoopWrapper(eventPump.createSecondaryLoop(filter), filter);
            }
            if (dispatchThread == null) {
                initDispatchThread();
            }
            return new WaitDispatchSupport(dispatchThread, cond, filter, interval);
        } finally {
            pushPopLock.unlock();
        }
    }

    /**
     * Returns true if the calling thread is
     * {@link Toolkit#getSystemEventQueue the current AWT EventQueue}'s
     * dispatch thread. Use this method to ensure that a particular
     * task is being executed (or not being) there.
     * <p>
     * Note: use the {@link #invokeLater} or {@link #invokeAndWait}
     * methods to execute a task in
     * {@link Toolkit#getSystemEventQueue the current AWT EventQueue}'s
     * dispatch thread.
     *
     * @return true if running in
     * {@link Toolkit#getSystemEventQueue the current AWT EventQueue}'s
     * dispatch thread
     * @see             #invokeLater
     * @see             #invokeAndWait
     * @see             Toolkit#getSystemEventQueue
     * @since           1.2
     */
    public static boolean isDispatchThread() {
        EventQueue eq = Toolkit.getEventQueue();
        return eq.isDispatchThreadImpl();
    }

    final boolean isDispatchThreadImpl() {
        EventQueue eq = this;
        pushPopLock.lock();
        try {
            EventQueue next = eq.nextQueue;
            while (next != null) {
                eq = next;
                next = eq.nextQueue;
            }
            if (eq.eventPump != null) {
                return eq.eventPump.isDispatchThread();
            }
            return eq.dispatchThread != null && eq.dispatchThread.isDispatchThread();
        } finally {
            pushPopLock.unlock();
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    final void initDispatchThread() {
        pushPopLock.lock();
        try {
            if (dispatchThread == null && !threadGroup.isDestroyed() && !appContext.isDisposed()) {
                dispatchThread = AccessController.doPrivileged(
                        new PrivilegedAction<EventDispatchThread>() {
                            public EventDispatchThread run() {
                                EventDispatchThread t =
                                        new EventDispatchThread(threadGroup,
                                                name,
                                                EventQueue.this);
                                t.setContextClassLoader(classLoader);
                                t.setPriority(Thread.NORM_PRIORITY + 1);
                                t.setDaemon(false);
                                EventPump eventPump = AWTAccessor.getToolkitAccessor().getEventPump();
                                if (eventPump != null) {
                                    t.configureExternalEventPump(eventPump);
                                    EventQueue.this.eventPump = eventPump;
                                } else {
                                    AWTAutoShutdown.getInstance().notifyThreadBusy(t);
                                }
                                return t;
                            }
                        }
                );
                if (eventPump == null) {
                    dispatchThread.start();
                }
            }
        } finally {
            pushPopLock.unlock();
        }
    }

    final void detachDispatchThread(EventDispatchThread edt) {
        if (edt.isUsingExternalEventPump()) {
            return;
        }

        /*
         * Minimize discard possibility for non-posted events
         */
        SunToolkit.flushPendingEvents(appContext);
        /*
         * This synchronized block is to secure that the event dispatch
         * thread won't die in the middle of posting a new event to the
         * associated event queue. It is important because we notify
         * that the event dispatch thread is busy after posting a new event
         * to its queue, so the EventQueue.dispatchThread reference must
         * be valid at that point.
         */
        pushPopLock.lock();
        try {
            if (edt == dispatchThread) {
                dispatchThread = null;
            }
            AWTAutoShutdown.getInstance().notifyThreadFree(edt);
            /*
             * Event was posted after EDT events pumping had stopped, so start
             * another EDT to handle this event
             */
            if (peekEvent() != null) {
                initDispatchThread();
            }
        } finally {
            pushPopLock.unlock();
        }
    }

    /*
     * Gets the {@code EventDispatchThread} for this
     * {@code EventQueue}.
     * @return the event dispatch thread associated with this event queue
     *         or {@code null} if this event queue doesn't have a
     *         working thread associated with it
     * @see    java.awt.EventQueue#initDispatchThread
     * @see    java.awt.EventQueue#detachDispatchThread
     */
    final EventDispatchThread getDispatchThread() {
        pushPopLock.lock();
        try {
            return dispatchThread;
        } finally {
            pushPopLock.unlock();
        }
    }

    /*
     * Removes any pending events for the specified source object.
     * If removeAllEvents parameter is {@code true} then all
     * events for the specified source object are removed, if it
     * is {@code false} then {@code SequencedEvent}, {@code SentEvent},
     * {@code FocusEvent}, {@code WindowEvent}, {@code KeyEvent},
     * and {@code InputMethodEvent} are kept in the queue, but all other
     * events are removed.
     *
     * This method is normally called by the source's
     * {@code removeNotify} method.
     */
    final void removeSourceEvents(Object source, boolean removeAllEvents) {
        SunToolkit.flushPendingEvents(appContext);
        pushPopLock.lock();
        try {
            Predicate<AWTEvent> p = event -> event.getSource() == source
                    && (removeAllEvents
                        || ! (event instanceof SequencedEvent
                           || event instanceof SentEvent
                           || event instanceof FocusEvent
                           || event instanceof WindowEvent
                           || event instanceof KeyEvent
                           || event instanceof InputMethodEvent));
            currentEvents.removeEvents(p, this::processRemovedSourceEvent);
            if (deferredEvents != null) {
                deferredEvents.removeEvents(p, this::processRemovedSourceEvent);
            }
        } finally {
            pushPopLock.unlock();
        }
    }

    private void processRemovedSourceEvent(EventQueueItem entry) {
        if (entry.event instanceof SequencedEvent) {
             ((SequencedEvent)entry.event).dispose();
         }
         if (entry.event instanceof SentEvent) {
             ((SentEvent)entry.event).dispose();
         }
         if (entry.event instanceof InvocationEvent) {
             AWTAccessor.getInvocationEventAccessor()
                     .dispose((InvocationEvent)entry.event);
         }
         if (entry.event instanceof SunDropTargetEvent) {
             ((SunDropTargetEvent)entry.event).dispose();
         }
         uncacheEQItem(entry);
    }

    synchronized long getMostRecentKeyEventTime() {
        pushPopLock.lock();
        try {
            return mostRecentKeyEventTime;
        } finally {
            pushPopLock.unlock();
        }
    }

    static void setCurrentEventAndMostRecentTime(AWTEvent e) {
        Toolkit.getEventQueue().setCurrentEventAndMostRecentTimeImpl(e);
    }
    private void setCurrentEventAndMostRecentTimeImpl(AWTEvent e) {
        pushPopLock.lock();
        try {
            if (!fxAppThreadIsDispatchThread && Thread.currentThread() != dispatchThread) {
                return;
            }

            currentEvent = new WeakReference<>(e);

            // This series of 'instanceof' checks should be replaced with a
            // polymorphic type (for example, an interface which declares a
            // getWhen() method). However, this would require us to make such
            // a type public, or to place it in sun.awt. Both of these approaches
            // have been frowned upon. So for now, we hack.
            //
            // In tiger, we will probably give timestamps to all events, so this
            // will no longer be an issue.
            long mostRecentEventTime2 = Long.MIN_VALUE;
            if (e instanceof InputEvent) {
                InputEvent ie = (InputEvent)e;
                mostRecentEventTime2 = ie.getWhen();
                if (e instanceof KeyEvent) {
                    mostRecentKeyEventTime = ie.getWhen();
                }
            } else if (e instanceof InputMethodEvent) {
                InputMethodEvent ime = (InputMethodEvent)e;
                mostRecentEventTime2 = ime.getWhen();
            } else if (e instanceof ActionEvent) {
                ActionEvent ae = (ActionEvent)e;
                mostRecentEventTime2 = ae.getWhen();
            } else if (e instanceof InvocationEvent) {
                InvocationEvent ie = (InvocationEvent)e;
                mostRecentEventTime2 = ie.getWhen();
            }
            mostRecentEventTime = Math.max(mostRecentEventTime, mostRecentEventTime2);
        } finally {
            pushPopLock.unlock();
        }
    }

    /**
     * Causes {@code runnable} to have its {@code run}
     * method called in the {@link #isDispatchThread dispatch thread} of
     * {@link Toolkit#getSystemEventQueue the system EventQueue}.
     * This will happen after all pending events are processed.
     *
     * @param runnable  the {@code Runnable} whose {@code run}
     *                  method should be executed
     *                  asynchronously in the
     *                  {@link #isDispatchThread event dispatch thread}
     *                  of {@link Toolkit#getSystemEventQueue the system EventQueue}
     * @see             #invokeAndWait
     * @see             Toolkit#getSystemEventQueue
     * @see             #isDispatchThread
     * @since           1.2
     */
    public static void invokeLater(Runnable runnable) {
        Toolkit.getEventQueue().postEvent(
            new InvocationEvent(Toolkit.getDefaultToolkit(), runnable));
    }

    /**
     * Causes {@code runnable} to have its {@code run}
     * method called in the {@link #isDispatchThread dispatch thread} of
     * {@link Toolkit#getSystemEventQueue the system EventQueue}.
     * This will happen after all pending events are processed.
     * The call blocks until this has happened.  This method
     * will throw an Error if called from the
     * {@link #isDispatchThread event dispatcher thread}.
     *
     * @param runnable  the {@code Runnable} whose {@code run}
     *                  method should be executed
     *                  synchronously in the
     *                  {@link #isDispatchThread event dispatch thread}
     *                  of {@link Toolkit#getSystemEventQueue the system EventQueue}
     * @throws       InterruptedException  if any thread has
     *                  interrupted this thread
     * @throws       InvocationTargetException  if an throwable is thrown
     *                  when running {@code runnable}
     * @see             #invokeLater
     * @see             Toolkit#getSystemEventQueue
     * @see             #isDispatchThread
     * @since           1.2
     */
    public static void invokeAndWait(Runnable runnable)
        throws InterruptedException, InvocationTargetException
    {
        invokeAndWait(Toolkit.getDefaultToolkit(), runnable);
    }

    static void invokeAndWait(Object source, Runnable runnable)
        throws InterruptedException, InvocationTargetException
    {
        if (EventQueue.isDispatchThread()) {
            throw new Error("Cannot call invokeAndWait from the event dispatcher thread");
        }

        class AWTInvocationLock {}
        Object lock = new AWTInvocationLock();

        InvocationEvent event =
            new InvocationEvent(source, runnable, lock, true);

        synchronized (lock) {
            Toolkit.getEventQueue().postEvent(event);
            while (!event.isDispatched()) {
                lock.wait();
            }
        }

        Throwable eventThrowable = event.getThrowable();
        if (eventThrowable != null) {
            throw new InvocationTargetException(eventThrowable);
        }
    }

    /*
     * Called from PostEventQueue.postEvent to notify that a new event
     * appeared. First it proceeds to the EventQueue on the top of the
     * stack, then notifies the associated dispatch thread if it exists
     * or starts a new one otherwise.
     */
    private void wakeup(boolean isShutdown) {
        pushPopLock.lock();
        try {
            if (nextQueue != null) {
                // Forward call to the top of EventQueue stack.
                nextQueue.wakeup(isShutdown);
            } else if (dispatchThread != null) {
                signalAll();
            } else if (!isShutdown && eventPump == null) {
                initDispatchThread();
            }
        } finally {
            pushPopLock.unlock();
        }
    }

    private void setEventPump(EventPump dispatcher) {
        if (nextQueue != null) {
            nextQueue.setEventPump(dispatcher);
        } else {
            eventPump = dispatcher;
        }
    }

    private void signalAll() {
        if (dispatchThread != null && dispatchThread.isUsingExternalEventPump()) {
            dispatchThread.eventPosted(null);
        } else {
            pushPopCond.signalAll();
        }
    }
}
