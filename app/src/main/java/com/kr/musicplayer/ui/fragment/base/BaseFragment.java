package com.kr.musicplayer.ui.fragment.base;

import static com.kr.musicplayer.ui.activity.base.BaseActivity.EXTERNAL_STORAGE_PERMISSIONS;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import butterknife.Unbinder;
import com.kr.musicplayer.App;
import com.kr.musicplayer.util.Util;


/**
 * 기초 fragment
 */
public abstract class BaseFragment extends Fragment {

  protected Unbinder mUnBinder;
  protected Context mContext;
  protected boolean mHasPermission = false;
  protected String mPageName = BaseFragment.class.getSimpleName();

  public Context getContext() {
    return mContext;
  }

  @Override
  public void onAttach(Context context) {
    super.onAttach(context);
    mContext = context;
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mHasPermission = Util.hasPermissions(EXTERNAL_STORAGE_PERMISSIONS);
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    if (mUnBinder != null) {
      mUnBinder.unbind();
    }
  }

  public RecyclerView.Adapter getAdapter() {
    return null;
  }

  public void onResume() {
    super.onResume();
  }

  public void onPause() {
    super.onPause();
  }

  /**
   * 문자렬 얻는 함수
   * @param res 얻으려는 문자렬의 resId
   * @return 얻어진 문자렬
   */
  protected String getStringSafely(@StringRes int res) {
    if (isAdded()) {
      return getString(res);
    } else {
      return App.getContext().getString(res);
    }
  }
}
