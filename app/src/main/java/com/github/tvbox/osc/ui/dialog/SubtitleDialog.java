package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.SubtitleHelper;

import org.jetbrains.annotations.NotNull;

public class SubtitleDialog extends BaseDialog {

    private TextView subtitleOption;
    public TextView selectInternal;
    public TextView selectLocal;
    public TextView selectRemote;
    public TextView selectClose;

    // 字幕大小
    private ImageView subtitleSizeMinus;
    private TextView subtitleSizeText;
    private ImageView subtitleSizePlus;

    // 字体颜色
    private ImageView subtitleTextColorMinus;
    private TextView subtitleTextColorText;
    private ImageView subtitleTextColorPlus;

    // 描边颜色
    private ImageView subtitleStrokeColorMinus;
    private TextView subtitleStrokeColorText;
    private ImageView subtitleStrokeColorPlus;

    // 字体粗细
    private ImageView subtitleBoldMinus;
    private TextView subtitleBoldText;
    private ImageView subtitleBoldPlus;

    // 预览
    private SimpleSubtitleView subtitleStyleText;

    // 时间调整
    private ImageView subtitleTimeMinus;
    private TextView subtitleTimeText;
    private ImageView subtitleTimePlus;

    // 重置按钮
    private TextView subtitleReset;

    // 字体粗细选项
    private static final String[] BOLD_NAMES = {"常规", "粗体"};
    private static final int[] BOLD_VALUES = {Typeface.NORMAL, Typeface.BOLD};

    private SubtitleViewListener mSubtitleViewListener;
    private LocalFileChooserListener mLocalFileChooserListener;
    private SearchSubtitleListener mSearchSubtitleListener;
    private CloseSubtitleListener mCloseSubtitleListener;

    public SubtitleDialog(@NotNull Context context) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setContentView(R.layout.dialog_subtitle);
        initView(context);
    }

    private void initView(Context context) {
        subtitleOption = findViewById(R.id.title);
        selectInternal = findViewById(R.id.selectInternal);
        selectLocal = findViewById(R.id.selectLocal);
        selectRemote = findViewById(R.id.selectRemote);
        selectClose = findViewById(R.id.selectClose);

        // 字幕大小
        subtitleSizeMinus = findViewById(R.id.subtitleSizeMinus);
        subtitleSizeText = findViewById(R.id.subtitleSizeText);
        subtitleSizePlus = findViewById(R.id.subtitleSizePlus);

        // 字体颜色
        subtitleTextColorMinus = findViewById(R.id.subtitleTextColorMinus);
        subtitleTextColorText = findViewById(R.id.subtitleTextColorText);
        subtitleTextColorPlus = findViewById(R.id.subtitleTextColorPlus);

        // 描边颜色
        subtitleStrokeColorMinus = findViewById(R.id.subtitleStrokeColorMinus);
        subtitleStrokeColorText = findViewById(R.id.subtitleStrokeColorText);
        subtitleStrokeColorPlus = findViewById(R.id.subtitleStrokeColorPlus);

        // 字体粗细
        subtitleBoldMinus = findViewById(R.id.subtitleBoldMinus);
        subtitleBoldText = findViewById(R.id.subtitleBoldText);
        subtitleBoldPlus = findViewById(R.id.subtitleBoldPlus);

        // 预览
        subtitleStyleText = findViewById(R.id.subtitleStyleText);

        // 时间调整
        subtitleTimeMinus = findViewById(R.id.subtitleTimeMinus);
        subtitleTimeText = findViewById(R.id.subtitleTimeText);
        subtitleTimePlus = findViewById(R.id.subtitleTimePlus);

        // 重置按钮
        subtitleReset = findViewById(R.id.subtitleReset);

        // 初始化预览
        updatePreview();

        // Set Title Tip
        subtitleOption.setText(HomeActivity.getRes().getString(R.string.vod_sub_option));
        selectInternal.setText(HomeActivity.getRes().getString(R.string.vod_sub_int));
        selectLocal.setText(HomeActivity.getRes().getString(R.string.vod_sub_ext));
        selectRemote.setText(HomeActivity.getRes().getString(R.string.vod_sub_remote));
        selectClose.setText(HomeActivity.getRes().getString(R.string.vod_sub_close));

        // Internal Subtitle from Video File
        selectInternal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                dismiss();
                mSubtitleViewListener.selectInternalSubtitle();
            }
        });
        // Local Drive Subtitle
        selectLocal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                dismiss();
                mLocalFileChooserListener.openLocalFileChooserDialog();
            }
        });
        // Remote Search Subtitle
        selectRemote.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                dismiss();
                mSearchSubtitleListener.openSearchSubtitleDialog();
            }
        });
        // Close Subtitle
        selectClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                dismiss();
                mCloseSubtitleListener.closeSubtitle();
            }
        });

        int size = SubtitleHelper.getTextSize(getOwnerActivity());
        subtitleSizeText.setText(Integer.toString(size));

        subtitleSizeMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String sizeStr = subtitleSizeText.getText().toString();
                int curSize = Integer.parseInt(sizeStr);
                curSize -= 2;
                if (curSize <= 10) {
                    curSize = 10;
                }
                subtitleSizeText.setText(Integer.toString(curSize));
                SubtitleHelper.setTextSize(curSize);
                mSubtitleViewListener.setTextSize(curSize);
            }
        });
        subtitleSizePlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String sizeStr = subtitleSizeText.getText().toString();
                int curSize = Integer.parseInt(sizeStr);
                curSize += 2;
                if (curSize >= 60) {
                    curSize = 60;
                }
                subtitleSizeText.setText(Integer.toString(curSize));
                SubtitleHelper.setTextSize(curSize);
                mSubtitleViewListener.setTextSize(curSize);
            }
        });

        // 字体颜色选择
        updateTextColorText();
        subtitleTextColorMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                int currentIndex = SubtitleHelper.getTextColorIndex();
                int newIndex = currentIndex - 1;
                if (newIndex < 0) {
                    newIndex = SubtitleHelper.TEXT_COLORS.length - 1;
                }
                SubtitleHelper.setTextColor(SubtitleHelper.TEXT_COLORS[newIndex]);
                updateTextColorText();
                updatePreview();
                mSubtitleViewListener.updateSubtitleStyle();
            }
        });
        subtitleTextColorPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                int currentIndex = SubtitleHelper.getTextColorIndex();
                int newIndex = currentIndex + 1;
                if (newIndex >= SubtitleHelper.TEXT_COLORS.length) {
                    newIndex = 0;
                }
                SubtitleHelper.setTextColor(SubtitleHelper.TEXT_COLORS[newIndex]);
                updateTextColorText();
                updatePreview();
                mSubtitleViewListener.updateSubtitleStyle();
            }
        });

        // 描边颜色选择
        updateStrokeColorText();
        subtitleStrokeColorMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                int currentIndex = SubtitleHelper.getStrokeColorIndex();
                int newIndex = currentIndex - 1;
                if (newIndex < 0) {
                    newIndex = SubtitleHelper.STROKE_COLORS.length - 1;
                }
                SubtitleHelper.setStrokeColor(SubtitleHelper.STROKE_COLORS[newIndex]);
                updateStrokeColorText();
                updatePreview();
                mSubtitleViewListener.updateSubtitleStyle();
            }
        });
        subtitleStrokeColorPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                int currentIndex = SubtitleHelper.getStrokeColorIndex();
                int newIndex = currentIndex + 1;
                if (newIndex >= SubtitleHelper.STROKE_COLORS.length) {
                    newIndex = 0;
                }
                SubtitleHelper.setStrokeColor(SubtitleHelper.STROKE_COLORS[newIndex]);
                updateStrokeColorText();
                updatePreview();
                mSubtitleViewListener.updateSubtitleStyle();
            }
        });

        // 字体粗细选择
        updateBoldText();
        subtitleBoldMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                int currentIndex = getBoldIndex();
                int newIndex = currentIndex - 1;
                if (newIndex < 0) {
                    newIndex = BOLD_NAMES.length - 1;
                }
                SubtitleHelper.setTextBold(BOLD_VALUES[newIndex]);
                updateBoldText();
                updatePreview();
                mSubtitleViewListener.updateSubtitleStyle();
            }
        });
        subtitleBoldPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                int currentIndex = getBoldIndex();
                int newIndex = currentIndex + 1;
                if (newIndex >= BOLD_NAMES.length) {
                    newIndex = 0;
                }
                SubtitleHelper.setTextBold(BOLD_VALUES[newIndex]);
                updateBoldText();
                updatePreview();
                mSubtitleViewListener.updateSubtitleStyle();
            }
        });

        // 时间调整
        int timeDelay = SubtitleHelper.getTimeDelay();
        String timeStr = "0秒";
        if (timeDelay != 0) {
            double dbTimeDelay = timeDelay / 1000.0;
            if (dbTimeDelay > 0) {
                timeStr = "+" + dbTimeDelay + "秒";
            } else {
                timeStr = dbTimeDelay + "秒";
            }
        }
        subtitleTimeText.setText(timeStr);

        subtitleTimeMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                String timeStr = subtitleTimeText.getText().toString().replace("秒", "").replace("+", "");
                double time = Double.parseDouble(timeStr);
                double oneceDelay = -0.5;
                time += oneceDelay;
                // 限制范围在 -10秒 到 +10秒
                if (time < -10.0) {
                    time = -10.0;
                }
                if (time == 0.0) {
                    timeStr = "0秒";
                } else if (time > 0) {
                    timeStr = "+" + time + "秒";
                } else {
                    timeStr = time + "秒";
                }
                subtitleTimeText.setText(timeStr);
                int mseconds = (int) Math.round(oneceDelay * 1000);
                SubtitleHelper.setTimeDelay((int) Math.round(time * 1000));
                mSubtitleViewListener.setSubtitleDelay(mseconds);
            }
        });
        subtitleTimePlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                String timeStr = subtitleTimeText.getText().toString().replace("秒", "").replace("+", "");
                double time = Double.parseDouble(timeStr);
                double oneceDelay = 0.5;
                time += oneceDelay;
                // 限制范围在 -10秒 到 +10秒
                if (time > 10.0) {
                    time = 10.0;
                }
                if (time == 0.0) {
                    timeStr = "0秒";
                } else if (time > 0) {
                    timeStr = "+" + time + "秒";
                } else {
                    timeStr = time + "秒";
                }
                subtitleTimeText.setText(timeStr);
                int mseconds = (int) Math.round(oneceDelay * 1000);
                SubtitleHelper.setTimeDelay((int) Math.round(time * 1000));
                mSubtitleViewListener.setSubtitleDelay(mseconds);
            }
        });

        // 重置按钮
        subtitleReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                SubtitleHelper.resetSettings(getOwnerActivity());
                // 更新所有UI显示
                int size = SubtitleHelper.getTextSize(getOwnerActivity());
                subtitleSizeText.setText(Integer.toString(size));
                updateTextColorText();
                updateStrokeColorText();
                updateBoldText();
                subtitleTimeText.setText("0秒");
                updatePreview();
                mSubtitleViewListener.setTextSize(size);
                mSubtitleViewListener.updateSubtitleStyle();
                mSubtitleViewListener.setSubtitleDelay(0);
            }
        });
    }

    private void updateTextColorText() {
        int index = SubtitleHelper.getTextColorIndex();
        subtitleTextColorText.setText(SubtitleHelper.TEXT_COLOR_NAMES[index]);
    }

    private void updateStrokeColorText() {
        int index = SubtitleHelper.getStrokeColorIndex();
        subtitleStrokeColorText.setText(SubtitleHelper.STROKE_COLOR_NAMES[index]);
    }

    private void updateBoldText() {
        int index = getBoldIndex();
        subtitleBoldText.setText(BOLD_NAMES[index]);
    }

    private int getBoldIndex() {
        int bold = SubtitleHelper.getTextBold();
        for (int i = 0; i < BOLD_VALUES.length; i++) {
            if (BOLD_VALUES[i] == bold) {
                return i;
            }
        }
        return 0;
    }

    private void updatePreview() {
        SubtitleHelper.applyStyle(subtitleStyleText);
    }

    public void setLocalFileChooserListener(LocalFileChooserListener localFileChooserListener) {
        mLocalFileChooserListener = localFileChooserListener;
    }

    public interface LocalFileChooserListener {
        void openLocalFileChooserDialog();
    }

    public void setSearchSubtitleListener(SearchSubtitleListener searchSubtitleListener) {
        mSearchSubtitleListener = searchSubtitleListener;
    }

    public interface SearchSubtitleListener {
        void openSearchSubtitleDialog();
    }

    public void setSubtitleViewListener(SubtitleViewListener subtitleViewListener) {
        mSubtitleViewListener = subtitleViewListener;
    }

    public interface SubtitleViewListener {
        void setTextSize(int size);

        void setSubtitleDelay(int milliseconds);

        void selectInternalSubtitle();

        void updateSubtitleStyle();
    }

    public void setCloseSubtitleListener(CloseSubtitleListener closeSubtitleListener) {
        mCloseSubtitleListener = closeSubtitleListener;
    }

    public interface CloseSubtitleListener {
        void closeSubtitle();
    }
}
