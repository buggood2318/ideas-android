package com.buggood2318.ideavault;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 必须先注册插件，再交给 BridgeActivity 初始化
        registerPlugin(SherpaAsrPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
