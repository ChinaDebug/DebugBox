/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

/** Decodes and renders video using FFmpeg. */
@UnstableApi
public final class ExperimentalFfmpegVideoRenderer extends DecoderVideoRenderer {

  private static final String TAG = "ExperimentalFfmpegVideoRenderer";

  private static final int DEFAULT_INPUT_BUFFER_SIZE = 4 * 1024 * 1024;

  /** 4K 高码率视频软解需充足并行解码线程，超过上限线程同步开销反噬吞吐 */
  private static final int MAX_DECODE_THREADS = 12;

  private final int numInputBuffers;
  private final int numOutputBuffers;
  private final int threads;

  @Nullable private FfmpegVideoDecoder decoder;

  /**
   * Creates a new instance.
   *
   * @param allowedJoiningTimeMs The maximum duration in milliseconds for which this video renderer
   *     can attempt to seamlessly join an ongoing playback.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param maxDroppedFramesToNotify The maximum number of frames that can be dropped between
   *     invocations of {@link VideoRendererEventListener#onDroppedFrames(int, long)}.
   */
  public ExperimentalFfmpegVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    this(
        allowedJoiningTimeMs,
        eventHandler,
        eventListener,
        maxDroppedFramesToNotify,
        Math.min(Runtime.getRuntime().availableProcessors(), MAX_DECODE_THREADS),
        /* numInputBuffers= */ 6,
        /* numOutputBuffers= */ 8);
  }

  private ExperimentalFfmpegVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify,
      int threads,
      int numInputBuffers,
      int numOutputBuffers) {
    super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
    this.threads = threads;
    this.numInputBuffers = numInputBuffers;
    this.numOutputBuffers = numOutputBuffers;
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public final @RendererCapabilities.Capabilities int supportsFormat(Format format) {
    if (!FfmpegLibrary.isAvailable() || !MimeTypes.isVideo(format.sampleMimeType)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
    if (!FfmpegLibrary.supportsFormat(format.sampleMimeType)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    }
    if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    }
    return RendererCapabilities.create(
        C.FORMAT_HANDLED, ADAPTIVE_SEAMLESS, TUNNELING_NOT_SUPPORTED);
  }

  @Override
  protected FfmpegVideoDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws FfmpegDecoderException {
    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    FfmpegVideoDecoder decoder =
        new FfmpegVideoDecoder(
            numInputBuffers, numOutputBuffers, initialInputBufferSize, threads, format);
    this.decoder = decoder;
    return decoder;
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    if (decoder == null) {
      throw new FfmpegDecoderException(
          "Failed to render output buffer to surface: decoder is not initialized.");
    }
    decoder.renderToSurface(outputBuffer, surface);
    outputBuffer.release();
  }

  @Override
  protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
    // The decoder always produces YUV frames; the surface path converts them at render time.
  }

  @Override
  protected DecoderReuseEvaluation canReuseDecoder(
      String decoderName, Format oldFormat, Format newFormat) {
    return new DecoderReuseEvaluation(
        decoderName,
        oldFormat,
        newFormat,
        DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,
        /* discardReasons= */ 0);
  }
}
