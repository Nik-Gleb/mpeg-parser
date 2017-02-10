/*
 * ExampleTest.java
 * lib
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

package ru.nikitenkogleb.mpegparser;

import junit.framework.Assert;

import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Instrumentation test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@SuppressWarnings("unused")
@RunWith(AndroidJUnit4.class)
public class MpegParserAndroidTest {

    /** The log-cat tag */
    private static final String TAG = "MpegParserAndroidTest";

    ///** Video source. */
    //private static final Uri SOURCE =
    //        Uri.parse("http://www.sample-videos.com/video/mp4/240/big_buck_bunny_240p_1mb.mp4");

    /** Video source. */
    private static final Uri SOURCE = Uri.parse("file:///android_asset/input2.mp4");

    /** Test convert. */
    @Test
    public final void testConvert() throws IOException {
        final Context context = InstrumentationRegistry.getContext();
        final MockEncoder mockEncoder = new MockEncoder();
        final long st = System.nanoTime();
        final boolean result = MpegParser
                .extract(context.getAssets().openFd("input.mp4"), mockEncoder);
        final long tot = System.nanoTime() - st;

        Log.d(TAG, "Delay " + tot / 1000000);
        Assert.assertTrue(result);
        Assert.assertEquals(205, mockEncoder.callsCounter);
    }

    /** The Mock Encoder. */
    private static final class MockEncoder implements MpegParser.FramesEncoder {

        /** The counter of calls */
        int callsCounter = 0;

        /** Calls by extract frame. */
        @Override
        public final void onFrameExtracted(int index,
                @NonNull ByteBuffer byteBuffer, int width, int height) {

            //BufferedOutputStream bos = null;
            //try {
            //bos = new BufferedOutputStream(new FileOutputStream(filename));
            final Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(byteBuffer);
                /*bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
                ge.encodeFrame(bmp, callsCounter * 100);*/

            Log.d(TAG, "onFrameExtracted: " + bmp);

                bmp.recycle();
            //} finally {
                /*if (bos != null) {
                    bos.close();
                }*/
            //}

            /*if (VERBOSE) {
                Log.d(TAG, "Saved " + mWidth + "x" + mHeight + " frame as '" + filename + "'");
            }*/

            callsCounter++;
        }
    }
}
