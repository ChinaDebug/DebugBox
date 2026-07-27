package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.util.ToastHelper;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SubtitleBean;
import com.github.tvbox.osc.bean.SubtitleData;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.ui.adapter.SearchSubtitleAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.viewmodel.SubtitleViewModel;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class SearchSubtitleDialog extends BaseDialog {

    private final Context mContext;
    private TvRecyclerView mGridView;
    private SearchSubtitleAdapter searchAdapter;

    private TextView subtitleSearchBtn;
    private EditText subtitleSearchEt;
    private SubtitleLoader mSubtitleLoader;
    private View loadingContainer;
    private ProgressBar loadingBar;
    private TextView loadingTip;
    private SubtitleViewModel subtitleViewModel;
    private int page = 1;
    private final int maxPage = 5;
    private String searchWord = "";

    private List<SubtitleBean> zipSubtitles = new ArrayList<>();
    private boolean isSearchPag = true;


    public SearchSubtitleDialog(@NonNull @NotNull Context context) {
        super(context);
        mContext = context;
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setContentView(R.layout.dialog_search_subtitle);
        setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                if (!isSearchPag) {
                    isSearchPag = true;
                    loadingContainer.setVisibility(View.GONE);
                    mGridView.setVisibility(View.VISIBLE);
                    searchAdapter.setNewData(zipSubtitles);
                    searchAdapter.setEnableLoadMore(page < maxPage);
                    return true;
                }
                dismiss();
                return true;
            }
            return false;
        });
        initView(context);
        initViewModel();
    }

    protected void initView(Context context) {
        loadingContainer = findViewById(R.id.loadingContainer);
        loadingBar = findViewById(R.id.loadingBar);
        loadingTip = findViewById(R.id.loadingTip);
        mGridView = findViewById(R.id.mGridView);
        subtitleSearchEt = findViewById(R.id.input_sub);
        subtitleSearchBtn = findViewById(R.id.inputSubmit);
        subtitleSearchBtn.setText(HomeActivity.getRes().getString(R.string.vod_sub_search));

        searchAdapter = new SearchSubtitleAdapter();
        mGridView.setHasFixedSize(true);
        mGridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 1, false));
        mGridView.setAdapter(searchAdapter);
        mGridView.setFocusable(true);
        mGridView.setFocusableInTouchMode(true);
        mGridView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        searchAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                SubtitleBean subtitle = searchAdapter.getData().get(position);
                //加载字幕
                if (mSubtitleLoader != null) {
                    if (subtitle.getIsZip()) {
                        isSearchPag = false;
                        loadingTip.setText("正在加载字幕列表...");
                        loadingContainer.setVisibility(View.VISIBLE);
                        mGridView.setVisibility(View.GONE);
                        subtitleViewModel.getSearchResultSubtitleUrls(subtitle);
                    } else {
                        // 显示加载中，不立即关闭弹窗，等待加载结果
                        loadingTip.setText("正在加载字幕...");
                        loadingContainer.setVisibility(View.VISIBLE);
                        mGridView.setVisibility(View.GONE);
                        loadSubtitle(subtitle);
                    }
                }
            }
        });

        searchAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                if (searchAdapter.getData().get(0).getIsZip()) {
                    subtitleViewModel.searchResult(searchWord, page);
                }
            }
        }, mGridView);

        // takagen99 : Fix on Key Enter
        subtitleSearchEt.setOnKeyListener(onSoftKeyPress);
        // 软键盘搜索/完成按钮直接触发搜索
        subtitleSearchEt.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO) {
                String wd = subtitleSearchEt.getText().toString().trim();
                search(wd);
                return true;
            }
            return false;
        });
        // 编辑框获取焦点时主动弹出软键盘
        subtitleSearchEt.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showSoftInput(subtitleSearchEt);
            }
        });

        subtitleSearchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                String wd = subtitleSearchEt.getText().toString().trim();
                search(wd);
            }
        });
        searchAdapter.setNewData(new ArrayList<>());
    }

    // 搜索框按键处理：TV端方向键焦点切换，回车/确认键直接执行搜索
    private final View.OnKeyListener onSoftKeyPress = new View.OnKeyListener() {
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    // 回车/确认键：直接执行搜索，不再把焦点移到搜索按钮
                    String wd = subtitleSearchEt.getText().toString().trim();
                    search(wd);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // 下键：焦点移到搜索结果列表
                    View nextView = subtitleSearchEt.focusSearch(View.FOCUS_DOWN);
                    if (nextView != null) {
                        nextView.requestFocus();
                    }
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // 右键：光标在末尾或文本为空时，焦点移到右侧搜索按钮
                    int len = subtitleSearchEt.getText().length();
                    if (len == 0 || subtitleSearchEt.getSelectionStart() == len) {
                        View nextView = subtitleSearchEt.focusSearch(View.FOCUS_RIGHT);
                        if (nextView != null) {
                            nextView.requestFocus();
                        }
                        return true;
                    }
                }
            }
            return false;
        }
    };

    /**
     * 主动显示软键盘
     */
    private void showSoftInput(View view) {
        view.post(() -> {
            InputMethodManager imm = (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    public void setSearchWord(String wd) {
        if (wd == null) {
            wd = "";
        }
        // 仅移除常见视频文件扩展名，保留片名原始格式，避免过度处理导致片名失真
        wd = wd.replaceAll("(?i)\\.(mp4|mkv|avi|mov|wmv|flv|ts)$", "");
        wd = wd.trim();
        subtitleSearchEt.setText(wd);
        subtitleSearchEt.setSelection(wd.length());
        subtitleSearchEt.requestFocus();
    }

    public void search(String wd) {
        isSearchPag = true;
        searchAdapter.setNewData(new ArrayList<>());
        if (!TextUtils.isEmpty(wd)) {
            loadingTip.setText("正在搜索字幕...");
            loadingContainer.setVisibility(View.VISIBLE);
            mGridView.setVisibility(View.GONE);
            searchWord = wd;
            // 搜索后搜索框失去焦点，结果列表请求焦点，便于方向键直接选择字幕
            subtitleSearchEt.clearFocus();
            subtitleViewModel.searchResult(wd, page = 1);
        } else {
            ToastHelper.showToast(getContext(), "输入内容不能为空");
        }
    }

    private void initViewModel() {
        subtitleViewModel = new ViewModelProvider((ViewModelStoreOwner) mContext).get(SubtitleViewModel.class);
        subtitleViewModel.searchResult.observe((LifecycleOwner) mContext, new Observer<SubtitleData>() {
            @Override
            public void onChanged(SubtitleData subtitleData) {
                loadingContainer.setVisibility(View.GONE);
                mGridView.setVisibility(View.VISIBLE);
                // 视图显示完成后再请求焦点，避免焦点被搜索框抢回
                mGridView.post(new Runnable() {
                    @Override
                    public void run() {
                        mGridView.requestFocus();
                    }
                });
                if (subtitleData == null) {
                    ToastHelper.showToast(getContext(), "搜索出错，请重试");
                    return;
                }
                List<SubtitleBean> data = subtitleData.getSubtitleList();
                if (data == null) {
                    mGridView.post(new Runnable() {
                        @Override
                        public void run() {
                            ToastHelper.showToast(getContext(), "未查询到匹配字幕");
                        }
                    });
                    return;
                }

                if (data.size() > 0) {
                    mGridView.requestFocus();
                    if (subtitleData.getIsZip()) {
                        if (subtitleData.getIsNew()) {
                            searchAdapter.setNewData(data);
                            zipSubtitles = data;
                        } else {
                            searchAdapter.addData(data);
                            zipSubtitles.addAll(data);
                        }
                        page++;
                        if (page > maxPage) {
                            searchAdapter.loadMoreEnd();
                            searchAdapter.setEnableLoadMore(false);
                        } else {
                            searchAdapter.loadMoreComplete();
                            searchAdapter.setEnableLoadMore(true);
                        }
                    } else {
                        searchAdapter.loadMoreComplete();
                        searchAdapter.setNewData(data);
                        searchAdapter.setEnableLoadMore(false);
                    }
                } else {
                    if (page > maxPage) {
                        searchAdapter.loadMoreEnd();
                    } else {
                        searchAdapter.loadMoreComplete();
                    }
                    searchAdapter.setEnableLoadMore(false);
                }

            }
        });
    }

    private void loadSubtitle(SubtitleBean subtitle) {
        subtitleViewModel.getSubtitleUrl(subtitle, mSubtitleLoader);
    }

    public void setSubtitleLoader(SubtitleLoader subtitleLoader) {
        mSubtitleLoader = subtitleLoader;
    }

    public interface SubtitleLoader {
        void loadSubtitle(SubtitleBean subtitle);

        void onLoadSuccess();

        void onLoadError(String error);
    }

    /**
     * 通知字幕加载结果，成功则关闭弹窗，失败则恢复列表显示。
     * 该方法可能在子线程被回调，因此切到主线程操作视图。
     */
    public void notifyLoadResult(boolean success, String message) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (!TextUtils.isEmpty(message)) {
                    ToastHelper.showToast(getContext(), message);
                }
                if (success) {
                    dismiss();
                } else {
                    loadingContainer.setVisibility(View.GONE);
                    mGridView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    @Override
    public void dismiss() {
        super.dismiss();
        // 关闭弹窗时清空上次搜索结果，避免下次打开仍显示旧数据
        searchAdapter.setNewData(new ArrayList<>());
        zipSubtitles.clear();
        page = 1;
    }

}