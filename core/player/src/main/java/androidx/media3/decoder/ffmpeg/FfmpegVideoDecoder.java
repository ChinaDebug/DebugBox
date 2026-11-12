/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.decoder.ffmpeg;

import static androidx.media3.common.util.Assertions.checkNotNull;

import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;

/** FFmpeg video decoder. */
@UnstableApi
/* package */ final class FfmpegVideoDecoder
    extends SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

  private static final int VIDEO_DECODER_SUCCESS = 0;
  private static final int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
  private static final int VIDEO_DECODER_ERROR_OTHER = -2;
  private static final int VIDEO_DECODER_ERROR_READ_FRAME = -3;
  private static final int VIDEO_DECODER_ERROR_SURFACE = -4;

  private final String codecName;
  private final int width;
  private final int height;
  private long nativeContext;

  /** Input samples queued while FFmpeg's internal queue is full (avcodec_send_packet EAGAIN). */
  private final ArrayDeque<PendingInput> pendingInputs = new ArrayDeque<>();

  /** A copy of an input sample waiting to be sent to FFmpeg. */
  private static final class PendingInput {
    public final ByteBuffer data;
    public final long timeUs;

    public PendingInput(ByteBuffer data, long timeUs) {
      this.data = data;
      this.timeUs = timeUs;
    }
  }

  public FfmpegVideoDecoder(
      int numInputBuffers, int numOutputBuffers, int initialInputBufferSize, int threads,
      Format format)
      throws FfmpegDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new VideoDecoderOutputBuffer[numOutputBuffers]);
    if (!FfmpegLibrary.isAvailable()) {
      throw new FfmpegDecoderException("Failed to load decoder native library.");
    }
    codecName = checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType));
    width = format.width;
    height = format.height;
    byte[] extraData = getExtraData(format.initializationData);
    // 4K MKV 的 format.maxInputSize 可达单帧 18.5MB，若直接作为 numInputBuffers 个输入缓冲的
    // 初始大小，切换集/重建解码器时 DirectByteBuffer 峰值叠加会 OOM。这里 clamp 初始大小，
    // 超限样本由 DecoderInputBuffer.ensureSpaceForWrite 按需扩容（单次仅扩当前样本）。
    if (initialInputBufferSize > 8 * 1024 * 1024) {
      initialInputBufferSize = 8 * 1024 * 1024;
    }
    nativeContext = ffmpegVideoInitialize(codecName, extraData, threads, width, height);
    if (nativeContext == 0) {
      throw new FfmpegDecoderException("Failed to initialize decoder.");
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  /** Returns FFmpeg-compatible codec-specific initialization data ("extra data"), or null. */
  @Nullable
  private static byte[] getExtraData(List<byte[]> initializationData) {
    int size = 0;
    for (int i = 0; i < initializationData.size(); i++) {
      size += initializationData.get(i).length;
    }
    if (size > 0) {
      byte[] extra = new byte[size];
      ByteBuffer wrapper = ByteBuffer.wrap(extra);
      for (int i = 0; i < initializationData.size(); i++) {
        wrapper.put(initializationData.get(i));
      }
      return extra;
    }
    return null;
  }

  @Override
  public String getName() {
    return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    // FFmpeg bitstream parsers may read up to AV_INPUT_BUFFER_PADDING_SIZE bytes past the
    // end of the packet data, so the input buffers must be padded like the audio decoder.
    return new DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
        FfmpegLibrary.getInputBufferPaddingSize());
  }

  @Override
  protected VideoDecoderOutputBuffer createOutputBuffer() {
    return new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  @Nullable
  protected FfmpegDecoderException decode(
      DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      nativeContext = ffmpegVideoReset(nativeContext);
      if (nativeContext == 0) {
        return new FfmpegDecoderException("Error resetting (see logcat).");
      }
      pendingInputs.clear();
    }

    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    boolean decodeOnly = !isAtLeastOutputStartTimeUs(inputBuffer.timeUs);

    // 1. Send as many pending inputs as FFmpeg currently accepts. A pending sample that
    // returns EAGAIN stays at the head and is retried on the next call.
    while (!pendingInputs.isEmpty()) {
      PendingInput pending = pendingInputs.peek();
      int pendingResult =
          ffmpegVideoSendPacket(nativeContext, pending.data, pending.data.limit(), pending.timeUs);
      if (pendingResult == VIDEO_DECODER_ERROR_READ_FRAME) {
        break;
      }
      pendingInputs.removeFirst();
      if (pendingResult == VIDEO_DECODER_ERROR_OTHER) {
        return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
      }
      // VIDEO_DECODER_ERROR_INVALID_DATA: drop the invalid sample and continue.
    }

    // 2. Send the current input packet. If FFmpeg's queue is still full, cache the input
    // (it must never be dropped: avcodec_send_packet EAGAIN means "not consumed") and
    // drain one decoded frame into this output buffer instead.
    int sendPacketResult =
        ffmpegVideoSendPacket(nativeContext, inputData, inputData.limit(), inputBuffer.timeUs);

    if (sendPacketResult == VIDEO_DECODER_ERROR_READ_FRAME) {
      pendingInputs.addLast(copyPendingInput(inputData, inputBuffer.timeUs));
      if (!decodeOnly) {
        outputBuffer.init(inputBuffer.timeUs, C.VIDEO_OUTPUT_MODE_SURFACE_YUV, null);
      }
      int drainResult =
          ffmpegVideoReceiveFrame(
              nativeContext, C.VIDEO_OUTPUT_MODE_SURFACE_YUV, outputBuffer, decodeOnly);
      if (drainResult == VIDEO_DECODER_ERROR_OTHER) {
        return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
      }
      if (drainResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
        outputBuffer.shouldBeSkipped = true;
      } else if (!decodeOnly) {
        // initForYuvFrame 会把 mode 重置为 YUV，此处恢复 SURFACE_YUV 以走 Surface 渲染路径
        outputBuffer.mode = C.VIDEO_OUTPUT_MODE_SURFACE_YUV;
        outputBuffer.format = inputBuffer.format;
      }
      return null;
    }

    if (sendPacketResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
      outputBuffer.shouldBeSkipped = true;
      return null;
    } else if (sendPacketResult == VIDEO_DECODER_ERROR_OTHER) {
      return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
    }

    // Receive frame. The decoded frame must be dequeued even for decode-only input.
    if (!decodeOnly) {
      outputBuffer.init(inputBuffer.timeUs, C.VIDEO_OUTPUT_MODE_SURFACE_YUV, null);
    }
    int getFrameResult =
        ffmpegVideoReceiveFrame(
            nativeContext, C.VIDEO_OUTPUT_MODE_SURFACE_YUV, outputBuffer, decodeOnly);
    if (getFrameResult == VIDEO_DECODER_ERROR_OTHER) {
      return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
    }
    if (getFrameResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
      outputBuffer.shouldBeSkipped = true;
    }
    if (!decodeOnly) {
      // initForYuvFrame 会把 mode 重置为 YUV，此处恢复 SURFACE_YUV 以走 Surface 渲染路径
      outputBuffer.mode = C.VIDEO_OUTPUT_MODE_SURFACE_YUV;
      outputBuffer.format = inputBuffer.format;
    }
    return null;
  }

  /** Copies the input sample so it survives the return from {@link #decode}. */
  private static PendingInput copyPendingInput(ByteBuffer inputData, long timeUs) {
    inputData.position(0);
    ByteBuffer copy = ByteBuffer.allocateDirect(inputData.limit());
    copy.put(inputData);
    copy.flip();
    return new PendingInput(copy, timeUs);
  }

  @Override
  protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
    return new FfmpegDecoderException("Unexpected decode error", error);
  }

  @Override
  public void release() {
    super.release();
    ffmpegVideoRelease(nativeContext);
    nativeContext = 0;
  }

  /** Renders the output buffer to the given surface. */
  public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
      throw new FfmpegDecoderException("Invalid output mode.");
    }
    int rst =
        ffmpegVideoRenderFrame(
            nativeContext, surface, outputBuffer, outputBuffer.width, outputBuffer.height);
    if (rst == VIDEO_DECODER_ERROR_OTHER || rst == VIDEO_DECODER_ERROR_SURFACE) {
      throw new FfmpegDecoderException("Buffer render error: " + rst);
    }
  }

  private native long ffmpegVideoInitialize(
      String codecName, @Nullable byte[] extraData, int threads, int width, int height);

  private native long ffmpegVideoReset(long context);

  private native void ffmpegVideoRelease(long context);

  private native int ffmpegVideoSendPacket(
      long context, ByteBuffer encodedData, int length, long inputTime);

  private native int ffmpegVideoReceiveFrame(
      long context, int outputMode, VideoDecoderOutputBuffer outputBuffer, boolean decodeOnly);

  private native int ffmpegVideoRenderFrame(
      long context,
      Surface surface,
      VideoDecoderOutputBuffer outputBuffer,
      int displayedWidth,
      int displayedHeight);
}
