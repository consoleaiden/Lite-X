/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package org.thunderdog.challegram.ui;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibFilesManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.vkryl.core.lambda.RunnableData;

/**
 * Re-upload engine used to bypass Telegram's "restrict saving content" (protected content)
 * limitation of channels.
 * <p>
 * Forwarding of protected content is rejected by the server ({@code CHAT_FORWARDS_RESTRICTED}),
 * so the only reliable way to move such messages elsewhere is to download the content
 * and send it again as brand-new messages. This engine performs exactly that: it downloads
 * every required file and re-sends text, media, albums, voice/video notes, stickers and other
 * supported content types to the target chat, preserving captions and message order.
 */
public final class MessageReuploader {
  public interface Callback {
    void onProgress (int completed, int total);
    void onDone (int successCount, int failCount, boolean cancelled);
  }

  private static final int MAX_ALBUM_SIZE = 10;
  private static final Pattern FLOOD_WAIT_PATTERN = Pattern.compile("(?:retry after|FLOOD_WAIT_|FLOOD_PREMIUM_WAIT_)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

  private final Tdlib tdlib;
  private final long targetChatId;
  private final Callback callback;
  private final Handler handler = new Handler(Looper.getMainLooper());

  private final List<Task> tasks = new ArrayList<>();
  private int taskIndex;
  private int successCount;
  private int failCount;
  private boolean cancelled;
  private boolean running;
  private Runnable pendingRetryRunnable;

  public MessageReuploader (Tdlib tdlib, long targetChatId, Callback callback) {
    this.tdlib = tdlib;
    this.targetChatId = targetChatId;
    this.callback = callback;
  }

  /**
   * Checks whether any of the given messages is protected from being forwarded.
   */
  public static boolean needsBypass (Tdlib tdlib, TdApi.Message[] messages) {
    if (messages == null) {
      return false;
    }
    for (TdApi.Message message : messages) {
      if (isProtected(tdlib, message)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isProtected (Tdlib tdlib, TdApi.Message message) {
    if (message == null || message.canBeSaved) {
      return false;
    }
    TdApi.Chat chat = tdlib.chat(message.chatId);
    return chat == null || chat.hasProtectedContent;
  }

  /**
   * Starts re-uploading the given messages. Messages must be ordered from oldest to newest.
   */
  public void start (List<TdApi.Message> messages) {
    if (running || messages == null || messages.isEmpty()) {
      return;
    }
    running = true;
    buildTasks(messages);
    if (tasks.isEmpty()) {
      finish();
      return;
    }
    processNextTask();
  }

  public void cancel () {
    if (!running) {
      return;
    }
    cancelled = true;
    if (pendingRetryRunnable != null) {
      handler.removeCallbacks(pendingRetryRunnable);
      pendingRetryRunnable = null;
    }
  }

  public boolean isRunning () {
    return running;
  }

  private void finish () {
    running = false;
    if (!cancelled) {
      callback.onDone(successCount, failCount, false);
    }
  }

  private void buildTasks (List<TdApi.Message> messages) {
    tasks.clear();
    taskIndex = 0;
    successCount = 0;
    failCount = 0;
    int index = 0;
    while (index < messages.size()) {
      TdApi.Message message = messages.get(index);
      if (message.content.getConstructor() == TdApi.MessageText.CONSTRUCTOR) {
        tasks.add(new SingleTask(message));
        index++;
        continue;
      }
      long albumId = message.mediaAlbumId;
      if (albumId != 0) {
        List<TdApi.Message> album = new ArrayList<>();
        while (index < messages.size() && album.size() < MAX_ALBUM_SIZE &&
               messages.get(index).mediaAlbumId == albumId &&
               messages.get(index).content.getConstructor() != TdApi.MessageText.CONSTRUCTOR) {
          album.add(messages.get(index));
          index++;
        }
        if (!album.isEmpty()) {
          tasks.add(new AlbumTask(album));
          continue;
        }
      }
      tasks.add(new SingleTask(message));
      index++;
    }
  }

  private void processNextTask () {
    if (cancelled) {
      return;
    }
    if (taskIndex >= tasks.size()) {
      finish();
      return;
    }
    final Task task = tasks.get(taskIndex);
    List<TdApi.File> files = task.collectFiles();
    if (files.isEmpty()) {
      sendTask(task, null);
      return;
    }
    downloadFiles(files, 0, new HashMap<>(), paths -> {
      if (cancelled) {
        return;
      }
      sendTask(task, paths);
    }, () -> {
      if (!cancelled) {
        failCount++;
        notifyProgress();
        advance();
      }
    });
  }

  private void downloadFiles (List<TdApi.File> files, int index, Map<Long, String> paths, RunnableData<Map<Long, String>> onComplete, Runnable onFailure) {
    if (cancelled) {
      return;
    }
    if (index >= files.size()) {
      onComplete.runWithData(paths);
      return;
    }
    final TdApi.File file = files.get(index);
    if (file.local != null && file.local.isDownloadingCompleted && file.local.path != null) {
      paths.put(file.id, file.local.path);
      downloadFiles(files, index + 1, paths, onComplete, onFailure);
      return;
    }
    tdlib.files().downloadFile(file, TdlibFilesManager.PRIORITY_USER_REQUEST_DOWNLOAD, (downloadedFile, error) -> {
      if (cancelled) {
        return;
      }
      if (error != null || downloadedFile == null || downloadedFile.local == null ||
          !downloadedFile.local.isDownloadingCompleted || downloadedFile.local.path == null) {
        onFailure.run();
        return;
      }
      paths.put(file.id, downloadedFile.local.path);
      downloadFiles(files, index + 1, paths, onComplete, onFailure);
    });
  }

  private void sendTask (Task task, @Nullable Map<Long, String> paths) {
    if (cancelled) {
      return;
    }
    final TdApi.Function<?> function = task.buildFunction(tdlib, targetChatId, paths);
    if (function == null) {
      failCount++;
      notifyProgress();
      advance();
      return;
    }
    tdlib.client().send(function, result -> tdlib.ui().post(() -> handleSendResult(result, function)));
  }

  private void handleSendResult (TdApi.Object result, final TdApi.Function<?> function) {
    if (cancelled || !running) {
      return;
    }
    if (result.getConstructor() == TdApi.Error.CONSTRUCTOR) {
      TdApi.Error error = (TdApi.Error) result;
      if (error.code == 429) {
        scheduleFloodWaitRetry(function, error.message);
        return;
      }
      failCount++;
      notifyProgress();
      advance();
    } else {
      successCount++;
      notifyProgress();
      advance();
    }
  }

  private void scheduleFloodWaitRetry (final TdApi.Function<?> function, @Nullable String errorMessage) {
    long retryAfterMs = parseRetryAfter(errorMessage);
    pendingRetryRunnable = new Runnable() {
      @Override
      public void run () {
        pendingRetryRunnable = null;
        if (cancelled || !running) {
          return;
        }
        tdlib.client().send(function, result -> tdlib.ui().post(() -> handleSendResult(result, function)));
      }
    };
    handler.postDelayed(pendingRetryRunnable, retryAfterMs);
  }

  private static long parseRetryAfter (@Nullable String errorMessage) {
    if (errorMessage != null) {
      Matcher matcher = FLOOD_WAIT_PATTERN.matcher(errorMessage);
      if (matcher.find()) {
        try {
          long seconds = Long.parseLong(matcher.group(1));
          return Math.max(1000L, (seconds + 1) * 1000L);
        } catch (NumberFormatException ignored) { }
      }
    }
    return 5000L;
  }

  private void notifyProgress () {
    callback.onProgress(successCount + failCount, tasks.size());
  }

  private void advance () {
    taskIndex++;
    processNextTask();
  }

  // Tasks

  private abstract static class Task {
    abstract List<TdApi.File> collectFiles ();
    abstract TdApi.Function<?> buildFunction (Tdlib tdlib, long targetChatId, @Nullable Map<Long, String> paths);
  }

  private static class SingleTask extends Task {
    private final TdApi.Message message;

    SingleTask (TdApi.Message message) {
      this.message = message;
    }

    @Override
    List<TdApi.File> collectFiles () {
      List<TdApi.File> files = new ArrayList<>();
      TdApi.File file = fileOf(message.content);
      if (file != null) {
        files.add(file);
      }
      return files;
    }

    @Override
    TdApi.Function<?> buildFunction (Tdlib tdlib, long targetChatId, @Nullable Map<Long, String> paths) {
      TdApi.InputMessageContent content = createInputContent(message, paths);
      if (content == null) {
        return null;
      }
      return new TdApi.SendMessage(targetChatId, null, null, newSendOptions(), null, content);
    }
  }

  private static class AlbumTask extends Task {
    private final List<TdApi.Message> messages;

    AlbumTask (List<TdApi.Message> messages) {
      this.messages = messages;
    }

    @Override
    List<TdApi.File> collectFiles () {
      List<TdApi.File> files = new ArrayList<>();
      for (TdApi.Message message : messages) {
        TdApi.File file = fileOf(message.content);
        if (file != null) {
          files.add(file);
        }
      }
      return files;
    }

    @Override
    TdApi.Function<?> buildFunction (Tdlib tdlib, long targetChatId, @Nullable Map<Long, String> paths) {
      List<TdApi.InputMessageContent> contents = new ArrayList<>(messages.size());
      for (TdApi.Message message : messages) {
        TdApi.InputMessageContent content = createInputContent(message, paths);
        if (content == null) {
          return null;
        }
        contents.add(content);
      }
      if (contents.isEmpty()) {
        return null;
      }
      if (contents.size() == 1) {
        return new TdApi.SendMessage(targetChatId, null, null, newSendOptions(), null, contents.get(0));
      }
      return new TdApi.SendMessageAlbum(targetChatId, null, null, newSendOptions(), contents.toArray(new TdApi.InputMessageContent[0]));
    }
  }

  private static TdApi.MessageSendOptions newSendOptions () {
    return new TdApi.MessageSendOptions(null, false, false, false, false, 0, false, null, 0, 0, false);
  }

  private static String pathOf (Map<Long, String> paths, TdApi.File file) {
    if (file == null) {
      return null;
    }
    if (file.local != null && file.local.isDownloadingCompleted && file.local.path != null) {
      return file.local.path;
    }
    return paths != null ? paths.get(file.id) : null;
  }

  /**
   * Extracts the main file of a message content, if any.
   */
  @Nullable
  private static TdApi.File fileOf (TdApi.MessageContent content) {
    switch (content.getConstructor()) {
      case TdApi.MessagePhoto.CONSTRUCTOR: {
        TdApi.Photo photo = ((TdApi.MessagePhoto) content).photo;
        if (photo == null || photo.sizes.length == 0) {
          return null;
        }
        TdApi.PhotoSize bestSize = photo.sizes[0];
        for (TdApi.PhotoSize size : photo.sizes) {
          if (size.width * size.height > bestSize.width * bestSize.height) {
            bestSize = size;
          }
        }
        return bestSize.photo;
      }
      case TdApi.MessageVideo.CONSTRUCTOR: {
        TdApi.Video video = ((TdApi.MessageVideo) content).video;
        return video != null ? video.video : null;
      }
      case TdApi.MessageDocument.CONSTRUCTOR: {
        TdApi.Document document = ((TdApi.MessageDocument) content).document;
        return document != null ? document.document : null;
      }
      case TdApi.MessageAudio.CONSTRUCTOR: {
        TdApi.Audio audio = ((TdApi.MessageAudio) content).audio;
        return audio != null ? audio.audio : null;
      }
      case TdApi.MessageAnimation.CONSTRUCTOR: {
        TdApi.Animation animation = ((TdApi.MessageAnimation) content).animation;
        return animation != null ? animation.animation : null;
      }
      case TdApi.MessageVoiceNote.CONSTRUCTOR: {
        TdApi.VoiceNote voiceNote = ((TdApi.MessageVoiceNote) content).voiceNote;
        return voiceNote != null ? voiceNote.voice : null;
      }
      case TdApi.MessageVideoNote.CONSTRUCTOR: {
        TdApi.VideoNote videoNote = ((TdApi.MessageVideoNote) content).videoNote;
        return videoNote != null ? videoNote.video : null;
      }
      case TdApi.MessageSticker.CONSTRUCTOR: {
        TdApi.Sticker sticker = ((TdApi.MessageSticker) content).sticker;
        return sticker != null ? sticker.sticker : null;
      }
      default: {
        return null;
      }
    }
  }

  /**
   * Extracts the caption (or text) of a message content.
   */
  @Nullable
  private static TdApi.FormattedText captionOf (TdApi.MessageContent content) {
    switch (content.getConstructor()) {
      case TdApi.MessageText.CONSTRUCTOR:
        return ((TdApi.MessageText) content).text;
      case TdApi.MessagePhoto.CONSTRUCTOR:
        return ((TdApi.MessagePhoto) content).caption;
      case TdApi.MessageVideo.CONSTRUCTOR:
        return ((TdApi.MessageVideo) content).caption;
      case TdApi.MessageDocument.CONSTRUCTOR:
        return ((TdApi.MessageDocument) content).caption;
      case TdApi.MessageAudio.CONSTRUCTOR:
        return ((TdApi.MessageAudio) content).caption;
      case TdApi.MessageAnimation.CONSTRUCTOR:
        return ((TdApi.MessageAnimation) content).caption;
      case TdApi.MessageVoiceNote.CONSTRUCTOR:
        return ((TdApi.MessageVoiceNote) content).caption;
      default:
        return null;
    }
  }

  /**
   * Creates {@link TdApi.InputMessageContent} for a message by using locally downloaded files.
   *
   * @return content to send, or {@code null} if the message type is not supported
   */
  @Nullable
  private static TdApi.InputMessageContent createInputContent (TdApi.Message message, @Nullable Map<Long, String> paths) {
    TdApi.MessageContent content = message.content;
    TdApi.FormattedText caption = captionOf(content);
    switch (content.getConstructor()) {
      case TdApi.MessageText.CONSTRUCTOR: {
        TdApi.FormattedText text = ((TdApi.MessageText) content).text;
        if (text == null || text.text.isEmpty()) {
          return null;
        }
        return new TdApi.InputMessageText(text, null, false);
      }
      case TdApi.MessagePhoto.CONSTRUCTOR: {
        TdApi.MessagePhoto photoContent = (TdApi.MessagePhoto) content;
        TdApi.PhotoSize bestSize = null;
        if (photoContent.photo != null) {
          for (TdApi.PhotoSize size : photoContent.photo.sizes) {
            if (bestSize == null || size.width * size.height > bestSize.width * bestSize.height) {
              bestSize = size;
            }
          }
        }
        if (bestSize == null) {
          return null;
        }
        String path = pathOf(paths, bestSize.photo);
        if (path == null) {
          return null;
        }
        return new TdApi.InputMessagePhoto(
          new TdApi.InputPhoto(new TdApi.InputFileLocal(path), null, null, null, bestSize.width, bestSize.height),
          caption, photoContent.showCaptionAboveMedia, null, photoContent.hasSpoiler
        );
      }
      case TdApi.MessageVideo.CONSTRUCTOR: {
        TdApi.MessageVideo videoContent = (TdApi.MessageVideo) content;
        TdApi.Video video = videoContent.video;
        if (video == null) {
          return null;
        }
        String path = pathOf(paths, video.video);
        if (path == null) {
          return null;
        }
        return new TdApi.InputMessageVideo(
          new TdApi.InputVideo(new TdApi.InputFileLocal(path), null, null, 0, null, video.duration, video.width, video.height, video.supportsStreaming),
          caption, videoContent.showCaptionAboveMedia, null, videoContent.hasSpoiler
        );
      }
      case TdApi.MessageDocument.CONSTRUCTOR: {
        TdApi.MessageDocument documentContent = (TdApi.MessageDocument) content;
        TdApi.Document document = documentContent.document;
        if (document == null) {
          return null;
        }
        String path = pathOf(paths, document.document);
        if (path == null) {
          return null;
        }
        return new TdApi.InputMessageDocument(
          new TdApi.InputDocument(new TdApi.InputFileLocal(path), null, false),
          caption
        );
      }
      case TdApi.MessageAudio.CONSTRUCTOR: {
        TdApi.MessageAudio audioContent = (TdApi.MessageAudio) content;
        TdApi.Audio audio = audioContent.audio;
        if (audio == null) {
          return null;
        }
        String path = pathOf(paths, audio.audio);
        if (path == null) {
          return null;
        }
        return new TdApi.InputMessageAudio(
          new TdApi.InputAudio(new TdApi.InputFileLocal(path), null, audio.duration, audio.title, audio.performer),
          caption
        );
      }
      case TdApi.MessageAnimation.CONSTRUCTOR: {
        TdApi.MessageAnimation animationContent = (TdApi.MessageAnimation) content;
        TdApi.Animation animation = animationContent.animation;
        if (animation == null) {
          return null;
        }
        String path = pathOf(paths, animation.animation);
        if (path == null) {
          return null;
        }
        return new TdApi.InputMessageAnimation(
          new TdApi.InputAnimation(new TdApi.InputFileLocal(path), null, null, animation.duration, animation.width, animation.height),
          caption, animationContent.showCaptionAboveMedia, animationContent.hasSpoiler
        );
      }
      case TdApi.MessageVoiceNote.CONSTRUCTOR: {
        TdApi.MessageVoiceNote voiceContent = (TdApi.MessageVoiceNote) content;
        TdApi.VoiceNote voiceNote = voiceContent.voiceNote;
        if (voiceNote == null) {
          return null;
        }
        String path = pathOf(paths, voiceNote.voice);
        if (path == null) {
          return null;
        }
        return new TdApi.InputMessageVoiceNote(
          new TdApi.InputVoiceNote(new TdApi.InputFileLocal(path), voiceNote.duration, voiceNote.waveform),
          caption, null
        );
      }
      case TdApi.MessageVideoNote.CONSTRUCTOR: {
        TdApi.MessageVideoNote videoNoteContent = (TdApi.MessageVideoNote) content;
        TdApi.VideoNote videoNote = videoNoteContent.videoNote;
        if (videoNote == null) {
          return null;
        }
        String path = pathOf(paths, videoNote.video);
        if (path == null) {
          return null;
        }
        return new TdApi.InputMessageVideoNote(
          new TdApi.InputVideoNote(new TdApi.InputFileLocal(path), null, videoNote.duration, videoNote.length),
          null
        );
      }
      case TdApi.MessageSticker.CONSTRUCTOR: {
        TdApi.MessageSticker stickerContent = (TdApi.MessageSticker) content;
        TdApi.Sticker sticker = stickerContent.sticker;
        if (sticker == null) {
          return null;
        }
        String path = pathOf(paths, sticker.sticker);
        if (path == null) {
          return null;
        }
        return new TdApi.InputMessageSticker(
          new TdApi.InputSticker(new TdApi.InputFileLocal(path), null, sticker.width, sticker.height),
          null
        );
      }
      case TdApi.MessageContact.CONSTRUCTOR: {
        return new TdApi.InputMessageContact(((TdApi.MessageContact) content).contact);
      }
      case TdApi.MessageLocation.CONSTRUCTOR: {
        return new TdApi.InputMessageLocation(((TdApi.MessageLocation) content).location);
      }
      case TdApi.MessageVenue.CONSTRUCTOR: {
        return new TdApi.InputMessageVenue(((TdApi.MessageVenue) content).venue);
      }
      case TdApi.MessageDice.CONSTRUCTOR: {
        return new TdApi.InputMessageDice(((TdApi.MessageDice) content).emoji, false);
      }
      case TdApi.MessageStory.CONSTRUCTOR: {
        TdApi.MessageStory story = (TdApi.MessageStory) content;
        return new TdApi.InputMessageStory(story.storyPosterChatId, story.storyId);
      }
      default: {
        if (caption != null && !caption.text.isEmpty()) {
          // Unsupported content type (poll, invoice, game, ...): at least preserve the caption text
          return new TdApi.InputMessageText(caption, null, false);
        }
        return null;
      }
    }
  }
}
