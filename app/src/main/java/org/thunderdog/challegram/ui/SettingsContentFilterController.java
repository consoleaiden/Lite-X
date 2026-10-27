/*
 * Settings screen for the keyword / regexp based content filter,
 * the ad blocker and the sensitive word filter.
 */
package org.thunderdog.challegram.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.IdRes;

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
      }
    };
    buildCells();
    recyclerView.setAdapter(adapter);
  }

  private void buildCells () {
    List<ListItem> items = new ArrayList<>();

    // == Ad blocker ==

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.AdFilterSection));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_contentFilterBlockAds, 0, R.string.AdFilterEnabled));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.AdFilterEnabledInfo));

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
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_contentFilterEnabled, 0, R.string.ContentFilterEnabled));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.ContentFilterEnabledInfo));

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.ContentFilterMode));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_contentFilterModeHide, 0, R.string.ContentFilterModeHide));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_contentFilterModeCollapse, 0, R.string.ContentFilterModeCollapse));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.ContentFilterModeCollapseInfo));

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
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.ContentFilterRegexExamples));

    // == Sensitive words ==

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.SensitiveFilterSection));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_sensitiveFilterEnabled, 0, R.string.SensitiveFilterEnabled));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.SensitiveFilterEnabledInfo));

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.SensitiveFilterMode));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_sensitiveFilterModeMask, 0, R.string.SensitiveFilterModeMask));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_sensitiveFilterModePinyin, 0, R.string.SensitiveFilterModePinyin));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.SensitiveFilterModePinyinInfo));

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
    } else if (id == R.id.btn_contentFilterToggleAdUrls) {
      adUrlsExpanded = !adUrlsExpanded;
      buildCells();
    } else if (id == R.id.btn_contentFilterToggleRules) {
      rulesExpanded = !rulesExpanded;
      buildCells();
    } else if (id == R.id.btn_sensitiveFilterToggleWords) {
      sensitiveWordsExpanded = !sensitiveWordsExpanded;
      buildCells();
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
        buildCells();
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
      buildCells();
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
        buildCells();
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
      buildCells();
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
        buildCells();
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
      buildCells();
    });
  }
}
