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
package org.telegram.messenger.exoplayer2.source;

import android.net.Uri;
import android.os.Handler;
import org.telegram.messenger.exoplayer2.C;
import org.telegram.messenger.exoplayer2.ExoPlayer;
import org.telegram.messenger.exoplayer2.Timeline;
import org.telegram.messenger.exoplayer2.extractor.DefaultExtractorsFactory;
import org.telegram.messenger.exoplayer2.extractor.Extractor;
import org.telegram.messenger.exoplayer2.extractor.ExtractorsFactory;
import org.telegram.messenger.exoplayer2.upstream.Allocator;
import org.telegram.messenger.exoplayer2.upstream.DataSource;
import org.telegram.messenger.exoplayer2.util.Assertions;
import java.io.IOException;

/**
 * Provides one period that loads data from a {@link Uri} and extracted using an {@link Extractor}.
 * <p>
 * If the possible input stream container formats are known, pass a factory that instantiates
 * extractors for them to the constructor. Otherwise, pass a {@link DefaultExtractorsFactory} to
 * use the default extractors. When reading a new stream, the first {@link Extractor} in the array
 * of extractors created by the factory that returns {@code true} from {@link Extractor#sniff} will
 * be used to extract samples from the input stream.
 * <p>
 * Note that the built-in extractors for AAC, MPEG PS/TS and FLV streams do not support seeking.
 */
public final class ExtractorMediaSource implements MediaSource, ExtractorMediaPeriod.Listener {

  /**
   * Listener of {@link ExtractorMediaSource} events.
   */
  public interface EventListener {

    /**
     * Called when an error occurs loading media data.
     *
     * @param error The load error.
     */
    void onLoadError(IOException error);

  }

  /**
   * The default minimum number of times to retry loading prior to failing for on-demand streams.
   */
  public static final int DEFAULT_MIN_LOADABLE_RETRY_COUNT_ON_DEMAND = 3;

  /**
   * The default minimum number of times to retry loading prior to failing for live streams.
   */
  public static final int DEFAULT_MIN_LOADABLE_RETRY_COUNT_LIVE = 6;

  /**
   * Value for {@code minLoadableRetryCount} that causes the loader to retry
   * {@link #DEFAULT_MIN_LOADABLE_RETRY_COUNT_LIVE} times for live streams and
   * {@link #DEFAULT_MIN_LOADABLE_RETRY_COUNT_ON_DEMAND} for on-demand streams.
   */
  public static final int MIN_RETRY_COUNT_DEFAULT_FOR_MEDIA = -1;

  /**
   * The default number of bytes that should be loaded between each each invocation of
   * {@link MediaPeriod.Callback#onContinueLoadingRequested(SequenceableLoader)}.
   */
  public static final int DEFAULT_LOADING_CHECK_INTERVAL_BYTES = 1024 * 1024;

  private final Uri uri;
  private final DataSource.Factory dataSourceFactory;
  private final ExtractorsFactory extractorsFactory;
  private final int minLoadableRetryCount;
  private final Handler eventHandler;
  private final EventListener eventListener;
  private final Timeline.Period period;
  private final String customCacheKey;
  private final int continueLoadingCheckIntervalBytes;

  private MediaSource.Listener sourceListener;
  private long timelineDurationUs;
  private boolean timelineIsSeekable;

  /**
   * @param uri The {@link Uri} of the media stream.
   * @param dataSourceFactory A factory for {@link DataSource}s to read the media.
   * @param extractorsFactory A factory for {@link Extractor}s to process the media stream. If the
   *     possible formats are known, pass a factory that instantiates extractors for those formats.
   *     Otherwise, pass a {@link DefaultExtractorsFactory} to use default extractors.
   * @param eventHandler A handler for events. May be null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   */
  public ExtractorMediaSource(Uri uri, DataSource.Factory dataSourceFactory,
      ExtractorsFactory extractorsFactory, Handler eventHandler, EventListener eventListener) {
    this(uri, dataSourceFactory, extractorsFactory, eventHandler, eventListener, null);
  }

  /**
   * @param uri The {@link Uri} of the media stream.
   * @param dataSourceFactory A factory for {@link DataSource}s to read the media.
   * @param extractorsFactory A factory for {@link Extractor}s to process the media stream. If the
   *     possible formats are known, pass a factory that instantiates extractors for those formats.
   *     Otherwise, pass a {@link DefaultExtractorsFactory} to use default extractors.
   * @param eventHandler A handler for events. May be null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param customCacheKey A custom key that uniquely identifies the original stream. Used for cache
   *     indexing. May be null.
   */
  public ExtractorMediaSource(Uri uri, DataSource.Factory dataSourceFactory,
      ExtractorsFactory extractorsFactory, Handler eventHandler, EventListener eventListener,
      String customCacheKey) {
    this(uri, dataSourceFactory, extractorsFactory, MIN_RETRY_COUNT_DEFAULT_FOR_MEDIA, eventHandler,
        eventListener, customCacheKey, DEFAULT_LOADING_CHECK_INTERVAL_BYTES);
  }

  /**
   * @param uri The {@link Uri} of the media stream.
   * @param dataSourceFactory A factory for {@link DataSource}s to read the media.
   * @param extractorsFactory A factory for {@link Extractor}s to process the media stream. If the
   *     possible formats are known, pass a factory that instantiates extractors for those formats.
   *     Otherwise, pass a {@link DefaultExtractorsFactory} to use default extractors.
   * @param minLoadableRetryCount The minimum number of times to retry if a loading error occurs.
   * @param eventHandler A handler for events. May be null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param customCacheKey A custom key that uniquely identifies the original stream. Used for cache
   *     indexing. May be null.
   * @param continueLoadingCheckIntervalBytes The number of bytes that should be loaded between each
   *     invocation of {@link MediaPeriod.Callback#onContinueLoadingRequested(SequenceableLoader)}.
   */
  public ExtractorMediaSource(Uri uri, DataSource.Factory dataSourceFactory,
      ExtractorsFactory extractorsFactory, int minLoadableRetryCount, Handler eventHandler,
      EventListener eventListener, String customCacheKey, int continueLoadingCheckIntervalBytes) {
    this.uri = uri;
    this.dataSourceFactory = dataSourceFactory;
    this.extractorsFactory = extractorsFactory;
    this.minLoadableRetryCount = minLoadableRetryCount;
    this.eventHandler = eventHandler;
    this.eventListener = eventListener;
    this.customCacheKey = customCacheKey;
    this.continueLoadingCheckIntervalBytes = continueLoadingCheckIntervalBytes;
    period = new Timeline.Period();
  }

  @Override
  public void prepareSource(ExoPlayer player, boolean isTopLevelSource, Listener listener) {
    sourceListener = listener;
    notifySourceInfoRefreshed(C.TIME_UNSET, false);
  }

  @Override
  public void maybeThrowSourceInfoRefreshError() throws IOException {
    // Do nothing.
  }

  @Override
  public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator) {
    Assertions.checkArgument(id.periodIndex == 0);
    return new ExtractorMediaPeriod(uri, dataSourceFactory.createDataSource(),
        extractorsFactory.createExtractors(), minLoadableRetryCount, eventHandler, eventListener,
        this, allocator, customCacheKey, continueLoadingCheckIntervalBytes);
  }

  @Override
  public void releasePeriod(MediaPeriod mediaPeriod) {
    ((ExtractorMediaPeriod) mediaPeriod).release();
  }

  @Override
  public void releaseSource() {
    sourceListener = null;
  }

  // ExtractorMediaPeriod.Listener implementation.

  @Override
  public void onSourceInfoRefreshed(long durationUs, boolean isSeekable) {
    // If we already have the duration from a previous source info refresh, use it.
    durationUs = durationUs == C.TIME_UNSET ? timelineDurationUs : durationUs;
    if (timelineDurationUs == durationUs && timelineIsSeekable == isSeekable
        || (timelineDurationUs != C.TIME_UNSET && durationUs == C.TIME_UNSET)) {
      // Suppress no-op source info changes.
      return;
    }
    notifySourceInfoRefreshed(durationUs, isSeekable);
  }

  // Internal methods.

  private void notifySourceInfoRefreshed(long durationUs, boolean isSeekable) {
    timelineDurationUs = durationUs;
    timelineIsSeekable = isSeekable;
    sourceListener.onSourceInfoRefreshed(
        new SinglePeriodTimeline(timelineDurationUs, timelineIsSeekable), null);
  }

}
