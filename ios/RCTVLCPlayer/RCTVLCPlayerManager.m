#import "RCTVLCPlayerManager.h"
#import "RCTVLCPlayer.h"
#import "React/RCTBridge.h"

@implementation RCTVLCPlayerManager

RCT_EXPORT_MODULE();

@synthesize bridge = _bridge;

- (UIView *)view
{
    // return [[RCTVLCPlayer alloc] initWithEventDispatcher:self.bridge.eventDispatcher];
    RCTVLCPlayer *player = [[RCTVLCPlayer alloc] initWithEventDispatcher:self.bridge.eventDispatcher];
    
    // Postavljanje frame-a na veličinu celog ekrana, ignorisanje safeAreaInsets
    player.frame = [UIScreen mainScreen].bounds;

    // Postavljanje boje pozadine za vizualnu proveru
    player.backgroundColor = [UIColor redColor];  // Možete izabrati bilo koju boju

    // Postavljanje drugih svojstava view-a
    player.clipsToBounds = YES;
    player.contentMode = UIViewContentModeScaleAspectFill;

    player.layer.borderWidth = 2.0;
    player.layer.borderColor = [UIColor greenColor].CGColor;
    return player;
}

/* Should support: onLoadStart, onLoad, and onError to stay consistent with Image */
RCT_EXPORT_VIEW_PROPERTY(onVideoProgress, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onVideoPaused, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onVideoStopped, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onVideoBuffering, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onVideoPlaying, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onVideoEnded, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onVideoError, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onVideoOpen, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onVideoLoadStart, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onAspectRatioChanged, RCTDirectEventBlock);

- (dispatch_queue_t)methodQueue
{
    return dispatch_get_main_queue();
}

RCT_EXPORT_VIEW_PROPERTY(source, NSDictionary);
RCT_EXPORT_VIEW_PROPERTY(paused, BOOL);
RCT_EXPORT_VIEW_PROPERTY(seek, int);
RCT_EXPORT_VIEW_PROPERTY(subtitleTrack, int);
RCT_EXPORT_VIEW_PROPERTY(audioTrack, int);
RCT_EXPORT_VIEW_PROPERTY(rate, float);
RCT_EXPORT_VIEW_PROPERTY(resume, BOOL);
RCT_EXPORT_VIEW_PROPERTY(videoAspectRatio, NSString);
RCT_EXPORT_VIEW_PROPERTY(snapshotPath, NSString);
RCT_EXPORT_VIEW_PROPERTY(fullscreenType, NSString);
RCT_CUSTOM_VIEW_PROPERTY(muted, BOOL, RCTVLCPlayer)
{
    BOOL isMuted = [RCTConvert BOOL:json];
    [view setMuted:isMuted];
};

@end