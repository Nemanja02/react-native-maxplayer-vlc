import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { RNVLCPiP } = NativeModules;

const pipEmitter = RNVLCPiP ? new NativeEventEmitter(RNVLCPiP) : null;

// Picture-in-Picture API — Android uses dedicated activity flow, iOS uses
// AVSampleBufferDisplayLayer + AVPictureInPictureController (iOS 15+).
// Both platforms emit the same events: 'RNVLCPiPModeChanged' and
// 'RNVLCPiPAction'.
const VLCPiP = {
  isSupported() {
    if (!RNVLCPiP) return Promise.resolve(false);
    return RNVLCPiP.isSupported();
  },

  enter(options = {}) {
    if (!RNVLCPiP) return Promise.resolve(false);
    return RNVLCPiP.enter(options);
  },

  setParams(options = {}) {
    if (!RNVLCPiP) return Promise.resolve();
    return RNVLCPiP.setParams(options);
  },

  // Android: API 31+ only. iOS: best-effort, system gates auto-PiP for
  // sample-buffer sources.
  setAutoEnterEnabled(enabled, options = {}) {
    if (!RNVLCPiP) return Promise.resolve(false);
    return RNVLCPiP.setAutoEnterEnabled(!!enabled, options);
  },

  addListener(callback) {
    if (!pipEmitter) return { remove() {} };
    return pipEmitter.addListener('RNVLCPiPModeChanged', callback);
  },

  // Fires when user taps a Play/Pause overlay button in the PiP window.
  // Event shape: { action: 'playPause' }
  addActionListener(callback) {
    if (!pipEmitter) return { remove() {} };
    return pipEmitter.addListener('RNVLCPiPAction', callback);
  },
};

const VLCPlayerControl = {
  VLCPlayer: require('./VLCPlayer').default,
  VLCPiP,
};

module.exports = VLCPlayerControl;
