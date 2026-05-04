//
//  RNVLCPiPModule.m
//

#import "RNVLCPiPModule.h"
#import "RCTVLCPlayer.h"
#import <AVKit/AVKit.h>
#import <AVFoundation/AVFoundation.h>

static NSHashTable<RCTVLCPlayer *> *gPlayers = nil;
static __weak RNVLCPiPModule *gShared = nil;

@interface RNVLCPiPModule ()
@property (nonatomic, assign) BOOL hasJSListeners;
@end

@implementation RNVLCPiPModule

RCT_EXPORT_MODULE(RNVLCPiP);

+ (void)initialize {
    if (self == [RNVLCPiPModule class]) {
        gPlayers = [NSHashTable weakObjectsHashTable];
    }
}

- (instancetype)init {
    if ((self = [super init])) {
        gShared = self;
    }
    return self;
}

+ (BOOL)requiresMainQueueSetup {
    return YES;
}

- (dispatch_queue_t)methodQueue {
    return dispatch_get_main_queue();
}

- (NSArray<NSString *> *)supportedEvents {
    return @[@"RNVLCPiPModeChanged", @"RNVLCPiPAction"];
}

- (void)startObserving {
    self.hasJSListeners = YES;
}

- (void)stopObserving {
    self.hasJSListeners = NO;
}

#pragma mark - Player registry

+ (void)registerPlayer:(RCTVLCPlayer *)player {
    if (!player) return;
    [gPlayers addObject:player];
}

+ (void)unregisterPlayer:(RCTVLCPlayer *)player {
    if (!player) return;
    [gPlayers removeObject:player];
}

+ (RCTVLCPlayer *)activePlayer {
    // Pick the most recently added player that's still in a window —
    // approximates "the one currently visible to the user".
    for (RCTVLCPlayer *p in gPlayers.allObjects) {
        if (p.window != nil) return p;
    }
    return gPlayers.allObjects.lastObject;
}

#pragma mark - JS-facing events

+ (void)emitModeChanged:(BOOL)inPip {
    RNVLCPiPModule *m = gShared;
    if (!m || !m.hasJSListeners) return;
    [m sendEventWithName:@"RNVLCPiPModeChanged"
                    body:@{@"isInPictureInPictureMode": @(inPip)}];
}

+ (void)emitAction:(NSString *)action {
    RNVLCPiPModule *m = gShared;
    if (!m || !m.hasJSListeners || !action) return;
    [m sendEventWithName:@"RNVLCPiPAction" body:@{@"action": action}];
}

#pragma mark - JS API

RCT_REMAP_METHOD(isSupported,
                 isSupportedResolver:(RCTPromiseResolveBlock)resolve
                 isSupportedRejecter:(RCTPromiseRejectBlock)reject) {
    if (@available(iOS 15.0, *)) {
        resolve(@([AVPictureInPictureController isPictureInPictureSupported]));
    } else {
        resolve(@NO);
    }
}

RCT_REMAP_METHOD(enter,
                 enterOptions:(NSDictionary *)options
                 enterResolver:(RCTPromiseResolveBlock)resolve
                 enterRejecter:(RCTPromiseRejectBlock)reject) {
    if (@available(iOS 15.0, *)) {
        if (![AVPictureInPictureController isPictureInPictureSupported]) {
            reject(@"E_UNSUPPORTED",
                   @"PiP not supported on this device", nil);
            return;
        }

        // Audio session must be .playback for PiP — switch eagerly.
        NSError *audioErr = nil;
        AVAudioSession *session = [AVAudioSession sharedInstance];
        [session setCategory:AVAudioSessionCategoryPlayback error:&audioErr];
        [session setActive:YES error:&audioErr];

        RCTVLCPlayer *player = [RNVLCPiPModule activePlayer];
        if (!player) {
            reject(@"E_NO_PLAYER",
                   @"No active VLCPlayer view to attach PiP to", nil);
            return;
        }

        BOOL ok = [player enterPictureInPicture];
        resolve(@(ok));
    } else {
        reject(@"E_UNSUPPORTED",
               @"PiP requires iOS 15.0 or later", nil);
    }
}

RCT_REMAP_METHOD(setParams,
                 setParamsOptions:(NSDictionary *)options
                 setParamsResolver:(RCTPromiseResolveBlock)resolve
                 setParamsRejecter:(RCTPromiseRejectBlock)reject) {
    // Reflect playback state into the iOS PiP overlay glyph
    NSNumber *playing = options[@"playing"];
    if (playing != nil) {
        RCTVLCPlayer *player = [RNVLCPiPModule activePlayer];
        if (player) {
            [player setPiPPlaying:[playing boolValue]];
        }
    }
    resolve(nil);
}

RCT_REMAP_METHOD(setAutoEnterEnabled,
                 enabled:(BOOL)enabled
                 setAutoEnterOptions:(NSDictionary *)options
                 setAutoEnterResolver:(RCTPromiseResolveBlock)resolve
                 setAutoEnterRejecter:(RCTPromiseRejectBlock)reject) {
    // iOS 15+: AVPictureInPictureController has
    // canStartPictureInPictureAutomaticallyFromInline. Only valid for
    // AVPlayer-backed PiP officially; for sample-buffer-based custom PiP
    // (our case) the system gates it. Ship it through anyway — falls
    // back to manual entry if the OS rejects.
    if (@available(iOS 15.0, *)) {
        RCTVLCPlayer *player = [RNVLCPiPModule activePlayer];
        if (player) {
            [player setPiPAutoEnter:enabled];
        }
        resolve(@YES);
    } else {
        resolve(@NO);
    }
}

@end
