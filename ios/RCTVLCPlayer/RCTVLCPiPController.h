//
//  RCTVLCPiPController.h
//
//  Picture-in-Picture controller that wraps a non-AVPlayer video source
//  (libvlc rendering into an OpenGL UIView) into the iOS 15+ custom
//  AVPictureInPictureController API.
//
//  Approach: snapshot the source UIView at ~25fps via drawViewHierarchyIn
//  Rect:, convert UIImage→CVPixelBuffer, wrap as CMSampleBuffer, enqueue
//  into an AVSampleBufferDisplayLayer that's used as the PiP content
//  source. The PiP overlay reads frames from that display layer.
//
//  This approach is portable across MobileVLCKit versions and doesn't
//  rely on private API access to libvlc handles. Trade-off: ~15-25fps
//  rather than the source's native framerate, and CPU cost for the
//  per-frame snapshot. Perfectly adequate for a small PiP window.
//

#import <UIKit/UIKit.h>
#import <AVKit/AVKit.h>
#import <AVFoundation/AVFoundation.h>

NS_ASSUME_NONNULL_BEGIN

@class RCTVLCPiPController;

@protocol RCTVLCPiPControllerDelegate <NSObject>
@optional
- (void)pipControllerDidStartPiP:(RCTVLCPiPController *)controller;
- (void)pipControllerDidStopPiP:(RCTVLCPiPController *)controller;
- (void)pipControllerPlayPauseToggled:(RCTVLCPiPController *)controller
                              playing:(BOOL)playing;
@end

API_AVAILABLE(ios(15.0))
@interface RCTVLCPiPController : NSObject

@property (nonatomic, weak) id<RCTVLCPiPControllerDelegate> delegate;
@property (nonatomic, readonly) BOOL isPictureInPictureActive;
@property (nonatomic, readonly) BOOL isSupported;

/// Set whether the underlying media is currently playing. Used by the PiP
/// overlay to drive the play/pause button glyph and pause-on-tap behaviour.
@property (nonatomic, assign) BOOL playing;

- (instancetype)initWithSourceView:(UIView *)sourceView;

/// Begin the snapshot pipeline and request PiP from the system. Audio
/// session must already be in .playback category before calling.
- (void)enterPictureInPicture;
- (void)exitPictureInPicture;

/// Stop the snapshot loop. Safe to call multiple times.
- (void)teardown;

@end

NS_ASSUME_NONNULL_END
