/*
 * "Restricted content" settings screen.
 *
 * Bundles everything that is not required for the built-in bypass but makes it
 * much more capable:
 *  - link based saving (the native equivalent of external save-restricted bots)
 *  - optional BOT_TOKEN for the server-side fast path
 *  - optional API_ID / API_HASH for external services / self-hosting
 *  - optional Cloudflare relay endpoint
 */
package org.thunderdog.challegram.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.BotApiClient;
import org.thunderdog.challegram.telegram.RestrictedContentSaver;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.BotRelayConfig;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SettingsBotRelayController extends RecyclerViewController<Void> implements View.OnClickListener {
  private static final int MAX_TARGETS = 30;

  public SettingsBotRelayController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_botRelay;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.BotRelayTitle);
  }

  private SettingsAdapter adapter;
  private final List<TdApi.Chat> postableChats = new ArrayList<>();
  private @Nullable String statusText;

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        // R.id.* are not compile-time constants in this project
        // (android.nonFinalResIds=true), so a switch is not possible here.
        final int id = item.getId();
        if (id == R.id.btn_saveLinkStart) {
          view.setData(statusText != null ? statusText : "");
        } else if (id == R.id.btn_botRelayUseBot) {
          view.getToggler().setRadioEnabled(BotRelayConfig.useBotRelay(), isUpdate);
        } else if (id == R.id.btn_botRelayUseRemote) {
          view.getToggler().setRadioEnabled(BotRelayConfig.useRemoteRelay(), isUpdate);
        } else if (id == R.id.btn_botRelayToken) {
          view.setData(BotRelayConfig.hasBot() ? BotRelayConfig.getBotTokenPreview() : Lang.getString(R.string.BotRelayNotSet));
        } else if (id == R.id.btn_botRelayTest) {
          String username = BotRelayConfig.getBotUsername();
          view.setData(!username.isEmpty() ? "@" + username : "");
        } else if (id == R.id.btn_botRelayApiId) {
          view.setData(BotRelayConfig.getApiId() != 0 ? String.valueOf(BotRelayConfig.getApiId()) : Lang.getString(R.string.BotRelayNotSet));
        } else if (id == R.id.btn_botRelayApiHash) {
          String hash = BotRelayConfig.getApiHash();
          if (hash.isEmpty()) {
            view.setData(Lang.getString(R.string.BotRelayNotSet));
          } else if (hash.length() <= 8) {
            view.setData("••••");
          } else {
            view.setData(hash.substring(0, 4) + "••••" + hash.substring(hash.length() - 4));
          }
        } else if (id == R.id.btn_botRelayUrl) {
          String url = BotRelayConfig.getRelayUrl();
          view.setData(!url.isEmpty() ? url : Lang.getString(R.string.BotRelayNotSet));
        } else if (id == R.id.btn_botRelaySecret) {
          view.setData(BotRelayConfig.getRelaySecret().isEmpty() ? Lang.getString(R.string.BotRelayNotSet) : "••••••");
        } else {
          super.setValuedSetting(item, view, isUpdate);
        }
      }
    };
    buildCells();
    recyclerView.setAdapter(adapter);
    loadPostableChats();
  }

  private void buildCells () {
    List<ListItem> items = new ArrayList<>();

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.BotRelaySaveSection));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_saveLinkStart, R.drawable.baseline_link_24, R.string.BotRelaySaveLink));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.BotRelaySaveLinkInfo));

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.BotRelayBotSection));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_botRelayUseBot, 0, R.string.BotRelayUseBot));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_botRelayToken, R.drawable.baseline_sync_24, R.string.BotRelayToken));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_botRelayTest, R.drawable.baseline_send_24, R.string.BotRelayTest));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.BotRelayUseBotInfo));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.BotRelayTokenInfo));

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.BotRelayApiSection));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_botRelayApiId, R.drawable.baseline_vpn_key_24, R.string.BotRelayApiId));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_botRelayApiHash, R.drawable.baseline_vpn_key_24, R.string.BotRelayApiHash));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.BotRelayApiInfo));

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.BotRelayCloudSection));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_botRelayUseRemote, 0, R.string.BotRelayUseRemote));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_botRelayUrl, R.drawable.baseline_cloud_download_24, R.string.BotRelayUrl));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_botRelaySecret, R.drawable.baseline_lock_24, R.string.BotRelaySecret));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_botRelayHelp, R.drawable.baseline_help_24, R.string.BotRelayHelp));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.BotRelayCloudInfo));

    adapter.setItems(items, false);
  }

  // == Target chats ==

  private void loadPostableChats () {
    tdlib.send(new TdApi.GetChats(new TdApi.ChatListMain(), 100), (chats, error) -> UI.post(() -> {
      if (isDestroyed() || chats == null || chats.chatIds == null) {
        return;
      }
      postableChats.clear();
      for (long chatId : chats.chatIds) {
        TdApi.Chat chat = tdlib.chat(chatId);
        if (chat != null && isPostable(chat)) {
          postableChats.add(chat);
          if (postableChats.size() >= MAX_TARGETS) {
            break;
          }
        }
      }
    }));
  }

  private boolean isPostable (TdApi.Chat chat) {
    if (chat.type instanceof TdApi.ChatTypeSupergroup) {
      if (!((TdApi.ChatTypeSupergroup) chat.type).isChannel) {
        return chat.permissions != null && chat.permissions.canSendBasicMessages;
      }
      TdApi.ChatMemberStatus status = tdlib.chatStatus(chat.id);
      if (status == null) {
        return true;
      }
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
    return chat.permissions == null || chat.permissions.canSendBasicMessages;
  }

  // == Clicks ==

  @Override
  public void onClick (View v) {
    int id = v.getId();
    if (id == R.id.btn_saveLinkStart) {
      promptLink();
    } else if (id == R.id.btn_botRelayUseBot) {
      BotRelayConfig.setUseBotRelay(!BotRelayConfig.useBotRelay());
      adapter.updateValuedSettingById(R.id.btn_botRelayUseBot);
    } else if (id == R.id.btn_botRelayUseRemote) {
      BotRelayConfig.setUseRemoteRelay(!BotRelayConfig.useRemoteRelay());
      adapter.updateValuedSettingById(R.id.btn_botRelayUseRemote);
    } else if (id == R.id.btn_botRelayToken) {
      promptBotToken();
    } else if (id == R.id.btn_botRelayTest) {
      testBot();
    } else if (id == R.id.btn_botRelayApiId) {
      promptApiId();
    } else if (id == R.id.btn_botRelayApiHash) {
      promptApiHash();
    } else if (id == R.id.btn_botRelayUrl) {
      promptRelayUrl();
    } else if (id == R.id.btn_botRelaySecret) {
      promptRelaySecret();
    } else if (id == R.id.btn_botRelayHelp) {
      openAlert(R.string.BotRelayHelp, Lang.getString(R.string.BotRelayHelpText), (dialog, which) -> { });
    }
  }

  // == Link saving ==

  private void promptLink () {
    showInput(R.string.BotRelaySaveLink, "", R.string.BotRelaySaveLinkHint, value -> {
      if (value.isEmpty()) {
        UI.showToast(R.string.BotRelaySaveLinkEmpty, Toast.LENGTH_SHORT);
        return;
      }
      pickTarget(value);
    });
  }

  private void pickTarget (String link) {
    if (postableChats.isEmpty()) {
      startSave(link, tdlib.selfChatId(), Lang.getString(R.string.BotRelaySavedMessages));
      return;
    }
    List<Integer> indices = new ArrayList<>();
    List<String> titles = new ArrayList<>();
    indices.add(-1);
    titles.add(Lang.getString(R.string.BotRelaySavedMessages));
    for (int i = 0; i < postableChats.size(); i++) {
      indices.add(i);
      titles.add(postableChats.get(i).title);
    }
    int[] ids = new int[indices.size()];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = indices.get(i);
    }
    showOptions(Lang.getString(R.string.BotRelayPickTarget), ids, titles.toArray(new String[0]), (itemView, id) -> {
      if (id == -1) {
        startSave(link, tdlib.selfChatId(), Lang.getString(R.string.BotRelaySavedMessages));
      } else if (id >= 0 && id < postableChats.size()) {
        TdApi.Chat target = postableChats.get(id);
        startSave(link, target.id, target.title);
      }
      return true;
    });
  }

  private void startSave (String link, long targetChatId, String targetTitle) {
    statusText = Lang.getString(R.string.BotRelayResolving);
    adapter.updateValuedSettingById(R.id.btn_saveLinkStart);
    RestrictedContentSaver.save(tdlib, link, targetChatId, new RestrictedContentSaver.Listener() {
      @Override
      public void onProgress (int sent, int total) {
        UI.post(() -> {
          statusText = Lang.getString(R.string.CloneChannelProgress, sent);
          adapter.updateValuedSettingById(R.id.btn_saveLinkStart);
        });
      }

      @Override
      public void onFinished (int sent, @Nullable String errorMessage) {
        statusText = null;
        adapter.updateValuedSettingById(R.id.btn_saveLinkStart);
        if (errorMessage != null) {
          UI.showToast(Lang.getString(R.string.BotRelaySaveFailed, errorMessage), Toast.LENGTH_LONG);
        } else {
          UI.showToast(Lang.getString(R.string.BotRelaySaveDone, sent) + " · " + targetTitle, Toast.LENGTH_SHORT);
        }
      }
    });
  }

  // == Credentials ==

  private void promptBotToken () {
    showInput(R.string.BotRelayToken, BotRelayConfig.getBotToken(), R.string.BotRelayTokenHint, value -> {
      BotRelayConfig.setBotToken(value);
      BotRelayConfig.setBotUsername("");
      adapter.updateValuedSettingById(R.id.btn_botRelayToken);
      adapter.updateValuedSettingById(R.id.btn_botRelayTest);
      if (!value.isEmpty()) {
        testBot();
      }
    });
  }

  private void testBot () {
    if (!BotRelayConfig.hasBot()) {
      UI.showToast(R.string.BotRelayNotSet, Toast.LENGTH_SHORT);
      return;
    }
    UI.showToast(R.string.BotRelayTesting, Toast.LENGTH_SHORT);
    BotApiClient.getMe((result, errorCode, errorMessage) -> {
      if (errorCode == 0 && result != null) {
        String username = result.optString("username", "");
        BotRelayConfig.setBotUsername(username);
        adapter.updateValuedSettingById(R.id.btn_botRelayTest);
        UI.showToast(Lang.getString(R.string.BotRelayTestOk, username), Toast.LENGTH_SHORT);
      } else {
        UI.showToast(Lang.getString(R.string.BotRelayTestFailed, errorMessage != null ? errorMessage : "error " + errorCode), Toast.LENGTH_LONG);
      }
    });
  }

  private void promptApiId () {
    int current = BotRelayConfig.getApiId();
    showInput(R.string.BotRelayApiId, current != 0 ? String.valueOf(current) : "", R.string.BotRelayApiHint, value -> {
      int parsed = 0;
      try {
        parsed = Integer.parseInt(value.trim());
      } catch (NumberFormatException ignored) { }
      BotRelayConfig.setApiId(parsed);
      adapter.updateValuedSettingById(R.id.btn_botRelayApiId);
    });
  }

  private void promptApiHash () {
    showInput(R.string.BotRelayApiHash, BotRelayConfig.getApiHash(), R.string.BotRelayApiHashHint, value -> {
      BotRelayConfig.setApiHash(value);
      adapter.updateValuedSettingById(R.id.btn_botRelayApiHash);
    });
  }

  private void promptRelayUrl () {
    showInput(R.string.BotRelayUrl, BotRelayConfig.getRelayUrl(), R.string.BotRelayUrlHint, value -> {
      BotRelayConfig.setRelayUrl(value);
      adapter.updateValuedSettingById(R.id.btn_botRelayUrl);
    });
  }

  private void promptRelaySecret () {
    showInput(R.string.BotRelaySecret, BotRelayConfig.getRelaySecret(), R.string.BotRelaySecretHint, value -> {
      BotRelayConfig.setRelaySecret(value);
      adapter.updateValuedSettingById(R.id.btn_botRelaySecret);
    });
  }

  // == Input dialog ==

  private interface InputCallback {
    void onInput (String value);
  }

  private void showInput (@StringRes int titleRes, @Nullable String initial, @StringRes int hintRes, InputCallback callback) {
    final EditText input = new EditText(context);
    input.setSingleLine(true);
    if (initial != null && !initial.isEmpty()) {
      input.setText(initial);
      input.setSelection(initial.length());
    }
    input.setHint(Lang.getString(hintRes));

    LinearLayout container = new LinearLayout(context);
    int padding = Screen.dp(20f);
    container.setPadding(padding, padding / 2, padding, 0);
    container.addView(input);

    AlertDialog.Builder b = new AlertDialog.Builder(context, Theme.dialogTheme());
    b.setTitle(Lang.getString(titleRes));
    b.setView(container);
    b.setPositiveButton(Lang.getString(R.string.Done), (dialog, which) -> {
      String text = input.getText() != null ? input.getText().toString().trim() : "";
      callback.onInput(text);
    });
    b.setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
    showAlert(b);
  }
}
