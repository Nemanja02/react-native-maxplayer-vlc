//
//  RNVLCPiPModule.h
//
//  iOS bridge for the RNVLCPiP module shared with Android. Coordinates
//  Picture-in-Picture entry/exit and forwards events back to JS.
//

#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

NS_ASSUME_NONNULL_BEGIN

@class RCTVLCPlayer;

@interface RNVLCPiPModule : RCTEventEmitter <RCTBridgeModule>

/// Called by RCTVLCPlayer instances to register themselves so the module
/// can target the active player when JS calls VLCPiP.enter().
+ (void)registerPlayer:(RCTVLCPlayer *)player;
+ (void)unregisterPlayer:(RCTVLCPlayer *)player;

/// Notify JS of a mode change. Called by RCTVLCPlayer's PiP controller
/// delegate.
+ (void)emitModeChanged:(BOOL)inPip;

/// Notify JS of a play/pause toggle from the PiP overlay buttons.
+ (void)emitAction:(NSString *)action;

@end

NS_ASSUME_NONNULL_END
