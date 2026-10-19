/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.ChannelCloner;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the user pick a target channel (own channels + "create new one")
 * and starts cloning of the source channel into it.
 */
public class CloneChannelController extends RecyclerViewController<CloneChannelController.Args> implements View.OnClickListener {
  public static class Args {
    public final long sourceChatId;

    public Args (long sourceChatId) {
      this.sourceChatId = sourceChatId;
    }
  }

  private static final int[] LOAD_LIMITS = {100, 300, 600};

  public CloneChannelController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_cloneChannel;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.CloneChannel);
  }

  private SettingsAdapter adapter;
  private final List<TdApi.Chat> targets = new ArrayList<>();
  private long sourceChatId;
  private boolean loading = true;

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    Args args = getArguments();
    sourceChatId = args != null ? args.sourceChatId : 0;

    adapter = new SettingsAdapter(this);
    recyclerView.setAdapter(adapter);

    buildCells();
    loadChannels();
  }

  private void buildCells () {
    List<ListItem> items = new ArrayList<>();

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.CloneChannelSelectTarget));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_cloneNewChannel, R.drawable.baseline_add_24, R.string.CloneChannelNew));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));

    if (loading) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.CloneChannelLoading));
    } else if (targets.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.CloneChannelNoTargets));
    } else {
      for (int i = 0; i < targets.size(); i++) {
        ListItem item = new ListItem(ListItem.TYPE_SETTING, R.id.btn_cloneTarget, 0, targets.get(i).title);
        item.setData(i);
        items.add(item);
        if (i != targets.size() - 1) {
          items.add(new ListItem(ListItem.TYPE_SEPARATOR));
        }
      }
    }

    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.CloneChannelSelectTargetInfo));

    if (adapter != null) {
      adapter.setItems(items, false);
    }
  }

  private void loadChannels () {
    loading = true;
    targets.clear();
    buildCells();

    List<Long> chatIds = new ArrayList<>();
    loadChatList(new TdApi.ChatListMain(), chatIds, 0, () ->
      UI.post(() -> {
        if (isDestroyed()) {
          return;
        }
        loadChatList(new TdApi.ChatListArchive(), chatIds, 0, () ->
          UI.post(() -> {
            if (isDestroyed()) {
              return;
            }
            processCollectedChats(chatIds);
          }));
      }));
  }

  private void loadChatList (TdApi.ChatList list, List<Long> out, int limitIndex, Runnable after) {
    if (isDestroyed() || limitIndex >= LOAD_LIMITS.length) {
      after.run();
      return;
    }
    final int limit = LOAD_LIMITS[limitIndex];
    tdlib.send(new TdApi.GetChats(list, limit), (chats, error) -> {
      UI.post(() -> {
        if (isDestroyed()) {
          return;
        }
        if (error != null || chats == null || chats.chatIds == null) {
          after.run();
          return;
        }
        int fetched = 0;
        for (long chatId : chats.chatIds) {
          if (chatId != 0 && !out.contains(chatId)) {
            out.add(chatId);
            fetched++;
          }
        }
        if (fetched >= limit && limitIndex < LOAD_LIMITS.length - 1) {
          loadChatList(list, out, limitIndex + 1, after);
        } else {
          after.run();
        }
      });
    });
  }

  private void processCollectedChats (List<Long> chatIds) {
    processNextChat(chatIds, 0);
  }

  private void processNextChat (List<Long> chatIds, int index) {
    if (isDestroyed()) {
      return;
    }
    if (index >= chatIds.size()) {
      loading = false;
      buildCells();
      return;
    }
    long chatId = chatIds.get(index);
    TdApi.Chat chat = tdlib.chat(chatId);
    if (chat != null) {
      if (isEligibleTarget(chat)) {
        targets.add(chat);
      }
      processNextChat(chatIds, index + 1);
      return;
    }
    tdlib.send(new TdApi.GetChat(chatId), (loaded, error) -> {
      UI.post(() -> {
        if (isDestroyed()) {
          return;
        }
        if (loaded != null && isEligibleTarget(loaded)) {
          targets.add(loaded);
        }
        processNextChat(chatIds, index + 1);
      });
    });
  }

  private boolean isEligibleTarget (TdApi.Chat chat) {
    if (chat == null || !(chat.type instanceof TdApi.ChatTypeSupergroup)) {
      return false;
    }
    if (!((TdApi.ChatTypeSupergroup) chat.type).isChannel) {
      return false;
    }
    // chat.permissions describes what NON-ADMIN members may do (always false for
    // broadcast channels), so instead check the current user's own member status.
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
    return chat.permissions != null && chat.permissions.canSendBasicMessages;
  }

  @Override
  public void onClick (View v) {
    int id = v.getId();
    if (id == R.id.btn_cloneNewChannel) {
      openCreateChannel();
    } else if (id == R.id.btn_cloneTargetRetry) {
      loadChannels();
    } else if (id == R.id.btn_cloneTarget) {
      Object data = ((ListItem) v.getTag()).getData();
      if (data instanceof Integer) {
        int index = (Integer) data;
        if (index >= 0 && index < targets.size()) {
          showRangePicker(targets.get(index));
        }
      }
    }
  }

  private void openCreateChannel () {
    CreateChannelController c = new CreateChannelController(context, tdlib);
    c.setArguments(new String[] {tdlib.chatTitle(sourceChatId), sourceDescription()});
    c.setOnChannelCreatedListener(chat -> {
      if (chat != null) {
        showRangePicker(chat);
      }
    });
    navigateTo(c);
  }

  private String sourceDescription () {
    TdApi.Chat chat = tdlib.chat(sourceChatId);
    if (chat != null && chat.type instanceof TdApi.ChatTypeSupergroup) {
      TdApi.SupergroupFullInfo fullInfo = tdlib.cache().supergroupFull(((TdApi.ChatTypeSupergroup) chat.type).supergroupId);
      if (fullInfo != null && fullInfo.description != null) {
        return fullInfo.description;
      }
    }
    return "";
  }

  private void showRangePicker (final TdApi.Chat target) {
    showOptions(Lang.getString(R.string.CloneChannelRange) + " · " + target.title, new int[] {
      R.id.btn_cloneRange100,
      R.id.btn_cloneRange1000,
      R.id.btn_cloneRangeAll
    }, new String[] {
      Lang.getString(R.string.CloneChannelRange100),
      Lang.getString(R.string.CloneChannelRange1000),
      Lang.getString(R.string.CloneChannelRangeAll)
    }, null, new int[] {
      R.drawable.baseline_history_24,
      R.drawable.baseline_history_24,
      R.drawable.baseline_arrow_downward_24
    }, (itemView, id) -> {
      int limit;
      if (id == R.id.btn_cloneRange100) {
        limit = 100;
      } else if (id == R.id.btn_cloneRange1000) {
        limit = 1000;
      } else if (id == R.id.btn_cloneRangeAll) {
        limit = ChannelCloner.LIMIT_ALL;
      } else {
        return true;
      }
      startClone(target, limit);
      return true;
    });
  }

  private void startClone (TdApi.Chat target, int limit) {
    if (limit == 0) {
      return;
    }
    UI.showToast(Lang.getStringBold(R.string.CloneChannelStarted, tdlib.chatTitle(sourceChatId), target.title), Toast.LENGTH_SHORT);
    ChannelCloner.start(tdlib, sourceChatId, target.id, limit, new ChannelCloner.Listener() {
      @Override
      public void onProgress (int copied, int total) { }

      @Override
      public void onFinished (int copied, @Nullable String errorMessage) {
        UI.post(() -> {
          if (errorMessage != null) {
            UI.showToast(Lang.getString(R.string.CloneChannelFailed, errorMessage), Toast.LENGTH_LONG);
          } else {
            UI.showToast(Lang.getString(R.string.CloneChannelDone, copied), Toast.LENGTH_SHORT);
          }
        });
      }
    });
    navigateBack();
  }
}
