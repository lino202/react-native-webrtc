/*
 *  Copyright 2016 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package com.oney.WebRTCModule;

import android.content.Context;
import android.hardware.Camera;
import android.content.pm.PackageManager; 
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat;
import org.webrtc.NV21Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.CameraEnumerationAndroid;
import org.webrtc.VideoFrame;
import org.webrtc.TextureBufferImpl;
import org.webrtc.Size;

@SuppressWarnings("deprecation")
class MyCameraSession implements CameraSession {
    private static final String TAG = "MyCameraSession";
    private static final int NUMBER_OF_CAPTURE_BUFFERS = 3;

    private static enum SessionState { RUNNING, STOPPED }
    private final Handler cameraThreadHandler;
    private final Events events;
    private final boolean captureToTexture;
    private final Context applicationContext;
    private final SurfaceTextureHelper surfaceTextureHelper;
    private final int cameraId;
    protected final android.hardware.Camera camera;
    private final android.hardware.Camera.CameraInfo info;
    private final CaptureFormat captureFormat;
    // Used only for stats. Only used on the camera thread.
    private final long constructionTimeNs; // Construction time of this class.
    private SessionState state;
    private boolean firstFrameReported;
    // TODO(titovartem) make correct fix during webrtc:9175
    @SuppressWarnings("ByteBufferBackingArray")
    public static void create(final CreateSessionCallback callback, final Events events,
                              final boolean captureToTexture, final Context applicationContext,
                              final SurfaceTextureHelper surfaceTextureHelper, final int cameraId, final int width,
                              final int height, final int framerate) {
        final long constructionTimeNs = System.nanoTime();
        Log.d(TAG, "Open camera " + cameraId);
        events.onCameraOpening();
        final android.hardware.Camera camera;
        try {
            camera = android.hardware.Camera.open(cameraId);
        } catch (RuntimeException e) {
            callback.onFailure(FailureType.ERROR, e.getMessage());
            return;
        }
        if (camera == null) {
            callback.onFailure(FailureType.ERROR,
                    "android.hardware.Camera.open returned null for camera id = " + cameraId);
            return;
        }
        try {
            camera.setPreviewTexture(surfaceTextureHelper.getSurfaceTexture());
        } catch (IOException | RuntimeException e) {
            camera.release();
            callback.onFailure(FailureType.ERROR, e.getMessage());
            return;
        }
        final android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(cameraId, info);
        final CaptureFormat captureFormat;
        try {
            final android.hardware.Camera.Parameters parameters = camera.getParameters();
            captureFormat = findClosestCaptureFormat(parameters, width, height, framerate);
            final Size pictureSize = findClosestPictureSize(parameters, width, height);
            updateCameraParameters(camera, parameters, captureFormat, pictureSize, captureToTexture);
        } catch (RuntimeException e) {
            camera.release();
            callback.onFailure(FailureType.ERROR, e.getMessage());
            return;
        }
        if (!captureToTexture) {
            final int frameSize = captureFormat.frameSize();
            for (int i = 0; i < NUMBER_OF_CAPTURE_BUFFERS; ++i) {
                final ByteBuffer buffer = ByteBuffer.allocateDirect(frameSize);
                camera.addCallbackBuffer(buffer.array());
            }
        }
        // Calculate orientation manually and send it as CVO insted.
        camera.setDisplayOrientation(0 /* degrees */);
        callback.onDone(new MyCameraSession(events, captureToTexture, applicationContext,
                surfaceTextureHelper, cameraId, camera, info, captureFormat, constructionTimeNs));
    }
    private static void updateCameraParameters(android.hardware.Camera camera,
                                               android.hardware.Camera.Parameters parameters, CaptureFormat captureFormat, Size pictureSize,
                                               boolean captureToTexture) {
        final List<String> focusModes = parameters.getSupportedFocusModes();
        parameters.setPreviewFpsRange(captureFormat.framerate.min, captureFormat.framerate.max);
        parameters.setPreviewSize(captureFormat.width, captureFormat.height);
        parameters.setPictureSize(pictureSize.width, pictureSize.height);
        if (!captureToTexture) {
            parameters.setPreviewFormat(captureFormat.imageFormat);
        }
        if (parameters.isVideoStabilizationSupported()) {
            parameters.setVideoStabilization(true);
        }
        if (focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }
        camera.setParameters(parameters);
    }
    private static CaptureFormat findClosestCaptureFormat(
            android.hardware.Camera.Parameters parameters, int width, int height, int framerate) {
        // Find closest supported format for |width| x |height| @ |framerate|.
        final List<CaptureFormat.FramerateRange> supportedFramerates =
                Camera1Enumerator.convertFramerates(parameters.getSupportedPreviewFpsRange());
        Log.d(TAG, "Available fps ranges: " + supportedFramerates);
        final CaptureFormat.FramerateRange fpsRange =
                CameraEnumerationAndroid.getClosestSupportedFramerateRange(supportedFramerates, framerate);
        final Size previewSize = CameraEnumerationAndroid.getClosestSupportedSize(
                Camera1Enumerator.convertSizes(parameters.getSupportedPreviewSizes()), width, height);
        //CameraEnumerationAndroid.reportCameraResolution(camera1ResolutionHistogram, previewSize);
        return new CaptureFormat(previewSize.width, previewSize.height, fpsRange);
    }
    private static Size findClosestPictureSize(
            android.hardware.Camera.Parameters parameters, int width, int height) {
        return CameraEnumerationAndroid.getClosestSupportedSize(
                Camera1Enumerator.convertSizes(parameters.getSupportedPictureSizes()), width, height);
    }
    private MyCameraSession(Events events, boolean captureToTexture, Context applicationContext,
                           SurfaceTextureHelper surfaceTextureHelper, int cameraId, android.hardware.Camera camera,
                           android.hardware.Camera.CameraInfo info, CaptureFormat captureFormat,
                           long constructionTimeNs) {
        Log.d(TAG, "Create new MyCameraSession on camera " + cameraId);
        this.cameraThreadHandler = new Handler();
        this.events = events;
        this.captureToTexture = captureToTexture;
        this.applicationContext = applicationContext;
        this.surfaceTextureHelper = surfaceTextureHelper;
        this.cameraId = cameraId;
        this.camera = camera;
        this.info = info;
        this.captureFormat = captureFormat;
        this.constructionTimeNs = constructionTimeNs;
        surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);
        startCapturing();
    }
    @Override
    public void stop() {
        Log.d(TAG, "Stop MyCameraSession on camera " + cameraId);
        checkIsOnCameraThread();
        if (state != SessionState.STOPPED) {
            final long stopStartTime = System.nanoTime();
            stopInternal();
            final int stopTimeMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
            //camera1StopTimeMsHistogram.addSample(stopTimeMs);
        }
    }
    private void startCapturing() {
        Log.d(TAG, "Start capturing");
        checkIsOnCameraThread();
        state = SessionState.RUNNING;
        camera.setErrorCallback(new android.hardware.Camera.ErrorCallback() {
            @Override
            public void onError(int error, android.hardware.Camera camera) {
                String errorMessage;
                if (error == android.hardware.Camera.CAMERA_ERROR_SERVER_DIED) {
                    errorMessage = "Camera server died!";
                } else {
                    errorMessage = "Camera error: " + error;
                }
                Log.e(TAG, errorMessage);
                stopInternal();
                if (error == android.hardware.Camera.CAMERA_ERROR_EVICTED) {
                    events.onCameraDisconnected(MyCameraSession.this);
                } else {
                    events.onCameraError(MyCameraSession.this, errorMessage);
                }
            }
        });
        if (captureToTexture) {
            listenForTextureFrames();
        } else {
            listenForBytebufferFrames();
        }
        try {
            camera.startPreview();
        } catch (RuntimeException e) {
            stopInternal();
            events.onCameraError(this, e.getMessage());
        }
    }
    private void stopInternal() {
        Log.d(TAG, "Stop internal");
        checkIsOnCameraThread();
        if (state == SessionState.STOPPED) {
            Log.d(TAG, "Camera is already stopped");
            return;
        }
        state = SessionState.STOPPED;
        surfaceTextureHelper.stopListening();
        // Note: stopPreview or other driver code might deadlock. Deadlock in
        // android.hardware.Camera._stopPreview(Native Method) has been observed on
        // Nexus 5 (hammerhead), OS version LMY48I.
        camera.stopPreview();
        camera.release();
        events.onCameraClosed(this);
        Log.d(TAG, "Stop done");
    }
    private void listenForTextureFrames() {
        surfaceTextureHelper.startListening((VideoFrame frame) -> {
            checkIsOnCameraThread();
            if (state != SessionState.RUNNING) {
                Log.d(TAG, "Texture frame captured but camera is no longer running.");
                return;
            }
            if (!firstFrameReported) {
                final int startTimeMs =
                        (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
                //camera1StartTimeMsHistogram.addSample(startTimeMs);
                firstFrameReported = true;
            }
            // Undo the mirror that the OS "helps" us with.
            // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
            final VideoFrame modifiedFrame = new VideoFrame(
                    CameraSession.createTextureBufferWithModifiedTransformMatrix(
                            (TextureBufferImpl) frame.getBuffer(),
                            /* mirror= */ info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT,
                            /* rotation= */ 0),
                    /* rotation= */ getFrameOrientation(), frame.getTimestampNs());
            events.onFrameCaptured(MyCameraSession.this, modifiedFrame);
            modifiedFrame.release();
        });
    }
    private void listenForBytebufferFrames() {
        camera.setPreviewCallbackWithBuffer(new android.hardware.Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(final byte[] data, android.hardware.Camera callbackCamera) {
                checkIsOnCameraThread();
                if (callbackCamera != camera) {
                    Log.e(TAG, "Callback from a different camera. This should never happen.");
                    return;
                }
                if (state != SessionState.RUNNING) {
                    Log.d(TAG, "Bytebuffer frame captured but camera is no longer running.");
                    return;
                }
                final long captureTimeNs = TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime());
                if (!firstFrameReported) {
                    final int startTimeMs =
                            (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
                    //camera1StartTimeMsHistogram.addSample(startTimeMs);
                    firstFrameReported = true;
                }
                VideoFrame.Buffer frameBuffer = new NV21Buffer(
                        data, captureFormat.width, captureFormat.height, () -> cameraThreadHandler.post(() -> {
                    if (state == SessionState.RUNNING) {
                        camera.addCallbackBuffer(data);
                    }
                }));
                final VideoFrame frame = new VideoFrame(frameBuffer, getFrameOrientation(), captureTimeNs);
                events.onFrameCaptured(MyCameraSession.this, frame);
                frame.release();
            }
        });
    }
    private int getFrameOrientation() {
        int rotation = CameraSession.getDeviceOrientation(applicationContext);
        if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK) {
            rotation = 360 - rotation;
        }
        return (info.orientation + rotation) % 360;
    }
    private void checkIsOnCameraThread() {
        if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
            throw new IllegalStateException("Wrong thread");
        }
    }





    //MINE
    void switchFlash(boolean isActive) {
        if(!this.applicationContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)){
            Log.e(TAG, "The device does not have a FlashLight");
            return;
        }
        Camera.Parameters params = camera.getParameters();

        if (isActive) {
            params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
        } else {
            params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        }

        camera.setParameters(params);
    }


    public boolean isZoomSupported() {
        return camera.getParameters().isZoomSupported();
    }

    void setZoom(int value){
      Camera.Parameters parameters = camera.getParameters();
      parameters.setZoom(value);
      camera.setParameters(parameters);
    }

    int getZoom(){
        return camera.getParameters().getZoom();
    }

    int getMaxZoom(){
        return camera.getParameters().getMaxZoom();
    }

    List<Integer> getZoomRatios(){
        return camera.getParameters().getZoomRatios();
    }


}
