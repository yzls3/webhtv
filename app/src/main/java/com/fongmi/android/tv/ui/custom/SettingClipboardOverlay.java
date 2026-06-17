package com.fongmi.android.tv.ui.custom;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.widget.NestedScrollView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SettingClipboardOverlay {

    private static final int MAX_ITEMS = 100;
    private static final int MAX_DISPLAY = 260;
    private static final List<String> HISTORY = new ArrayList<>();

    private final DialogFragment fragment;
    private final View root;
    private final ClipboardManager clipboard;
    private final Set<String> selected = new LinkedHashSet<>();
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = this::captureSystemClipboard;
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener = (oldFocus, newFocus) -> {
        if (newFocus instanceof EditText) lastInput = (EditText) newFocus;
    };
    private FrameLayout overlay;
    private LinearLayoutCompat panel;
    private LinearLayoutCompat list;
    private MaterialButton trigger;
    private EditText lastInput;
    private boolean expanded;

    private SettingClipboardOverlay(DialogFragment fragment, View root) {
        this.fragment = fragment;
        this.root = root;
        this.clipboard = (ClipboardManager) root.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
    }

    public static SettingClipboardOverlay attach(DialogFragment fragment, View root) {
        if (!Util.isMobile()) return null;
        SettingClipboardOverlay overlay = new SettingClipboardOverlay(fragment, root);
        overlay.attach();
        return overlay;
    }

    public static void record(String value) {
        String text = normalize(value);
        if (TextUtils.isEmpty(text)) return;
        synchronized (HISTORY) {
            HISTORY.remove(text);
            HISTORY.add(0, text);
            while (HISTORY.size() > MAX_ITEMS) HISTORY.remove(HISTORY.size() - 1);
        }
    }

    public void detach() {
        if (clipboard != null) clipboard.removePrimaryClipChangedListener(clipListener);
        try {
            root.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusListener);
        } catch (Throwable ignored) {
        }
        if (overlay != null && overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
        overlay = null;
        panel = null;
        list = null;
        trigger = null;
        selected.clear();
    }

    private void attach() {
        Window window = fragment.getDialog() == null ? null : fragment.getDialog().getWindow();
        if (window == null || !(window.getDecorView() instanceof ViewGroup)) return;
        ViewGroup decor = (ViewGroup) window.getDecorView();
        if (overlay != null) return;
        captureSystemClipboard();
        root.getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);
        if (clipboard != null) clipboard.addPrimaryClipChangedListener(clipListener);
        overlay = new FrameLayout(root.getContext());
        overlay.setClickable(false);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        decor.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildTrigger();
        buildPanel();
        render();
    }

    private void buildTrigger() {
        trigger = iconButton();
        trigger.setOnClickListener(view -> {
            expanded = !expanded;
            captureSystemClipboard();
            render();
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(40), dp(36), Gravity.TOP | Gravity.END);
        params.topMargin = dp(8);
        params.rightMargin = dp(10);
        overlay.addView(trigger, params);
    }

    private void buildPanel() {
        panel = new LinearLayoutCompat(root.getContext());
        panel.setOrientation(LinearLayoutCompat.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        panel.setBackground(round(Color.WHITE, 10, Color.parseColor("#DADCE0")));
        panel.setClickable(true);
        panel.setFocusable(false);

        LinearLayoutCompat actions = new LinearLayoutCompat(root.getContext());
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayoutCompat.HORIZONTAL);
        addAction(actions, "插入", view -> insert());
        addAction(actions, "编辑", view -> edit());
        addAction(actions, "删除", view -> delete());
        addAction(actions, "复制", view -> copy());
        panel.addView(actions, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        NestedScrollView scroll = new NestedScrollView(root.getContext());
        scroll.setFillViewport(false);
        list = new LinearLayoutCompat(root.getContext());
        list.setOrientation(LinearLayoutCompat.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayoutCompat.LayoutParams scrollParams = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        scrollParams.topMargin = dp(8);
        panel.addView(scroll, scrollParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        params.leftMargin = dp(10);
        params.rightMargin = dp(10);
        params.bottomMargin = dp(8);
        overlay.addView(panel, params);
    }

    private void render() {
        if (trigger == null || panel == null || list == null) return;
        trigger.setChecked(expanded);
        panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        list.removeAllViews();
        List<String> items = snapshot();
        if (items.isEmpty()) {
            MaterialTextView empty = text("暂无剪贴内容", 13, Color.parseColor("#5F6368"), false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(20), 0, dp(20));
            list.addView(empty);
            return;
        }
        for (String item : items) list.addView(row(item));
    }

    private View row(String value) {
        LinearLayoutCompat row = new LinearLayoutCompat(root.getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayoutCompat.HORIZONTAL);
        row.setPadding(dp(2), dp(5), dp(4), dp(5));
        row.setBackground(round(selected.contains(value) ? Color.parseColor("#E8F0FE") : Color.parseColor("#F8F9FA"), 7, selected.contains(value) ? Color.parseColor("#D2E3FC") : Color.parseColor("#E0E3E7")));
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(5);
        row.setLayoutParams(params);

        MaterialCheckBox check = new MaterialCheckBox(root.getContext());
        check.setButtonTintList(ContextCompat.getColorStateList(root.getContext(), R.color.dialog_checkbox_tint));
        check.setChecked(selected.contains(value));
        check.setOnClickListener(view -> toggle(value));
        row.addView(check, new LinearLayoutCompat.LayoutParams(dp(38), dp(38)));

        MaterialTextView content = text(display(value), 13, Color.parseColor("#202124"), false);
        content.setMaxLines(3);
        content.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(content, new LinearLayoutCompat.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(view -> toggle(value));
        return row;
    }

    private void toggle(String value) {
        if (selected.contains(value)) selected.remove(value);
        else selected.add(value);
        render();
    }

    private void insert() {
        EditText input = activeInput();
        if (input == null) {
            Notify.show("请先点一下输入框");
            return;
        }
        String text = selectedText();
        if (TextUtils.isEmpty(text)) {
            Notify.show("请选择剪贴内容");
            return;
        }
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(0, input.getSelectionEnd());
        Editable editable = input.getText();
        if (editable == null) {
            input.setText(text);
            input.setSelection(input.length());
        } else {
            editable.replace(Math.min(start, end), Math.max(start, end), text);
        }
        input.requestFocus();
    }

    private void edit() {
        String item = firstSelected();
        if (TextUtils.isEmpty(item)) {
            Notify.show("请选择一条内容");
            return;
        }
        TextInputEditText input = new TextInputEditText(root.getContext());
        input.setSingleLine(false);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setText(item);
        TextInputLayout layout = new TextInputLayout(root.getContext());
        layout.setHint("剪贴内容");
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(root.getContext(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle("编辑剪贴板")
                .setView(layout)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String next = normalize(input.getText() == null ? "" : input.getText().toString());
                    if (TextUtils.isEmpty(next)) return;
                    replace(item, next);
                    selected.clear();
                    selected.add(next);
                    render();
                })
                .show();
    }

    private void delete() {
        if (selected.isEmpty()) {
            Notify.show("请选择要删除的内容");
            return;
        }
        synchronized (HISTORY) {
            HISTORY.removeAll(selected);
        }
        selected.clear();
        render();
    }

    private void copy() {
        String text = selectedText();
        if (TextUtils.isEmpty(text)) {
            Notify.show("请选择剪贴内容");
            return;
        }
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("clipboard", text));
        record(text);
        Notify.show("已复制");
    }

    private EditText activeInput() {
        View focused = root.findFocus();
        if (focused instanceof EditText) lastInput = (EditText) focused;
        return lastInput;
    }

    private String selectedText() {
        if (selected.isEmpty()) return "";
        return TextUtils.join("\n", new ArrayList<>(selected));
    }

    private String firstSelected() {
        return selected.isEmpty() ? "" : selected.iterator().next();
    }

    private void captureSystemClipboard() {
        try {
            if (clipboard == null || !clipboard.hasPrimaryClip()) return;
            ClipData data = clipboard.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) return;
            CharSequence text = data.getItemAt(0).coerceToText(root.getContext());
            record(text == null ? "" : text.toString());
        } catch (Throwable ignored) {
        }
    }

    private void replace(String oldValue, String newValue) {
        synchronized (HISTORY) {
            int index = HISTORY.indexOf(oldValue);
            HISTORY.remove(newValue);
            if (index >= 0) HISTORY.set(index, newValue);
            else HISTORY.add(0, newValue);
            while (HISTORY.size() > MAX_ITEMS) HISTORY.remove(HISTORY.size() - 1);
        }
    }

    private List<String> snapshot() {
        synchronized (HISTORY) {
            return new ArrayList<>(HISTORY);
        }
    }

    private void addAction(LinearLayoutCompat parent, String label, View.OnClickListener listener) {
        MaterialButton button = actionButton(label);
        button.setOnClickListener(listener);
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(0, dp(30), 1);
        if (parent.getChildCount() > 0) params.leftMargin = dp(6);
        parent.addView(button, params);
    }

    private MaterialButton iconButton() {
        MaterialButton button = new MaterialButton(root.getContext());
        button.setCheckable(true);
        button.setIconResource(R.drawable.ic_setting_clipboard);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setIconPadding(0);
        button.setIconSize(dp(20));
        button.setIconTint(ContextCompat.getColorStateList(root.getContext(), R.color.dialog_tonal_button_text));
        button.setBackgroundTintList(ContextCompat.getColorStateList(root.getContext(), R.color.dialog_tonal_button_bg));
        button.setStrokeColor(ContextCompat.getColorStateList(root.getContext(), R.color.dialog_outlined_button_stroke));
        button.setStrokeWidth(dp(1));
        button.setMinWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription("剪贴板");
        return button;
    }

    private MaterialButton actionButton(String label) {
        MaterialButton button = new MaterialButton(root.getContext());
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(dp(30));
        button.setMinimumHeight(dp(30));
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackgroundTintList(ContextCompat.getColorStateList(root.getContext(), R.color.dialog_tonal_button_bg));
        button.setTextColor(ContextCompat.getColorStateList(root.getContext(), R.color.dialog_tonal_button_text));
        return button;
    }

    private MaterialTextView text(String value, int sp, int color, boolean bold) {
        MaterialTextView view = new MaterialTextView(root.getContext());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setSingleLine(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private String display(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= MAX_DISPLAY) return text;
        return "..." + text.substring(text.length() - MAX_DISPLAY);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String text = value.trim();
        return text.length() == 0 ? "" : text;
    }

    private int dp(int value) {
        return ResUtil.dp2px(value);
    }
}
