/*
 * Settings screen for the keyword / regexp based content filter.
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

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        @IdRes int id = item.getId();
        if (id == R.id.btn_contentFilterEnabled) {
          view.getToggler().setRadioEnabled(ContentFilter.isEnabled(), isUpdate);
        } else if (id == R.id.btn_contentFilterModeHide) {
          view.getToggler().setRadioEnabled(ContentFilter.getMode() == ContentFilter.MODE_HIDE, isUpdate);
        } else if (id == R.id.btn_contentFilterModeCollapse) {
          view.getToggler().setRadioEnabled(ContentFilter.getMode() == ContentFilter.MODE_COLLAPSE, isUpdate);
        }
      }
    };
    buildCells();
    recyclerView.setAdapter(adapter);
  }

  private void buildCells () {
    List<ListItem> items = new ArrayList<>();

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

    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.ContentFilterRules));
    List<ContentFilter.Rule> rules = ContentFilter.getRules();
    if (rules.isEmpty()) {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.ContentFilterNoRules));
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    } else {
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      for (int i = 0; i < rules.size(); i++) {
        items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_contentFilterRemoveRule, 0, ruleTitle(rules.get(i))));
        if (i != rules.size() - 1) {
          items.add(new ListItem(ListItem.TYPE_SEPARATOR));
        }
      }
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    }

    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_contentFilterAddRule, R.drawable.baseline_add_24, R.string.ContentFilterAddRule));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    adapter.setItems(items, false);
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
    } else if (id == R.id.btn_contentFilterModeHide || id == R.id.btn_contentFilterModeCollapse) {
      ContentFilter.setMode(id == R.id.btn_contentFilterModeHide ? ContentFilter.MODE_HIDE : ContentFilter.MODE_COLLAPSE);
      adapter.updateValuedSettingById(R.id.btn_contentFilterModeHide);
      adapter.updateValuedSettingById(R.id.btn_contentFilterModeCollapse);
    } else if (id == R.id.btn_contentFilterAddRule) {
      showAddRuleTypePicker();
    } else if (id == R.id.btn_contentFilterRemoveRule) {
      showRemoveRulePicker();
    }
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
    input.setHint(Lang.getString(R.string.ContentFilterPatternHint));

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

  private void showRemoveRulePicker () {
    final List<ContentFilter.Rule> rules = ContentFilter.getRules();
    if (rules.isEmpty()) {
      return;
    }
    int[] ids = new int[rules.size()];
    String[] titles = new String[rules.size()];
    for (int i = 0; i < rules.size(); i++) {
      ids[i] = i;
      titles[i] = ruleTitle(rules.get(i));
    }
    showOptions(Lang.getString(R.string.ContentFilterRemoveRule), ids, titles, (itemView, id) -> {
      confirmRemoveRule(id);
      return true;
    });
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
}
