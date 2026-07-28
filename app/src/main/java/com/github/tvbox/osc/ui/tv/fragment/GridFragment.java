package com.github.tvbox.osc.ui.tv.fragment;
import android.content.res.TypedArray;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.BounceInterpolator;
import androidx.core.content.ContextCompat;
import android.widget.Toast;

import com.github.tvbox.osc.util.ToastHelper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import com.blankj.utilcode.util.GsonUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.adapter.GridAdapter;
import com.github.tvbox.osc.ui.adapter.GridFilterKVAdapter;
import com.github.tvbox.osc.ui.dialog.GridFilterDialog;
import com.github.tvbox.osc.ui.tv.widget.LoadMoreView;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;
import me.jessyan.autosize.utils.AutoSizeUtils;

import java.util.ArrayList;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Stack;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class GridFragment extends BaseLazyFragment {
    private MovieSort.SortData sortData = null;
    private TvRecyclerView mGridView;
    private SourceViewModel sourceViewModel;
    private GridFilterDialog gridFilterDialog;
    private GridAdapter gridAdapter;
    private int page = 1;
    private int maxPage = 1;
    private boolean isLoad = false;
    private boolean isTop = true;
    private View focusedView = null;
    // 记录当前筛选弹窗对应的分类 ID，分类切换后需重新创建弹窗
    private String currentFilterSortId = null;

    private static class GridInfo{
        public String sortID="";
        public TvRecyclerView mGridView;
        public GridAdapter gridAdapter;
        public int page = 1;
        public int maxPage = 1;
        public boolean isLoad = false;
        public View focusedView = null;
    }

    Stack<GridInfo> mGrids = new Stack<GridInfo>(); //ui栈

    public static GridFragment newInstance(MovieSort.SortData sortData) {
        return new GridFragment().setArguments(sortData);
    }

    public GridFragment setArguments(MovieSort.SortData sortData) {
        this.sortData = sortData;
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_grid;
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null && this.sortData == null) {
            this.sortData = GsonUtils.fromJson(savedInstanceState.getString("sortDataJson"), MovieSort.SortData.class);
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
    }
    
    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("sortDataJson", GsonUtils.toJson(sortData));        
    }

    private void changeView(String id,Boolean isFolder){
        if(isFolder){
            this.sortData.flag =style==null?"1":"2"; // 修改sortData.flag
        }else {
            this.sortData.flag ="2"; // 修改sortData.flag
        }
        initView();
        this.sortData.id = id; // 修改sortData.id为新的ID
        // 进入新分类（文件夹）时清空旧筛选条件，避免把上一个分类的筛选应用到新分类
        if (sortData.filterSelect != null) {
            sortData.filterSelect.clear();
        }
        toggleFilterStatus();
        initViewModel();
        initData();
    }

    public boolean isFolederMode() {
        return (getUITag() == '1');
    }

    // 获取当前页面UI的显示模式 ‘0’ 正常模式 '1' 文件夹模式 '2' 显示缩略图的文件夹模式
    public char getUITag() {
        return (sortData == null || sortData.flag == null || sortData.flag.length() == 0) ? '0' : sortData.flag.charAt(0);
    }

    // 是否允许聚合搜索 sortData.flag的第二个字符为‘1’时允许聚搜
    public boolean enableFastSearch() {  return sortData.flag == null || sortData.flag.length() < 2 || (sortData.flag.charAt(1) == '1'); }
    //public boolean enableFastSearch() {  return (sortData.flag == null || sortData.flag.length() < 2) ? true : (sortData.flag.charAt(1) == '1'); }

    // 保存当前页面
    private void saveCurrentView() {
        if (this.mGridView == null) return;
        GridInfo info = new GridInfo();
        info.sortID = this.sortData.id;
        info.mGridView = this.mGridView;
        info.gridAdapter = this.gridAdapter;
        info.page = this.page;
        info.maxPage = this.maxPage;
        info.isLoad = this.isLoad;
        info.focusedView = this.focusedView;
        this.mGrids.push(info);
    }

    // 丢弃当前页面，将页面还原成上一个保存的页面
    public boolean restoreView() {
        if (mGrids.empty()) return false;
        if (mGridView == null) return false;
        this.showSuccess();
        ViewParent parent = mGridView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(this.mGridView);
        }
        GridInfo info = mGrids.pop();// 还原上次保存的控件
        this.sortData.id = info.sortID;
        this.mGridView = info.mGridView;
        this.gridAdapter = info.gridAdapter;
        this.page = info.page;
        this.maxPage = info.maxPage;
        this.isLoad = info.isLoad;
        this.focusedView = info.focusedView;
        this.mGridView.setVisibility(View.VISIBLE);
        if (mGridView != null) mGridView.requestFocus();
        return true;
    }

    private ImgUtil.Style style;
    // 更改当前页面
    private void createView() {
        this.saveCurrentView(); // 保存当前页面
        if (mGridView == null) { // 从layout中拿view
            mGridView = findViewById(R.id.mGridView);
        } else { // 复制当前view
            // 修复：mGridView 引用的 View 可能已被销毁，getParent 返回 null，需要判空保护
            ViewParent parent = mGridView.getParent();
            if (parent instanceof ViewGroup) {
                TvRecyclerView v3 = new TvRecyclerView(this.mContext);
                int spacing = AutoSizeUtils.mm2px(this.mContext, 20);
                v3.setSpacingWithMargins(spacing, spacing);
                v3.setLayoutParams(mGridView.getLayoutParams());
                v3.setPadding(mGridView.getPaddingLeft(), mGridView.getPaddingTop(), mGridView.getPaddingRight(), mGridView.getPaddingBottom());
                v3.setClipToPadding(mGridView.getClipToPadding());
                ((ViewGroup) parent).addView(v3);
                mGridView.setVisibility(View.GONE);
                mGridView = v3;
                mGridView.setVisibility(View.VISIBLE);
            } else {
                // 重新查找
                mGridView = findViewById(R.id.mGridView);
            }
        }
        mGridView.setHasFixedSize(true);
        style=ImgUtil.initStyle();
        gridAdapter = new GridAdapter(isFolederMode(), style);
        this.page = 1;
        this.maxPage = 1;
        this.isLoad = false;
    }

    private void initView() {
        this.createView();
        if (isFolederMode()) {
            mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        } else {
            int spanCount = isBaseOnWidth() ? 5 : 6;
            if (style != null) {
                spanCount = ImgUtil.spanCountByStyle(style, spanCount);
            }
            if (spanCount == 1) {
                mGridView.setLayoutManager(new V7LinearLayoutManager(mContext, spanCount, false));
            } else {
                mGridView.setLayoutManager(new V7GridLayoutManager(mContext, spanCount));
            }
        }
        mGridView.setAdapter(gridAdapter);
        mGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // 实时更新顶部状态，用于返回键判断是先回顶还是返回分类菜单
                isTop = !recyclerView.canScrollVertically(-1);
            }
        });

        gridAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                gridAdapter.setEnableLoadMore(true);
                sourceViewModel.getList(sortData, page);
            }
        }, mGridView);
        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.1f).scaleY(1.1f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_UP) {
                }
                return false;
            }
        });
        gridAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    // 配置中心类卡片：action 字段非空时回调 jar 内 spider.action 并刷新列表
                    if (video.action != null) {
                        sourceViewModel.action(video.sourceKey, video.action);
                        return;
                    }
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    if( video.tag !=null && (video.tag.equals("folder") || video.tag.equals("cover"))){
                        focusedView = view;
                        if(("12".indexOf(getUITag()) != -1)){
                            changeView(video.id,video.tag.equals("folder"));
                        }else {
                            changeView(video.id,false);
                        }
                    } else {
                        if (video.id == null || video.id.isEmpty() || video.id.startsWith("msearch:")) {
                            if(Hawk.get(HawkConfig.FAST_SEARCH_MODE, false) && enableFastSearch()){
                                jumpActivity(FastSearchActivity.class, bundle);
                            }else {
                                jumpActivity(SearchActivity.class, bundle);
                            }
                        } else {
                            jumpActivity(DetailActivity.class, bundle);
                        }
                    }

                }
            }
        });
        // takagen99 : Long Press to Fast Search
        gridAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    jumpActivity(FastSearchActivity.class, bundle);
                }
                return true;
            }
        });
        gridAdapter.setLoadMoreView(new LoadMoreView());
        setLoadSir(mGridView);
    }

    private void initViewModel() {
        if (sourceViewModel != null) return;
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                // Fragment 重建过程中 gridAdapter 可能为 null，需要判空保护
                if (gridAdapter == null) return;
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    if (page == 1) {
                        showSuccess();
                        isLoad = true;
                        gridAdapter.setNewData(absXml.movie.videoList);
                    } else {
                        // 限制总数据量，防止异常累积导致 OOM
                        if (gridAdapter.getData().size() < 5000) {
                            gridAdapter.addData(absXml.movie.videoList);
                        }
                    }
                    page++;
                    maxPage = absXml.movie.pagecount;
                    if (page > maxPage && maxPage!=0) {
                        gridAdapter.loadMoreEnd();
                        gridAdapter.setEnableLoadMore(false);
                    } else {
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    }
                } else {
                    if (page == 1) {
                        showEmpty();
                    }
                    if (page > maxPage && maxPage!=0) {
                        ToastHelper.showToast(getContext(), "没有更多了");
                        gridAdapter.loadMoreEnd();
                    } else {
                        gridAdapter.loadMoreComplete();
                    }
                    gridAdapter.setEnableLoadMore(false);
                }
            }
        });

        // 配置中心类卡片 action 回调：解析 jar 返回 JSON 中的 msg 字段并刷新列表
        sourceViewModel.actionResult.observe(this, new Observer<JSONObject>() {
            @Override
            public void onChanged(JSONObject jsonObject) {
                if (jsonObject == null) return;
                String msg = jsonObject.optString("msg");
                if (!msg.isEmpty()) {
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                    forceRefresh();
                }
            }
        });
    }

    public boolean isLoad() {
        return isLoad || !mGrids.empty(); //如果有缓存页的话也可以认为是加载了数据的
    }

    private void initData() {
    	if (ApiConfig.get().getHomeSourceBean().getApi()==null) {
            showEmpty();
            return;
        }
        if (sourceViewModel == null) {
            return;
        }
        if (sortData == null) {
            showEmpty();
            return;
        }
        showLoading();
        isLoad = false;
        scrollTop();
        toggleFilterStatus();
        sourceViewModel.getList(sortData, page);
    }

    private void toggleFilterStatus() {
        if (sortData!=null && sortData.filters != null && !sortData.filters.isEmpty()) {
            int count = sortData.filterSelectCount();
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_FILTER_CHANGE, count));
        }
    }

    public boolean isTop() {
        return isTop;
    }

    public void scrollTop() {
        isTop = true;
        if (mGridView != null) {
            mGridView.scrollToPosition(0);
        }
    }

    public void showFilter() {
        if (sortData == null || sortData.filters == null || sortData.filters.isEmpty() || mContext == null) {
            return;
        }
        // 分类切换后（包括进入文件夹导致 sortData.id 变化），释放旧的筛选弹窗并清空旧筛选条件
        if (gridFilterDialog != null && (currentFilterSortId == null || !currentFilterSortId.equals(sortData.id))) {
            gridFilterDialog.dismiss();
            gridFilterDialog = null;
        }
        // 当前弹窗对应的分类发生变化时，清空已选筛选条件；同一分类重复打开则保留记忆
        if (currentFilterSortId == null || !currentFilterSortId.equals(sortData.id)) {
            if (sortData.filterSelect != null) {
                sortData.filterSelect.clear();
            }
            currentFilterSortId = sortData.id;
        }
        if (gridFilterDialog == null) {
            gridFilterDialog = new GridFilterDialog(mContext);
            setFilterDialogData();
        }
        if (gridFilterDialog != null) {
            gridFilterDialog.show();
        }
    }

    public void setFilterDialogData() {
        Context context = getContext();
        if (context == null || gridFilterDialog == null || gridFilterDialog.filterRoot == null) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(context);

        // 获取动态主题颜色
        TypedArray a = context.obtainStyledAttributes(R.styleable.themeColor);
        int selectedColor = a.getColor(R.styleable.themeColor_color_theme, 0); // 选择的颜色
        int defaultColor = ContextCompat.getColor(context, R.color.color_FFFFFF);
        // 释放 TypedArray 资源
        a.recycle();

        ArrayList<TvRecyclerView> filterRows = new ArrayList<>();
        // 遍历过滤条件数据
        for (MovieSort.SortFilter filter : sortData.filters) {
            View line = inflater.inflate(R.layout.item_grid_filter, gridFilterDialog.filterRoot, false);
            TextView filterNameTv = line.findViewById(R.id.filterName);
            filterNameTv.setText(filter.name);
            TvRecyclerView gridView = line.findViewById(R.id.mFilterKv);
            gridView.setHasFixedSize(true);
            gridView.setLayoutManager(new V7LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
            GridFilterKVAdapter adapter = new GridFilterKVAdapter();
            gridView.setAdapter(adapter);
            final String key = filter.key;
            final ArrayList<String> values = new ArrayList<>(filter.values.keySet());
            final ArrayList<String> keys = new ArrayList<>(filter.values.values());
            adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
                // 用于记录上一次选中的 view
                View previousSelectedView = null;
                @Override
                public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                    String currentSelection = sortData.filterSelect.get(key);
                    String newSelection = keys.get(position);
                    if (currentSelection == null || !currentSelection.equals(newSelection)) {
                        // 更新选中状态
                        sortData.filterSelect.put(key, newSelection);
                        updateViewStyle(view, selectedColor, true);
                        if (previousSelectedView != null) {
                            updateViewStyle(previousSelectedView, defaultColor, false);
                        }
                        previousSelectedView = view;
                    } else {
                        // 取消选中
                        sortData.filterSelect.remove(key);
                        if (previousSelectedView != null) {
                            updateViewStyle(previousSelectedView, defaultColor, false);
                        }
                        previousSelectedView = null;
                    }
                    forceRefresh();
                }
                private void updateViewStyle(View view, int color, boolean isBold) {
                    TextView valueTv = view.findViewById(R.id.filterValue);
                    valueTv.getPaint().setFakeBoldText(isBold);
                    valueTv.setTextColor(color);
                }
            });
            adapter.setNewData(values);
            gridFilterDialog.filterRoot.addView(line);
            filterRows.add(gridView);
        }

        // 为每行筛选条件设置边界按键监听，实现上下键跨行切换焦点并保持横向位置
        for (int i = 0; i < filterRows.size(); i++) {
            final int rowIndex = i;
            TvRecyclerView row = filterRows.get(i);
            row.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
                @Override
                public boolean onInBorderKeyEvent(int direction, View focused) {
                    if (direction == View.FOCUS_DOWN && rowIndex < filterRows.size() - 1) {
                        moveFocusToFilterRow(row, filterRows.get(rowIndex + 1));
                        return true;
                    }
                    if (direction == View.FOCUS_UP && rowIndex > 0) {
                        moveFocusToFilterRow(row, filterRows.get(rowIndex - 1));
                        return true;
                    }
                    return false;
                }
            });
        }
    }

    /**
     * 将焦点移动到指定筛选行，并保持当前横向位置，带重试机制防止 RecyclerView 子 View 尚未布局完成
     */
    private void moveFocusToFilterRow(TvRecyclerView fromRow, TvRecyclerView targetRow) {
        if (targetRow == null || targetRow.getLayoutManager() == null) {
            return;
        }
        int targetPosition = 0;
        if (fromRow != null && fromRow.getLayoutManager() != null) {
            View focusedChild = fromRow.getFocusedChild();
            if (focusedChild != null) {
                int currentPosition = fromRow.getChildAdapterPosition(focusedChild);
                if (currentPosition >= 0 && targetRow.getAdapter() != null) {
                    int targetCount = targetRow.getAdapter().getItemCount();
                    targetPosition = Math.min(currentPosition, Math.max(0, targetCount - 1));
                }
            }
        }
        final int finalPosition = targetPosition;
        targetRow.post(new Runnable() {
            int retryCount = 0;

            @Override
            public void run() {
                if (targetRow.getLayoutManager() == null) {
                    return;
                }
                View item = targetRow.getLayoutManager().findViewByPosition(finalPosition);
                if (item != null) {
                    item.requestFocus();
                    return;
                }
                // 目标子 View 尚未布局完成，先滚动到目标位置并高亮，再等待下一帧重试
                targetRow.scrollToPosition(finalPosition);
                targetRow.setSelectedPosition(finalPosition);
                if (++retryCount < 5) {
                    targetRow.post(this);
                }
            }
        });
    }

    public void forceRefresh() {
        page = 1;
        initData();
    }

    public void resetFilterState() {
        if (sortData != null && sortData.filterSelect != null) {
            sortData.filterSelect.clear();
        }
        if (gridFilterDialog != null) {
            gridFilterDialog.dismiss();
            gridFilterDialog = null;
        }
        currentFilterSortId = null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mGrids != null) {
            mGrids.clear();
        }
        // 释放 View 引用并重置分页状态
        mGridView = null;
        gridAdapter = null;
        gridFilterDialog = null;
        currentFilterSortId = null;
        focusedView = null;
        style = null;
        page = 1;
        maxPage = 1;
        isLoad = false;
        isTop = true;
    }
}
