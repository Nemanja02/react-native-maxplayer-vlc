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
}

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
    if(_player){
        [self _release];
    }
    // [bavv edit start]
    NSString* uri    = [_source objectForKey:@"uri"];
    NSURL* _uri    = [NSURL URLWithString:uri];
    NSDictionary* initOptions = [_source objectForKey:@"initOptions"];

    _player = [[VLCMediaPlayer alloc] init];
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
    BOOL    autoplay = [RCTConvert BOOL:[source objectForKey:@"autoplay"]];
    NSURL* _uri    = [NSURL URLWithString:uri];
    NSDictionary* initOptions = [source objectForKey:@"initOptions"];

    if(_player){
        [_player pause];
        _player = nil;
    }

    _player = [[VLCMediaPlayer alloc] init];

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

    _player.media = media;
    [[AVAudioSession sharedInstance] setActive:NO withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation error:nil];
    NSLog(@"autoplay: %i",autoplay);
    self.onVideoLoadStart(@{
                           @"target": self.reactTag
                           });
//    if(autoplay)
        [self play];
}

- (void)mediaPlayerTimeChanged:(NSNotification *)aNotification
{
    [self updateVideoProgress];
}

- (void)mediaPlayerStateChanged:(NSNotification *)aNotification
{

     NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
     NSLog(@"userInfo %@",[aNotification userInfo]);
     NSLog(@"standardUserDefaults %@",defaults);
    if(_player){
        VLCMediaPlayerState state = _player.state;
        switch (state) {
            case VLCMediaPlayerStateESAdded: {
                NSLog(@"VLCMediaPlayerStateESAdded %i",1);
                NSMutableDictionary *subtitles  = [NSMutableDictionary dictionary];
                    NSArray* sub_indexes = [_player videoSubTitlesIndexes];
                    NSArray* sub_names = [_player videoSubTitlesNames];
                    for (NSUInteger i = 0; i < [_player numberOfSubtitlesTracks]; i++) {
                        subtitles[sub_indexes[i]] = sub_names[i];
                    }

                    NSMutableDictionary *audio  = [NSMutableDictionary dictionary];
                    NSArray* audio_indexes = [_player audioTrackIndexes];
                    NSArray* audio_names = [_player audioTrackNames];
                    for (NSUInteger i = 0; i < [_player numberOfAudioTracks]; i++) {
                        audio[audio_indexes[i]] = audio_names[i];
                    }

                    // get video width and height
                    _videoWidth = [NSString stringWithFormat:@"%f", _player.videoSize.width];
                    _videoHeight = [NSString stringWithFormat:@"%f", _player.videoSize.height];

                    NSLog(@"_videoWidth: %@", _videoWidth);
                    NSLog(@"_videoHeight: %@", _videoHeight);
                    self.onVideoOpen(@{
                                         @"target": self.reactTag,
                                         @"subtitles": subtitles,
                                         @"audio_tracks": audio
                                         });
                break;
            }
            case VLCMediaPlayerStateOpening: {
                NSLog(@"VLCMediaPlayerStateOpening %i",1);
                self.onVideoOpen(@{
                    @"target": self.reactTag
                });
                break;
            }
            case VLCMediaPlayerStatePaused: {
                _paused = YES;
                NSLog(@"VLCMediaPlayerStatePaused %i",1);
                self.onVideoPaused(@{
                    @"target": self.reactTag
                });
                break;
            }
            case VLCMediaPlayerStateStopped: {
                NSLog(@"VLCMediaPlayerStateStopped %i",1);
                self.onVideoStopped(@{
                    @"target": self.reactTag
                });
                break;
            }
            case VLCMediaPlayerStateBuffering:
                NSLog(@"VLCMediaPlayerStateBuffering %i",1);
                self.onVideoBuffering(@{
                                        @"target": self.reactTag
                                        });
                break;
            case VLCMediaPlayerStatePlaying:
                _paused = NO;
                NSLog(@"VLCMediaPlayerStatePlaying %i",1);
                self.onVideoPlaying(@{
                                      @"target": self.reactTag,
                                      @"seekable": [NSNumber numberWithBool:[_player isSeekable]],
                                      @"duration":[NSNumber numberWithInt:[_player.media.length intValue]]
                                      });
                break;
            case VLCMediaPlayerStateEnded:
                NSLog(@"VLCMediaPlayerStateEnded %i",1);
                int currentTime   = [[_player time] intValue];
                int remainingTime = [[_player remainingTime] intValue];
                int duration      = [_player.media.length intValue];

                self.onVideoEnded(@{
                                    @"target": self.reactTag,
                                    @"currentTime": [NSNumber numberWithInt:currentTime],
                                    @"remainingTime": [NSNumber numberWithInt:remainingTime],
                                    @"duration":[NSNumber numberWithInt:duration],
                                    @"position":[NSNumber numberWithFloat:_player.position]
                                    });
                break;
            case VLCMediaPlayerStateError:
                NSLog(@"VLCMediaPlayerStateError %i",1);
                self.onVideoError(@{
                                    @"target": self.reactTag
                                    });
                [self _release];
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
    if(_player){
        [_player pause];
        [_player stop];
        _player = nil;
        _eventDispatcher = nil;
        [[NSNotificationCenter defaultCenter] removeObserver:self];
    }
}


#pragma mark - Lifecycle
- (void)removeFromSuperview
{
    NSLog(@"removeFromSuperview");
    [self _release];
    [super removeFromSuperview];
}


- (void)layoutSubviews {
    [super layoutSubviews];
    NSLog(@"layoutSubviews");

    [self setVideoAspectRatio:_videoAspectRatio];
}

@end
