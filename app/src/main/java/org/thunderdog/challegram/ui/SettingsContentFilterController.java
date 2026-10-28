/*
 * Settings screen for the keyword / regexp based content filter,
 * the ad blocker and the sensitive word filter.
 */
package org.thunderdog.challegram.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.IdRes;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.ContentFilter;
import org.thunderdog.challegram.v.CustomRecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SettingsContentFilterController extends RecyclerViewController<Void> implements View.OnClickListener {
  /** How many entries are shown while a list is collapsed. */
  private static final int COLLAPSED_LIMIT = 3;

  public SettingsContentFilterController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_contentFilter;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.ContentFilterTitle);
  }

  private SettingsAdapter adapter;

  private boolean adUrlsExpanded, rulesExpanded, sensitiveWordsExpanded;

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        @IdRes int id = item.getId();
        if (id == R.id.btn_contentFilterEnabled) {
          view.getToggler().setRadioEnabled(ContentFilter.isEnabled(), isUpdate);
        } else if (id == R.id.btn_contentFilterBlockAds) {
          view.getToggler().setRadioEnabled(ContentFilter.blockAds(), isUpdate);
        } else if (id == R.id.btn_contentFilterNoImages) {
          view.getToggler().setRadioEnabled(ContentFilter.isNoImagesEnabled(), isUpdate);
        } else if (id == R.id.btn_contentFilterModeHide) {
          view.getToggler().setRadioEnabled(ContentFilter.getMode() == ContentFilter.MODE_HIDE, isUpdate);
        } else if (id == R.id.btn_contentFilterModeCollapse) {
          view.getToggler().setRadioEnabled(ContentFilter.getMode() == ContentFilter.MODE_COLLAPSE, isUpdate);
        } else if (id == R.id.btn_sensitiveFilterEnabled) {
          view.getToggler().setRadioEnabled(ContentFilter.isSensitiveEnabled(), isUpdate);
        } else if (id == R.id.btn_sensitiveFilterModeMask) {
          view.getToggler().setRadioEnabled(ContentFilter.getSensitiveMode() == ContentFilter.SENSITIVE_MODE_MASK, isUpdate);
        } else if (id == R.id.btn_sensitiveFilterModePinyin) {
          view.getToggler().setRadioEnabled(ContentFilter.getSensitiveMode() == ContentFilter.SENSITIVE_MODE_PINYIN, isUpdate);
        }
        attachInfoTooltip(item, view);
      }
    };
    buildCells();
    recyclerView.setAdapter(adapter);
  }

  // == Info ("!") tooltips ==

  /** Description shown as a tooltip when the "!" icon of a row is tapped, 0 if none. */
  private static int infoForId (@IdRes int id) {
    switch (id) {
      case R.id.btn_contentFilterBlockAds:
        return R.string.AdFilterEnabledInfo;
      case R.id.btn_contentFilterEnabled:
        return R.string.ContentFilterEnabledInfo;
      case R.id.btn_contentFilterModeCollapse:
        return R.string.ContentFilterModeCollapseInfo;
      case R.id.btn_contentFilterNoImages:
        return R.string.NoImagesEnabledInfo;
      case R.id.btn_sensitiveFilterEnabled:
        return R.string.SensitiveFilterEnabledInfo;
      case R.id.btn_sensitiveFilterModePinyin:
        return R.string.SensitiveFilterModePinyinInfo;
      case R.id.btn_contentFilterRegexExamples:
        return R.string.ContentFilterRegexExamples;
      default:
        return 0;
    }
  }

  /**
   * Rows with a long description show a small "!" icon before the name instead
   * of a full-width description line; tapping the icon displays the description
   * as a floating tooltip, keeping the page compact.
   */
  private void attachInfoTooltip (ListItem item, SettingView view) {
    final int infoRes = infoForId(item.getId());
    if (infoRes == 0) {
      view.setOnTouchListener(null);
      return;
    }
    view.setOnTouchListener((v, event) -> {
      float x = event.getX();
      if (Lang.rtl()) {
        x = v.getWidth() - x;
      }
      boolean inIconZone = x >= 0 && x <= Screen.dp(56f);
      if (!inIconZone) {
        return false; // let the row behave normally (toggle / menu)
      }
      switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
          v.setPressed(true);
          return true;
        case MotionEvent.ACTION_UP: {
          v.setPressed(false);
          showInfoTooltip(v, Lang.getString(infoRes));
          return true;
        }
        case MotionEvent.ACTION_CANCEL:
          v.setPressed(false);
          return true;
      }
      return true;
    });
  }

  // == Rebuilding ==

  private void buildCells () {
    List<ListItem> items = new ArrayList<>();

    // == Ad blocker ==

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.AdFilterSection));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_contentFilterBlockAds, R.drawable.baseline_error_24, R.string.AdFilterEnabled));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    List<String> adUrls = ContentFilter.getBlockedAdUrls();
    if (adUrls.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.AdFilterNoUrls));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    } else {
      items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, Lang.getStringBold(R.string.AdFilterBlockedUrls, adUrls.size())));
      int visible = adUrlsExpanded ? adUrls.size() : Math.min(COLLAPSED_LIMIT, adUrls.size());
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      for (int i = 0; i < visible; i++) {
        if (i > 0) {
          items.add(new ListItem(ListItem.TYPE_SEPARATOR));
        }
        items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_contentFilterRemoveAdUrl, 0, adUrls.get(i)).setData(i));
      }
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      if (adUrls.size() > COLLAPSED_LIMIT) {
        addExpandRow(items, R.id.btn_contentFilterToggleAdUrls, adUrlsExpanded, adUrls.size());
      }
    }

    // == Content filter ==

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.ContentFilterTitle));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_contentFilterEnabled, R.drawable.baseline_error_24, R.string.ContentFilterEnabled));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.ContentFilterMode));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_contentFilterModeHide, 0, R.string.ContentFilterModeHide));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_contentFilterModeCollapse, R.drawable.baseline_error_24, R.string.ContentFilterModeCollapse));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    // == No images mode ==

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.NoImagesSection));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_contentFilterNoImages, R.drawable.baseline_error_24, R.string.NoImagesEnabled));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    List<ContentFilter.Rule> rules = ContentFilter.getRules();
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, Lang.getStringBold(R.string.ContentFilterRules, rules.size())));
    if (rules.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.ContentFilterNoRules));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    } else {
      int visible = rulesExpanded ? rules.size() : Math.min(COLLAPSED_LIMIT, rules.size());
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      for (int i = 0; i < visible; i++) {
        if (i > 0) {
          items.add(new ListItem(ListItem.TYPE_SEPARATOR));
        }
        items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_contentFilterEditRule, 0, ruleTitle(rules.get(i))).setData(i));
      }
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      if (rules.size() > COLLAPSED_LIMIT) {
        addExpandRow(items, R.id.btn_contentFilterToggleRules, rulesExpanded, rules.size());
      }
    }

    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_contentFilterAddRule, R.drawable.baseline_add_24, R.string.ContentFilterAddRule));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_contentFilterRegexExamples, R.drawable.baseline_error_24, R.string.ContentFilterRegexExamplesTitle));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    // == Sensitive words ==

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.SensitiveFilterSection));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_sensitiveFilterEnabled, R.drawable.baseline_error_24, R.string.SensitiveFilterEnabled));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.SensitiveFilterMode));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_sensitiveFilterModeMask, 0, R.string.SensitiveFilterModeMask));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_sensitiveFilterModePinyin, R.drawable.baseline_error_24, R.string.SensitiveFilterModePinyin));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    List<String> sensitiveWords = ContentFilter.getSensitiveWords();
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, Lang.getStringBold(R.string.SensitiveFilterWords, sensitiveWords.size())));
    if (sensitiveWords.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.SensitiveFilterNoWords));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    } else {
      int visible = sensitiveWordsExpanded ? sensitiveWords.size() : Math.min(COLLAPSED_LIMIT, sensitiveWords.size());
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      for (int i = 0; i < visible; i++) {
        if (i > 0) {
          items.add(new ListItem(ListItem.TYPE_SEPARATOR));
        }
        items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_sensitiveFilterRemoveWord, 0, sensitiveWords.get(i)).setData(i));
      }
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
      if (sensitiveWords.size() > COLLAPSED_LIMIT) {
        addExpandRow(items, R.id.btn_sensitiveFilterToggleWords, sensitiveWordsExpanded, sensitiveWords.size());
      }
    }

    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_sensitiveFilterAddWord, R.drawable.baseline_add_24, R.string.SensitiveFilterAddWord));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    adapter.setItems(items, false);
  }

  /**
   * Rebuilds the cell list while keeping the current scroll position, so that
   * expanding / collapsing a list does not visually jump to the top.
   */
  private void rebuildPreservingScroll () {
    RecyclerView recyclerView = getRecyclerView();
    LinearLayoutManager layoutManager = recyclerView != null ? (LinearLayoutManager) recyclerView.getLayoutManager() : null;
    final int anchorPosition = layoutManager != null ? layoutManager.findFirstVisibleItemPosition() : RecyclerView.NO_POSITION;
    final int anchorOffset;
    if (layoutManager != null && anchorPosition != RecyclerView.NO_POSITION) {
      View anchorView = layoutManager.findViewByPosition(anchorPosition);
      anchorOffset = anchorView != null ? anchorView.getTop() : 0;
    } else {
      anchorOffset = 0;
    }
    buildCells();
    if (layoutManager != null && anchorPosition != RecyclerView.NO_POSITION) {
      layoutManager.scrollToPositionWithOffset(anchorPosition, anchorOffset);
      // Apply again after the layout pass settles (item counts may differ).
      recyclerView.post(() -> {
        if (anchorPosition < layoutManager.getItemCount()) {
          layoutManager.scrollToPositionWithOffset(anchorPosition, anchorOffset);
        }
      });
    }
  }

  private static void addExpandRow (List<ListItem> items, @IdRes int id, boolean expanded, int count) {
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_SETTING, id,
      expanded ? R.drawable.baseline_expand_less_24 : R.drawable.baseline_expand_more_24,
      expanded ? Lang.getString(R.string.FilterListShowLess) : Lang.getString(R.string.FilterListShowMore, count)));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
  }

  private static String ruleTitle (ContentFilter.Rule rule) {
    String type = Lang.getString(rule.isRegex() ? R.string.ContentFilterTypeRegex : R.string.ContentFilterTypeKeyword);
    String state = rule.isBroken() ? " (" + Lang.getString(R.string.ContentFilterInvalidRegex) + ")" : "";
    return type + "：" + rule.pattern + state;
  }

  @Override
  public void onClick (View v) {
    @IdRes int id = v.getId();
    if (id == R.id.btn_contentFilterEnabled) {
      ContentFilter.setEnabled(adapter.toggleView(v));
      adapter.updateValuedSettingById(R.id.btn_contentFilterModeHide);
      adapter.updateValuedSettingById(R.id.btn_contentFilterModeCollapse);
    } else if (id == R.id.btn_contentFilterBlockAds) {
      ContentFilter.setBlockAds(adapter.toggleView(v));
    } else if (id == R.id.btn_contentFilterNoImages) {
      ContentFilter.setNoImagesEnabled(adapter.toggleView(v));
    } else if (id == R.id.btn_contentFilterToggleAdUrls) {
      adUrlsExpanded = !adUrlsExpanded;
      rebuildPreservingScroll();
    } else if (id == R.id.btn_contentFilterToggleRules) {
      rulesExpanded = !rulesExpanded;
      rebuildPreservingScroll();
    } else if (id == R.id.btn_sensitiveFilterToggleWords) {
      sensitiveWordsExpanded = !sensitiveWordsExpanded;
      rebuildPreservingScroll();
    } else if (id == R.id.btn_contentFilterRegexExamples) {
      showInfoTooltip(v, Lang.getString(R.string.ContentFilterRegexExamples));
    } else if (id == R.id.btn_sensitiveFilterEnabled) {
      ContentFilter.setSensitiveEnabled(adapter.toggleView(v));
      adapter.updateValuedSettingById(R.id.btn_sensitiveFilterModeMask);
      adapter.updateValuedSettingById(R.id.btn_sensitiveFilterModePinyin);
    } else if (id == R.id.btn_sensitiveFilterModeMask || id == R.id.btn_sensitiveFilterModePinyin) {
      ContentFilter.setSensitiveMode(id == R.id.btn_sensitiveFilterModeMask ?
        ContentFilter.SENSITIVE_MODE_MASK : ContentFilter.SENSITIVE_MODE_PINYIN);
      adapter.updateValuedSettingById(R.id.btn_sensitiveFilterModeMask);
      adapter.updateValuedSettingById(R.id.btn_sensitiveFilterModePinyin);
    } else if (id == R.id.btn_contentFilterRemoveAdUrl) {
      Object data = ((ListItem) v.getTag()).getData();
      if (data instanceof Integer) {
        confirmRemoveAdUrl((Integer) data);
      }
    } else if (id == R.id.btn_sensitiveFilterRemoveWord) {
      Object data = ((ListItem) v.getTag()).getData();
      if (data instanceof Integer) {
        confirmRemoveSensitiveWord((Integer) data);
      }
    } else if (id == R.id.btn_contentFilterModeHide || id == R.id.btn_contentFilterModeCollapse) {
      ContentFilter.setMode(id == R.id.btn_contentFilterModeHide ? ContentFilter.MODE_HIDE : ContentFilter.MODE_COLLAPSE);
      adapter.updateValuedSettingById(R.id.btn_contentFilterModeHide);
      adapter.updateValuedSettingById(R.id.btn_contentFilterModeCollapse);
    } else if (id == R.id.btn_contentFilterAddRule) {
      showAddRuleTypePicker();
    } else if (id == R.id.btn_sensitiveFilterAddWord) {
      showAddSensitiveWordInput();
    } else if (id == R.id.btn_contentFilterEditRule) {
      Object data = ((ListItem) v.getTag()).getData();
      if (data instanceof Integer) {
        showRuleMenu((Integer) data);
      }
    }
  }

  private void showRuleMenu (final int index) {
    List<ContentFilter.Rule> rules = ContentFilter.getRules();
    if (index < 0 || index >= rules.size()) {
      return;
    }
    showOptions(ruleTitle(rules.get(index)), new int[] {R.id.btn_contentFilterEditRule, R.id.btn_contentFilterRemoveRule}, new String[] {
      Lang.getString(R.string.ContentFilterEditRule),
      Lang.getString(R.string.ContentFilterRemoveRule)
    }, null, new int[] {R.drawable.baseline_edit_24, R.drawable.baseline_delete_24}, (itemView, optionId) -> {
      if (optionId == R.id.btn_contentFilterEditRule) {
        showEditRuleInput(index);
      } else if (optionId == R.id.btn_contentFilterRemoveRule) {
        confirmRemoveRule(index);
      }
      return true;
    });
  }

  private void showEditRuleInput (final int index) {
    List<ContentFilter.Rule> rules = ContentFilter.getRules();
    if (index < 0 || index >= rules.size()) {
      return;
    }
    final ContentFilter.Rule rule = rules.get(index);
    final EditText input = new EditText(context);
    input.setSingleLine(true);
    input.setText(rule.pattern);
    input.setSelection(input.getText() != null ? input.getText().length() : 0);
    input.setHint(Lang.getString(rule.isRegex() ? R.string.ContentFilterRegexHint : R.string.ContentFilterPatternHint));

    LinearLayout container = new LinearLayout(context);
    int padding = Screen.dp(20f);
    container.setPadding(padding, padding / 2, padding, 0);
    container.addView(input);

    AlertDialog.Builder b = new AlertDialog.Builder(context, Theme.dialogTheme());
    b.setTitle(Lang.getString(rule.isRegex() ? R.string.ContentFilterTypeRegex : R.string.ContentFilterTypeKeyword));
    b.setView(container);
    b.setPositiveButton(Lang.getString(R.string.Done), (dialog, which) -> {
      String text = input.getText() != null ? input.getText().toString().trim() : "";
      if (!text.isEmpty() && !text.equals(rule.pattern)) {
        ContentFilter.updateRule(index, text);
        rebuildPreservingScroll();
        if (rule.isRegex()) {
          List<ContentFilter.Rule> updated = ContentFilter.getRules();
          ContentFilter.Rule changed = index < updated.size() ? updated.get(index) : null;
          if (changed != null && changed.isBroken()) {
            UI.showToast(R.string.ContentFilterInvalidRegex, Toast.LENGTH_SHORT);
          }
        }
      }
    });
    b.setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
    showAlert(b);
  }

  private void confirmRemoveAdUrl (final int index) {
    List<String> urls = ContentFilter.getBlockedAdUrls();
    if (index < 0 || index >= urls.size()) {
      return;
    }
    final String url = urls.get(index);
    openAlert(R.string.AdFilterRemoveUrl, url + "\n\n" + Lang.getString(R.string.AdFilterRemoveConfirm), (dialog, which) -> {
      ContentFilter.unblockAdUrl(index);
      rebuildPreservingScroll();
    });
  }

  private void showAddRuleTypePicker () {
    showOptions(new int[] {ContentFilter.TYPE_KEYWORD, ContentFilter.TYPE_REGEX}, new String[] {
      Lang.getString(R.string.ContentFilterTypeKeyword),
      Lang.getString(R.string.ContentFilterTypeRegex)
    }, (itemView, id) -> {
      showAddRuleInput(id == ContentFilter.TYPE_REGEX ? ContentFilter.TYPE_REGEX : ContentFilter.TYPE_KEYWORD);
      return true;
    });
  }

  private void showAddRuleInput (final int type) {
    final EditText input = new EditText(context);
    input.setSingleLine(true);
    input.setHint(Lang.getString(type == ContentFilter.TYPE_REGEX ? R.string.ContentFilterRegexHint : R.string.ContentFilterPatternHint));

    LinearLayout container = new LinearLayout(context);
    int padding = Screen.dp(20f);
    container.setPadding(padding, padding / 2, padding, 0);
    container.addView(input);

    AlertDialog.Builder b = new AlertDialog.Builder(context, Theme.dialogTheme());
    b.setTitle(Lang.getString(type == ContentFilter.TYPE_REGEX ? R.string.ContentFilterTypeRegex : R.string.ContentFilterTypeKeyword));
    b.setView(container);
    b.setPositiveButton(Lang.getString(R.string.Done), (dialog, which) -> {
      String text = input.getText() != null ? input.getText().toString().trim() : "";
      if (!text.isEmpty()) {
        ContentFilter.addRule(type, text);
        rebuildPreservingScroll();
        if (type == ContentFilter.TYPE_REGEX) {
          List<ContentFilter.Rule> rules = ContentFilter.getRules();
          ContentFilter.Rule added = rules.isEmpty() ? null : rules.get(rules.size() - 1);
          if (added != null && added.isBroken()) {
            UI.showToast(R.string.ContentFilterInvalidRegex, Toast.LENGTH_SHORT);
          }
        }
      }
    });
    b.setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
    showAlert(b);
  }

  private void confirmRemoveRule (final int index) {
    List<ContentFilter.Rule> rules = ContentFilter.getRules();
    if (index < 0 || index >= rules.size()) {
      return;
    }
    openAlert(R.string.ContentFilterRemoveRule, ruleTitle(rules.get(index)) + "\n\n" + Lang.getString(R.string.ContentFilterRemoveConfirm), (dialog, which) -> {
      ContentFilter.removeRuleAt(index);
      rebuildPreservingScroll();
    });
  }

  private void showAddSensitiveWordInput () {
    final EditText input = new EditText(context);
    input.setSingleLine(true);
    input.setHint(Lang.getString(R.string.SensitiveFilterAddWordHint));

    LinearLayout container = new LinearLayout(context);
    int padding = Screen.dp(20f);
    container.setPadding(padding, padding / 2, padding, 0);
    container.addView(input);

    AlertDialog.Builder b = new AlertDialog.Builder(context, Theme.dialogTheme());
    b.setTitle(Lang.getString(R.string.SensitiveFilterAddWord));
    b.setView(container);
    b.setPositiveButton(Lang.getString(R.string.Done), (dialog, which) -> {
      String text = input.getText() != null ? input.getText().toString().trim() : "";
      if (text.isEmpty()) {
        return;
      }
      int added = ContentFilter.addSensitiveWords(text);
      if (added > 0) {
        UI.showToast(Lang.getString(R.string.SensitiveFilterAddedToast, added), Toast.LENGTH_SHORT);
        rebuildPreservingScroll();
      } else {
        UI.showToast(R.string.SensitiveFilterDuplicateToast, Toast.LENGTH_SHORT);
      }
    });
    b.setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
    showAlert(b);
  }

  private void confirmRemoveSensitiveWord (final int index) {
    List<String> words = ContentFilter.getSensitiveWords();
    if (index < 0 || index >= words.size()) {
      return;
    }
    openAlert(R.string.SensitiveFilterRemoveWord, words.get(index) + "\n\n" + Lang.getString(R.string.SensitiveFilterRemoveConfirm), (dialog, which) -> {
      ContentFilter.removeSensitiveWordAt(index);
      rebuildPreservingScroll();
    });
  }
}
