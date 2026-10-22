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

import android.os.Build;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.tool.UI;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    private final List<String> createdFiles = new ArrayList<>();
    private int stagingCounter;

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
      List<TdApi.InputMessageContent> overflowCaptions = new ArrayList<>(1);
      prepareAlbum(album, 0, contents, overflowCaptions, () -> {
        if (finished) {
          return;
        }
        if (contents.isEmpty()) {
          if (overflowCaptions.isEmpty()) {
            index += album.length;
            processNext();
          } else {
            sendOverflowTexts(overflowCaptions, album.length);
          }
        } else {
          sendContents(contents, album.length, overflowCaptions);
        }
      });
    }

    private void prepareAlbum (TdApi.Message[] album, int i, List<TdApi.InputMessageContent> out, List<TdApi.InputMessageContent> overflowCaptions, Runnable onReady) {
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
        addContent(out, overflowCaptions, message, null);
        prepareAlbum(album, i + 1, out, overflowCaptions, onReady);
        return;
      }
      ensureDownloaded(file, localPath -> {
        addContent(out, overflowCaptions, message, localPath);
        prepareAlbum(album, i + 1, out, overflowCaptions, onReady);
      });
    }

    private void addContent (List<TdApi.InputMessageContent> out, List<TdApi.InputMessageContent> overflowCaptions, TdApi.Message message, @Nullable String localPath) {
      TdApi.InputMessageContent content = TD.toInputMessageContent(message.content, removeCaption, localPath);
      if (content != null) {
        moveOverlongCaptionToText(content, overflowCaptions);
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
      List<TdApi.InputMessageContent> overflowCaptions = new ArrayList<>(1);
      moveOverlongCaptionToText(input, overflowCaptions);
      contents.add(input);
      sendContents(contents, consumed, overflowCaptions);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void sendContents (List<TdApi.InputMessageContent> contents, int consumed, List<TdApi.InputMessageContent> overflowTexts) {
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
          if (isCaptionTooLong(error)) {
            // Proactive split missed this case (different length counting on the server) -
            // strip every caption and retry once, posting captions as text messages.
            List<TdApi.InputMessageContent> texts = new ArrayList<>(overflowTexts);
            for (TdApi.InputMessageContent content : contents) {
              TdApi.FormattedText caption = mediaCaption(content);
              if (caption != null && caption.text != null && !caption.text.isEmpty()) {
                setMediaCaption(content, null);
                texts.add(new TdApi.InputMessageText(caption, null, false));
              }
            }
            sendOverflowText(texts, 0, consumed);
            return;
          }
          finish(error);
          return;
        }
        sent += contents.size();
        if (callback != null) {
          callback.onProgress(sent, messages.length);
        }
        if (overflowTexts.isEmpty()) {
          index += consumed;
          processNext();
        } else {
          sendOverflowTexts(overflowTexts, consumed);
        }
      });
    }

    private static boolean isCaptionTooLong (TdApi.Error error) {
      return error.code == 400 && error.message != null && error.message.contains("caption is too long");
    }

    /**
     * Telegram allows up to 1024 characters in a media caption, but plain text messages
     * may be up to 4096 characters. Long channel posts that travel as a media caption do
     * not fit into the caption of a re-published copy and the server rejects the whole
     * message with "Message caption is too long". The official apps work around this by
     * sending the media without a caption and posting the caption as separate text
     * messages right after it - do the same here.
     */
    private void moveOverlongCaptionToText (TdApi.InputMessageContent content, List<TdApi.InputMessageContent> out) {
      if (removeCaption) {
        return;
      }
      TdApi.FormattedText caption = mediaCaption(content);
      if (caption == null || caption.text == null || caption.text.isEmpty()) {
        return;
      }
      if (caption.text.codePointCount(0, caption.text.length()) <= tdlib.maxCaptionLength()) {
        return;
      }
      setMediaCaption(content, null);
      out.add(new TdApi.InputMessageText(caption, null, false));
    }

    private static @Nullable TdApi.FormattedText mediaCaption (TdApi.InputMessageContent content) {
      switch (content.getConstructor()) {
        case TdApi.InputMessagePhoto.CONSTRUCTOR:
          return ((TdApi.InputMessagePhoto) content).caption;
        case TdApi.InputMessageVideo.CONSTRUCTOR:
          return ((TdApi.InputMessageVideo) content).caption;
        case TdApi.InputMessageDocument.CONSTRUCTOR:
          return ((TdApi.InputMessageDocument) content).caption;
        case TdApi.InputMessageAnimation.CONSTRUCTOR:
          return ((TdApi.InputMessageAnimation) content).caption;
        case TdApi.InputMessageAudio.CONSTRUCTOR:
          return ((TdApi.InputMessageAudio) content).caption;
        case TdApi.InputMessageVoiceNote.CONSTRUCTOR:
          return ((TdApi.InputMessageVoiceNote) content).caption;
        default:
          return null;
      }
    }

    private static void setMediaCaption (TdApi.InputMessageContent content, @Nullable TdApi.FormattedText caption) {
      switch (content.getConstructor()) {
        case TdApi.InputMessagePhoto.CONSTRUCTOR:
          ((TdApi.InputMessagePhoto) content).caption = caption;
          break;
        case TdApi.InputMessageVideo.CONSTRUCTOR:
          ((TdApi.InputMessageVideo) content).caption = caption;
          break;
        case TdApi.InputMessageDocument.CONSTRUCTOR:
          ((TdApi.InputMessageDocument) content).caption = caption;
          break;
        case TdApi.InputMessageAnimation.CONSTRUCTOR:
          ((TdApi.InputMessageAnimation) content).caption = caption;
          break;
        case TdApi.InputMessageAudio.CONSTRUCTOR:
          ((TdApi.InputMessageAudio) content).caption = caption;
          break;
        case TdApi.InputMessageVoiceNote.CONSTRUCTOR:
          ((TdApi.InputMessageVoiceNote) content).caption = caption;
          break;
      }
    }

    private void sendOverflowTexts (List<TdApi.InputMessageContent> texts, int consumed) {
      sendOverflowText(texts, 0, consumed);
    }

    private void sendOverflowText (List<TdApi.InputMessageContent> texts, int i, int consumed) {
      if (finished) {
        return;
      }
      if (i >= texts.size()) {
        index += consumed;
        processNext();
        return;
      }
      List<TdApi.InputMessageContent> parts = TD.explodeText(texts.get(i), tdlib.maxMessageTextLength());
      sendTextPart(parts, 0, () -> sendOverflowText(texts, i + 1, consumed));
    }

    private void sendTextPart (List<TdApi.InputMessageContent> parts, int i, Runnable then) {
      if (finished) {
        return;
      }
      if (i >= parts.size()) {
        then.run();
        return;
      }
      @SuppressWarnings({"unchecked", "rawtypes"})
      TdApi.Function rawFunction = new TdApi.SendMessage(toChatId, topicId, null, options, null, parts.get(i));
      tdlib.send(rawFunction, (result, error) -> {
        if (finished) {
          return;
        }
        if (error != null) {
          finish(error);
          return;
        }
        sendTextPart(parts, i + 1, then);
      });
    }

    private void ensureDownloaded (TdApi.File file, PathCallback callback) {
      if (TD.isFileLoadedAndExists(file)) {
        deliverUploadablePath(file.local.path, callback);
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
          deliverUploadablePath(file.local.path, callback);
          return;
        }
        if (attempt >= DOWNLOAD_POLL_MAX) {
          finish(new TdApi.Error(400, "Media download timed out"));
          return;
        }
        UI.post(() -> pollDownload(fileId, attempt + 1, callback), DOWNLOAD_POLL_MS);
      });
    }

    /**
     * TDLib indexes local files by path and remembers the remote file id that was
     * once bound to that path. Handing over the very file TDLib downloaded from a
     * protected chat therefore makes it reuse the original remote id, and the
     * server rejects the message with CHAT_FORWARDS_RESTRICTED — exactly as if we
     * had sent InputFileId. Presenting the same bytes under a path TDLib has never
     * seen forces a genuine upload, which the server accepts.
     */
    private void deliverUploadablePath (String sourcePath, PathCallback callback) {
      final File source = new File(sourcePath);
      if (!source.isFile()) {
        callback.onReady(sourcePath);
        return;
      }
      Background.instance().post(() -> {
        String staged = null;
        File dir = new File(UI.getContext().getCacheDir(), "reupload");
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
          Log.e("Cannot create staging dir %s", dir);
        } else {
          File target = new File(dir, "upload_" + System.nanoTime() + "_" + (stagingCounter++) + extensionOf(source));
          if (target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
          }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
              // Zero-copy: the same bytes under a path TDLib has no record of.
              java.nio.file.Files.createLink(target.toPath(), source.toPath());
              staged = target.getAbsolutePath();
              createdFiles.add(staged);
            } catch (Throwable ignored) {
              // different volume or hard links unsupported: fall through to copy
            }
          }
          if (staged == null) {
            try {
              copyFile(source, target);
              staged = target.getAbsolutePath();
              createdFiles.add(staged);
            } catch (Throwable t) {
              Log.e("Cannot stage %s for re-upload", t, sourcePath);
            }
          }
        }
        final String readyPath = staged != null ? staged : sourcePath;
        UI.post(() -> {
          if (!finished) {
            callback.onReady(readyPath);
          }
        });
      });
    }

    private static String extensionOf (File file) {
      String name = file.getName();
      int dot = name.lastIndexOf('.');
      return dot > 0 && dot < name.length() - 1 ? name.substring(dot) : "";
    }

    private static void copyFile (File source, File target) throws IOException {
      try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
          out.write(buffer, 0, read);
        }
        out.flush();
      }
    }

    private void scheduleCleanup () {
      if (createdFiles.isEmpty()) {
        return;
      }
      final List<String> files = new ArrayList<>(createdFiles);
      createdFiles.clear();
      // Uploads are done by now, but TDLib may still hold the file briefly: wait.
      UI.post(() -> Background.instance().post(() -> {
        for (String path : files) {
          //noinspection ResultOfMethodCallIgnored
          new File(path).delete();
        }
      }), 60_000L);
    }

    private void finish (@Nullable TdApi.Error error) {
      if (finished) {
        return;
      }
      finished = true;
      scheduleCleanup();
      if (callback != null) {
        // "[copy]" tells a download+re-upload failure apart from a plain forward
        // failure when a bug report comes in.
        callback.onFinished(sent, error != null ? new TdApi.Error(error.code, (error.message != null ? error.message : "") + " [copy]") : null);
      }
    }
  }
}
