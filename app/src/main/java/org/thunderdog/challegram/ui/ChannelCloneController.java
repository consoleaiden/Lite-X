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
import android.view.WindowManager;

import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.widget.PopupLayout;
import org.thunderdog.challegram.widget.ViewPager;

import me.vkryl.android.widget.FrameLayoutFix;

public class ChannelCloneController extends BottomSheetViewController<ChannelCloneController.Arguments> {
  public static class Arguments {
    public final long sourceChatId;

    public Arguments (long sourceChatId) {
      this.sourceChatId = sourceChatId;
    }
  }

  private final ChannelClonePage singlePage;

  public ChannelCloneController (Context context, Tdlib tdlib) {
    super(context, tdlib);
    this.singlePage = new ChannelClonePage(this);
  }

  @Override
  public int getId () {
    return R.id.controller_channelClone;
  }

  @Override
  protected int getPagerItemCount () {
    return 1;
  }

  @Override
  protected void onBeforeCreateView () {
    singlePage.getValue();
  }

  @Override
  protected void onAfterCreateView () {
    setLickViewColor(Theme.getColor(ColorId.headerLightBackground));
  }

  @Override
  public void onThemeColorsChanged (boolean areTemp, ColorState state) {
    super.onThemeColorsChanged(areTemp, state);
    setLickViewColor(Theme.getColor(ColorId.headerLightBackground));
  }

  @Override
  public void setArguments (Arguments args) {
    super.setArguments(args);
    singlePage.setArguments(args);
  }

  @Override
  protected void onCreateView (Context context, FrameLayoutFix contentView, ViewPager pager) {
    pager.setOffscreenPageLimit(1);
    tdlib.ui().post(this::launchOpenAnimation);
  }

  @Override
  protected ViewController<?> onCreatePagerItemForPosition (Context context, int position) {
    if (position != 0) return null;
    setHeaderPosition(getContentOffset() + HeaderView.getTopOffset());
    setDefaultListenersAndDecorators(singlePage);
    return singlePage;
  }

  @Override
  protected int getHeaderHeight () {
    return Screen.dp(56f);
  }

  @Override
  protected int getContentOffset () {
    return (getTargetHeight() - getHeaderHeight(true)) / 2;
  }

  @Override
  protected boolean canHideByScroll () {
    return true;
  }

  @Override
  protected HeaderView onCreateHeaderView () {
    return singlePage.getHeaderView();
  }

  @Override
  public boolean needsTempUpdates () {
    return true;
  }

  @Override
  public boolean supportsBottomInset () {
    return true;
  }

  @Override
  protected void setupPopupLayout (PopupLayout popupLayout) {
    popupLayout.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    popupLayout.setBoundController(singlePage);
    popupLayout.setPopupHeightProvider(this);
    popupLayout.init(true);
    popupLayout.setHideKeyboard();
    popupLayout.setNeedRootInsets();
    popupLayout.setTouchProvider(this);
    popupLayout.setIgnoreHorizontal();
  }
}
