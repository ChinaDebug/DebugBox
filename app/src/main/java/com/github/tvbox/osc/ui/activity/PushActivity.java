package com.github.tvbox.osc.ui.activity;

import android.widget.ImageView;
import android.widget.TextView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.tv.QRCodeGen;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class PushActivity extends BaseActivity {
    private ImageView ivQRCode;
    private TextView tvAddress;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_push;
    }

    @Override
    protected void init() {
        initView();
        initData();
    }

    private void initView() {
        ivQRCode = findViewById(R.id.ivQRCode);
        tvAddress = findViewById(R.id.tvAddress);
        refreshQRCode();
    }

    private void refreshQRCode() {
        String address = ControlManager.get().getAddress(false);
        if (address == null || address.isEmpty()) {
            tvAddress.setText("服务地址获取失败，请返回首页重新进入");
            ivQRCode.setImageBitmap(null);
            return;
        }
        tvAddress.setText(String.format("手机/电脑扫描上方二维码或者直接浏览器访问地址\n%s", address));
        ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address, AutoSizeUtils.mm2px(this, 300), AutoSizeUtils.mm2px(this, 300), 4));
    }

    private void initData() {

    }
}