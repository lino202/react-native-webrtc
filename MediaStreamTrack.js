'use strict';

import {NativeModules} from 'react-native';
import EventTarget from 'event-target-shim';
import MediaStreamErrorEvent from './MediaStreamErrorEvent';
import type MediaStreamError from './MediaStreamError';
import { deepClone } from './RTCUtil';

const {WebRTCModule} = NativeModules;

const MEDIA_STREAM_TRACK_EVENTS = [
  'ended',
  'mute',
  'unmute',
  // see: https://www.w3.org/TR/mediacapture-streams/#constrainable-interface
  'overconstrained',
];

type MediaStreamTrackState = "live" | "ended";


//MINE
type SnapshotOptions = {
  maxSize: number,
  maxJpegQuality: number,
};
  
function convertToNativeOptions(options){
  let mutableDefaults = {};
  mutableDefaults.maxSize = MediaStreamTrack.defaults.maxSize;
  mutableDefaults.maxJpegQuality = MediaStreamTrack.defaults.maxJpegQuality;

  const mergedOptions = Object.assign(mutableDefaults, options);

  if (typeof mergedOptions.captureTarget === 'string') {
    mergedOptions.captureTarget = WebRTCModule.CaptureTarget[options.captureTarget];
  }

  return mergedOptions;
}




class MediaStreamTrack extends EventTarget(MEDIA_STREAM_TRACK_EVENTS) {
  _constraints: Object;
  _enabled: boolean;
  id: string;
  kind: string;
  label: string;
  muted: boolean;
  // readyState in java: INITIALIZING, LIVE, ENDED, FAILED
  readyState: MediaStreamTrackState;
  remote: boolean;

  onended: ?Function;
  onmute: ?Function;
  onunmute: ?Function;
  overconstrained: ?Function;

  //MINE
  static constants = {
    captureTarget: {
      memory: 'memory',
      temp: 'temp',
      disk: 'disk',
      cameraRoll: 'cameraRoll'
    }
  };
    
  static defaults = {
    captureTarget: MediaStreamTrack.constants.captureTarget.temp,
    maxSize: 2000,
    maxJpegQuality: 1
  };

  constructor(info) {
    super();

    this._constraints = info.constraints || {};
    this._enabled = info.enabled;
    this.id = info.id;
    this.kind = info.kind;
    this.label = info.label;
    this.muted = false;
    this.remote = info.remote;

    const _readyState = info.readyState.toLowerCase();
    this.readyState = (_readyState === "initializing"
                    || _readyState === "live") ? "live" : "ended";
  }

  get enabled(): boolean {
    return this._enabled;
  }

  set enabled(enabled: boolean): void {
    if (enabled === this._enabled) {
      return;
    }
    WebRTCModule.mediaStreamTrackSetEnabled(this.id, !this._enabled);
    this._enabled = !this._enabled;
    this.muted = !this._enabled;
  }

  stop() {
    WebRTCModule.mediaStreamTrackSetEnabled(this.id, false);
    this.readyState = 'ended';
    // TODO: save some stopped flag?
  }

  /**
   * Private / custom API for switching the cameras on the fly, without the
   * need for adding / removing tracks or doing any SDP renegotiation.
   *
   * This is how the reference application (AppRTCMobile) implements camera
   * switching.
   */
  _switchCamera() {
    if (this.remote) {
      throw new Error('Not implemented for remote tracks');
    }
    if (this.kind !== 'video') {
      throw new Error('Only implemented for video tracks');
    }
    WebRTCModule.mediaStreamTrackSwitchCamera(this.id);
  }

  applyConstraints() {
    throw new Error('Not implemented.');
  }

  clone() {
    throw new Error('Not implemented.');
  }

  getCapabilities() {
    throw new Error('Not implemented.');
  }

  getConstraints() {
    return deepClone(this._constraints);
  }

  getSettings() {
    throw new Error('Not implemented.');
  }

  release() {
    WebRTCModule.mediaStreamTrackRelease(this.id);
  }

  setZoom(percentage){
    if (this.kind !== 'video') {
      throw new Error('Only implemented for video tracks');
    }
    WebRTCModule.mediaStreamTrackZoom(this.id, percentage);
  }

  switchFlash(){
    if (this.kind !== 'video') {
      throw new Error('Only implemented for video tracks');
    }
    WebRTCModule.mediaStreamTrackFlash(this.id);
  }

  // TODO: restrict option values resp. document them
  takePicture(options: SnapshotOptions, success: (any) => {}, error: (any) => {}) {
    let nativeOptions = convertToNativeOptions(options);
    WebRTCModule.mediaStreamTrackTakePhoto(nativeOptions, success, error);
  }


  
}

export default MediaStreamTrack;
