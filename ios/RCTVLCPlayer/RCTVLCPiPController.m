//
//  RCTVLCPiPController.m
//

#import "RCTVLCPiPController.h"
#import <CoreVideo/CoreVideo.h>
#import <CoreMedia/CoreMedia.h>

#define kSnapshotFps 12

@interface RCTVLCPiPController () <AVPictureInPictureControllerDelegate, AVPictureInPictureSampleBufferPlaybackDelegate>

@property (nonatomic, weak) UIView *sourceView;
@property (nonatomic, strong) AVSampleBufferDisplayLayer *displayLayer;
@property (nonatomic, strong) UIView *displayLayerHostView;
@property (nonatomic, strong) AVPictureInPictureController *pipController;
@property (nonatomic, strong) CADisplayLink *displayLink;
@property (nonatomic, assign) CFTimeInterval lastFrameTime;
@property (nonatomic, assign) CVPixelBufferPoolRef pixelBufferPool;
@property (nonatomic, assign) CMVideoFormatDescriptionRef formatDescription;
@property (nonatomic, assign) NSInteger lastWidth;
@property (nonatomic, assign) NSInteger lastHeight;
@property (nonatomic, assign) BOOL pipActive;

@end

@implementation RCTVLCPiPController

- (instancetype)initWithSourceView:(UIView *)sourceView {
    if ((self = [super init])) {
        _sourceView = sourceView;
        _playing = YES;
        [self setupDisplayLayer];
    }
    return self;
}

- (void)dealloc {
    [self teardown];
    if (_pixelBufferPool) {
        CVPixelBufferPoolRelease(_pixelBufferPool);
        _pixelBufferPool = NULL;
    }
    if (_formatDescription) {
        CFRelease(_formatDescription);
        _formatDescription = NULL;
    }
}

- (BOOL)isSupported {
    if (@available(iOS 15.0, *)) {
        return [AVPictureInPictureController isPictureInPictureSupported];
    }
    return NO;
}

- (BOOL)isPictureInPictureActive {
    return self.pipActive;
}

#pragma mark - Setup

- (void)setupDisplayLayer {
    // PiP framework requires the display layer to live in a real, on-screen
    // view hierarchy with non-zero size and alpha 1.0 (alpha < 1 is treated
    // as effectively hidden). We position the host view far off-screen so
    // it's invisible to the user but the framework still considers it
    // "presenting" sample buffers.
    CGRect offscreen = CGRectMake(-2000, -2000, 4, 4);
    _displayLayerHostView = [[UIView alloc] initWithFrame:offscreen];
    _displayLayerHostView.userInteractionEnabled = NO;
    _displayLayerHostView.hidden = NO;
    _displayLayerHostView.alpha = 1.0;
    _displayLayerHostView.backgroundColor = [UIColor blackColor];

    _displayLayer = [[AVSampleBufferDisplayLayer alloc] init];
    _displayLayer.videoGravity = AVLayerVideoGravityResizeAspect;
    _displayLayer.frame = _displayLayerHostView.bounds;
    [_displayLayerHostView.layer addSublayer:_displayLayer];

    if (_sourceView) {
        UIView *parent = _sourceView.superview ?: _sourceView;
        [parent addSubview:_displayLayerHostView];
    }
}

- (void)ensurePixelBufferPoolWithWidth:(NSInteger)width height:(NSInteger)height {
    if (_pixelBufferPool && width == _lastWidth && height == _lastHeight) {
        return;
    }
    if (_pixelBufferPool) {
        CVPixelBufferPoolRelease(_pixelBufferPool);
        _pixelBufferPool = NULL;
    }
    if (_formatDescription) {
        CFRelease(_formatDescription);
        _formatDescription = NULL;
    }

    _lastWidth = width;
    _lastHeight = height;

    NSDictionary *bufferAttrs = @{
        (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
        (id)kCVPixelBufferWidthKey: @(width),
        (id)kCVPixelBufferHeightKey: @(height),
        (id)kCVPixelBufferIOSurfacePropertiesKey: @{},
        (id)kCVPixelBufferMetalCompatibilityKey: @YES,
    };

    NSDictionary *poolAttrs = @{
        (id)kCVPixelBufferPoolMinimumBufferCountKey: @3,
    };

    CVReturn rc = CVPixelBufferPoolCreate(
        kCFAllocatorDefault,
        (__bridge CFDictionaryRef)poolAttrs,
        (__bridge CFDictionaryRef)bufferAttrs,
        &_pixelBufferPool);
    if (rc != kCVReturnSuccess) {
        NSLog(@"RCTVLCPiPController: CVPixelBufferPoolCreate failed: %d", rc);
        _pixelBufferPool = NULL;
    }
}

#pragma mark - PiP Control

- (void)enterPictureInPicture {
    if (![self isSupported]) {
        NSLog(@"RCTVLCPiPController: PiP not supported on this device");
        return;
    }

    if (@available(iOS 15.0, *)) {
        if (!_pipController) {
            AVPictureInPictureControllerContentSource *source =
                [[AVPictureInPictureControllerContentSource alloc]
                    initWithSampleBufferDisplayLayer:_displayLayer
                                    playbackDelegate:self];
            _pipController = [[AVPictureInPictureController alloc]
                initWithContentSource:source];
            _pipController.delegate = self;
            _pipController.canStartPictureInPictureAutomaticallyFromInline = NO;
        }

        // Force-render a couple of frames synchronously so the display
        // layer has content before we ask the system to enter PiP. The
        // PiP framework refuses startPictureInPicture if the layer has
        // never received a sample buffer.
        [self startSnapshotLoop];
        [self snapshotTick:nil];
        [self snapshotTick:nil];

        // Defer the actual PiP request enough for the system to register
        // the layer is "presenting" content. 500ms is conservative; it
        // accounts for slow first-frame delivery on older devices.
        __weak __typeof(self) weakSelf = self;
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.5 * NSEC_PER_SEC)),
                       dispatch_get_main_queue(), ^{
            __strong __typeof(weakSelf) self = weakSelf;
            if (!self) return;
            BOOL possible = self.pipController.isPictureInPicturePossible;
            NSLog(@"RCTVLCPiPController: about to start PiP — possible=%d "
                  @"layer.status=%ld layer.error=%@",
                  possible, (long)self.displayLayer.status,
                  self.displayLayer.error);
            if (possible) {
                [self.pipController startPictureInPicture];
            } else {
                NSLog(@"RCTVLCPiPController: PiP not possible — layer not ready or "
                      @"audio session not .playback or backgroundModes missing "
                      @"picture-in-picture");
            }
        });
    }
}

- (void)exitPictureInPicture {
    if (@available(iOS 15.0, *)) {
        if (_pipController && _pipController.isPictureInPictureActive) {
            [_pipController stopPictureInPicture];
        }
    }
    [self stopSnapshotLoop];
}

- (void)teardown {
    [self stopSnapshotLoop];
    if (_displayLayerHostView) {
        [_displayLayerHostView removeFromSuperview];
        _displayLayerHostView = nil;
    }
    _displayLayer = nil;
    _pipController = nil;
}

#pragma mark - Snapshot Loop

- (void)startSnapshotLoop {
    if (_displayLink) return;
    _displayLink = [CADisplayLink displayLinkWithTarget:self
                                               selector:@selector(snapshotTick:)];
    _displayLink.preferredFramesPerSecond = kSnapshotFps;
    [_displayLink addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
}

- (void)stopSnapshotLoop {
    if (_displayLink) {
        [_displayLink invalidate];
        _displayLink = nil;
    }
}

- (void)snapshotTick:(CADisplayLink *)link {
    UIView *src = self.sourceView;
    if (!src) return;
    CGSize size = src.bounds.size;
    if (size.width < 16 || size.height < 16) return; // need real bounds
    if (!src.window) return; // not on-screen, nothing to capture

    // Cap snapshot resolution — at PiP overlay size (≤480x270 typically),
    // capturing 1920x1080 is wasted CPU/memory. Aim for ~640px max edge.
    NSInteger w = (NSInteger)size.width;
    NSInteger h = (NSInteger)size.height;
    CGFloat scale = 1.0;
    NSInteger maxDim = MAX(w, h);
    if (maxDim > 640) {
        scale = 640.0 / (CGFloat)maxDim;
        w = (NSInteger)(w * scale);
        h = (NSInteger)(h * scale);
    }
    if (w < 16 || h < 16) return;

    [self ensurePixelBufferPoolWithWidth:w height:h];
    if (!_pixelBufferPool) return;

    CVPixelBufferRef pb = NULL;
    CVReturn rc = CVPixelBufferPoolCreatePixelBuffer(
        kCFAllocatorDefault, _pixelBufferPool, &pb);
    if (rc != kCVReturnSuccess || !pb) return;

    CVPixelBufferLockBaseAddress(pb, 0);
    void *baseAddr = CVPixelBufferGetBaseAddress(pb);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(pb);

    CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
    CGContextRef ctx = CGBitmapContextCreate(
        baseAddr, w, h, 8, bytesPerRow, colorSpace,
        (CGBitmapInfo)kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little);
    CGColorSpaceRelease(colorSpace);

    if (ctx) {
        UIGraphicsPushContext(ctx);
        CGContextTranslateCTM(ctx, 0, h);
        CGContextScaleCTM(ctx, 1.0, -1.0);
        if (scale != 1.0) {
            CGContextScaleCTM(ctx, scale, scale);
        }
        // afterScreenUpdates:NO — YES blocks main thread waiting for the
        // next screen flush, which deadlocks against the CADisplayLink that
        // fires this method (also on main). The trade-off is that OpenGL/
        // Metal-rendered content captured this way may not always reflect
        // the latest GL frame; for libvlc's GLES2 video view this typically
        // captures the last presented frame, which is good enough for
        // a 12fps PiP overlay.
        [src drawViewHierarchyInRect:CGRectMake(0, 0, src.bounds.size.width,
                                                src.bounds.size.height)
                  afterScreenUpdates:NO];
        UIGraphicsPopContext();
        CGContextRelease(ctx);
    }

    CVPixelBufferUnlockBaseAddress(pb, 0);

    [self enqueuePixelBuffer:pb];
    CVPixelBufferRelease(pb);

    if (link) self.lastFrameTime = link.timestamp;
}

- (void)enqueuePixelBuffer:(CVPixelBufferRef)pixelBuffer {
    if (!_displayLayer) return;

    if (!_formatDescription
        || CMVideoFormatDescriptionGetDimensions(_formatDescription).width != _lastWidth
        || CMVideoFormatDescriptionGetDimensions(_formatDescription).height != _lastHeight) {
        if (_formatDescription) {
            CFRelease(_formatDescription);
            _formatDescription = NULL;
        }
        OSStatus rc = CMVideoFormatDescriptionCreateForImageBuffer(
            kCFAllocatorDefault, pixelBuffer, &_formatDescription);
        if (rc != noErr) {
            NSLog(@"RCTVLCPiPController: CMVideoFormatDescription create failed: %d", (int)rc);
            return;
        }
    }

    CMSampleTimingInfo timing;
    timing.duration = CMTimeMake(1, kSnapshotFps);
    timing.presentationTimeStamp = CMTimeMakeWithSeconds(CACurrentMediaTime(), 1000);
    timing.decodeTimeStamp = kCMTimeInvalid;

    CMSampleBufferRef sample = NULL;
    OSStatus rc = CMSampleBufferCreateReadyWithImageBuffer(
        kCFAllocatorDefault, pixelBuffer, _formatDescription, &timing, &sample);
    if (rc != noErr || !sample) {
        NSLog(@"RCTVLCPiPController: CMSampleBufferCreate failed: %d", (int)rc);
        return;
    }

    // Mark "display immediately" so the layer doesn't queue
    CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sample, YES);
    if (attachments) {
        CFMutableDictionaryRef dict = (CFMutableDictionaryRef)CFArrayGetValueAtIndex(attachments, 0);
        CFDictionarySetValue(dict, kCMSampleAttachmentKey_DisplayImmediately, kCFBooleanTrue);
    }

    [_displayLayer enqueueSampleBuffer:sample];
    CFRelease(sample);

    if (_displayLayer.status == AVQueuedSampleBufferRenderingStatusFailed) {
        NSLog(@"RCTVLCPiPController: display layer failed: %@",
              _displayLayer.error.localizedDescription);
        [_displayLayer flush];
    }
}

#pragma mark - AVPictureInPictureControllerDelegate

- (void)pictureInPictureControllerDidStartPictureInPicture:(AVPictureInPictureController *)pip
        API_AVAILABLE(ios(15.0)) {
    self.pipActive = YES;
    if ([self.delegate respondsToSelector:@selector(pipControllerDidStartPiP:)]) {
        [self.delegate pipControllerDidStartPiP:self];
    }
}

- (void)pictureInPictureControllerDidStopPictureInPicture:(AVPictureInPictureController *)pip
        API_AVAILABLE(ios(15.0)) {
    self.pipActive = NO;
    [self stopSnapshotLoop];
    if ([self.delegate respondsToSelector:@selector(pipControllerDidStopPiP:)]) {
        [self.delegate pipControllerDidStopPiP:self];
    }
}

- (void)pictureInPictureController:(AVPictureInPictureController *)pip
            failedToStartPictureInPictureWithError:(NSError *)error
        API_AVAILABLE(ios(15.0)) {
    NSLog(@"RCTVLCPiPController: failed to start PiP: %@", error);
    self.pipActive = NO;
    [self stopSnapshotLoop];
}

#pragma mark - AVPictureInPictureSampleBufferPlaybackDelegate

- (void)pictureInPictureController:(AVPictureInPictureController *)pip
                       setPlaying:(BOOL)playing
        API_AVAILABLE(ios(15.0)) {
    self.playing = playing;
    if ([self.delegate respondsToSelector:@selector(pipControllerPlayPauseToggled:playing:)]) {
        [self.delegate pipControllerPlayPauseToggled:self playing:playing];
    }
}

- (CMTimeRange)pictureInPictureControllerTimeRangeForPlayback:
        (AVPictureInPictureController *)pip API_AVAILABLE(ios(15.0)) {
    // Live-stream style: indeterminate timeline. The +/-INFINITY trick tells
    // the PiP overlay to show only Play/Pause without a scrubber/skip controls.
    return CMTimeRangeMake(kCMTimePositiveInfinity, kCMTimeInvalid);
}

- (BOOL)pictureInPictureControllerIsPlaybackPaused:(AVPictureInPictureController *)pip
        API_AVAILABLE(ios(15.0)) {
    return !self.playing;
}

- (void)pictureInPictureController:(AVPictureInPictureController *)pip
                       didTransitionToRenderSize:(CMVideoDimensions)newRenderSize
        API_AVAILABLE(ios(15.0)) {
    // No-op — the snapshot pool will resize on the next tick if dims change.
}

- (void)pictureInPictureController:(AVPictureInPictureController *)pip
                          skipByInterval:(CMTime)skipInterval
                       completionHandler:(void (^)(void))completionHandler
        API_AVAILABLE(ios(15.0)) {
    completionHandler();
}

@end
