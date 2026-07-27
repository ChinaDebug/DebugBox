package com.github.tvbox.osc.ui.dialog;

import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.adapter.LocalFilePickerAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 本地文件/文件夹选择对话框，统一替代 android-file-chooser。
 * 支持文件夹选择（本地盘）和文件选择（字幕等），同时兼容 TV 遥控器焦点与手机触摸。
 */
public class LocalFilePickerDialog extends BaseDialog {

    /**
     * 选择模式：FOLDER 选择文件夹；FILE 选择文件
     */
    public enum PickMode {
        FOLDER, FILE
    }

    private File currentDir;
    private String startPath;
    private PickMode mode = PickMode.FOLDER;
    private List<String> extensions = new ArrayList<>();
    private OnPathSelectedListener listener;
    private final LocalFilePickerAdapter adapter = new LocalFilePickerAdapter();

    public interface OnPathSelectedListener {
        void onPathSelected(String path);
    }

    public LocalFilePickerDialog(@NonNull @NotNull android.content.Context context) {
        super(context);
        setContentView(R.layout.dialog_local_file_picker);
    }

    public void setOnPathSelectedListener(OnPathSelectedListener listener) {
        this.listener = listener;
    }

    public void setMode(PickMode mode) {
        this.mode = mode;
    }

    public void setExtensions(List<String> extensions) {
        this.extensions.clear();
        if (extensions != null) {
            this.extensions.addAll(extensions);
        }
    }

    public void setStartPath(String startPath) {
        this.startPath = startPath;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initView();
        initStartDir();
        refreshFiles();
    }

    private void initStartDir() {
        if (startPath != null) {
            File startFile = new File(startPath);
            if (startFile.exists()) {
                currentDir = startFile.isDirectory() ? startFile : startFile.getParentFile();
            }
        }
        if (currentDir == null) {
            currentDir = Environment.getExternalStorageDirectory();
        }
        if (currentDir == null || !currentDir.canRead()) {
            currentDir = getContext().getFilesDir();
        }
    }

    private void initView() {
        TvRecyclerView recyclerView = findViewById(R.id.rv_files);
        recyclerView.setAdapter(adapter);
        adapter.setOnFileClickListener((file, position) -> {
            if (file.isDirectory()) {
                currentDir = file;
                refreshFiles();
                recyclerView.setSelectedPosition(0);
            } else if (mode == PickMode.FILE) {
                if (listener != null) {
                    listener.onPathSelected(file.getAbsolutePath());
                }
                dismiss();
            }
        });

        TextView btnSelectCurrent = findViewById(R.id.btn_select_current);
        TextView btnParent = findViewById(R.id.btn_parent);

        // 文件选择模式下隐藏"选择当前文件夹"按钮，点击文件即确认
        if (mode == PickMode.FILE) {
            btnSelectCurrent.setVisibility(View.GONE);
        } else {
            btnSelectCurrent.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPathSelected(currentDir.getAbsolutePath());
                }
                dismiss();
            });
        }

        btnParent.setOnClickListener(v -> navigateToParent());
    }

    private void navigateToParent() {
        File parent = currentDir.getParentFile();
        if (parent != null && parent.canRead()) {
            currentDir = parent;
            refreshFiles();
        } else {
            Toast.makeText(getContext(), R.string.local_file_picker_title, Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshFiles() {
        TextView title = findViewById(R.id.tv_title);
        title.setText(currentDir.getAbsolutePath());

        File[] files = currentDir.listFiles(file -> {
            if (file.isDirectory()) {
                return true;
            }
            if (mode == PickMode.FOLDER) {
                return false;
            }
            if (extensions == null || extensions.isEmpty()) {
                return true;
            }
            String name = file.getName().toLowerCase();
            for (String ext : extensions) {
                if (name.endsWith("." + ext.toLowerCase())) {
                    return true;
                }
            }
            return false;
        });

        List<File> list = new ArrayList<>();
        if (files != null) {
            list.addAll(Arrays.asList(files));
        }

        // 文件夹排在前面，文件排在后面，同类型按名称排序
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                if (o1.isDirectory() && !o2.isDirectory()) {
                    return -1;
                }
                if (!o1.isDirectory() && o2.isDirectory()) {
                    return 1;
                }
                return o1.getName().compareToIgnoreCase(o2.getName());
            }
        });

        adapter.setData(list);
    }
}
