package org.schabi.newpipe.settings.tabs;

import android.content.Context;

import androidx.annotation.DrawableRes;
import androidx.fragment.app.Fragment;

import org.schabi.newpipe.R;

import io.github.agelinena.bergapipe.folders.FoldersFragment;

/**
 * Bergapipe: aba "Pastas". Fica neste pacote porque os construtores de {@link Tab} são
 * visíveis só dentro dele.
 */
public class BergaFoldersTab extends Tab {
    /** Longe dos IDs do PipePipe (0, 1, 2...), para não colidir com abas novas do criador. */
    public static final int ID = 1000;

    @Override
    public int getTabId() {
        return ID;
    }

    @Override
    public String getTabName(final Context context) {
        return context.getString(R.string.berga_folders_tab);
    }

    @DrawableRes
    @Override
    public int getTabIconRes(final Context context) {
        return R.drawable.berga_ic_folder;
    }

    @Override
    public Fragment getFragment(final Context context) {
        return new FoldersFragment();
    }
}
