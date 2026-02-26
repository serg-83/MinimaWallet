package com.example.minimawallet;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

/**
 * Root fragment for the FutureCash feature.
 * Contains two tabs: Send (lock funds) and My Coins (collect unlocked funds).
 */
public class FutureCashFragment extends Fragment {

    private static final String[] TAB_TITLES_EN = {"Send to Future", "My Coins"};
    private static final String[] TAB_TITLES_RU = {"Отправить", "Мои монеты"};

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_future_coins, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TabLayout tabLayout = view.findViewById(R.id.future_tabs);
        ViewPager2 pager = view.findViewById(R.id.future_pager);

        pager.setAdapter(new PagerAdapter(requireActivity()));
        pager.setOffscreenPageLimit(2);

        // Choose tab titles based on locale
        String lang = getResources().getConfiguration().locale.getLanguage();
        String[] titles = "ru".equals(lang) ? TAB_TITLES_RU : TAB_TITLES_EN;

        new TabLayoutMediator(tabLayout, pager,
                (tab, position) -> tab.setText(titles[position])
        ).attach();
    }

    private static class PagerAdapter extends FragmentStateAdapter {
        PagerAdapter(FragmentActivity fa) { super(fa); }

        @Override
        public int getItemCount() { return 2; }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return position == 0 ? new FutureSendFragment() : new FutureCoinsFragment();
        }
    }
}
