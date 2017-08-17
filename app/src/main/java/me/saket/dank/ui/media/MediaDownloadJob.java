package me.saket.dank.ui.media;

import android.os.Parcelable;
import android.support.annotation.IntRange;
import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;

import java.io.File;

import me.saket.dank.data.MediaLink;

@AutoValue
public abstract class MediaDownloadJob implements Parcelable {

  public enum ProgressState {
    CONNECTING,
    IN_FLIGHT,
    FAILED,
    DOWNLOADED,
  }

  public abstract MediaLink mediaLink();

  public abstract ProgressState progressState();

  @IntRange(from = 0, to = 100)
  public abstract int downloadProgress();

  /**
   * Null until the file is downloaded.
   */
  @Nullable
  public abstract File downloadedFile();

  public abstract long timestamp();

  public static MediaDownloadJob createConnecting(MediaLink mediaLink, long startTimeMillis) {
    return new AutoValue_MediaDownloadJob(mediaLink, ProgressState.CONNECTING, 0, null, startTimeMillis);
  }

  public static MediaDownloadJob createProgress(MediaLink mediaLink, @IntRange(from = 0, to = 100) int progress, long startTimeMillis) {
    return new AutoValue_MediaDownloadJob(mediaLink, ProgressState.IN_FLIGHT, progress, null, startTimeMillis);
  }

  public static MediaDownloadJob createFailed(MediaLink mediaLink, long failTimeMillis) {
    return new AutoValue_MediaDownloadJob(mediaLink, ProgressState.FAILED, -1, null, failTimeMillis);
  }

  public static MediaDownloadJob createDownloaded(MediaLink mediaLink, File downloadedFile, long completeTimeMillis) {
    return new AutoValue_MediaDownloadJob(mediaLink, ProgressState.DOWNLOADED, 100, downloadedFile, completeTimeMillis);
  }
}
