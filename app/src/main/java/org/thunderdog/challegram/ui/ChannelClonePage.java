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

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.charts.LayoutHelper;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.DoubleTextWrapper;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.BackHeaderButton;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.telegram.ChatPosition;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibChatList;
import org.thunderdog.challegram.telegram.TdlibFilesManager;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.CheckBoxView;
import org.thunderdog.challegram.widget.SeparatorView;
import org.thunderdog.challegram.widget.SmallChatView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.RunnableData;

public class ChannelClonePage extends BottomSheetViewController.BottomSheetBaseRecyclerViewController<ChannelCloneController.Arguments> implements View.OnClickListener {
  private static final int STATE_TARGET = 0;
  private static final int STATE_OPTIONS = 1;
  private static final int STATE_PROGRESS = 2;
  private static final int STATE_DONE = 3;

  private static final int RANGE_ALL = 0;
  private static final int RANGE_100 = 100;
  private static final int RANGE_500 = 500;
  private static final int RANGE_1000 = 1000;
  private static final int HISTORY_BATCH_SIZE = 50;
  private static final int CHANNEL_LIST_TARGET_SIZE = 150;

  private static final float BUTTON_HEIGHT_DP = 56f;

  private final BottomSheetViewController<?> parent;
  private final List<TdApi.Chat> myChannels = new ArrayList<>();

  private SettingsAdapter adapter;
  private FrameLayoutFix actionButton;
  private SeparatorView bottomShadowView;

  private long sourceChatId;
  private long targetChatId;

  private int state = STATE_TARGET;
  private boolean loadingChannels;
  private boolean creatingChannel;

  private int cloneRange = RANGE_ALL;
  private boolean includeMedia = true;
  private boolean cloneChannelInfo = true;

  private MessageReuploader reuploader;
  private int cloneCompleted;
  private int cloneFailed;
  private int cloneTotal;
  private boolean cloneCancelled;
  private String cloneError;

  public ChannelClonePage (BottomSheetViewController<?> parent) {
    super(parent.context(), parent.tdlib());
    this.parent = parent;
  }

  @Override
  public void setArguments (ChannelCloneController.Arguments args) {
    super.setArguments(args);
    this.sourceChatId = args.sourceChatId;
  }

  @Override
  public int getId () {
    return R.id.controller_channelClone;
  }

  public HeaderView getHeaderView () {
    if (headerView == null) {
      headerView = new HeaderView(context);
      headerView.initWithSingleController(this, false);
      headerView.getFilling().setColor(Theme.fillingColor());
      headerView.getFilling().setShadowAlpha(1f);
      headerView.setWillNotDraw(false);
      addThemeInvalidateListener(headerView);
    }
    return headerView;
  }

  @Override
  public int getItemsHeight (RecyclerView parent) {
    int totalHeight = 0;
    List<ListItem> items = adapter.getItems();
    for (ListItem item : items) {
      int itemHeight;
      if (item.getViewType() == ListItem.TYPE_DESCRIPTION) {
        itemHeight = Math.max(Screen.dp(20f), item.getHeight());
      } else {
        itemHeight = SettingHolder.measureHeightForType(item);
      }
      totalHeight += itemHeight;
    }
    return totalHeight;
  }

  @Override
  public boolean supportsBottomInset () {
    return true;
  }

  @Override
  protected boolean needRecyclerBottomInset () {
    return false;
  }

  @Override
  protected void onBottomInsetChanged (int extraBottomInset, int extraBottomInsetWithoutIme, boolean isImeInset) {
    super.onBottomInsetChanged(extraBottomInset, extraBottomInsetWithoutIme, isImeInset);

    int buttonHeight = getActionButtonHeight();

    Views.setLayoutHeight(actionButton, buttonHeight);
    Views.setPaddingBottom(actionButton, extraBottomInsetWithoutIme);
    Views.setBottomMargin(getRecyclerView(), buttonHeight);
    Views.setBottomMargin(bottomShadowView, buttonHeight);
  }

  private int getActionButtonHeight () {
    return state == STATE_TARGET ? 0 : Screen.dp(BUTTON_HEIGHT_DP) + extraBottomInsetWithoutIme;
  }

  @Override
  protected View onCreateView (Context context) {
    View view = super.onCreateView(context);

    FrameLayoutFix wrap = (FrameLayoutFix) view;

    int buttonHeight = getActionButtonHeight();
    wrap.addView(actionButton = createActionButton(), FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, buttonHeight, Gravity.BOTTOM));
    Views.setPaddingBottom(actionButton, extraBottomInsetWithoutIme);
    updateActionButton();

    bottomShadowView = SeparatorView.simpleSeparator(context, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1f, Gravity.BOTTOM), false);
    addThemeInvalidateListener(bottomShadowView);
    bottomShadowView.setAlignBottom();
    wrap.addView(bottomShadowView);

    Views.setBottomMargin(getRecyclerView(), buttonHeight);
    Views.setBottomMargin(bottomShadowView, buttonHeight);
    return view;
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    getHeaderView();
    addThemeInvalidateListener(recyclerView);

    recyclerView.setItemAnimator(null);
    recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    recyclerView.setLayoutManager(new LinearLayoutManager(context, RecyclerView.VERTICAL, false));

    adapter = new Adapter(this);
    adapter.setNoEmptyProgress();
    recyclerView.setAdapter(adapter);
    buildCells();
    loadMyChannels();
  }

  private FrameLayoutFix createActionButton () {
    TextView button = new TextView(context);
    button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f);
    button.setTypeface(Fonts.getRobotoMedium());
    button.setPadding(Screen.dp(12f), 0, Screen.dp(12f), 0);
    button.setEllipsize(TextUtils.TruncateAt.END);
    button.setGravity(Gravity.CENTER);
    button.setAllCaps(true);

    FrameLayoutFix frame = new FrameLayoutFix(context);
    frame.setOnClickListener(this);
    frame.setId(R.id.btn_done);
    frame.addView(button, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
    RippleSupport.setSimpleWhiteBackground(frame, this);
    return frame;
  }

  private void updateActionButton () {
    boolean enabled = !isFinishing();
    boolean visible = state != STATE_TARGET;
    actionButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    actionButton.setEnabled(enabled);
    int textColorId;
    if (enabled) {
      textColorId = state == STATE_PROGRESS ? ColorId.textNegative : ColorId.textLink;
    } else {
      textColorId = ColorId.textLight;
    }
    TextView textView = (TextView) actionButton.getChildAt(0);
    textView.setTextColor(Theme.getColor(textColorId));
    removeThemeListenerByTarget(textView);
    addThemeTextColorListener(textView, textColorId);
    CharSequence text;
    switch (state) {
      case STATE_PROGRESS:
        text = Lang.getString(R.string.CloneStop);
        break;
      case STATE_DONE:
        text = Lang.getString(R.string.OK);
        break;
      case STATE_OPTIONS:
      case STATE_TARGET:
      default:
        text = Lang.getString(R.string.OK);
        break;
    }
    Views.setMediumText(textView, text);
  }

  private boolean isFinishing () {
    return parent.getPopupLayout().isWindowHidden();
  }

  // Cells

  private void buildCells () {
    List<ListItem> items = new ArrayList<>();
    switch (state) {
      case STATE_TARGET: {
        items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
        items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_newChannel, R.drawable.baseline_add_24, Lang.getString(R.string.NewChannel)));
        items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
        items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.CloneCreateChannelHint));
        items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.CloneMyChannels));
        items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
        if (loadingChannels) {
          items.add(new ListItem(ListItem.TYPE_PROGRESS));
        } else if (myChannels.isEmpty()) {
          items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.CloneNoChannels));
        } else {
          for (int index = 0; index < myChannels.size(); index++) {
            if (index > 0) {
              items.add(new ListItem(ListItem.TYPE_SEPARATOR));
            }
            items.add(chatItem(myChannels.get(index)));
          }
        }
        items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
        break;
      }
      case STATE_OPTIONS: {
        items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
        items.add(new ListItem(ListItem.TYPE_VALUED_SETTING, R.id.btn_cloneChangeTarget, R.drawable.baseline_edit_24,
          Lang.getString(R.string.CloneTarget)).setStringValue(tdlib.chatTitle(targetChatId)));
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        items.add(new ListItem(ListItem.TYPE_VALUED_SETTING, R.id.btn_cloneRange, 0, R.string.CloneRange)
          .setStringValue(rangeText()));
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_cloneIncludeMedia, 0, R.string.CloneIncludeMedia).setBoolValue(includeMedia));
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_cloneChannelInfo, 0, R.string.CloneChannelInfo).setBoolValue(cloneChannelInfo));
        items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
        items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.CloneOptionsDesc));
        break;
      }
      case STATE_PROGRESS: {
        items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
        items.add(new ListItem(ListItem.TYPE_VALUED_SETTING, R.id.btn_cloneProgress, 0, R.string.CloneProgressTitle)
          .setStringValue(Lang.getString(R.string.CloneProgress, cloneCompleted + cloneFailed, cloneTotal)));
        items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
        items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.Cloning));
        break;
      }
      case STATE_DONE:
      default: {
        items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
        CharSequence summary;
        if (cloneCancelled) {
          summary = Lang.getString(R.string.CloneCancelledProgress, cloneCompleted + cloneFailed);
        } else if (cloneError != null) {
          summary = Lang.getString(R.string.CloneFailed, cloneError);
        } else {
          summary = Lang.getString(R.string.CloneCompleted, cloneCompleted, cloneFailed);
        }
        items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, summary));
        items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
        break;
      }
    }
    adapter.replaceItems(items);
    updateActionButton();
    HeaderView headerView = getHeaderView();
    if (headerView != null) {
      headerView.setTitle(this);
    }
  }

  private CharSequence rangeText () {
    switch (cloneRange) {
      case RANGE_100:
      case RANGE_500:
      case RANGE_1000:
        return Lang.getString(R.string.CloneRecentMessages, String.valueOf(cloneRange));
      case RANGE_ALL:
      default:
        return Lang.getString(R.string.CloneAllMessages);
    }
  }

  private ListItem chatItem (TdApi.Chat chat) {
    DoubleTextWrapper data = new DoubleTextWrapper(tdlib, chat);
    data.setAdminSignVisible(false, false);
    String username = tdlib.chatUsername(chat.id);
    if (!StringUtils.isEmpty(username)) {
      data.setForcedSubtitle("@" + username);
    } else {
      data.setForcedSubtitle(Lang.getString(tdlib.isPublicChat(chat.id) ? R.string.ChannelPublic : R.string.ChannelPrivate));
    }
    return new ListItem(ListItem.TYPE_CHAT_SMALL, R.id.chat).setLongId(chat.id).setData(data);
  }

  @Override
  public CharSequence getName () {
    switch (state) {
      case STATE_OPTIONS:
        return Lang.getString(R.string.CloneOptionsTitle);
      case STATE_PROGRESS:
        return Lang.getString(R.string.CloneProgressTitle);
      case STATE_DONE:
        return Lang.getString(R.string.CloneDoneTitle);
      case STATE_TARGET:
      default:
        return Lang.getString(R.string.CloneChannelTitle);
    }
  }

  @Override
  public void onThemeColorsChanged (boolean areTemp, ColorState state) {
    super.onThemeColorsChanged(areTemp, state);
    if (headerView != null) {
      headerView.resetColors(this, null);
    }
  }

  @Override
  protected void openMoreMenu () { }

  @Override
  protected int getBackButton () {
    return BackHeaderButton.TYPE_CLOSE;
  }

  @Override
  protected int getMenuId () {
    return 0;
  }

  @Override
  protected int getHeaderTextColorId () {
    return ColorId.text;
  }

  @Override
  protected int getHeaderColorId () {
    return ColorId.filling;
  }

  @Override
  protected int getHeaderIconColorId () {
    return ColorId.icon;
  }

  @Override
  public boolean needsTempUpdates () {
    return true;
  }

  @Override
  public void onClick (View v) {
    if (isFinishing()) return;
    int id = v.getId();
    if (id == R.id.chat) {
      ListItem item = (ListItem) v.getTag();
      selectTargetChat(item.getLongId());
    } else if (id == R.id.btn_newChannel) {
      createNewChannel();
    } else if (id == R.id.btn_cloneChangeTarget) {
      state = STATE_TARGET;
      buildCells();
    } else if (id == R.id.btn_cloneRange) {
      showRangeOptions();
    } else if (id == R.id.btn_cloneIncludeMedia) {
      includeMedia = adapter.toggleView(v);
    } else if (id == R.id.btn_cloneChannelInfo) {
      cloneChannelInfo = adapter.toggleView(v);
    } else if (id == R.id.btn_done) {
      switch (state) {
        case STATE_TARGET:
          parent.hidePopupWindow(true);
          break;
        case STATE_OPTIONS:
          startClone();
          break;
        case STATE_PROGRESS:
          stopClone();
          break;
        case STATE_DONE:
        default:
          parent.hidePopupWindow(true);
          break;
      }
    }
  }

  private void showRangeOptions () {
    showOptions(Lang.getString(R.string.CloneRange),
      new int[] {R.id.btn_rangeAll, R.id.btn_range100, R.id.btn_range500, R.id.btn_range1000},
      new String[] {
        Lang.getString(R.string.CloneAllMessages),
        Lang.getString(R.string.CloneRecentMessages, "100"),
        Lang.getString(R.string.CloneRecentMessages, "500"),
        Lang.getString(R.string.CloneRecentMessages, "1000")
      },
      (itemView, id) -> {
        if (id == R.id.btn_rangeAll) {
          cloneRange = RANGE_ALL;
        } else if (id == R.id.btn_range100) {
          cloneRange = RANGE_100;
        } else if (id == R.id.btn_range500) {
          cloneRange = RANGE_500;
        } else if (id == R.id.btn_range1000) {
          cloneRange = RANGE_1000;
        }
        adapter.updateValuedSettingById(R.id.btn_cloneRange);
        return true;
      });
  }

  private void selectTargetChat (long chatId) {
    if (chatId == sourceChatId) {
      return;
    }
    this.targetChatId = chatId;
    this.state = STATE_OPTIONS;
    buildCells();
  }

  // Channels list

  private void loadMyChannels () {
    if (loadingChannels) {
      return;
    }
    loadingChannels = true;
    buildCells();
    TdlibChatList chatList = tdlib.chatList(ChatPosition.CHAT_LIST_MAIN);
    chatList.loadAtLeast(null, CHANNEL_LIST_TARGET_SIZE, () -> runOnUiThreadOptional(() -> {
      chatList.iterate(chat -> {
        if (isManageableChannel(chat) && chat.id != sourceChatId) {
          myChannels.add(chat);
        }
      });
      loadingChannels = false;
      if (state == STATE_TARGET) {
        buildCells();
      }
    }));
  }

  private boolean isManageableChannel (TdApi.Chat chat) {
    if (chat.type.getConstructor() != TdApi.ChatTypeSupergroup.CONSTRUCTOR) {
      return false;
    }
    if (!((TdApi.ChatTypeSupergroup) chat.type).isChannel) {
      return false;
    }
    TdApi.Supergroup supergroup = tdlib.chatToSupergroup(chat.id);
    if (supergroup == null) {
      return false;
    }
    if (TD.isCreator(supergroup.status)) {
      return true;
    }
    if (supergroup.status.getConstructor() == TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR) {
      TdApi.ChatMemberStatusAdministrator administrator = (TdApi.ChatMemberStatusAdministrator) supergroup.status;
      return administrator.rights != null && administrator.rights.canPostMessages;
    }
    return false;
  }

  private void createNewChannel () {
    if (creatingChannel) {
      return;
    }
    creatingChannel = true;
    TdApi.Chat source = tdlib.chat(sourceChatId);
    String title = source != null && !StringUtils.isEmpty(source.title) ? source.title : Lang.getString(R.string.NewChannel);
    UI.showProgress(Lang.getString(R.string.ProgressCreateChannel), null, 300L);
    tdlib.send(new TdApi.CreateNewSupergroupChat(title, false, true, "", null, 0, false), (remoteChat, error) -> {
      UI.hideProgress();
      creatingChannel = false;
      if (error != null || remoteChat == null) {
        if (error != null) {
          UI.showError(error);
        }
        return;
      }
      long chatId = remoteChat.id;
      TdApi.Chat chat = tdlib.chat(chatId);
      if (chat != null) {
        myChannels.add(0, chat);
      }
      if (state == STATE_TARGET) {
        buildCells();
      }
      selectTargetChat(chatId);
    });
  }

  // Cloning

  private void startClone () {
    if (targetChatId == 0 || (reuploader != null && reuploader.isRunning())) {
      return;
    }
    cloneCompleted = 0;
    cloneFailed = 0;
    cloneTotal = 0;
    cloneCancelled = false;
    cloneError = null;
    state = STATE_PROGRESS;
    buildCells();
    if (cloneChannelInfo) {
      copyChannelInfo();
    }
    loadHistory(messages -> {
      if (messages.isEmpty()) {
        state = STATE_DONE;
        buildCells();
        return;
      }
      cloneTotal = messages.size();
      updateProgress();
      reuploader = new MessageReuploader(tdlib, targetChatId, new MessageReuploader.Callback() {
        @Override
        public void onProgress (int completed, int total) {
          if (!isDestroyed()) {
            updateProgress();
          }
        }

        @Override
        public void onDone (int successCount, int failCount, boolean cancelled) {
          cloneCompleted = successCount;
          cloneFailed = failCount;
          cloneCancelled = cancelled;
          reuploader = null;
          if (!isDestroyed()) {
            state = STATE_DONE;
            buildCells();
          }
        }
      });
      reuploader.start(messages);
    });
  }

  private void stopClone () {
    if (reuploader != null) {
      reuploader.cancel();
      reuploader = null;
    }
  }

  private void updateProgress () {
    adapter.updateValuedSettingById(R.id.btn_cloneProgress);
  }

  private void copyChannelInfo () {
    TdApi.Chat source = tdlib.chat(sourceChatId);
    if (source == null) {
      return;
    }
    tdlib.send(new TdApi.SetChatTitle(targetChatId, source.title), tdlib.typedOkHandler());
    if (source.type.getConstructor() == TdApi.ChatTypeSupergroup.CONSTRUCTOR) {
      long supergroupId = ((TdApi.ChatTypeSupergroup) source.type).supergroupId;
      tdlib.send(new TdApi.GetSupergroupFullInfo(supergroupId), (fullInfo, error) -> {
        if (error == null && fullInfo != null) {
          tdlib.send(new TdApi.SetChatDescription(targetChatId, fullInfo.description), tdlib.typedOkHandler());
        }
      });
    }
    if (source.photo != null && source.photo.small != null) {
      TdApi.File smallPhoto = source.photo.small;
      tdlib.files().downloadFile(smallPhoto, TdlibFilesManager.PRIORITY_USER_REQUEST_DOWNLOAD, (file, error) -> {
        if (error == null && file.local != null && file.local.isDownloadingCompleted && file.local.path != null) {
          tdlib.send(new TdApi.SetChatPhoto(targetChatId, new TdApi.InputChatPhotoStatic(new TdApi.InputFileLocal(file.local.path))), tdlib.typedOkHandler());
        }
      });
    }
  }

  private void loadHistory (RunnableData<List<TdApi.Message>> callback) {
    final List<TdApi.Message> history = new ArrayList<>();
    fetchHistoryBatch(0, history, () -> {
      List<TdApi.Message> messages = new ArrayList<>(history.size());
      if (cloneRange != RANGE_ALL && history.size() > cloneRange) {
        // history 按从新到旧排列，仅保留最新的 cloneRange 条
        history.subList(cloneRange, history.size()).clear();
      }
      for (int i = history.size() - 1; i >= 0; i--) {
        TdApi.Message message = history.get(i);
        if (isCloneable(message)) {
          messages.add(message);
        }
      }
      callback.runWithData(messages);
    });
  }

  private void fetchHistoryBatch (long fromMessageId, List<TdApi.Message> history, Runnable onComplete) {
    if (isDestroyed()) {
      return;
    }
    tdlib.send(new TdApi.GetChatHistory(sourceChatId, fromMessageId, 0, HISTORY_BATCH_SIZE, false), (messages, error) -> {
      if (isDestroyed()) {
        return;
      }
      if (error != null || messages.length == 0) {
        cloneError = error != null ? TD.toErrorString(error) : null;
        onComplete.run();
        return;
      }
      Collections.addAll(history, messages);
      if (cloneRange != RANGE_ALL && history.size() >= cloneRange) {
        onComplete.run();
        return;
      }
      if (messages.length < HISTORY_BATCH_SIZE) {
        onComplete.run();
        return;
      }
      fetchHistoryBatch(messages[messages.length - 1].id, history, onComplete);
    });
  }

  private boolean isCloneable (TdApi.Message message) {
    switch (message.content.getConstructor()) {
      case TdApi.MessageText.CONSTRUCTOR:
      case TdApi.MessagePhoto.CONSTRUCTOR:
      case TdApi.MessageVideo.CONSTRUCTOR:
      case TdApi.MessageDocument.CONSTRUCTOR:
      case TdApi.MessageAudio.CONSTRUCTOR:
      case TdApi.MessageAnimation.CONSTRUCTOR:
      case TdApi.MessageVoiceNote.CONSTRUCTOR:
      case TdApi.MessageVideoNote.CONSTRUCTOR:
      case TdApi.MessageSticker.CONSTRUCTOR:
      case TdApi.MessageContact.CONSTRUCTOR:
      case TdApi.MessageLocation.CONSTRUCTOR:
      case TdApi.MessageVenue.CONSTRUCTOR:
      case TdApi.MessageDice.CONSTRUCTOR:
        break;
      default:
        return false;
    }
    if (!includeMedia) {
      return message.content.getConstructor() == TdApi.MessageText.CONSTRUCTOR;
    }
    return true;
  }

  @Override
  public void destroy () {
    super.destroy();
    if (reuploader != null) {
      reuploader.cancel();
      reuploader = null;
    }
  }

  private class Adapter extends SettingsAdapter {
    public Adapter (ViewController<?> context) {
      super(context);
    }

    @Override
    protected void setValuedSetting (ListItem item, SettingView v, boolean isUpdate) {
      v.setDrawModifier(item.getDrawModifier());
      int itemId = item.getId();
      if (itemId == R.id.btn_cloneRange) {
        v.setData(item.getStringValue());
      } else if (itemId == R.id.btn_cloneChangeTarget) {
        v.setData(item.getStringValue());
      } else if (itemId == R.id.btn_cloneProgress) {
        v.setData(Lang.getString(R.string.CloneProgress, cloneCompleted + cloneFailed, cloneTotal));
      } else if (itemId == R.id.btn_cloneIncludeMedia) {
        v.getToggler().setRadioEnabled(includeMedia, isUpdate);
      } else if (itemId == R.id.btn_cloneChannelInfo) {
        v.getToggler().setRadioEnabled(cloneChannelInfo, isUpdate);
      }
    }

    @Override
    protected void modifyChatView (ListItem item, SmallChatView chatView, @Nullable CheckBoxView checkBox, boolean isUpdate) {
      if (item.getId() == R.id.chat) {
        chatView.setEnabled(true);
      }
    }
  }
}
