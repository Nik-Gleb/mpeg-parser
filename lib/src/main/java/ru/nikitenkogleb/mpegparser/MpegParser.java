package ru.nikitenkogleb.mpegparser;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.GLES20;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import proguard.annotation.Keep;
import proguard.annotation.KeepPublicProtectedClassMembers;

/**
 * Mpeg parser.
 *
 * Mp4 frames extractor.
 *
 * @author Nikitenko Gleb
 * @since 1.0, 09/02/2017
 */
@Keep
@KeepPublicProtectedClassMembers
@SuppressWarnings({"WeakerAccess, unused", "deprecation"})
public final class MpegParser {

    /** The log-cat tag. */
    private static final String TAG = "MpegParser";

    /** Stop extracting after this many. */
    private static final int MAX_FRAMES = 1000;

    /**
     * The caller should be prevented from constructing objects of this class.
     * Also, this prevents even the native class from calling this constructor.
     **/
    private MpegParser() {
        throw new AssertionError();
    }

    /**
     * Extract process.
     *
     * @param context the Context to use when resolving the Uri
     * @param uri     the Content URI of the data you want to extract from.
     * @param headers the headers to be sent together with the request for the data.
     *                This can be {@code null} if no specific headers are to be sent with the
     *                request.
     * @param encoder output data
     * @return true - by successful, otherwise - false
     */
    public static boolean extract(@NonNull Context context, @NonNull Uri uri,
            @Nullable Map<String, String> headers, @NonNull FramesEncoder encoder) {

        final MediaExtractor mediaExtractor = new MediaExtractor();
        try {
            mediaExtractor.setDataSource(context, uri, headers);
            extract(mediaExtractor, encoder);
            return true;
        } catch (IOException exception) {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, exception);
            }
            return false;
        } finally {
            mediaExtractor.release();
        }
    }

    /**
     * Extract process.
     *
     * @param afd     asset file descriptor
     * @param encoder output data
     * @return true - by successful, otherwise - false
     */
    public static boolean extract(@NonNull AssetFileDescriptor afd,
            @NonNull FramesEncoder encoder) {
        final MediaExtractor mediaExtractor = new MediaExtractor();
        try {
            if (afd.getDeclaredLength() < 0) {
                mediaExtractor.setDataSource(afd.getFileDescriptor(), 0, 0x7ffffffffffffffL);
            } else {
                mediaExtractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(),
                        afd.getDeclaredLength());
            }
            extract(mediaExtractor, encoder);
            return true;
        } catch (IOException exception) {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, exception);
            }
            return false;
        } finally {
            mediaExtractor.release();
        }
    }

    /**
     * Do extracting.
     *
     * @param mediaExtractor the media extractor
     * @param encoder        the encoder
     * @throws IOException the IO Throws
     */
    private static void extract(
            @NonNull MediaExtractor mediaExtractor,
            @NonNull FramesEncoder encoder) throws IOException {

        final int trackIndex = selectTrack(mediaExtractor);
        if (trackIndex < 0) {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "No video track found in source");
            }
            return;
        }

        mediaExtractor.selectTrack(trackIndex);

        final MediaFormat format = mediaExtractor.getTrackFormat(trackIndex);
        final int width = format.getInteger(MediaFormat.KEY_WIDTH);
        final int height = format.getInteger(MediaFormat.KEY_HEIGHT);

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Video size is " + width + "x" + height);
        }

        // Could use width/height from the MediaFormat to get full-size frames.
        final CodecOutputSurface outputSurface = new CodecOutputSurface(width, height);
        try {
            // Create a MediaCodec decoder, and configure it with the MediaFormat from the
            // extractor.  It's very important to use the format from the extractor because
            // it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
            final String mime = format.getString(MediaFormat.KEY_MIME);
            final MediaCodec decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, outputSurface.mSurface, null, 0);
            decoder.start();

            try {
                doExtract(mediaExtractor, trackIndex, decoder, outputSurface, encoder, width,
                        height);
            } finally {
                decoder.stop();
                decoder.release();
            }

        } finally {
            outputSurface.release();
        }
    }

    /**
     * Selects the video track, if any.
     *
     * @return the track index, or -1 if no video track is found.
     */
    private static int selectTrack(@NonNull MediaExtractor extractor) {

        // Select the first video track we find, ignore the rest.
        final int numTracks = extractor.getTrackCount();

        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }
        return -1;
    }

    /** Work loop. */
    static void doExtract(@NonNull MediaExtractor extractor, int trackIndex,
            @NonNull MediaCodec decoder, @NonNull CodecOutputSurface outputSurface,
            @NonNull FramesEncoder encoder, int width, int height) throws IOException {

        final int TIMEOUT_USEC = 10000;

        final ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        int inputChunk = 0;
        int decodeCount = 0;
        long frameSaveTime = 0;

        boolean outputDone = false;
        boolean inputDone = false;

        while (!outputDone) {

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "loop");
            }

            // Feed more data to the decoder.
            if (!inputDone) {
                final int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputBufIndex >= 0) {
                    ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                    // Read the sample data into the ByteBuffer.  This neither respects nor
                    // updates inputBuf's position, limit, etc.
                    int chunkSize = extractor.readSampleData(inputBuf, 0);
                    if (chunkSize < 0) {

                        // End of stream -- send empty frame with EOS flag set.
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                        inputDone = true;
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "sent input EOS");
                        }
                    } else {
                        if (extractor.getSampleTrackIndex() != trackIndex &&
                                Log.isLoggable(TAG, Log.WARN)) {
                            Log.w(TAG, "WEIRD: got sample from track " +
                                    extractor.getSampleTrackIndex() + ", expected " + trackIndex);
                        }

                        final long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufIndex, 0, chunkSize,
                                presentationTimeUs, 0 /*flags*/);

                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "submitted frame " + inputChunk + " to dec, size=" +
                                    chunkSize);
                        }

                        inputChunk++;
                        extractor.advance();
                    }
                } else {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "input buffer not available");
                    }
                }
            }

            //noinspection ConstantConditions
            if (!outputDone) {
                int decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {

                    // no output available yet
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "no output from decoder available");
                    }
                } else
                    if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

                        // not important for us, since we're using Surface
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "decoder output buffers changed");
                        }
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                        final MediaFormat newFormat = decoder.getOutputFormat();
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "decoder output format changed: " + newFormat);
                        }
                    } else if (decoderStatus < 0) {

                        throw new RuntimeException(
                                "unexpected result from decoder.dequeueOutputBuffer: " +
                                        decoderStatus);
                    } else {

                        // decoderStatus >= 0
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "surface decoder given buffer " + decoderStatus +
                                    " (size=" + info.size + ")");
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                Log.d(TAG, "output EOS");
                            }
                            outputDone = true;
                        }

                        final boolean doRender = (info.size != 0);

                        // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                        // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
                        // that the texture will be available before the call returns, so we
                        // need to wait for the onFrameAvailable callback to fire.
                        decoder.releaseOutputBuffer(decoderStatus, doRender);
                        if (doRender) {

                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                Log.d(TAG, "awaiting decode of frame " + decodeCount);
                            }

                            outputSurface.awaitNewImage();
                            outputSurface.drawImage();

                            if (decodeCount < MAX_FRAMES) {
                                final long startWhen = System.nanoTime();

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

                                outputSurface.mPixelBuf.rewind();
                                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA,
                                        GLES20.GL_UNSIGNED_BYTE, outputSurface.mPixelBuf);
                                outputSurface.mPixelBuf.rewind();

                                encoder.onFrameExtracted(decodeCount, outputSurface.mPixelBuf, width, height);

                                frameSaveTime += System.nanoTime() - startWhen;
                            }
                            decodeCount++;

                        }
                    }
            }
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            final int numSaved = (MAX_FRAMES < decodeCount) ? MAX_FRAMES : decodeCount;
            Log.d(TAG, "Saving " + numSaved + " frames took " +
                    (frameSaveTime / numSaved / 1000) + " us per frame");
        }
    }

    /** Frames encoder */
    @Keep
    @KeepPublicProtectedClassMembers
    public interface FramesEncoder {

        /** Calls by extract frame */
        void onFrameExtracted(int count, @NonNull ByteBuffer byteBuffer, int width, int height);

    }

}
