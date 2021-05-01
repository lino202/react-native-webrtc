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
import org.webrtc.SurfaceTextureHelper;
import java.util.List;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReadableMap;



import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.hardware.Camera;
import android.util.Base64; 


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;




public class MyCameraCapturer extends CameraCapturer {
    private final boolean captureToTexture;


    static final String TAG = WebRTCModule.class.getCanonicalName();

    //MINE
    private MyCameraSession cameraSession;
    public static final int RCT_CAMERA_CAPTURE_TARGET_MEMORY = 0;
    public static final int RCT_CAMERA_CAPTURE_TARGET_DISK = 1;
    public static final int RCT_CAMERA_CAPTURE_TARGET_CAMERA_ROLL = 2;
    public static final int RCT_CAMERA_CAPTURE_TARGET_TEMP = 3;

    


    public MyCameraCapturer(
            String cameraName, CameraEventsHandler eventsHandler, boolean captureToTexture) {
        super(cameraName, eventsHandler, new Camera1Enumerator(captureToTexture));
        this.captureToTexture = captureToTexture;
    }


    @Override
    protected void createCameraSession(CameraSession.CreateSessionCallback createSessionCallback,
                                       CameraSession.Events events, Context applicationContext,
                                       SurfaceTextureHelper surfaceTextureHelper, String cameraName,
                                       int width, int height, int framerate) {
        
        CameraSession.CreateSessionCallback myCallback = new CameraSession.CreateSessionCallback() {
            @Override
            public void onDone(CameraSession cameraSession) {
                MyCameraCapturer.this.cameraSession = (MyCameraSession) cameraSession;
                createSessionCallback.onDone(cameraSession);
                
                
                if (MyCameraCapturer.this.pictureTaken){
                    try{
                        MyCameraCapturer.this.setZoom(zoomValue);
                        if(MyCameraCapturer.this.flashEnabled){
                            MyCameraCapturer.this.switchFlash(true);
                        }
                        
    
    
                    }catch(final MyCameraCapturer.CameraException e){
                        Log.i("CAMERA EXCEPTION ", e.getMessage() + e.getCause());
                    }
                    MyCameraCapturer.this.pictureTaken = false;

                }




            }

            @Override
            public void onFailure(CameraSession.FailureType failureType, String s) {
                createSessionCallback.onFailure(failureType, s);
            }
        };

        MyCameraSession.create(myCallback, events, captureToTexture, applicationContext, surfaceTextureHelper, Camera1Enumerator.getCameraIndex(cameraName), width, height, framerate);
    }


    public void switchFlash(boolean enable) {
        cameraSession.switchFlash(enable);
    }


    /**
    * Returns true if zoom is supported. Applications should call this
    * before using other zoom methods.
     **/
    public boolean isZoomSupported() throws CameraException {
        synchronized (stateLock) {
            try {
                return ((MyCameraSession) currentSession).isZoomSupported();
            } catch (Exception e) {
                throw new CameraException(e);
            }
        }
    }

     /**
     * Sets current zoom value.
     *
     * @param value zoom value. The valid range is 0 to {@link #getMaxZoom}.
     **/
        public void setZoom(int value) throws CameraException {
            synchronized (stateLock) {
                try {
                    ((MyCameraSession) currentSession).setZoom(value);
                } catch (Exception e) {
                    throw new CameraException(e);
                }
            }
        }

/**
* Gets the maximum zoom value allowed for snapshot. This is the maximum
* value that applications can set to {@link #setZoom(int)}.
* Applications should call {@link #isZoomSupported} before using this
* method. This value may change in different preview size. Applications
* should call this again after setting preview size.
*
* @return the maximum zoom value supported by the camera.
 **/

       public int getZoom() throws CameraException {
           synchronized (stateLock) {
               try {
                   return ((MyCameraSession) currentSession).getZoom();
               } catch (Exception e) {
                   throw new CameraException(e);
               }
           }
       }

    /**
    * Gets the maximum zoom value allowed for snapshot. This is the maximum
    * value that applications can set to {@link #setZoom(int)}.
    * Applications should call {@link #isZoomSupported} before using this
    * method. This value may change in different preview size. Applications
    * should call this again after setting preview size.
    *
    * @return the maximum zoom value supported by the camera.
    **/
    public int getMaxZoom() throws CameraException {
        synchronized (stateLock) {
            try {
                return ((MyCameraSession) currentSession).getMaxZoom();
            } catch (Exception e) {
                throw new CameraException(e);
            }
        }
    }

    /**
    * Gets the zoom ratios of all zoom values. Applications should check
    * {@link #isZoomSupported} before using this method.
    *
    * @return the zoom ratios in 1/100 increments. Ex: a zoom of 3.2x is
    * returned as 320. The number of elements is {@link
    * #getMaxZoom} + 1. The list is sorted from small to large. The
    * first element is always 100. The last element is the zoom
    * ratio of the maximum zoom value.
    **/
    public List<Integer> getZoomRatios() throws CameraException {
        synchronized (stateLock) {
            try {
                return ((MyCameraSession) currentSession).getZoomRatios();
            } catch (Exception e) {
                throw new CameraException(e);
            }
        }
    }

    public class CameraException extends Exception{

        CameraException(Exception e){
            super(e.getMessage(),e.getCause());
        }
    }


    private Map<String, Object> getCaptureTargetConstants() {
        return Collections.unmodifiableMap(new HashMap<String, Object>() {
            {
                put("memory", RCT_CAMERA_CAPTURE_TARGET_MEMORY);
                put("temp", RCT_CAMERA_CAPTURE_TARGET_TEMP);
                put("cameraRoll", RCT_CAMERA_CAPTURE_TARGET_CAMERA_ROLL);
                put("disk", RCT_CAMERA_CAPTURE_TARGET_DISK);
            }
        });
    }

    private int getFrameOrientation(CameraSession cameraSession) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method getFrameOrientation = cameraSession.getClass().getDeclaredMethod("getFrameOrientation");
        getFrameOrientation.setAccessible(true);
        return (Integer) getFrameOrientation.invoke(cameraSession);
    }
        
     
    @SuppressWarnings("deprecation")
    public void takePicture(final ReadableMap options, final Callback successCallback, final Callback errorCallback, int zoomValue, boolean flashEnabled) {

        final String streamId = options.getString("streamId");
        final int captureTarget = options.getInt("captureTarget");
        final double maxJpegQuality = options.getDouble("maxJpegQuality");
        final int maxSize = options.getInt("maxSize");

        this.zoomValue = zoomValue;
        this.flashEnabled = flashEnabled;


        int orientation = -1;
        try {
            orientation = getFrameOrientation(cameraSession);
        } catch (Exception e) {
            Log.d(TAG, "Error getting frame orientation for stream id " + streamId, e);
        }

        final int finalOrientation = orientation;
        this.cameraSession.camera.takePicture(null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(final byte[] jpeg, final Camera camera) {
                //The picture is jpeg and can be send through a datachannel?
                MyCameraCapturer.this.pictureTaken = true;


                if (captureTarget == RCT_CAMERA_CAPTURE_TARGET_MEMORY) {
                    String encoded = Base64.encodeToString(jpeg, Base64.DEFAULT);
                    successCallback.invoke(encoded);
                    
                } else {
                    try {
                        String path = savePicture(jpeg, captureTarget, maxJpegQuality, maxSize, finalOrientation);
                        successCallback.invoke(path);
                    } catch (IOException e) {
                        String message = "Error saving picture";
                        Log.d(TAG, message, e);
                        errorCallback.invoke(message);
                    }
                }

                //This is for resee the stream in the remote and local peers
                restartSession();


            }
        });
    }
        
    
        
    private synchronized String savePicture(byte[] jpeg, int captureTarget, double maxJpegQuality, int maxSize,
                                            int orientation) throws IOException {

        // TODO: check if rotation is needed
        //int rotationAngle = currentFrame.rotationDegree;


        //The pictures is jpeg!!!
        String filename = UUID.randomUUID().toString();
        File file = null;
        switch (captureTarget) {
            case RCT_CAMERA_CAPTURE_TARGET_DISK: {
                file = getOutputMediaFile(filename);
                writePictureToFile(jpeg, file, maxSize, maxJpegQuality, orientation);
                break;
            }
            default:
                Log.e(TAG,"Cam roll and temp cases are not used");
        }

        return Uri.fromFile(file).toString();
    }
        
    private String writePictureToFile(byte[] jpeg, File file, int maxSize, double jpegQuality, int orientation) throws IOException {

        FileOutputStream output = new FileOutputStream(file);
        output.write(jpeg);
        output.close();

        Matrix matrix = new Matrix();
        Log.d(TAG, "orientation " + orientation);
        if (orientation != 0) {
            matrix.postRotate(orientation);
        }

        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());

        // scale if needed
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // only resize if image larger than maxSize
        if (width > maxSize && width > maxSize) {
            Rect originalRect = new Rect(0, 0, width, height);
            Rect scaledRect = scaleDimension(originalRect, maxSize);

            Log.d(TAG, "scaled width = " + scaledRect.width() + ", scaled height = " + scaledRect.height());

            // calculate the scale
            float scaleWidth = ((float) scaledRect.width()) / width;
            float scaleHeight = ((float) scaledRect.height()) / height;

            matrix.postScale(scaleWidth, scaleHeight);
        }

        FileOutputStream finalOutput = new FileOutputStream(file, false);

        int compression = (int) (100 * jpegQuality);
        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, compression, finalOutput);

        finalOutput.close();

        return file.getAbsolutePath();
    }

    private File getOutputMediaFile(String fileName) {
        // Get environment directory type id from requested media type.
        String environmentDirectoryType;
        environmentDirectoryType = Environment.DIRECTORY_PICTURES;

        return getOutputFile(
                fileName + ".jpeg",
                Environment.getExternalStoragePublicDirectory(environmentDirectoryType)
        );
    }

    private File getOutputFile(String fileName, File storageDir) {
        // Create the storage directory if it does not exist
        if (!storageDir.exists()) {
            if (!storageDir.mkdirs()) {
                Log.e(TAG, "failed to create directory:" + storageDir.getAbsolutePath());
                return null;
            }
        }

        return new File(String.format("%s%s%s", storageDir.getPath(), File.separator, fileName));
    }

    private static Rect scaleDimension(Rect originalRect, int maxSize) {

        int originalWidth = originalRect.width();
        int originalHeight = originalRect.height();
        int newWidth = originalWidth;
        int newHeight = originalHeight;

        // first check if we need to scale width
        if (originalWidth > maxSize) {
            //scale width to fit
            newWidth = maxSize;
            //scale height to maintain aspect ratio
            newHeight = (newWidth * originalHeight) / originalWidth;
        }

        // then check if we need to scale even with the new height
        if (newHeight > maxSize) {
            //scale height to fit instead
            newHeight = maxSize;
            //scale width to maintain aspect ratio
            newWidth = (newHeight * originalWidth) / originalHeight;
        }

        return new Rect(0, 0, newWidth, newHeight);
    }




}
