#import "React/RCTConvert.h"
#import "RCTVLCPlayer.h"
#import "React/RCTBridgeModule.h"
#import "React/RCTEventDispatcher.h"
#import "React/UIView+React.h"
// #ifdef TARGET_OS_TV
// #import <TVVLCKit/TVVLCKit.h>
// #else
#import <MobileVLCKit/MobileVLCKit.h>
// #endif
#import <AVFoundation/AVFoundation.h>
static NSString *const statusKeyPath = @"status";
static NSString *const playbackLikelyToKeepUpKeyPath = @"playbackLikelyToKeepUp";
static NSString *const playbackBufferEmptyKeyPath = @"playbackBufferEmpty";
static NSString *const readyForDisplayKeyPath = @"readyForDisplay";
static NSString *const playbackRate = @"rate";

@implementation RCTVLCPlayer
{

    /* Required to publish events */
    RCTEventDispatcher *_eventDispatcher;
    VLCMediaPlayer *_player;

    NSDictionary * _source;
    BOOL _paused;
    BOOL _started;
    NSString *_videoAspectRatio;
    NSString *_videoWidth;
    NSString *_videoHeight;
    NSString *_mediaType;

    // Retry / reconnect state — mirrors cordova VLCPlayer.java
    NSTimer *_reconnectTimer;
    NSTimer *_stallTimer;
    NSTimeInterval _lastTimeChangedMs;
    NSInteger _reconnectAttempt;
    BOOL _autoReconnect;
    NSInteger _maxReconnectAttempts;
    BOOL _isLiveStream;
    // YES while setSource is invoked from the retry timer block. Lets setSource
    // skip counter reset so exponential backoff accumulates across attempts.
    BOOL _reloadingForRetry;
}

static const NSTimeInterval kReconnectInitialDelaySec = 2.0;
static const NSTimeInterval kReconnectMaxDelaySec = 8.0; // cap at 8s per user preference
static const NSInteger kDefaultMaxReconnects = 10;
static const NSTimeInterval kStallTimeoutSec = 12.0;
static const NSTimeInterval kStallCheckIntervalSec = 5.0;

- (instancetype)initWithEventDispatcher:(RCTEventDispatcher *)eventDispatcher
{
    if ((self = [super init])) {
        _eventDispatcher = eventDispatcher;

        [[NSNotificationCenter defaultCenter] addObserver:self
                                                 selector:@selector(applicationWillResignActive:)
                                                     name:UIApplicationWillResignActiveNotification
                                                   object:nil];

        [[NSNotificationCenter defaultCenter] addObserver:self
                                                 selector:@selector(applicationWillEnterForeground:)
                                                     name:UIApplicationWillEnterForegroundNotification
                                                   object:nil];

    }

    return self;
}

- (void)applicationWillResignActive:(NSNotification *)notification
{
    if (!_paused) {
        [self setPaused:_paused];
    }
}

- (void)applicationWillEnterForeground:(NSNotification *)notification
{
    [self applyModifiers];
}

- (void)applyModifiers
{
    if(!_paused)
        [self play];
}

- (void)setPaused:(BOOL)paused
{
    if(_player){
        if(!paused){
            [self play];
        }else {
            [_player pause];
            _paused =  YES;
            _started = NO;
        }
    }
}

-(void)play
{
    if(_player){
        [_player play];
        _paused = NO;
        _started = YES;
    }
}

- (void)setupPlayerView { 
    // Postavljanje frame-a na veličinu celog ekrana, ignorisanje safeAreaInsets
    self.frame = [UIScreen mainScreen].bounds;

    // self.backgroundColor = [UIColor redColor];

    self.clipsToBounds = YES;
    self.contentMode = UIViewContentModeScaleAspectFill;
}

-(void)setResume:(BOOL)autoplay
{
    NSLog(@"setResume: %i",autoplay);
    if(_player){
        [self _release];
    }
    // [bavv edit start]
    NSString* uri    = [_source objectForKey:@"uri"];
    NSString* mediaType    = [_source objectForKey:@"mediaType"];
    NSURL* _uri    = [NSURL URLWithString:uri];
    NSArray* initOptions = [_source objectForKey:@"initOptions"];

    if(mediaType){
        _mediaType = mediaType;
    }

    // freetype-* i druge LibVLC opcije moraju pri init-u, ne addOption
    if (initOptions != nil && [initOptions count] > 0) {
        _player = [[VLCMediaPlayer alloc] initWithOptions:initOptions];
    } else {
        _player = [[VLCMediaPlayer alloc] init];
    }
	// [bavv edit end]

    self.frame = [UIScreen mainScreen].bounds; // Podesite frame na veličinu celog ekrana  // ovo možda nije neophodno
    [_player setDrawable:self];
    [self setupPlayerView]; // ovo možda nije neophodno

    _player.delegate = self;
    _player.scaleFactor = 0;
    VLCMedia *media = [VLCMedia mediaWithURL:_uri];

    // NSMutableDictionary *options = [NSMutableDictionary new];
    // [options setObject:@"1" forKey:@"rtsp-tcp"];
    // [options setObject:@"1000" forKey:@"input-repeat"];
    // [media addOptions:[options copy]];
    // options = nil;
    for (NSString* option in initOptions) {
        [media addOption:[option stringByReplacingOccurrencesOfString:@"--" withString:@""]];
    }

    _player.media = media;
    [[AVAudioSession sharedInstance] setActive:NO withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation error:nil];
    NSLog(@"autoplay: %i",autoplay);
    self.onVideoLoadStart(@{
                            @"target": self.reactTag
                            });
}

-(void)setSource:(NSDictionary *)source
{
    NSLog(@"setSource: %@",source);
    _source = source;
    // [bavv edit start]
    NSString* uri    = [source objectForKey:@"uri"];
    NSString* agent    = [source objectForKey:@"userAgent"];
    NSString* mediaType    = [source objectForKey:@"mediaType"];
    BOOL    autoplay = [RCTConvert BOOL:[source objectForKey:@"autoplay"]];
    NSURL* _uri    = [NSURL URLWithString:uri];
    NSArray* initOptions = [source objectForKey:@"initOptions"];


    if(mediaType){
        _mediaType = mediaType;
    }

    // Retry config — read each time (setSource is called on mount + source updates)
    // isLive controls unlimited retries. Treat live + timeshift alike for backoff cap.
    _isLiveStream = (_mediaType != nil &&
                     ([_mediaType isEqualToString:@"live"] ||
                      [_mediaType isEqualToString:@"timeshift"]));
    // Default auto-reconnect ONLY for "live" — timeshift consumers typically have
    // their own fallback URL logic and should opt-in explicitly.
    BOOL defaultAutoReconnect = [_mediaType isEqualToString:@"live"];
    id autoReconnectVal = [source objectForKey:@"autoReconnect"];
    _autoReconnect = autoReconnectVal != nil
        ? [RCTConvert BOOL:autoReconnectVal]
        : defaultAutoReconnect;
    id maxVal = [source objectForKey:@"maxReconnectAttempts"];
    _maxReconnectAttempts = maxVal != nil ? [RCTConvert NSInteger:maxVal] : kDefaultMaxReconnects;

    // External source change = fresh retry budget. Skip when called from retry timer.
    if (!_reloadingForRetry) {
        _reconnectAttempt = 0;
    }

    // Cancel any pending retry/stall work from previous source
    [self cancelPendingReconnect];
    [self stopStallDetection];

    if(_player){
        [_player pause];
        _player = nil;
    }

    // freetype-* i druge LibVLC opcije moraju pri init-u, ne addOption
    if (initOptions != nil && [initOptions count] > 0) {
        _player = [[VLCMediaPlayer alloc] initWithOptions:initOptions];
    } else {
        _player = [[VLCMediaPlayer alloc] init];
    }

    // [bavv edit end]

    [_player setDrawable:self];
    _player.delegate = self;
    _player.scaleFactor = 0;

    VLCMedia *media = [VLCMedia mediaWithURL:_uri];

    // NSMutableDictionary *options = [NSMutableDictionary new];
    // [options setObject:@"1" forKey:@"rtsp-tcp"];
    // [options setObject:@"1000" forKey:@"input-repeat"];
    // [options setObject:agent forKey:@"http-user-agent"];
    // [media addOptions:[options copy]];
    // options = nil;
    // user agent
    [media addOption:[agent stringByReplacingOccurrencesOfString:@"--" withString:@""]];

    for (NSString* option in initOptions) {
        [media addOption:[option stringByReplacingOccurrencesOfString:@"--" withString:@""]];
    }
    // log init options
    NSLog(@"initOptions: %@",initOptions);

    _player.media = media;
    [[AVAudioSession sharedInstance] setActive:NO withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation error:nil];

    self.onVideoLoadStart(@{
                           @"target": self.reactTag
                           });
//    if(autoplay)
        [self play];

    // set video aspect ratio
    // NSLog(@"INIT_videoAspectRatio: %@",_videoAspectRatio);
    [self setVideoAspectRatio:_videoAspectRatio];

}

- (void)mediaPlayerTimeChanged:(NSNotification *)aNotification
{
    _lastTimeChangedMs = [[NSDate date] timeIntervalSince1970] * 1000.0;
    [self updateVideoProgress];
}

- (void)mediaPlayerStateChanged:(NSNotification *)aNotification
{

     NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    //  NSLog(@"userInfo %@",[aNotification userInfo]);
    //  NSLog(@"standardUserDefaults %@",defaults);
    if(_player){
        VLCMediaPlayerState state = _player.state;
        // NSLog(@"state %i",state);
        switch (state) {
            case VLCMediaPlayerStateESAdded: {
                NSLog(@"VLCMediaPlayerStateESAdded %i",1);
                    // save number of subtitles and audio tracks in temp variables
                    NSUInteger numberOfSubtitles = [_player numberOfSubtitlesTracks];
                    NSUInteger numberOfAudioTracks = [_player numberOfAudioTracks];

                    NSMutableDictionary *subtitles  = [NSMutableDictionary dictionary];
                    NSMutableDictionary *audio  = [NSMutableDictionary dictionary];

                    NSArray* sub_indexes = [_player videoSubTitlesIndexes];
                    NSArray* sub_names = [_player videoSubTitlesNames];
                    // for (NSUInteger i = 0; i < [_player numberOfSubtitlesTracks]; i++) {
                    //     subtitles[sub_indexes[i]] = sub_names[i];
                    // }
                    for (NSUInteger i = 0; i < numberOfSubtitles; i++) {
                        if (sub_indexes[i] && sub_names[i]) {
                            subtitles[sub_indexes[i]] = sub_names[i];
                        }
                    }

                    NSArray* audio_indexes = [_player audioTrackIndexes];
                    NSArray* audio_names = [_player audioTrackNames];
                    // for (NSUInteger i = 0; i < [_player numberOfAudioTracks]; i++) {
                    //     audio[audio_indexes[i]] = audio_names[i];
                    // }
                    for (NSUInteger i = 0; i < numberOfAudioTracks; i++) {
                        if (audio_indexes[i] && audio_names[i]) {
                            audio[audio_indexes[i]] = audio_names[i];
                        }
                    }

                    // // get video width and height
                    // _videoWidth = [NSString stringWithFormat:@"%f", _player.videoSize.width];
                    // _videoHeight = [NSString stringWithFormat:@"%f", _player.videoSize.height];

                    // NSLog(@"_videoWidth: %@", _videoWidth);
                    // NSLog(@"_videoHeight: %@", _videoHeight);
                    // Expose VLC's currently-selected subtitle and audio track IDs so
                    // the JS side can mirror what the player is actually rendering
                    // (VLC may auto-select a default track based on stream metadata).
                    NSInteger currentSubtitle = [_player currentVideoSubTitleIndex];
                    NSInteger currentAudio = [_player currentAudioTrackIndex];

                    self.onVideoOpen(@{
                                         @"target": self.reactTag,
                                         @"subtitles": subtitles,
                                         @"audio_tracks": audio,
                                         @"currentSubtitle": @(currentSubtitle),
                                         @"currentAudio": @(currentAudio)
                                         });
                break;
            }
            case VLCMediaPlayerStateOpening: {
                NSLog(@"VLCMediaPlayerStateOpening %i",1);
                _lastTimeChangedMs = [[NSDate date] timeIntervalSince1970] * 1000.0;
                self.onVideoOpen(@{
                    @"target": self.reactTag
                });
                break;
            }
            case VLCMediaPlayerStatePaused: {
                _paused = YES;
                NSLog(@"VLCMediaPlayerStatePaused %i",1);
                [self stopStallDetection];
                self.onVideoPaused(@{
                    @"target": self.reactTag
                });
                break;
            }
            case VLCMediaPlayerStateStopped: {
                NSLog(@"VLCMediaPlayerStateStopped %i",1);
                [self stopStallDetection];
                self.onVideoStopped(@{
                    @"target": self.reactTag
                });
                break;
            }
            case VLCMediaPlayerStateBuffering:
                // NSLog(@"VLCMediaPlayerStateBuffering %i",1);
                self.onVideoBuffering(@{
                                        @"target": self.reactTag
                                        });
                break;
            case VLCMediaPlayerStatePlaying:
                _paused = NO;
                NSLog(@"VLCMediaPlayerStatePlaying %i",1);
                // Successful playback — reset retry state, start stall detection
                _reconnectAttempt = 0;
                _lastTimeChangedMs = [[NSDate date] timeIntervalSince1970] * 1000.0;
                if (_autoReconnect) {
                    [self startStallDetection];
                }
                // Snapshot of currently selected SPU/audio track at the moment playback
                // begins. By this time VLC has applied its default selection (locale
                // or stream-flagged), so this is a reliable signal for the JS side
                // to mirror in its UI state.
                self.onVideoPlaying(@{
                                      @"target": self.reactTag,
                                      @"seekable": [NSNumber numberWithBool:[_player isSeekable]],
                                      @"duration":[NSNumber numberWithInt:[_player.media.length intValue]],
                                      @"currentSubtitle": @([_player currentVideoSubTitleIndex]),
                                      @"currentAudio": @([_player currentAudioTrackIndex]),
                                      @"source": @"playing"
                                      });
                break;
            case VLCMediaPlayerStateEnded: {
                NSLog(@"VLCMediaPlayerStateEnded %i",1);
                int currentTime   = [[_player time] intValue];
                int remainingTime = [[_player remainingTime] intValue];
                int duration      = [_player.media.length intValue];

                if (_autoReconnect && _isLiveStream) {
                    // Live stream ended unexpectedly — full reconnect via retry path
                    [self scheduleReconnectWithReason:@"EndReached on live"];
                } else {
                    [self stopStallDetection];
                    [self cancelPendingReconnect];
                }

                self.onVideoEnded(@{
                                    @"target": self.reactTag,
                                    @"currentTime": [NSNumber numberWithInt:currentTime],
                                    @"remainingTime": [NSNumber numberWithInt:remainingTime],
                                    @"duration":[NSNumber numberWithInt:duration],
                                    @"position":[NSNumber numberWithFloat:_player.position]
                                    });
                break;
            }
            case VLCMediaPlayerStateError:
                NSLog(@"VLCMediaPlayerStateError %i",1);
                self.onVideoError(@{
                                    @"target": self.reactTag
                                    });
                if (_autoReconnect) {
                    // Do NOT call _release here — scheduleReconnect will recreate the player
                    [self scheduleReconnectWithReason:@"EncounteredError"];
                } else {
                    [self _release];
                }
                break;
            default:
                break;
        }
    }
}

-(void)updateVideoProgress
{
    if(_player){
        int currentTime   = [[_player time] intValue];
        int remainingTime = [[_player remainingTime] intValue];
        int duration      = [_player.media.length intValue];

        if( currentTime >= 0 && currentTime < duration) {
            self.onVideoProgress(@{
                                   @"target": self.reactTag,
                                   @"currentTime": [NSNumber numberWithInt:currentTime],
                                   @"remainingTime": [NSNumber numberWithInt:remainingTime],
                                   @"duration":[NSNumber numberWithInt:duration],
                                   @"position":[NSNumber numberWithFloat:_player.position]
                                   });
        } else if (_mediaType && [_mediaType isEqualToString:@"live"] || [_mediaType isEqualToString:@"timeshift"]) {
                
            self.onVideoProgress(@{
                    @"target": self.reactTag,
                    @"mediaType": _mediaType,
                    @"currentTime": [NSNumber numberWithInt:currentTime],
                    @"remainingTime": [NSNumber numberWithInt:remainingTime],
                    @"duration":[NSNumber numberWithInt:duration],
                    @"position":[NSNumber numberWithFloat:_player.position]
            });
        }
    }
}

- (void)jumpBackward:(int)interval
{
    if(interval>=0 && interval <= [_player.media.length intValue])
        [_player jumpBackward:interval];
}

- (void)jumpForward:(int)interval
{
    if(interval>=0 && interval <= [_player.media.length intValue])
        [_player jumpForward:interval];
}

- (void)setSubtitleTrack:(int)track
{
    [_player setCurrentVideoSubTitleIndex:track];
}
- (void)setAudioTrack:(int)track
{
    [_player setCurrentAudioTrackIndex:track];
}

// replay stream
- (void)replay
{
    NSLog(@"replay");
    if(_player){
        [_player stop];
        [_player play];
    }
}

// RETRY STREAM WITH 5 RETRIES

- (void)setSeek:(int)pos
{
    int currentTime = [[_player time] intValue];
    pos = pos - (currentTime/1000);
    if ([_player isSeekable]) {
        if (pos > 0) {
            [_player jumpForward:pos];
        } else {
            [_player jumpBackward:-pos];
        }
        
    }
}

-(void)setSnapshotPath:(NSString*)path
{
    if(_player)
        [_player saveVideoSnapshotAt:path withWidth:0 andHeight:0];
}

-(void)setRate:(float)rate
{
    [_player setRate:rate];
}

- (NSInteger)greatestCommonDivisorOfA:(NSInteger)a b:(NSInteger)b {
    while (b != 0) {
        NSInteger temp = b;
        b = a % b;
        a = temp;
    }
    return a;
}

-(void)setVideoAspectRatio:(NSString *)ratio {
    NSLog(@"setVideoAspectRatio: %@", ratio);
    
    if (ratio == nil || [ratio isEqual:[NSNull null]]) {
        ratio = @"fill";
        NSLog(@"setVideoAspectRatio: ratio was nil, using default 'fill'");
    }
    
    _videoAspectRatio = ratio;
    
    if ([ratio isEqualToString:@"original"]) {
        // ratio = @"DEFAULT";
        // default nece iz nekog razloga da radi
        // ratio = @"0";
        // Pa cemo da cuvamo video width i height

        if (_videoWidth == nil || _videoHeight == nil) {
            ratio = @"16:9";
        } else {
            NSInteger gcd = [self greatestCommonDivisorOfA:[_videoWidth integerValue] b:[_videoHeight integerValue]];

            // NZD za smanjenje width i height na celobrojne vrednosti
            NSInteger reducedWidth = [_videoWidth integerValue] / gcd;
            NSInteger reducedHeight = [_videoHeight integerValue] / gcd;

            ratio = [NSString stringWithFormat:@"%ld:%ld", (long)reducedWidth, (long)reducedHeight];
        }

    } else if ([ratio isEqualToString:@"fill"]) {
        CGFloat viewWidth = self.bounds.size.width;
        CGFloat viewHeight = self.bounds.size.height;

        NSInteger gcd = [self greatestCommonDivisorOfA:viewWidth b:viewHeight];

        // NZD za smanjenje width i height na celobrojne vrednosti
        NSInteger reducedWidth = viewWidth / gcd;
        NSInteger reducedHeight = viewHeight / gcd;

        ratio = [NSString stringWithFormat:@"%ld:%ld", (long)reducedWidth, (long)reducedHeight];
    }
    
    char *char_content = [ratio cStringUsingEncoding:NSASCIIStringEncoding];
    [_player setVideoAspectRatio:char_content];
    
    self.onAspectRatioChanged(@{
        // @"target": self.reactTag,
        @"ratio": ratio
    });
}

- (void)setMuted:(BOOL)value
{
    if (_player) {
        [[_player audio] setMuted:value];
    }
}

- (void)_release
{
    // Cancel retry/stall work before destroying the player to prevent
    // timers from firing with a nil _player or racing teardown
    [self cancelPendingReconnect];
    [self stopStallDetection];
    if(_player){
        [_player pause];
        [_player stop];
        _player = nil;
        _eventDispatcher = nil;
        [[NSNotificationCenter defaultCenter] removeObserver:self];
    }
}

// ==================== Retry / reconnect ====================

- (void)startStallDetection
{
    if (_stallTimer != nil) return; // already running
    __weak RCTVLCPlayer *weakSelf = self;
    _stallTimer = [NSTimer scheduledTimerWithTimeInterval:kStallCheckIntervalSec
                                                  repeats:YES
                                                    block:^(NSTimer * _Nonnull timer) {
        RCTVLCPlayer *strongSelf = weakSelf;
        if (strongSelf == nil || strongSelf->_player == nil) {
            [timer invalidate];
            return;
        }
        NSTimeInterval nowMs = [[NSDate date] timeIntervalSince1970] * 1000.0;
        NSTimeInterval sinceLastMs = nowMs - strongSelf->_lastTimeChangedMs;
        if (strongSelf->_lastTimeChangedMs > 0 && sinceLastMs > kStallTimeoutSec * 1000.0) {
            NSLog(@"Stall detected: no TimeChanged for %.0fms", sinceLastMs);
            [strongSelf stopStallDetection];
            [strongSelf scheduleReconnectWithReason:@"stall"];
        }
    }];
}

- (void)stopStallDetection
{
    if (_stallTimer != nil) {
        [_stallTimer invalidate];
        _stallTimer = nil;
    }
}

- (void)cancelPendingReconnect
{
    if (_reconnectTimer != nil) {
        [_reconnectTimer invalidate];
        _reconnectTimer = nil;
    }
}

- (void)scheduleReconnectWithReason:(NSString *)reason
{
    if (!_autoReconnect || _source == nil) return;
    // Cancel any pending work so we don't stack up reconnects
    [self cancelPendingReconnect];
    [self stopStallDetection];

    // Live = unlimited. VOD = _maxReconnectAttempts.
    if (!_isLiveStream && _reconnectAttempt >= _maxReconnectAttempts) {
        NSLog(@"Max reconnect attempts (%ld) reached, giving up", (long)_maxReconnectAttempts);
        if (self.onVideoReconnectFailed) {
            self.onVideoReconnectFailed(@{
                @"target": self.reactTag ?: @0,
                @"attempts": @(_reconnectAttempt)
            });
        }
        return;
    }

    _reconnectAttempt++;
    // Exponential backoff: 2s, 4s, 8s, 8s, 8s ... (capped at kReconnectMaxDelaySec)
    NSInteger shiftBy = MIN(_reconnectAttempt - 1, 4);
    NSTimeInterval delaySec = MIN(kReconnectInitialDelaySec * (1 << shiftBy), kReconnectMaxDelaySec);
    NSLog(@"Reconnect attempt %ld in %.1fs (reason=%@, live=%d)",
          (long)_reconnectAttempt, delaySec, reason, _isLiveStream);

    if (self.onVideoReconnecting) {
        self.onVideoReconnecting(@{
            @"target": self.reactTag ?: @0,
            @"attempt": @(_reconnectAttempt),
            @"maxAttempts": @(_isLiveStream ? -1 : _maxReconnectAttempts),
            @"delayMs": @((NSInteger)(delaySec * 1000)),
            @"reason": reason ?: @""
        });
    }

    __weak RCTVLCPlayer *weakSelf = self;
    _reconnectTimer = [NSTimer scheduledTimerWithTimeInterval:delaySec
                                                      repeats:NO
                                                        block:^(NSTimer * _Nonnull timer) {
        RCTVLCPlayer *strongSelf = weakSelf;
        if (strongSelf == nil || strongSelf->_source == nil) return;
        strongSelf->_reconnectTimer = nil;
        strongSelf->_reloadingForRetry = YES;
        @try {
            // Full recreate via setSource — tears down _player and rebuilds
            // with same source dict, including freetype opts and autoplay
            [strongSelf setSource:strongSelf->_source];
        } @catch (NSException *e) {
            NSLog(@"Reconnect error: %@", e);
            [strongSelf scheduleReconnectWithReason:@"reconnect-exception"];
        } @finally {
            strongSelf->_reloadingForRetry = NO;
        }
    }];
}


#pragma mark - Lifecycle
- (void)removeFromSuperview
{
    NSLog(@"removeFromSuperview");
    [self _release];
    [super removeFromSuperview];
}

- (void)dealloc
{
    // Defensive: if ARC deallocates without removeFromSuperview (unusual but possible
    // in some lifecycle races), ensure retry/stall NSTimers aren't left on the runloop
    // holding a block with a now-nil weakSelf.
    if (_reconnectTimer != nil) { [_reconnectTimer invalidate]; _reconnectTimer = nil; }
    if (_stallTimer != nil) { [_stallTimer invalidate]; _stallTimer = nil; }
}


- (void)layoutSubviews {
    [super layoutSubviews];
    NSLog(@"layoutSubviews");

    [self setVideoAspectRatio:_videoAspectRatio];
}

@end
