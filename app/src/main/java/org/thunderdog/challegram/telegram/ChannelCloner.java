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
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.tool.UI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import me.vkryl.core.StringUtils;

/**
 * Copies a channel (profile + messages) into another channel.
 * Messages are re-published instead of being forwarded, so it also works for
 * channels that forbid forwarding.
 */
public final class ChannelCloner {
  public static final int LIMIT_ALL = -1;

  private static final int PAGE_SIZE = 100;
  private static final long PAGE_DELAY_MS = 150L;

  public interface Listener {
    void onProgress (int copied, int total);

    void onFinished (int copied, @Nullable String errorMessage);
  }

  public static void start (Tdlib tdlib, long sourceChatId, long targetChatId, int limit, @Nullable Listener listener) {
    new Session(tdlib, sourceChatId, targetChatId, limit, listener).start();
  }

  private static final class Session {
    private final Tdlib tdlib;
    private final long sourceChatId;
    private final long targetChatId;
    private final int limit;
    private final @Nullable Listener listener;

    private final List<long[]> pages = new ArrayList<>();
    private int collected;
    private int copied;

    private int pageIndex;
    private int skipInOldestPage;

    private boolean finished;

    Session (Tdlib tdlib, long sourceChatId, long targetChatId, int limit, @Nullable Listener listener) {
      this.tdlib = tdlib;
      this.sourceChatId = sourceChatId;
      this.targetChatId = targetChatId;
      this.limit = limit;
      this.listener = listener;
    }

    void start () {
      TdApi.Chat sourceChat = tdlib.chat(sourceChatId);
      TdApi.Chat targetChat = tdlib.chat(targetChatId);
      if (sourceChat == null) {
        finish(0, Lang.getString(R.string.CloneChannelSourceMissing));
        return;
      }
      if (targetChat == null || !canPostTo(targetChat)) {
        finish(0, Lang.getString(R.string.CloneChannelTargetNoRights));
        return;
      }
      copyProfile(sourceChat, targetChat);
      loadPage(0);
    }

    private boolean canPostTo (TdApi.Chat chat) {
      // chat.permissions describes NON-admin members; for the current user check own status
      TdApi.ChatMemberStatus status = tdlib.chatStatus(chat.id);
      if (status != null) {
        switch (status.getConstructor()) {
          case TdApi.ChatMemberStatusCreator.CONSTRUCTOR:
            return true;
          case TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR: {
            TdApi.ChatAdministratorRights rights = ((TdApi.ChatMemberStatusAdministrator) status).rights;
            return rights != null && rights.canPostMessages;
          }
          default:
            return false;
        }
      }
      // Status not cached yet (e.g. a chat created moments ago): stay optimistic,
      // an actual send failure will be reported with the server error.
      return true;
    }

    private void copyProfile (TdApi.Chat sourceChat, TdApi.Chat targetChat) {
      if (targetChat.photo == null && sourceChat.photo != null && sourceChat.photo.big != null && sourceChat.photo.big.id != 0) {
        tdlib.send(new TdApi.SetChatPhoto(targetChatId, new TdApi.InputChatPhotoStatic(new TdApi.InputFileId(sourceChat.photo.big.id))), tdlib.typedOkHandler());
      }
      if (sourceChat.type instanceof TdApi.ChatTypeSupergroup) {
        long supergroupId = ((TdApi.ChatTypeSupergroup) sourceChat.type).supergroupId;
        tdlib.send(new TdApi.GetSupergroupFullInfo(supergroupId), (fullInfo, error) -> {
          if (fullInfo != null && !StringUtils.isEmpty(fullInfo.description)) {
            tdlib.send(new TdApi.SetChatDescription(targetChatId, fullInfo.description), tdlib.typedOkHandler());
          }
        });
      }
    }

    private void loadPage (long fromMessageId) {
      tdlib.send(new TdApi.GetChatHistory(sourceChatId, fromMessageId, 0, PAGE_SIZE, false), (messages, error) -> {
        if (finished) {
          return;
        }
        if (error != null) {
          finish(copied, error.message);
          return;
        }
        if (messages == null || messages.messages.length == 0) {
          startSending();
          return;
        }
        long[] ids = new long[messages.messages.length];
        for (int i = 0; i < ids.length; i++) {
          ids[i] = messages.messages[i].id;
        }
        pages.add(ids);
        collected += ids.length;
        if (limit != LIMIT_ALL && collected >= limit) {
          startSending();
          return;
        }
        long nextFromMessageId = ids[ids.length - 1];
        UI.post(() -> loadPage(nextFromMessageId), PAGE_DELAY_MS);
      });
    }

    private void startSending () {
      if (pages.isEmpty()) {
        finish(0, null);
        return;
      }
      if (limit != LIMIT_ALL && collected > limit) {
        skipInOldestPage = collected - limit;
      }
      pageIndex = pages.size() - 1;
      sendNextPage();
    }

    private void sendNextPage () {
      if (finished) {
        return;
      }
      if (pageIndex < 0) {
        finish(copied, null);
        return;
      }
      long[] pageIds = pages.get(pageIndex);
      int skip = pageIndex == pages.size() - 1 ? skipInOldestPage : 0;
      if (skip >= pageIds.length) {
        pageIndex--;
        sendNextPage();
        return;
      }
      long[] ids = skip > 0 ? Arrays.copyOfRange(pageIds, skip, pageIds.length) : pageIds;
      tdlib.send(new TdApi.GetMessages(sourceChatId, ids), (messages, error) -> {
        if (finished) {
          return;
        }
        if (error != null) {
          finish(copied, error.message);
          return;
        }
        List<TdApi.Message> ordered = new ArrayList<>(messages.messages.length);
        for (TdApi.Message message : messages.messages) {
          if (message != null) {
            ordered.add(message);
          }
        }
        Collections.sort(ordered, (a, b) -> Long.compare(a.id, b.id));

        final int total = limit == LIMIT_ALL ? collected : Math.min(collected, limit);
        ContentCopySender.send(tdlib, targetChatId, null, ordered.toArray(new TdApi.Message[0]), false, ContentCopySender.defaultSendOptions(), new ContentCopySender.Callback() {
          @Override
          public void onProgress (int sent, int totalMessages) {
            if (listener != null) {
              listener.onProgress(copied + sent, total);
            }
          }

          @Override
          public void onFinished (int sent, TdApi.Error error) {
            if (finished) {
              return;
            }
            if (error != null) {
              finish(copied + sent, error.message);
              return;
            }
            copied += sent;
            if (listener != null) {
              listener.onProgress(copied, total);
            }
            pageIndex--;
            UI.post(() -> {
              if (!finished) {
                sendNextPage();
              }
            }, PAGE_DELAY_MS);
          }
        });
      });
    }

    private void finish (int copied, @Nullable String errorMessage) {
      if (finished) {
        return;
      }
      finished = true;
      if (listener != null) {
        listener.onFinished(copied, errorMessage);
      }
    }
  }
}
