package com.github.tvbox.osc.ui.dialog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import org.jetbrains.annotations.NotNull;

public class CleanResetDialog extends BaseDialog {
    private final TextView tvClean;
    private final TextView tvReset;

    @SuppressLint("MissingInflatedId")
    public CleanResetDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_clean_reset);
        setCanceledOnTouchOutside(true);
        tvClean = findViewById(R.id.btnClean);
        tvReset = findViewById(R.id.btnReset);
        tvClean.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                new ConfirmClearDialog(context, "Cache").show();
            }
        });
        tvReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                new ResetDialog(context).show();
            }
        });
    }
}
