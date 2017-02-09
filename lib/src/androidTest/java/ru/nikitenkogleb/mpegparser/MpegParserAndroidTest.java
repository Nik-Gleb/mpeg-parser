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
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

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

    /** Video source. */
    private static final Uri SOURCE =
            Uri.parse("http://www.sample-videos.com/video/mp4/240/big_buck_bunny_240p_1mb.mp4");

    /** Test convert. */
    @Test
    public final void testConvert() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final MockEncoder mockEncoder = new MockEncoder();
        final boolean result = MpegParser.extract(context, SOURCE, null, mockEncoder);

        Assert.assertTrue(result);
        Assert.assertEquals(8, mockEncoder.callsCounter);
    }

    /** The Mock Encoder. */
    private static final class MockEncoder implements MpegParser.FramesEncoder {

        /** The counter of calls */
        int callsCounter = 0;

        /** Calls by extract frame. */
        @Override
        public final void onFrameExtracted(int index, @NonNull ByteBuffer byteBuffer) {

            // glReadPixels gives us a ByteBuffer filled with what is essentially big-endian RGBA
            // data (i.e. a byte of red, followed by a byte of green...).  To use the Bitmap
            // constructor that takes an int[] array with pixel data, we need an int[] filled
            // with little-endian ARGB data.
            //
            // If we implement this as a series of buf.get() calls, we can spend 2.5 seconds just
            // copying data around for a 720p frame.  It's better to do a bulk get() and then
            // rearrange the data in memory.  (For comparison, the PNG compress takes about 500ms
            // for a trivial frame.)
            //
            // So... we set the ByteBuffer to little-endian, which should turn the bulk IntBuffer
            // get() into a straight memcpy on most Android devices.  Our ints will hold ABGR data.
            // Swapping B and R gives us ARGB.  We need about 30ms for the bulk get(), and another
            // 270ms for the color swap.
            //
            // We can avoid the costly B/R swap here if we do it in the fragment shader (see
            // http://stackoverflow.com/questions/21634450/ ).
            //
            // Having said all that... it turns out that the Bitmap#copyPixelsFromBuffer()
            // method wants RGBA pixels, not ARGB, so if we create an empty bitmap and then
            // copy pixel data in we can avoid the swap issue entirely, and just copy straight
            // into the Bitmap from the ByteBuffer.
            //
            // Making this even more interesting is the upside-down nature of GL, which means
            // our output will look upside-down relative to what appears on screen if the
            // typical GL conventions are used.  (For ExtractMpegFrameTest, we avoid the issue
            // by inverting the frame when we render it.)
            //
            // Allocating large buffers is expensive, so we really want mPixelBuf to be
            // allocated ahead of time if possible.  We still get some allocations from the
            // Bitmap / PNG creation.

            /*byteBuffer.rewind();
            GLES20.glReadPixels(0, 0, mWidth, mHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, byteBuffer);

            BufferedOutputStream bos = null;
            try {
                bos = new BufferedOutputStream(new FileOutputStream(filename));
                Bitmap bmp = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
                byteBuffer.rewind();
                bmp.copyPixelsFromBuffer(byteBuffer);
                bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
                ge.encodeFrame(bmp, callsCounter * 100);
                bmp.recycle();
            } finally {
                if (bos != null) {
                    bos.close();
                }
            }
            if (VERBOSE) {
                Log.d(TAG, "Saved " + mWidth + "x" + mHeight + " frame as '" + filename + "'");
            }*/

            callsCounter++;
        }
    }
}
