#import <Cocoa/Cocoa.h>
#include "jni.h"

@interface AWTEventDispatchRunLoopSource : NSObject { }
- (id)init:(jobject)runnable;
- (void)addToCurrentRunLoop;
- (void)announceReady;
@end
