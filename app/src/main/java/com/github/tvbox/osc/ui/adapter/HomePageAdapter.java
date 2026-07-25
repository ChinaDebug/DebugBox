package com.github.tvbox.osc.ui.adapter;

import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;

import com.github.tvbox.osc.base.BaseLazyFragment;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @user acer
 * @date 2018/12/4
 */

@SuppressWarnings("deprecation")
public class HomePageAdapter extends FragmentPagerAdapter {
    public FragmentManager fragmentManager;
    public List<BaseLazyFragment> list;
    private static final AtomicLong ADAPTER_ID_GENERATOR = new AtomicLong();
    // 每次创建 Adapter 时生成唯一 ID，配合 getItemId 让 FragmentPagerAdapter
    // 在数据源变化（如切换站点推荐/历史记录）时能够重建 Fragment。
    private final long adapterId = ADAPTER_ID_GENERATOR.incrementAndGet();

    public HomePageAdapter(FragmentManager fm, List<BaseLazyFragment> list) {
        super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        this.fragmentManager = fm;
        this.list = list;
    }

    @Override
    public Fragment getItem(int position) {
        return list.get(position);
    }

    @Override
    public int getCount() {
        return list != null ? list.size() : 0;
    }

    @Override
    public int getItemPosition(Object object) {
        return POSITION_NONE;
    }

    @Override
    public long getItemId(int position) {
        return adapterId + position;
    }

    @Override
    public Fragment instantiateItem(ViewGroup container, int position) {
        Fragment fragment = (Fragment) super.instantiateItem(container, position);
        fragmentManager.beginTransaction().show(fragment).commitAllowingStateLoss();
        return fragment;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        // 与原版保持一致：只隐藏 Fragment，不销毁 View。
        // 这样切换分类时不需要重新 createView + initData，避免卡顿和重复加载。
        Fragment fragment = (Fragment) object;
        fragmentManager.beginTransaction().hide(fragment).commitAllowingStateLoss();
    }
}
