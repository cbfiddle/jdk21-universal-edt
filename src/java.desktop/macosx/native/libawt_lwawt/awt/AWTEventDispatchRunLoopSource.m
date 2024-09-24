#import "AWTEventDispatchRunLoopSource.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"

static jobject performCallback;

@implementation AWTEventDispatchRunLoopSource
{
    CFRunLoopSourceRef runLoopSource;
    BOOL isArmed;
    jobject performer;
}

void RunLoopSourceScheduleRoutine (void *info, CFRunLoopRef rl, CFStringRef mode);
void RunLoopSourcePerformRoutine (void *info);
void RunLoopSourceCancelRoutine (void *info, CFRunLoopRef rl, CFStringRef mode);

- (id)init:(jobject)runnable
{
    // version, info, retain, release, copyDescription, equal, hash, schedule, cancel, perform

    CFRunLoopSourceContext context = {0, self, NULL, NULL, NULL, NULL, NULL,
                                        RunLoopSourceScheduleRoutine,
                                        RunLoopSourceCancelRoutine,
                                        RunLoopSourcePerformRoutine};

    runLoopSource = CFRunLoopSourceCreate(NULL, 0, &context);
    performer = runnable;
    return self;
}

- (void)addToCurrentRunLoop
{
    //NSLog(@"Registering run loop source on %@", NSThread.currentThread);
    CFRunLoopAddSource(CFRunLoopGetMain(), runLoopSource, NSDefaultRunLoopMode);
    CFRunLoopAddSource(CFRunLoopGetMain(), runLoopSource, NSModalPanelRunLoopMode);
    CFRunLoopAddSource(CFRunLoopGetMain(), runLoopSource, NSEventTrackingRunLoopMode);
    CFRunLoopAddSource(CFRunLoopGetMain(), runLoopSource, [ThreadUtilities javaRunLoopMode]);
    isArmed = false;
}

- (void)announceReady
{
    if (!isArmed) {
        //NSLog(@"Arming run loop source on %@", NSThread.currentThread);
        CFRunLoopSourceSignal(runLoopSource);
        CFRunLoopWakeUp(CFRunLoopGetMain());
        isArmed = YES;
    }
}

- (void)perform
{
    //NSLog(@"Run loop source called to perform on %@", NSThread.currentThread);
    isArmed = NO;
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    DECLARE_CLASS(sjc_Runnable, "java/lang/Runnable");
    DECLARE_METHOD(jm_Runnable_run, sjc_Runnable, "run", "()V");
    (*env)->CallVoidMethod(env, performer, jm_Runnable_run);
    CHECK_EXCEPTION();
}

@end

void RunLoopSourceScheduleRoutine (void *info, CFRunLoopRef rl, CFStringRef mode)
{
    AWTEventDispatchRunLoopSource* obj = (AWTEventDispatchRunLoopSource*)info;
    //NSLog(@"Schedule called %@", mode);
}

void RunLoopSourcePerformRoutine (void *info)
{
    AWTEventDispatchRunLoopSource* obj = (AWTEventDispatchRunLoopSource*)info;
    //NSLog(@"Perform called");
    [obj perform];
}

void RunLoopSourceCancelRoutine (void *info, CFRunLoopRef rl, CFStringRef mode)
{
    AWTEventDispatchRunLoopSource* obj = (AWTEventDispatchRunLoopSource*)info;
    //NSLog(@"Cancel called %@", mode);
}
