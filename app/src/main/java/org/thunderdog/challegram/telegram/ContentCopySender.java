/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.thunderdog.challegram.telegram;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.tool.UI;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-publishes messages as brand new ones, downloading their media first and
 * uploading it back. This is the only reliable way to move content out of chats
 * that forbid forwarding: the server rejects both messages.forwardMessages and
 * re-sending media that merely references a protected chat (CHAT_FORWARDS_RESTRICTED).
 */
public final class ContentCopySender {
  private static final long DOWNLOAD_POLL_MS = 500L;
  private static final int DOWNLOAD_POLL_MAX = 2400; // 20 minutes

  public interface Callback {
    default void onProgress (int sent, int total) { }

    void onFinished (int sent, @Nullable TdApi.Error error);
  }

  private interface PathCallback {
    void onReady (@Nullable String localPath);
  }

  private ContentCopySender () { }

  public static TdApi.MessageSendOptions defaultSendOptions () {
    return new TdApi.MessageSendOptions(null, true, false, false, false, 0, false, null, 0, 0, false);
  }

  public static void send (Tdlib tdlib, long toChatId, @Nullable TdApi.MessageTopic topicId, TdApi.Message[] messages, boolean removeCaption, TdApi.MessageSendOptions options, @Nullable Callback callback) {
    if (messages == null || messages.length == 0) {
      if (callback != null) {
        callback.onFinished(0, null);
      }
      return;
    }
    new Session(tdlib, toChatId, topicId, messages, removeCaption, options, callback).start();
  }

  private static final class Session {
    private final Tdlib tdlib;
    private final long toChatId;
    private final @Nullable TdApi.MessageTopic topicId;
    private final TdApi.Message[] messages;
    private final boolean removeCaption;
    private final TdApi.MessageSendOptions options;
    private final @Nullable Callback callback;

    private int index;
    private int sent;
    private boolean finished;

    Session (Tdlib tdlib, long toChatId, @Nullable TdApi.MessageTopic topicId, TdApi.Message[] messages, boolean removeCaption, TdApi.MessageSendOptions options, @Nullable Callback callback) {
      this.tdlib = tdlib;
      this.toChatId = toChatId;
      this.topicId = topicId;
      this.messages = messages;
      this.removeCaption = removeCaption;
      this.options = options;
      this.callback = callback;
    }

    void start () {
      processNext();
    }

    private void processNext () {
      if (finished) {
        return;
      }
      if (index >= messages.length) {
        finish(null);
        return;
      }
      TdApi.Message message = messages[index];
      long albumId = message.mediaAlbumId;
      if (albumId == 0) {
        sendSingle(message);
      } else {
        int end = index;
        while (end < messages.length && messages[end].mediaAlbumId == albumId && messages[end].chatId == message.chatId) {
          end++;
        }
        TdApi.Message[] album = new TdApi.Message[end - index];
        System.arraycopy(messages, index, album, 0, album.length);
        sendAlbum(album);
      }
    }

    private void sendSingle (TdApi.Message message) {
      TdApi.File file = TD.getCopySourceFile(message.content);
      if (file == null) {
        dispatch(message.content, null, 1);
        return;
      }
      ensureDownloaded(file, localPath -> dispatch(message.content, localPath, 1));
    }

    private void sendAlbum (TdApi.Message[] album) {
      List<TdApi.InputMessageContent> contents = new ArrayList<>(album.length);
      prepareAlbum(album, 0, contents, () -> {
        if (finished) {
          return;
        }
        if (contents.isEmpty()) {
          index += album.length;
          processNext();
        } else {
          sendContents(contents, album.length);
        }
      });
    }

    private void prepareAlbum (TdApi.Message[] album, int i, List<TdApi.InputMessageContent> out, Runnable onReady) {
      if (finished) {
        return;
      }
      if (i >= album.length) {
        onReady.run();
        return;
      }
      final TdApi.Message message = album[i];
      TdApi.File file = TD.getCopySourceFile(message.content);
      if (file == null) {
        addContent(out, message, null);
        prepareAlbum(album, i + 1, out, onReady);
        return;
      }
      ensureDownloaded(file, localPath -> {
        addContent(out, message, localPath);
        prepareAlbum(album, i + 1, out, onReady);
      });
    }

    private void addContent (List<TdApi.InputMessageContent> out, TdApi.Message message, @Nullable String localPath) {
      TdApi.InputMessageContent content = TD.toInputMessageContent(message.content, removeCaption, localPath);
      if (content != null) {
        out.add(content);
      }
    }

    private void dispatch (TdApi.MessageContent content, @Nullable String localPath, int consumed) {
      TdApi.InputMessageContent input = TD.toInputMessageContent(content, removeCaption, localPath);
      if (input == null) {
        index += consumed;
        processNext();
        return;
      }
      List<TdApi.InputMessageContent> contents = new ArrayList<>(1);
      contents.add(input);
      sendContents(contents, consumed);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void sendContents (List<TdApi.InputMessageContent> contents, int consumed) {
      final TdApi.Function<?> function;
      if (contents.size() == 1) {
        function = new TdApi.SendMessage(toChatId, topicId, null, options, null, contents.get(0));
      } else {
        function = new TdApi.SendMessageAlbum(toChatId, topicId, null, options, contents.toArray(new TdApi.InputMessageContent[0]));
      }
      @SuppressWarnings({"unchecked", "rawtypes"})
      TdApi.Function rawFunction = function;
      tdlib.send(rawFunction, (result, error) -> {
        if (finished) {
          return;
        }
        if (error != null) {
          finish(error);
          return;
        }
        sent += contents.size();
        if (callback != null) {
          callback.onProgress(sent, messages.length);
        }
        index += consumed;
        processNext();
      });
    }

    private void ensureDownloaded (TdApi.File file, PathCallback callback) {
      if (TD.isFileLoadedAndExists(file)) {
        callback.onReady(file.local.path);
        return;
      }
      // Asynchronous on purpose: a synchronous DownloadFile parks TDLib's whole
      // request queue until the transfer finishes, which freezes the UI for
      // minutes on a large video.
      tdlib.send(new TdApi.DownloadFile(file.id, TdlibFilesManager.PRIORITY_USER_REQUEST_SHARE, 0, 0, false), (started, error) -> {
        if (finished) {
          return;
        }
        if (error != null) {
          finish(new TdApi.Error(error.code, error.message != null && !error.message.isEmpty() ? error.message : "Failed to download media"));
          return;
        }
        pollDownload(file.id, 0, callback);
      });
    }

    private void pollDownload (int fileId, int attempt, PathCallback callback) {
      if (finished) {
        return;
      }
      tdlib.send(new TdApi.GetFile(fileId), (file, error) -> {
        if (finished) {
          return;
        }
        if (error != null) {
          finish(error);
          return;
        }
        if (file != null && file.local != null && file.local.isDownloadingCompleted && file.local.path != null && !file.local.path.isEmpty()) {
          callback.onReady(file.local.path);
          return;
        }
        if (attempt >= DOWNLOAD_POLL_MAX) {
          finish(new TdApi.Error(400, "Media download timed out"));
          return;
        }
        UI.post(() -> pollDownload(fileId, attempt + 1, callback), DOWNLOAD_POLL_MS);
      });
    }

    private void finish (@Nullable TdApi.Error error) {
      if (finished) {
        return;
      }
      finished = true;
      if (callback != null) {
        callback.onFinished(sent, error);
      }
    }
  }
}
