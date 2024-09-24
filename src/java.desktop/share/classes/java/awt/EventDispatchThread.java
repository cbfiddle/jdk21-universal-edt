/*
 * Copyright (c) 1996, 2022, Oracle and/or its affiliates. All rights reserved.
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

import java.awt.event.MouseEvent;
import java.awt.event.ActionEvent;
import java.awt.event.WindowEvent;

import java.util.ArrayList;

import sun.awt.AWTAutoShutdown;
import sun.awt.dnd.SunDragSourceContextPeer;
import sun.util.logging.PlatformLogger;

/**
 * EventDispatchThread is a package-private AWT class which takes
 * events off the EventQueue and dispatches them to the appropriate
 * AWT components.
 *
 * The Thread starts a "permanent" event pump with a call to
 * pumpEvents(Conditional) in its run() method. Event handlers can choose to
 * block this event pump at any time, but should start a new pump (<b>not</b>
 * a new EventDispatchThread) by again calling pumpEvents(Conditional). This
 * secondary event pump will exit automatically as soon as the Conditional
 * evaluate()s to false and an additional Event is pumped and dispatched.
 *
 * @author Tom Ball
 * @author Amy Fowler
 * @author Fred Ecks
 * @author David Mendenhall
 *
 * @since 1.1
 */
class EventDispatchThread extends Thread {

    private static final PlatformLogger eventLog = PlatformLogger.getLogger("java.awt.event.EventDispatchThread");

    private EventQueue theQueue;
    private volatile boolean doDispatch = true;
    private EventPump externalEventPump;

    private static final int ANY_EVENT = -1;

    private ArrayList<EventFilter> eventFilters = new ArrayList<EventFilter>();

   /**
    * Must always call 5 args super-class constructor passing false
    * to indicate not to inherit locals.
    */
    private EventDispatchThread() {
        throw new UnsupportedOperationException("Must erase locals");
    }

    EventDispatchThread(ThreadGroup group, String name, EventQueue queue) {
        super(group, null, name, 0, false);
        setEventQueue(queue);
    }

    void configureExternalEventPump(EventPump pump) {
        externalEventPump = pump;
    }

    boolean isDispatchThread() {
        return externalEventPump != null ? externalEventPump.isDispatchThread() : Thread.currentThread() == this;
    }

    boolean isUsingExternalEventPump() {
        return externalEventPump != null;
    }

    /**
     * Called from EventQueue when an event is posted or wakeup has been called (indicating that an event has been
     * posted to the PostEventQueue.
     * @param e The posted event, or null if called from wakeup.
     */
    void eventPosted(AWTEvent e) {
        if (externalEventPump != null && (e == null || !AWTAutoShutdown.isShutdownEvent(e))) {
            externalEventPump.eventsPosted();
        }
    }

    /*
     * Must be called on EDT only, that's why no synchronization
     */
    public void stopDispatching() {
        doDispatch = false;
    }

    public void run() {
        if (externalEventPump == null) {
            try {
                pumpEvents();
            } finally {
                getEventQueue().detachDispatchThread(this);
            }
        }
    }

    private final static Conditional ALWAYS_TRUE = () -> true;

    private void pumpEvents() {
        pumpEvents(ALWAYS_TRUE);
    }

    void pumpEvents(Conditional cond) {
        pumpEvents(ANY_EVENT, cond);
    }

    void pumpEvents(EventFilter filter) {
        pumpEventsForFilter(ALWAYS_TRUE, filter);
    }

    void pumpEventsForHierarchy(Conditional cond, Component modalComponent) {
        pumpEventsForHierarchy(ANY_EVENT, cond, modalComponent);
    }

    void pumpEvents(int id, Conditional cond) {
        pumpEventsForHierarchy(id, cond, null);
    }

    private void pumpEventsForHierarchy(int id, Conditional cond, Component modalComponent) {
        pumpEventsForFilter(id, cond, modalComponent != null ? new HierarchyEventFilter(modalComponent) : null);
    }

    void pumpEventsForFilter(Conditional cond, EventFilter filter) {
        pumpEventsForFilter(ANY_EVENT, cond, filter);
    }

    private void pumpEventsForFilter(int id, Conditional cond, EventFilter filter) {
        if (filter != null) {
            addEventFilter(filter);
        }
        doDispatch = true;
        while (doDispatch && !isInterrupted() && cond.evaluate()) {
            pumpOneEventForFilters(id);
        }
        if (filter != null) {
            removeEventFilter(filter);
        }
    }

    void addEventFilter(EventFilter filter) {
        if (eventLog.isLoggable(PlatformLogger.Level.FINEST)) {
            eventLog.finest("adding the event filter: " + filter);
        }
        synchronized (eventFilters) {
            if (!eventFilters.contains(filter)) {
                if (filter instanceof ModalEventFilter) {
                    ModalEventFilter newFilter = (ModalEventFilter)filter;
                    int k = 0;
                    for (k = 0; k < eventFilters.size(); k++) {
                        EventFilter f = eventFilters.get(k);
                        if (f instanceof ModalEventFilter) {
                            ModalEventFilter cf = (ModalEventFilter)f;
                            if (cf.compareTo(newFilter) > 0) {
                                break;
                            }
                        }
                    }
                    eventFilters.add(k, filter);
                } else {
                    eventFilters.add(filter);
                }
            }
        }
    }

    void removeEventFilter(EventFilter filter) {
        if (eventLog.isLoggable(PlatformLogger.Level.FINEST)) {
            eventLog.finest("removing the event filter: " + filter);
        }
        synchronized (eventFilters) {
            eventFilters.remove(filter);
        }
    }

    boolean filterAndCheckEvent(AWTEvent event) {
        boolean eventOK = true;
        synchronized (eventFilters) {
            for (int i = eventFilters.size() - 1; i >= 0; i--) {
                EventFilter f = eventFilters.get(i);
                EventFilter.FilterAction result = f.acceptEvent(event);

                if (result != EventFilter.FilterAction.ACCEPT && eventLog.isLoggable(PlatformLogger.Level.FINEST)) {
                    if (result == EventFilter.FilterAction.REJECT) {
                        eventLog.finest("Event rejected: " + event);
                    } else if (result == EventFilter.FilterAction.ACCEPT_IMMEDIATELY) {
                        eventLog.finest("Event accepted immediately: " + event);
                    }
                }

                if (result == EventFilter.FilterAction.REJECT) {
                    eventOK = false;
                    break;
                } else if (result == EventFilter.FilterAction.ACCEPT_IMMEDIATELY) {
                    break;
                }
            }
        }
        return eventOK && SunDragSourceContextPeer.checkEvent(event);
    }

    void pumpOneEventForFilters(int id) {
        AWTEvent event = null;
        boolean eventOK = false;
        try {
            EventQueue eq = null;
            do {
                // EventQueue may change during the dispatching
                eq = getEventQueue();
                event = (id == ANY_EVENT) ? eq.getNextEvent() : eq.getNextEvent(id);
                if (event == null) {
                    doDispatch = false;
                    return;
                }

                eventOK = filterAndCheckEvent(event);
                if (!eventOK) {
                    event.consume();
                }
            }
            while (eventOK == false);

            if (eventLog.isLoggable(PlatformLogger.Level.FINEST)) {
                eventLog.finest("Dispatching: " + event);
            }

            eq.dispatchEvent(event);
        }
        catch (InterruptedException interruptedException) {
            doDispatch = false; // AppContext.dispose() interrupts all
                                // Threads in the AppContext
        }
        catch (Throwable e) {
            processException(e);
        }
    }

    private void processException(Throwable e) {
        if (eventLog.isLoggable(PlatformLogger.Level.FINE)) {
            eventLog.fine("Processing exception: " + e);
        }
        UncaughtExceptionHandler h = getUncaughtExceptionHandler();
        if (h != null) {
            h.uncaughtException(this, e);
        } else {
            // When used with an external event pump, probably will not get an uncaught exception handler
            // because the thread has terminated.
            h = Thread.getDefaultUncaughtExceptionHandler();
            if (h != null) {
                h.uncaughtException(this, e);
            } else {
                System.err.print("Exception in EventDispatchThread");
                e.printStackTrace(System.err);
            }
        }
    }

    public synchronized EventQueue getEventQueue() {
        return theQueue;
    }
    public synchronized void setEventQueue(EventQueue eq) {
        theQueue = eq;
    }

    public static LightweightModalEventFilter createLightweightModalEventFilter(Component modalComponent) {
        return new HierarchyEventFilter(modalComponent);
    }

    public static class HierarchyEventFilter extends LightweightModalEventFilter {
        private HierarchyEventFilter(Component modalComponent) {
            super(modalComponent);
        }
        public Component get() {
            return modalComponent;
        }
        public FilterAction acceptEvent(AWTEvent event) {
            if (modalComponent != null) {
                int eventID = event.getID();
                boolean mouseEvent = (eventID >= MouseEvent.MOUSE_FIRST) &&
                                     (eventID <= MouseEvent.MOUSE_LAST);
                boolean actionEvent = (eventID >= ActionEvent.ACTION_FIRST) &&
                                      (eventID <= ActionEvent.ACTION_LAST);
                boolean windowClosingEvent = (eventID == WindowEvent.WINDOW_CLOSING);
                /*
                 * filter out MouseEvent and ActionEvent that's outside
                 * the modalComponent hierarchy.
                 * KeyEvent is handled by using enqueueKeyEvent
                 * in Dialog.show
                 */
                if (Component.isInstanceOf(modalComponent, "javax.swing.JInternalFrame")) {
                    /*
                     * Modal internal frames are handled separately. If event is
                     * for some component from another heavyweight than modalComp,
                     * it is accepted. If heavyweight is the same - we still accept
                     * event and perform further filtering in LightweightDispatcher
                     */
                    return windowClosingEvent ? FilterAction.REJECT : FilterAction.ACCEPT;
                }
                if (mouseEvent || actionEvent || windowClosingEvent) {
                    Object o = event.getSource();
                    if (o instanceof sun.awt.ModalExclude) {
                        // Exclude this object from modality and
                        // continue to pump it's events.
                        return FilterAction.ACCEPT;
                    } else if (o instanceof Component) {
                        Component c = (Component) o;
                        // 5.0u3 modal exclusion
                        boolean modalExcluded = false;
                        if (modalComponent instanceof Container) {
                            while (c != modalComponent && c != null) {
                                if ((c instanceof Window) &&
                                    (sun.awt.SunToolkit.isModalExcluded((Window)c))) {
                                    // Exclude this window and all its children from
                                    //  modality and continue to pump it's events.
                                    modalExcluded = true;
                                    break;
                                }
                                c = c.getParent();
                            }
                        }
                        if (!modalExcluded && (c != modalComponent)) {
                            return FilterAction.REJECT;
                        }
                    }
                }
            }
            return FilterAction.ACCEPT;
        }
    }
}
