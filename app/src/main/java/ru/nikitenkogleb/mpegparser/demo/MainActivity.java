/*
 * MainActivity.java
 * app
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 Gleb Nikitenko
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package ru.nikitenkogleb.mpegparser.demo;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatImageView;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

import proguard.annotation.Keep;
import proguard.annotation.KeepPublicProtectedClassMembers;
import ru.nikitenkogleb.mpegparser.MpegParser;

/**
 * @author Nikitenko Gleb
 * @since 1.0, 09/02/2017
 */
@SuppressWarnings("unused")
@Keep
@KeepPublicProtectedClassMembers
public final class MainActivity extends AppCompatActivity {

    /** The log-cat tag. */
    private static final String TAG = "MainActivity";

    /** The content view. */
    @Nullable
    private AppCompatImageView mContentView = null;

    /** The parsing task. */
    @Nullable
    private ParseTask mParseTask = null;

    /** {@inheritDoc} */
    @Override
    protected final void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContentView = new AppCompatImageView(this);

        final FrameLayout.LayoutParams layoutParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER);
        mContentView.setLayoutParams(layoutParams);

        setContentView(mContentView);

        startParseTask();
    }

    /** {@inheritDoc} */
    @Override
    protected final void onDestroy() {

        stopParseTask();

        assert mContentView != null;
        final Drawable drawable = mContentView.getDrawable();
        if (drawable != null && drawable instanceof AnimationDrawable) {
            ((AnimationDrawable) drawable).stop();
        }

        mContentView.setImageDrawable(null);
        mContentView = null;

        super.onDestroy();
    }

    /** Start parse task. */
    private void startParseTask() {
        stopParseTask();
        mParseTask = new ParseTask(this, 67);
    }

    /** Cancel Parse Task. */
    private void stopParseTask() {
        if (mParseTask != null) {
            mParseTask.close();
            mParseTask = null;
        }
    }

    /**
     * Calls when drawable created.
     *
     * @param drawable the animated drawable
     */
    private void onDrawableCreated(@NonNull Drawable drawable) {
        assert mContentView != null;
        mContentView.setImageDrawable(drawable);
    }


    /** The mp4 parsing task */
    private static final class ParseTask extends AsyncTask<Void, Bitmap, Boolean>
            implements Closeable, MpegParser.FramesEncoder {

        /** The main activity weak reference. */
        private final WeakReference<MainActivity> mMainActivityWeakReference;

        /** The asset manager. */
        private final AssetManager mAssetManager;

        /** The duration of frames. */
        private final int mFrameDuration;

        /** The animation drawable. */
        @Nullable
        private AnimationDrawable mAnimationDrawable;

        /**
         * Constructs a new {@link ParseTask} with a {@link MainActivity} reference.
         *
         * @param activity the {@link MainActivity} instance
         * @param duration the frames duration
         */
        ParseTask(@NonNull MainActivity activity, int duration) {
            mMainActivityWeakReference = new WeakReference<>(activity);
            mAssetManager = activity.getAssets();
            mFrameDuration = duration;
            execute();
        }

        /** {@inheritDoc} */
        @Override
        public final void close() {
            mMainActivityWeakReference.clear();
            mAnimationDrawable = null;
            cancel(false);
        }

        /** {@inheritDoc} */
        @Override
        protected final Boolean doInBackground(Void... params) {
            try {
                return MpegParser.extract(mAssetManager.openFd("input.mp4"), this);
            } catch (IOException exception) {
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, exception);
                }
            }
            return false;
        }

        /** {@inheritDoc} */
        @Override
        public final void onFrameExtracted(int index, @NonNull ByteBuffer byteBuffer,
                int width, int height) {
            final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(byteBuffer);
            publishProgress(bitmap);
        }

        /** {@inheritDoc} */
        @Override
        protected final void onPreExecute() {
            super.onPreExecute();
            mAnimationDrawable = new AnimationDrawable();
            mAnimationDrawable.setOneShot(false);
        }

        /** {@inheritDoc} */
        @Override
        protected final void onPostExecute(Boolean value) {
            super.onPostExecute(value);

            final MainActivity mainActivity = mMainActivityWeakReference.get();
            if (mainActivity != null && value && mAnimationDrawable != null) {
                mAnimationDrawable.start();
                mainActivity.onDrawableCreated(mAnimationDrawable);
            }

            close();
        }

        /** {@inheritDoc} */
        @Override
        protected final void onProgressUpdate(Bitmap... values) {
            super.onProgressUpdate(values);

            final MainActivity mainActivity = mMainActivityWeakReference.get();
            if (mainActivity != null && mAnimationDrawable != null) {
                for (Bitmap value : values) {
                    final BitmapDrawable frame =
                            new BitmapDrawable(mainActivity.getResources(), value);
                    mAnimationDrawable.addFrame(frame, mFrameDuration);
                }
            }
        }
    }

}
