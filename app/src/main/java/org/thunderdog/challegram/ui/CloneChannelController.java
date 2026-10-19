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
  private int loadIndex;

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
    buildCells();

    final int limit = LOAD_LIMITS[loadIndex];
    tdlib.send(new TdApi.GetChats(new TdApi.ChatListMain(), limit), (chats, error) -> {
      UI.post(() -> {
        if (isDestroyed()) {
          return;
        }
        if (error != null) {
          if (loadIndex < LOAD_LIMITS.length - 1) {
            loadIndex++;
            loadChannels();
          } else {
            loading = false;
            buildCells();
          }
          return;
        }
        boolean mayBeMore = chats != null && chats.chatIds != null && chats.chatIds.length >= limit;
        if (mayBeMore && loadIndex < LOAD_LIMITS.length - 1) {
          loadIndex++;
          loadChannels();
          return;
        }
        targets.clear();
        if (chats != null && chats.chatIds != null) {
          for (long chatId : chats.chatIds) {
            TdApi.Chat chat = tdlib.chat(chatId);
            if (isEligibleTarget(chat)) {
              targets.add(chat);
            }
          }
        }
        loading = false;
        buildCells();
      });
    });
  }

  private static boolean isEligibleTarget (@Nullable TdApi.Chat chat) {
    if (chat == null || !(chat.type instanceof TdApi.ChatTypeSupergroup)) {
      return false;
    }
    if (!((TdApi.ChatTypeSupergroup) chat.type).isChannel) {
      return false;
    }
    return chat.permissions != null && chat.permissions.canSendBasicMessages;
  }

  @Override
  public void onClick (View v) {
    int id = v.getId();
    if (id == R.id.btn_cloneNewChannel) {
      openCreateChannel();
    } else if (id == R.id.btn_cloneTargetRetry) {
      loadIndex = 0;
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
