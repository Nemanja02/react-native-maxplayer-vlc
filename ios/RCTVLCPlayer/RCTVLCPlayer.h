#import "React/RCTView.h"

@class RCTEventDispatcher;

@interface RCTVLCPlayer : UIView

@property (nonatomic, copy) RCTBubblingEventBlock onVideoProgress;
@property (nonatomic, copy) RCTBubblingEventBlock onVideoPaused;
@property (nonatomic, copy) RCTBubblingEventBlock onVideoStopped;
@property (nonatomic, copy) RCTBubblingEventBlock onVideoBuffering;
@property (nonatomic, copy) RCTBubblingEventBlock onVideoPlaying;
@property (nonatomic, copy) RCTBubblingEventBlock onVideoEnded;
@property (nonatomic, copy) RCTBubblingEventBlock onVideoError;
@property (nonatomic, copy) RCTBubblingEventBlock onVideoOpen;
@property (nonatomic, copy) RCTBubblingEventBlock onVideoLoadStart;
@property (nonatomic, copy) RCTBubblingEventBlock onAspectRatioChanged;
@property (nonatomic, copy) RCTBubblingEventBlock onVideoReconnecting;
@property (nonatomic, copy) RCTBubblingEventBlock onVideoReconnectFailed;

- (instancetype)initWithEventDispatcher:(RCTEventDispatcher *)eventDispatcher NS_DESIGNATED_INITIALIZER;
- (void)setMuted:(BOOL)value;
- (void)setPlayInBackground:(BOOL)value;

// Picture-in-Picture API — called by RNVLCPiPModule
- (BOOL)enterPictureInPicture;
- (void)exitPictureInPicture;
- (void)setPiPPlaying:(BOOL)playing;
- (void)setPiPAutoEnter:(BOOL)enabled;

@end
