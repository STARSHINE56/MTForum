package com.solosu.mtforum.ui.widget;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;

/**
 * 毛玻璃弹窗工具。
 * 取代 AlertDialog.Builder,所有弹窗使用 CardView 作为根容器,
 * 通过 FrostedGlassHelper 自动应用毛玻璃效果。
 */
public class FrostedDialog {

    private final Context context;
    private CharSequence title;
    private CharSequence message;
    private View view;
    private CharSequence positiveText;
    private DialogInterface.OnClickListener positiveListener;
    private CharSequence negativeText;
    private DialogInterface.OnClickListener negativeListener;
    private CharSequence neutralText;
    private DialogInterface.OnClickListener neutralListener;
    private boolean cancelable = true;
    private DialogInterface.OnCancelListener cancelListener;
    private DialogInterface.OnDismissListener dismissListener;
    private CharSequence[] items;
    private DialogInterface.OnClickListener itemClickListener;
    private boolean isBottomSheet;

    private FrostedDialog(Context context) {
        this.context = context;
    }

    public static FrostedDialog build(Context context) {
        return new FrostedDialog(context);
    }

    public FrostedDialog setTitle(CharSequence title) {
        this.title = title;
        return this;
    }

    public FrostedDialog setMessage(CharSequence message) {
        this.message = message;
        return this;
    }

    public FrostedDialog setView(View view) {
        this.view = view;
        return this;
    }

    public FrostedDialog setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
        this.positiveText = text;
        this.positiveListener = listener;
        return this;
    }

    public FrostedDialog setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
        this.negativeText = text;
        this.negativeListener = listener;
        return this;
    }

    public FrostedDialog setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
        this.neutralText = text;
        this.neutralListener = listener;
        return this;
    }

    public FrostedDialog setCancelable(boolean cancelable) {
        this.cancelable = cancelable;
        return this;
    }

    public FrostedDialog setOnCancelListener(DialogInterface.OnCancelListener listener) {
        this.cancelListener = listener;
        return this;
    }

    public FrostedDialog setOnDismissListener(DialogInterface.OnDismissListener listener) {
        this.dismissListener = listener;
        return this;
    }

    public FrostedDialog setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
        this.items = items;
        this.itemClickListener = listener;
        return this;
    }

    /** 创建并显示 Dialog */
    public Dialog show() {
        Dialog dialog = create();
        dialog.show();
        return dialog;
    }

    /** 创建 Dialog(不自动显示) */
    public Dialog create() {
        // 使用 Dialog 替代 AlertDialog,避免 AppCompat 的复杂布局
        final Dialog dialog = new Dialog(context, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(cancelable);
        if (cancelListener != null) dialog.setOnCancelListener(cancelListener);
        if (dismissListener != null) dialog.setOnDismissListener(dismissListener);

        // 构建内容布局
        CardView cardView = new CardView(context);
        cardView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        cardView.setRadius(16f * context.getResources().getDisplayMetrics().density);
        cardView.setUseCompatPadding(true);
        cardView.setContentPadding(0, 0, 0, 0);

        // 内容垂直布局
        android.widget.LinearLayout contentLayout = new android.widget.LinearLayout(context);
        contentLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        contentLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题
        if (title != null) {
            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextSize(18);
            titleView.setGravity(Gravity.CENTER);
            int padding = (int) (16f * context.getResources().getDisplayMetrics().density);
            titleView.setPadding(padding, padding, padding, padding / 2);
            titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleView.setTextColor(isDarkMode(context) ? 0xFFFFFFFF : 0xFF000000);
            contentLayout.addView(titleView);
        }

        // 列表项
        if (items != null && itemClickListener != null) {
            android.widget.LinearLayout listLayout = new android.widget.LinearLayout(context);
            listLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            for (int i = 0; i < items.length; i++) {
                final int index = i;
                TextView itemView = new TextView(context);
                itemView.setText(items[i]);
                itemView.setTextSize(16);
                int pad = (int) (12f * context.getResources().getDisplayMetrics().density);
                itemView.setPadding(pad * 2, pad, pad * 2, pad);
                itemView.setGravity(Gravity.CENTER);
                itemView.setTextColor(isDarkMode(context) ? 0xFFCCCCCC : 0xFF333333);
                itemView.setOnClickListener(v -> {
                    itemClickListener.onClick(dialog, index);
                    dialog.dismiss();
                });
                listLayout.addView(itemView);
                if (i < items.length - 1) {
                    View divider = new View(context);
                    divider.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 1));
                    divider.setBackgroundColor(Color.argb(30, 0, 0, 0));
                    listLayout.addView(divider);
                }
            }
            contentLayout.addView(listLayout);
        }

        // 消息/自定义视图
        if (message != null || view != null) {
            ScrollView scrollView = new ScrollView(context);
            scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            int hp = (int) (16f * context.getResources().getDisplayMetrics().density);
            scrollView.setPadding(hp, 0, hp, 0);

            if (view != null) {
                scrollView.addView(view);
            } else {
                TextView msgView = new TextView(context);
                msgView.setText(message);
                msgView.setTextSize(15);
                int pad = (int) (8f * context.getResources().getDisplayMetrics().density);
                msgView.setPadding(0, pad, 0, pad);
                msgView.setLineSpacing(4f, 1.2f);
                msgView.setTextColor(isDarkMode(context) ? 0xFFCCCCCC : 0xFF333333);
                scrollView.addView(msgView);
            }
            contentLayout.addView(scrollView);
        }

        // 按钮栏
        if (positiveText != null || negativeText != null || neutralText != null) {
            android.widget.LinearLayout buttonBar = new android.widget.LinearLayout(context);
            buttonBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            buttonBar.setGravity(Gravity.END);
            int bp = (int) (8f * context.getResources().getDisplayMetrics().density);
            buttonBar.setPadding(bp, bp, bp, bp);

            if (neutralText != null) {
                android.widget.Button neutralBtn = makeButton(context, neutralText, v -> {
                    if (neutralListener != null) neutralListener.onClick(dialog, DialogInterface.BUTTON_NEUTRAL);
                    dialog.dismiss();
                });
                buttonBar.addView(neutralBtn);
            }
            if (negativeText != null) {
                android.widget.Button negBtn = makeButton(context, negativeText, v -> {
                    if (negativeListener != null) negativeListener.onClick(dialog, DialogInterface.BUTTON_NEGATIVE);
                    dialog.dismiss();
                });
                buttonBar.addView(negBtn);
            }
            if (positiveText != null) {
                android.widget.Button posBtn = makeButton(context, positiveText, v -> {
                    if (positiveListener != null) positiveListener.onClick(dialog, DialogInterface.BUTTON_POSITIVE);
                    dialog.dismiss();
                });
                posBtn.setTextColor(0xFF1976D2);
                buttonBar.addView(posBtn);
            }
            contentLayout.addView(buttonBar);
        }

        cardView.addView(contentLayout);

        // 应用毛玻璃
        FrostedGlassHelper.applyToCard(cardView, context);

        // 设置到 Dialog
        dialog.setContentView(cardView);

        // 窗口背景透明
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // 设置宽度为屏幕宽度的 85%
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.85f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }

        return dialog;
    }

    private android.widget.Button makeButton(Context context, CharSequence text, View.OnClickListener listener) {
        android.widget.Button btn = new android.widget.Button(context, null, android.R.attr.borderlessButtonStyle);
        btn.setText(text);
        btn.setTextSize(14);
        int bp = (int) (4f * context.getResources().getDisplayMetrics().density);
        int hp = (int) (12f * context.getResources().getDisplayMetrics().density);
        btn.setPadding(hp, bp, hp, bp);
        btn.setOnClickListener(listener);
        btn.setTextColor(isDarkMode(context) ? 0xFFBBBBBB : 0xFF555555);
        return btn;
    }

    private static boolean isDarkMode(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
}
