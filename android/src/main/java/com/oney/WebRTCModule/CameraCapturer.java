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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import org.webrtc.CameraEnumerator;
import org.webrtc.VideoFrame;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.CameraVideoCapturer;

@SuppressWarnings("deprecation")
abstract class CameraCapturer implements CameraVideoCapturer {
    enum SwitchState {
        IDLE, // No switch requested.
        PENDING, // Waiting for previous capture session to open.
        IN_PROGRESS, // Waiting for new switched capture session to start.
    }
    private static final String TAG = "CameraCapturer";
    private final static int MAX_OPEN_CAMERA_ATTEMPTS = 3;
    private final static int OPEN_CAMERA_DELAY_MS = 500;
    private final static int OPEN_CAMERA_TIMEOUT = 10000;
    private final CameraEnumerator cameraEnumerator;
    private final CameraEventsHandler eventsHandler;
    private final Handler uiThreadHandler;

    //MINE
    protected int zoomValue = 0;
    protected boolean flashEnabled = false;
    protected boolean pictureTaken = false;
    
    private final CameraSession.CreateSessionCallback createSessionCallback =
            new CameraSession.CreateSessionCallback() {
                @Override
                public void onDone(CameraSession session) {
                    checkIsOnCameraThread();
                    Log.d(TAG, "Create session done. Switch state: " + switchState);
                    uiThreadHandler.removeCallbacks(openCameraTimeoutRunnable);
                    synchronized (stateLock) {
                        capturerObserver.onCapturerStarted(true /* success */);
                        sessionOpening = false;
                        currentSession = session;
                        cameraStatistics = new CameraStatistics(surfaceHelper, eventsHandler);
                        firstFrameObserved = false;
                        stateLock.notifyAll();
                        if (switchState == SwitchState.IN_PROGRESS) {
                            switchState = SwitchState.IDLE;
                            if (switchEventsHandler != null) {
                                switchEventsHandler.onCameraSwitchDone(cameraEnumerator.isFrontFacing(cameraName));
                                switchEventsHandler = null;
                            }
                        } else if (switchState == SwitchState.PENDING) {
                            String selectedCameraName = pendingCameraName;
                            pendingCameraName = null;
                            switchState = SwitchState.IDLE;
                            switchCameraInternal(switchEventsHandler, selectedCameraName);
                        }
                    }
                }
                @Override
                public void onFailure(CameraSession.FailureType failureType, String error) {
                    checkIsOnCameraThread();
                    uiThreadHandler.removeCallbacks(openCameraTimeoutRunnable);
                    synchronized (stateLock) {
                        capturerObserver.onCapturerStarted(false /* success */);
                        openAttemptsRemaining--;
                        if (openAttemptsRemaining <= 0) {
                            Log.w(TAG, "Opening camera failed, passing: " + error);
                            sessionOpening = false;
                            stateLock.notifyAll();
                            if (switchState != SwitchState.IDLE) {
                                if (switchEventsHandler != null) {
                                    switchEventsHandler.onCameraSwitchError(error);
                                    switchEventsHandler = null;
                                }
                                switchState = SwitchState.IDLE;
                            }
                            if (failureType == CameraSession.FailureType.DISCONNECTED) {
                                eventsHandler.onCameraDisconnected();
                            } else {
                                eventsHandler.onCameraError(error);
                            }
                        } else {
                            Log.w(TAG, "Opening camera failed, retry: " + error);
                            createSessionInternal(OPEN_CAMERA_DELAY_MS);
                        }
                    }
                }
            };
    
    private final CameraSession.Events cameraSessionEventsHandler = new CameraSession.Events() {
        @Override
        public void onCameraOpening() {
            checkIsOnCameraThread();
            synchronized (stateLock) {
                if (currentSession != null) {
                    Log.w(TAG, "onCameraOpening while session was open.");
                    return;
                }
                eventsHandler.onCameraOpening(cameraName);
            }
        }
        @Override
        public void onCameraError(CameraSession session, String error) {
            checkIsOnCameraThread();
            synchronized (stateLock) {
                if (session != currentSession) {
                    Log.w(TAG, "onCameraError from another session: " + error);
                    return;
                }
                eventsHandler.onCameraError(error);
                stopCapture();
            }
        }
        @Override
        public void onCameraDisconnected(CameraSession session) {
            checkIsOnCameraThread();
            synchronized (stateLock) {
                if (session != currentSession) {
                    Log.w(TAG, "onCameraDisconnected from another session.");
                    return;
                }
                eventsHandler.onCameraDisconnected();
                stopCapture();
            }
        }
        @Override
        public void onCameraClosed(CameraSession session) {
            checkIsOnCameraThread();
            synchronized (stateLock) {
                if (session != currentSession && currentSession != null) {
                    Log.d(TAG, "onCameraClosed from another session.");
                    return;
                }
                eventsHandler.onCameraClosed();
            }
        }
        @Override
        public void onFrameCaptured(CameraSession session, VideoFrame frame) {
            checkIsOnCameraThread();
            synchronized (stateLock) {
                if (session != currentSession) {
                    Log.w(TAG, "onFrameCaptured from another session.");
                    return;
                }
                if (!firstFrameObserved) {
                    eventsHandler.onFirstFrameAvailable();
                    firstFrameObserved = true;
                }
                cameraStatistics.addFrame();
                capturerObserver.onFrameCaptured(frame);
            }
        }
    };
    private final Runnable openCameraTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            eventsHandler.onCameraError("Camera failed to start within timeout.");
        }
    };
    // Initialized on initialize
    // -------------------------
    //CAMBIE STATELOCK y CURRENTSESSION a PROTECTED
    private Handler cameraThreadHandler;
    private Context applicationContext;
    private org.webrtc.CapturerObserver capturerObserver;
    private SurfaceTextureHelper surfaceHelper;
    protected final Object stateLock = new Object();
    private boolean sessionOpening; /* guarded by stateLock */
    protected CameraSession currentSession; /* guarded by stateLock */
    private String cameraName; /* guarded by stateLock */
    private String pendingCameraName; /* guarded by stateLock */
    private int width; /* guarded by stateLock */
    private int height; /* guarded by stateLock */
    private int framerate; /* guarded by stateLock */
    private int openAttemptsRemaining; /* guarded by stateLock */
    private SwitchState switchState = SwitchState.IDLE; /* guarded by stateLock */
     private CameraSwitchHandler switchEventsHandler; /* guarded by stateLock */
    // Valid from onDone call until stopCapture, otherwise null.
    private CameraStatistics cameraStatistics; /* guarded by stateLock */
    private boolean firstFrameObserved; /* guarded by stateLock */
    public CameraCapturer(String cameraName,  CameraEventsHandler eventsHandler,
                          CameraEnumerator cameraEnumerator) {
        if (eventsHandler == null) {
            eventsHandler = new CameraEventsHandler() {
                @Override
                public void onCameraError(String errorDescription) {}
                @Override
                public void onCameraDisconnected() {}
                @Override
                public void onCameraFreezed(String errorDescription) {}
                @Override
                public void onCameraOpening(String cameraName) {}
                @Override
                public void onFirstFrameAvailable() {}
                @Override
                public void onCameraClosed() {}
            };
        }
        this.eventsHandler = eventsHandler;
        this.cameraEnumerator = cameraEnumerator;
        this.cameraName = cameraName;
        List<String> deviceNames = Arrays.asList(cameraEnumerator.getDeviceNames());
        uiThreadHandler = new Handler(Looper.getMainLooper());
        if (deviceNames.isEmpty()) {
            throw new RuntimeException("No cameras attached.");
        }
        if (!deviceNames.contains(this.cameraName)) {
            throw new IllegalArgumentException(
                    "Camera name " + this.cameraName + " does not match any known camera device.");
        }
    }
    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext,
                           org.webrtc.CapturerObserver capturerObserver) {
        this.applicationContext = applicationContext;
        this.capturerObserver = capturerObserver;
        this.surfaceHelper = surfaceTextureHelper;
        this.cameraThreadHandler = surfaceTextureHelper.getHandler();
    }
    @Override
    public void startCapture(int width, int height, int framerate) {
        Log.d(TAG, "startCapture: " + width + "x" + height + "@" + framerate);
        if (applicationContext == null) {
            throw new RuntimeException("CameraCapturer must be initialized before calling startCapture.");
        }
        synchronized (stateLock) {
            if (sessionOpening || currentSession != null) {
                Log.w(TAG, "Session already open");
                return;
            }
            this.width = width;
            this.height = height;
            this.framerate = framerate;
            sessionOpening = true;
            openAttemptsRemaining = MAX_OPEN_CAMERA_ATTEMPTS;
            createSessionInternal(0);
        }
    }
    private void createSessionInternal(int delayMs) {
        uiThreadHandler.postDelayed(openCameraTimeoutRunnable, delayMs + OPEN_CAMERA_TIMEOUT);
        cameraThreadHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                createCameraSession(createSessionCallback, cameraSessionEventsHandler, applicationContext,
                        surfaceHelper, cameraName, width, height, framerate);
            }
        }, delayMs);
    }
    @Override
    public void stopCapture() {
        Log.d(TAG, "Stop capture");
        synchronized (stateLock) {
            while (sessionOpening) {
                Log.d(TAG, "Stop capture: Waiting for session to open");
                try {
                    stateLock.wait();
                } catch (InterruptedException e) {
                    Log.w(TAG, "Stop capture interrupted while waiting for the session to open.");
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (currentSession != null) {
                Log.d(TAG, "Stop capture: Nulling session");
                cameraStatistics.release();
                cameraStatistics = null;
                final CameraSession oldSession = currentSession;
                cameraThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        oldSession.stop();
                    }
                });
                currentSession = null;
                capturerObserver.onCapturerStopped();
            } else {
                Log.d(TAG, "Stop capture: No session open");
            }
        }
        Log.d(TAG, "Stop capture done");
    }
    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        Log.d(TAG, "changeCaptureFormat: " + width + "x" + height + "@" + framerate);
        synchronized (stateLock) {
            stopCapture();
            startCapture(width, height, framerate);
        }
    }
    @Override
    public void dispose() {
        Log.d(TAG, "dispose");
        stopCapture();
    }
    @Override
    public void switchCamera(final CameraSwitchHandler switchEventsHandler) {
        Log.d(TAG, "switchCamera");
        cameraThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                List<String> deviceNames = Arrays.asList(cameraEnumerator.getDeviceNames());
                if (deviceNames.size() < 2) {
                    reportCameraSwitchError("No camera to switch to.", switchEventsHandler);
                    return;
                }
                int cameraNameIndex = deviceNames.indexOf(cameraName);
                String cameraName = deviceNames.get((cameraNameIndex + 1) % deviceNames.size());
                switchCameraInternal(switchEventsHandler, cameraName);
            }
        });
    }


    public void switchCamera(final CameraSwitchHandler switchEventsHandler, final String cameraName) {
        Log.d(TAG, "switchCamera");
        cameraThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                switchCameraInternal(switchEventsHandler, cameraName);
            }
        });
    }
    
    @Override
    public boolean isScreencast() {
        return false;
    }
    public void printStackTrace() {
        Thread cameraThread = null;
        if (cameraThreadHandler != null) {
            cameraThread = cameraThreadHandler.getLooper().getThread();
        }
        if (cameraThread != null) {
            StackTraceElement[] cameraStackTrace = cameraThread.getStackTrace();
            if (cameraStackTrace.length > 0) {
                Log.d(TAG, "CameraCapturer stack trace:");
                for (StackTraceElement traceElem : cameraStackTrace) {
                    Log.d(TAG, traceElem.toString());
                }
            }
        }
    }
    private void reportCameraSwitchError(
            String error,  CameraSwitchHandler switchEventsHandler) {
        Log.e(TAG, error);
        if (switchEventsHandler != null) {
            switchEventsHandler.onCameraSwitchError(error);
        }
    }
    private void switchCameraInternal(
             final CameraSwitchHandler switchEventsHandler, final String selectedCameraName) {
        Log.d(TAG, "switchCamera internal");
        List<String> deviceNames = Arrays.asList(cameraEnumerator.getDeviceNames());
        if (!deviceNames.contains(selectedCameraName)) {
            reportCameraSwitchError("Attempted to switch to unknown camera device " + selectedCameraName,
                    switchEventsHandler);
            return;
        }
        synchronized (stateLock) {
            if (switchState != SwitchState.IDLE) {
                reportCameraSwitchError("Camera switch already in progress.", switchEventsHandler);
                return;
            }
            if (!sessionOpening && currentSession == null) {
                reportCameraSwitchError("switchCamera: camera is not running.", switchEventsHandler);
                return;
            }
            this.switchEventsHandler = switchEventsHandler;
            if (sessionOpening) {
                switchState = SwitchState.PENDING;
                pendingCameraName = selectedCameraName;
                return;
            } else {
                switchState = SwitchState.IN_PROGRESS;
            }
            Log.d(TAG, "switchCamera: Stopping session");
            cameraStatistics.release();
            cameraStatistics = null;
            final CameraSession oldSession = currentSession;
            cameraThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    oldSession.stop();
                }
            });
            currentSession = null;
            cameraName = selectedCameraName;
            sessionOpening = true;
            openAttemptsRemaining = 1;
            createSessionInternal(0);
        }
        Log.d(TAG, "switchCamera done");
    }
    private void checkIsOnCameraThread() {
        if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
            Log.e(TAG, "Check is on camera thread failed.");
            throw new RuntimeException("Not on camera thread.");
        }
    }
    protected String getCameraName() {
        synchronized (stateLock) {
            return cameraName;
        }
    }
    abstract protected void createCameraSession(
            CameraSession.CreateSessionCallback createSessionCallback, CameraSession.Events events,
            Context applicationContext, SurfaceTextureHelper surfaceTextureHelper, String cameraName,
            int width, int height, int framerate);


    //MINE
    //This is called when a phot has been taken in order to restart the camera session
    protected void restartSession(){

        Log.d(TAG, "Take Photo: Stopping session Trying to reactivate the stream");
        cameraStatistics.release();
        cameraStatistics = null;
        final CameraSession oldSession = currentSession;
        cameraThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                oldSession.stop();
            }
        });
        currentSession = null;
        sessionOpening = true;
        openAttemptsRemaining = 1;

        createSessionInternal(0);
    }

}