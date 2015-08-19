/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(60);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    public static final String SUNSHINE_DATA_PATH = "/sunshine_data";
    public static final String SUNSHINE_DATA_HIGH = "sunshine_high";
    public static final String SUNSHINE_DATA_LOW = "sunshine_low";
    public static final String SUNSHINE_DATA_IMG = "sunshine_img";

    public static final String TAG = MyWatchFace.class.getSimpleName();

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine  implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{


        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mWeatherBackgroundPaint;
        Paint mTimeBackgroundPaint;
        Paint mTimeTextPaint;
        Paint mDateTextPaint;
        Paint mHighTempTextPaint;
        Paint mLowTempTextPaint;

        boolean mAmbient;

        Time mTime;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM d");

        Bitmap mBackgroundScaledBitmap;
        Bitmap mSunshineImage;

        String mSunshineHighTemp;
        String mSunshineLowTemp;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mWeatherBackgroundPaint = new Paint();
            mWeatherBackgroundPaint.setColor(resources.getColor(R.color.digital_background));

            mTimeBackgroundPaint = new Paint();
            mTimeBackgroundPaint.setColor(Color.WHITE);

            mTimeTextPaint = createTimeTextPaint();
            mDateTextPaint = createDateTextPaint();
            mHighTempTextPaint = createHighTempTextPaint();
            mLowTempTextPaint = createLowTempTextPaint();

            //initFakeWeatherInfo();

            mTime = new Time();

            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {

            if (null != mGoogleApiClient && mGoogleApiClient.isConnected()) {
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }

            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private void initFakeWeatherInfo(){
            mSunshineImage = BitmapFactory.decodeResource(getResources(), R.drawable.art_clear);
            mBackgroundScaledBitmap = Bitmap.createScaledBitmap(mSunshineImage, 80, 80, true);
            mSunshineHighTemp = "90\u00B0";
            mSunshineLowTemp = "70\u00B0";
        }


        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createTimeTextPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setTextSize(80f);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createDateTextPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.DKGRAY);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setTextSize(40f);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createHighTempTextPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.WHITE);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setTextSize(50f);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createLowTempTextPaint() {
            Paint paint = new Paint();
            paint.setColor(Color.LTGRAY);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setTextSize(40f);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            //mTimeTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimeTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the time.
            //start with background
            canvas.drawRect(0, 0, bounds.width(), 200, mTimeBackgroundPaint);
            //then time
            mTime.setToNow();
            String time = String.format("%d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(time, 50, 125, mTimeTextPaint);
            //then date
            String date = sdf.format(new Date());
            canvas.drawText(date, 30, 190, mDateTextPaint);


            //Draw weather info
            //background
            canvas.drawRect(0, 200, bounds.width(), bounds.height(), mWeatherBackgroundPaint);
            //weather icon
            if (!mAmbient && mBackgroundScaledBitmap != null)
                canvas.drawBitmap(mBackgroundScaledBitmap, 40, 201, null);

            //high temp
            if (mSunshineHighTemp != null)
                canvas.drawText(mSunshineHighTemp, 140, 260, mHighTempTextPaint);
            if (mSunshineLowTemp != null)
                canvas.drawText(mSunshineLowTemp, 220, 260, mLowTempTextPaint);

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Log.d(TAG, "onConnected");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "onConnectionSuspended");

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "onDataChanged: " + dataEventBuffer);
            if (!mGoogleApiClient.isConnected() || !mGoogleApiClient.isConnecting()) {
                ConnectionResult connectionResult = mGoogleApiClient
                        .blockingConnect(30, TimeUnit.SECONDS);
                if (!connectionResult.isSuccess()) {
                    Log.e(TAG, "DataLayerListenerService failed to connect to GoogleApiClient, "
                            + "error code: " + connectionResult.getErrorCode());
                    return;
                }
            }

            for (DataEvent event : dataEventBuffer) {
                if (event.getType()== DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                    String path = event.getDataItem().getUri().getPath();
                    if (SUNSHINE_DATA_PATH.equals(path)){
                        mSunshineHighTemp = dataMap.getString(SUNSHINE_DATA_HIGH);
                        mSunshineLowTemp = dataMap.getString(SUNSHINE_DATA_LOW);
                        Asset photo = dataMap.getAsset(SUNSHINE_DATA_IMG);
                        mSunshineImage = loadBitmapFromAsset(mGoogleApiClient, photo);

                        mBackgroundScaledBitmap = Bitmap.createScaledBitmap(mSunshineImage, 80, 80, true );

                        updateTimer();

                    }
                }
            }
        }

        /**from android studio sample code*/

        private Bitmap loadBitmapFromAsset(GoogleApiClient apiClient, Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }

            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    apiClient, asset).await().getInputStream();

            if (assetInputStream == null) {
                Log.w(TAG, "Requested an unknown Asset.");
                return null;
            }
            return BitmapFactory.decodeStream(assetInputStream);
        }
        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d(TAG, "onConnectionFailed");
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
//            mHeight = height;
//            mWidth = width;
//            if (mSunshineImage != null)
//                if (mBackgroundScaledBitmap == null
//                        || mBackgroundScaledBitmap.getWidth() != width
//                        || mBackgroundScaledBitmap.getHeight() != height) {
//                    mBackgroundScaledBitmap = Bitmap.createScaledBitmap(mSunshineImage,
//                            width, height, true /* filter */);
//                }
            super.onSurfaceChanged(holder, format, width, height);
        }

    }



    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}
