package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.HawkUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.widget.OnItemClickListener;
import com.github.tvbox.osc.widget.OnItemSelectedListener;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.owen.tvrecyclerview.widget.SimpleOnItemListener;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.orhanobut.hawk.Hawk;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class MediaSettingDialog extends BaseDialog {

    private MediaSettingContentAdapter contentAdapter;
    private TvRecyclerView listMediaContent;

    public MediaSettingDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_media_setting);
        setCanceledOnTouchOutside(true);
        TvRecyclerView listMediaTitle = findViewById(R.id.list_media_title);
        listMediaContent = findViewById(R.id.list_media_content);
        //右侧设置内容数据
        contentAdapter = new MediaSettingContentAdapter();
        listMediaContent.setAdapter(contentAdapter);
        //默认填充第一个
        List<MediaSettingEntity> listTitle = getListTitle();
        contentAdapter.replaceData(getListContent(listTitle.get(0).tag));
        //左侧数据展示
        MediaSettingTitleAdapter titleAdapter = new MediaSettingTitleAdapter(listTitle);
        listMediaTitle.setAdapter(titleAdapter);
        titleAdapter.setSelectedPosition(0); // 默认选中第一个
        // 设置默认焦点到左侧第一个分类
        listMediaTitle.post(() -> {
            View firstItem = listMediaTitle.getLayoutManager().findViewByPosition(0);
            if (firstItem != null) {
                firstItem.requestFocus();
            }
        });
        listMediaTitle.setOnItemListener(new SimpleOnItemListener() {
            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                //重新替换右侧数据
                contentAdapter.replaceData(getListContent(titleAdapter.getItem(position).tag));
                listMediaContent.setSelectedPosition(0);
                // 更新选中位置，让选中状态跟随焦点
                titleAdapter.setSelectedPosition(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                this.onItemSelected(parent, itemView, position);
                itemView.requestFocus();
            }
        });


        listMediaContent.setOnItemListener((OnItemClickListener) (tvRecyclerView, view, i) -> {
            //处理点击事件
            MediaSettingEntity item = contentAdapter.getItem(i);
            MediaSettingEnum mediaSettingEnum = MediaSettingEnum.valueOf(item.tag);
            switch (mediaSettingEnum) {
                case IjkMediaCodecMode:
                    HawkUtils.nextIJKCodec();
                    contentAdapter.refreshNotifyItemChanged(i);
                    break;
                case IjkCache:
                    HawkUtils.nextIJKCache();
                    contentAdapter.refreshNotifyItemChanged(i);
                    break;
                case BufferMode:
                    FastClickCheckUtil.check(view);
                    int defaultBufferMode = Hawk.get(HawkConfig.BUFFER_MODE, 3);
                    ArrayList<Integer> bufferModes = new ArrayList<>();
                    bufferModes.add(0);
                    bufferModes.add(1);
                    bufferModes.add(2);
                    bufferModes.add(3);
                    bufferModes.add(4);
                    SelectDialog<Integer> bufferModeDialog = new SelectDialog<>(getContext());
                    bufferModeDialog.setCanceledOnTouchOutside(true);
                    bufferModeDialog.setTip("缓冲模式");
                    bufferModeDialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                        @Override
                        public void click(Integer value, int pos) {
                            Hawk.put(HawkConfig.BUFFER_MODE, value);
                            contentAdapter.refreshNotifyItemChanged(i);
                            bufferModeDialog.dismiss();
                        }

                        @Override
                        public String getDisplay(Integer val) {
                            return HawkUtils.getBufferModeDisplay(val);
                        }
                    }, new DiffUtil.ItemCallback<Integer>() {
                        @Override
                        public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                            return oldItem.intValue() == newItem.intValue();
                        }

                        @Override
                        public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                            return oldItem.intValue() == newItem.intValue();
                        }
                    }, bufferModes, defaultBufferMode);
                    bufferModeDialog.show();
                    break;
                case ExoRenderer:
                    break;
                case ExoBufferMode:
                    FastClickCheckUtil.check(view);
                    int defaultExoBufferMode = Hawk.get(HawkConfig.BUFFER_MODE_EXO, 2);
                    ArrayList<Integer> exoBufferModes = new ArrayList<>();
                    exoBufferModes.add(0);
                    exoBufferModes.add(1);
                    exoBufferModes.add(2);
                    exoBufferModes.add(3);
                    SelectDialog<Integer> exoBufferModeDialog = new SelectDialog<>(getContext());
                    exoBufferModeDialog.setCanceledOnTouchOutside(true);
                    exoBufferModeDialog.setTip("Exo缓冲模式");
                    exoBufferModeDialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                        @Override
                        public void click(Integer value, int pos) {
                            Hawk.put(HawkConfig.BUFFER_MODE_EXO, value);
                            contentAdapter.refreshNotifyItemChanged(i);
                            exoBufferModeDialog.dismiss();
                        }

                        @Override
                        public String getDisplay(Integer val) {
                            return HawkUtils.getExoBufferModeDisplay(val);
                        }
                    }, new DiffUtil.ItemCallback<Integer>() {
                        @Override
                        public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                            return oldItem.intValue() == newItem.intValue();
                        }

                        @Override
                        public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                            return oldItem.intValue() == newItem.intValue();
                        }
                    }, exoBufferModes, defaultExoBufferMode);
                    exoBufferModeDialog.show();
                    break;
                case ExoRendererMode:
                    FastClickCheckUtil.check(view);
                    int defaultRendererMode = HawkUtils.getExoRendererMode();
                    ArrayList<Integer> rendererModes = new ArrayList<>();
                    rendererModes.add(0);
                    rendererModes.add(1);
                    rendererModes.add(2);
                    SelectDialog<Integer> rendererModeDialog = new SelectDialog<>(getContext());
                    rendererModeDialog.setTip(getContext().getString(R.string.dia_render));
                    rendererModeDialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                        @Override
                        public void click(Integer value, int pos) {
                            HawkUtils.setExoRendererMode(value);
                            refreshExoPlayerContent();
                            rendererModeDialog.dismiss();
                        }

                        @Override
                        public String getDisplay(Integer val) {
                            return HawkUtils.getExoRendererModeDisplay(val);
                        }
                    }, new DiffUtil.ItemCallback<Integer>() {
                        @Override
                        public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                            return oldItem.intValue() == newItem.intValue();
                        }

                        @Override
                        public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                            return oldItem.intValue() == newItem.intValue();
                        }
                    }, rendererModes, defaultRendererMode);
                    rendererModeDialog.show();
                    break;
                case VodPlayerPreferred:
                    FastClickCheckUtil.check(view);
                    int defaultPos = HawkUtils.getVodPlayerPreferred();
                    ArrayList<Integer> players = new ArrayList<>();
                    players.add(0);
                    players.add(1);
                    players.add(2);
                    players.add(3);
                    players.add(4);
                    players.add(5);
                    players.add(6);
                    SelectDialog<Integer> dialog = new SelectDialog<>(getContext());
                    dialog.setTip(getContext().getString(R.string.dia_player));
                    dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                        @Override
                        public void click(Integer value, int pos) {
                            HawkUtils.setVodPlayerPreferred(value);
                            contentAdapter.refreshNotifyItemChanged(i);
                            dialog.dismiss();
                        }

                        @Override
                        public String getDisplay(Integer val) {
                            return HawkUtils.getVodPlayerPreferredDisplay(val);
                        }
                    }, new DiffUtil.ItemCallback<Integer>() {
                        @Override
                        public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                            return oldItem.intValue() == newItem.intValue();
                        }

                        @Override
                        public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                            return oldItem.intValue() == newItem.intValue();
                        }
                    }, players, defaultPos);
                    dialog.show();
                    break;
                case PlayRender:
                    FastClickCheckUtil.check(view);
                    int defaultRender = HawkUtils.getPlayRender();
                    ArrayList<Integer> renders = new ArrayList<>();
                    renders.add(0);
                    renders.add(1);
                    SelectDialog<Integer> renderDialog = new SelectDialog<>(getContext());
                    renderDialog.setTip(getContext().getString(R.string.dia_render));
                    renderDialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                        @Override
                        public void click(Integer value, int pos) {
                            HawkUtils.setPlayRender(value);
                            contentAdapter.refreshNotifyItemChanged(i);
                            renderDialog.dismiss();
                        }

                        @Override
                        public String getDisplay(Integer val) {
                            return HawkUtils.getPlayRenderDisplay(val);
                        }
                    }, new DiffUtil.ItemCallback<Integer>() {
                        @Override
                        public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                            return oldItem.intValue() == newItem.intValue();
                        }

                        @Override
                        public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                            return oldItem.intValue() == newItem.intValue();
                        }
                    }, renders, defaultRender);
                    renderDialog.show();
                    break;
                case AutoSwitchPlayer:
                    HawkUtils.nextAutoSwitchPlayer();
                    contentAdapter.refreshNotifyItemChanged(i);
                    break;
            }
        });
    }

    public List<MediaSettingEntity> getListTitle() {
        List<MediaSettingEntity> contentEntityList = new ArrayList<>();
        String[] stringTitle = getContext().getResources().getStringArray(R.array.media_title);
        String[] tags = getContext().getResources().getStringArray(R.array.media_title_tag);
        for (int i = 0; i < stringTitle.length; i++) {
            String content = stringTitle[i];
            String tag = tags[i];
            contentEntityList.add(new MediaSettingEntity(content, tag));
        }
        return contentEntityList;
    }


    /**
     * 获取 展示用的数据以及tag
     *
     * @param key
     */
    public List<MediaSettingEntity> getListContent(String key) {
        List<MediaSettingEntity> contentEntityList = new ArrayList<>();
        try {
            int id = getContext().getResources().getIdentifier("media_content_" + key, "array", BuildConfig.APPLICATION_ID);
            String[] strings = getContext().getResources().getStringArray(id);
            int idTag = getContext().getResources().getIdentifier("media_content_tag_" + key, "array", BuildConfig.APPLICATION_ID);
            String[] tags = getContext().getResources().getStringArray(idTag);
            for (int i = 0; i < strings.length; i++) {
                String content = strings[i];
                String tag = tags[i];
                
                if ("ExoPlayer".equals(key)) {
                    if ("ExoRenderer".equals(tag)) {
                        int rendererMode = HawkUtils.getExoRendererMode();
                        if (rendererMode != 1) {
                            continue;
                        }
                        HawkUtils.setExoRenderer(1);
                    }
                }
                
                contentEntityList.add(new MediaSettingEntity(content, tag));
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return contentEntityList;
    }

    private void refreshExoPlayerContent() {
        contentAdapter.replaceData(getListContent("ExoPlayer"));
        listMediaContent.setSelectedPosition(0);
    }

    //左侧数据展示
    public static class MediaSettingTitleAdapter extends BaseQuickAdapter<MediaSettingEntity, BaseViewHolder> {
        private int selectedPosition = 0;

        public MediaSettingTitleAdapter(List<MediaSettingEntity> strings) {
            super(R.layout.item_dialog_select, strings);
        }

        public void setSelectedPosition(int position) {
            int oldPosition = selectedPosition;
            selectedPosition = position;
            notifyItemChanged(oldPosition);
            notifyItemChanged(selectedPosition);
        }

        public int getSelectedPosition() {
            return selectedPosition;
        }

        @Override
        protected void convert(BaseViewHolder helper, MediaSettingEntity item) {
            TextView name = helper.getView(R.id.tvName);
            name.setText(item.content);
            // 设置选中状态的背景
            if (helper.getAdapterPosition() == selectedPosition) {
                name.setBackgroundResource(R.drawable.button_dialog_main_selected);
            } else {
                name.setBackgroundResource(R.drawable.button_dialog_main);
            }
        }
    }

    //右侧的数据展示
    public static class MediaSettingContentAdapter extends BaseQuickAdapter<MediaSettingEntity, BaseViewHolder> {
        public MediaSettingContentAdapter() {
            super(R.layout.item_dialog_select2);
        }

        @Override
        protected void convert(@NonNull BaseViewHolder helper, MediaSettingEntity item) {
            TextView tvTitle = helper.getView(R.id.tv_title);
            tvTitle.setText(item.content);
            TextView tvContent = helper.getView(R.id.tv_content);
            MediaSettingEnum mediaSettingEnum = MediaSettingEnum.valueOf(item.tag);
            switch (mediaSettingEnum) {
                case IjkMediaCodecMode:
                    tvContent.setText(HawkUtils.getIJKCodec());
                    break;
                case IjkCache:
                    tvContent.setText(HawkUtils.getIJKCacheDesc());
                    break;
                case BufferMode:
                    tvContent.setText(HawkUtils.getBufferModeDesc());
                    break;
                case ExoRenderer:
                    tvContent.setText(HawkUtils.getExoRendererDesc());
                    break;
                case ExoBufferMode:
                    tvContent.setText(HawkUtils.getExoBufferModeDesc());
                    break;
                case ExoRendererMode:
                    tvContent.setText(HawkUtils.getExoRendererModeDesc());
                    break;
                case VodPlayerPreferred:
                    tvContent.setText(HawkUtils.getVodPlayerPreferredDesc());
                    break;
                case PlayRender:
                    tvContent.setText(HawkUtils.getPlayRenderDesc());
                    break;
                case AutoSwitchPlayer:
                    tvContent.setText(HawkUtils.getAutoSwitchPlayerDesc());
                    break;
            }
        }
    }

    //数据Bean
    public static class MediaSettingEntity {
        //展示名称
        private String content;
        //处理方式
        private String tag;
        //描述作用
        private String describe;

        public MediaSettingEntity(String content) {
            this.content = content;
        }

        public MediaSettingEntity(String content, String tag) {
            this.content = content;
            this.tag = tag;
        }

        public MediaSettingEntity(String content, String tag, String describe) {
            this.content = content;
            this.tag = tag;
            this.describe = describe;
        }
    }

    //数据枚举
    public enum MediaSettingEnum {
        IjkMediaCodecMode, IjkCache, BufferMode, ExoRenderer, ExoBufferMode, ExoRendererMode, VodPlayerPreferred, PlayRender, AutoSwitchPlayer
    }
}
