This repo contains an experimental “proof of concept” version of OpenJDK 21 that (optionally) uses the AppKit main thread on macOS as the AWT event dispatch thread (EDT). (The standard JDK on macOS uses a separate thread to dispatch AWT events.)

The ultimate goal of unification is to support improved integration with AppKit to allow Java applications to provide a native experience. There may also be benefits for JavaFX and SWT integration, but I have not explored those options.

The unified EDT implementation is optional because it is behaviorally incompatible with the standard EDT implementation.

The unified EDT changes are in the `eventQueue` branch.

Select the unified EDT implementation using: `-Dsun.awt.macos.useMainThread=true`

The known limitations of the unified event thread implementation:

- With only one EDT, multiple application contexts cannot be supported.

- Because the platform modal run loop is used, document modal dialogs cannot be supported. Multiple non-nested open modal dialogs also cannot be supported.

- When using the unified EDT, it is particularly important to create and display UI components only on the EDT.
Creating UI components on a non-EDT thread can easily deadlock.

- Using Robot to inject a mouse pressed event without also injecting additional mouse events can hang, because when the mouse pressed event is delivered to AppKit, it assumes another event will become available and it will block the main thread indefinitely waiting for it.

The major implementation change involves the AWT event queue. In the unified EDT implementation, the AWT event pump runs as one of many AppKit event handlers. Therefore, it cannot run indefinitely or even remain running as new AWT events are posted, to avoid starving the platform event processing. I modified the event queue implementation to support deferring the processing of events that are added during AWT event dispatch. When there are deferred events, the AWT event handler is rescheduled.

Another significant change involves the primitives for running code on the AppKit main thread, typically used
by code running on the AWT EDT. The current primitives make a distinction between code that produces a result (requiring the
caller to wait for the code to terminate) and code that does not produce a result (which allows the caller to
continue execution without waiting). When the AWT EDT *is* the AppKit main thread, a better distinction
is between code that is permitted to execute immediately and code that must be deferred for scheduling reasons.
Because both EDT implementations are supported, there are now three options: (1) must wait, (2) must perform later,
and (3) don't care.

Because AWT on macOS is actually Swing, macOS programs should always create and display AWT components on the EDT.
AWT test programs do not typically follow this rule, but they pass anyway using the standard EDT implementation.
I have made some implementation changes that allow most AWT test programs to run successfully on the unified EDT
without any modification. These changes
allow some AWT operations to be safely invoked from a non-EDT thread and allow Robot operations to be invoked from the EDT.
However, the best approach is to follow the EDT-only rule for all UI/graphics operations.

**This code is completely unsupported and I am not committing to update it.**
