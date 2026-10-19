/*
 * Saves the content behind a t.me message link into the user's own chat.
 *
 * This is the native counterpart of the popular "save restricted content" bots:
 * they resolve a link on a user session and re-publish the message, because the
 * server refuses to forward protected content. Here the same three-step chain is
 * used, from the cheapest to the most compatible:
 *
 *   1. Cloudflare relay  — one HTTP call, the bot copies the message server-side
 *   2. Bot API           — the bot copies the message server-side
 *   3. Local re-upload   — download the media and upload it as a brand new file
 *
 * Steps 1 and 2 need a bot that can see the source chat and post to the target.
 * Step 3 always works, as long as the current account can read the source and
 * post to the target, and it is the only step that bypasses content protection.
 */
package org.thunderdog.challegram.telegram;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.BotRelayConfig;

import java.util.Collections;

public final class RestrictedContentSaver {
  private static final int STAGE_REMOTE = 0;
  private static final int STAGE_BOT = 1;
  private static final int STAGE_LOCAL = 2;

  public interface Listener {
    void onProgress (int sent, int total);

    void onFinished (int sent, @Nullable String errorMessage);
  }

  private RestrictedContentSaver () { }

  /**
   * @param link    any message link (public channel, t.me/c/…, group message)
   * @param targetChatId chat that receives the copy (Saved Messages is fine)
   */
  public static void save (Tdlib tdlib, String link, long targetChatId, @Nullable Listener listener) {
    if (link == null || link.trim().isEmpty()) {
      notifyFinished(listener, 0, Lang.getString(R.string.BotRelaySaveLinkEmpty));
      return;
    }
    tdlib.send(new TdApi.GetMessageLinkInfo(link.trim()), (info, error) -> UI.post(() -> {
      if (error != null) {
        notifyFinished(listener, 0, !error.message.isEmpty() ? error.message : Lang.getString(R.string.BotRelaySaveLinkInvalid));
        return;
      }
      if (info == null || info.message == null) {
        notifyFinished(listener, 0, Lang.getString(R.string.BotRelaySaveLinkNoAccess));
        return;
      }
      copy(tdlib, info.message, targetChatId, listener, STAGE_REMOTE);
    }));
  }

  private static void copy (Tdlib tdlib, TdApi.Message message, long targetChatId, @Nullable Listener listener, int stage) {
    switch (stage) {
      case STAGE_REMOTE: {
        if (RemoteRelayClient.isConfigured()) {
          RemoteRelayClient.copyMessages(targetChatId, message.chatId, Collections.singletonList(message.id), result -> {
            if (result.error == null && result.copied > 0) {
              notifyFinished(listener, result.copied, null);
            } else {
              copy(tdlib, message, targetChatId, listener, STAGE_BOT);
            }
          });
          return;
        }
        copy(tdlib, message, targetChatId, listener, STAGE_BOT);
        return;
      }
      case STAGE_BOT: {
        if (BotRelayConfig.canUseBot()) {
          BotApiClient.copyMessage(targetChatId, message.chatId, message.id, (result, errorCode, errorMessage) -> {
            if (errorCode == 0 && result != null) {
              notifyFinished(listener, 1, null);
            } else {
              copy(tdlib, message, targetChatId, listener, STAGE_LOCAL);
            }
          });
          return;
        }
        copy(tdlib, message, targetChatId, listener, STAGE_LOCAL);
        return;
      }
      default: {
        ContentCopySender.send(tdlib, targetChatId, null, new TdApi.Message[] {message}, false, ContentCopySender.defaultSendOptions(), new ContentCopySender.Callback() {
          @Override
          public void onProgress (int sent, int total) {
            if (listener != null) {
              listener.onProgress(sent, total);
            }
          }

          @Override
          public void onFinished (int sent, TdApi.Error error) {
            notifyFinished(listener, sent, error != null ? (!error.message.isEmpty() ? error.message : "error " + error.code) : null);
          }
        });
      }
    }
  }

  private static void notifyFinished (@Nullable Listener listener, int sent, @Nullable String errorMessage) {
    if (listener != null) {
      UI.post(() -> listener.onFinished(sent, errorMessage));
    }
  }
}
