package com.kr.musicplayer.ui.activity;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;

import android.transition.Slide;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.kr.musicplayer.R;
import com.kr.musicplayer.bean.mp3.Song;
import com.kr.musicplayer.helper.MusicServiceRemote;
import com.kr.musicplayer.lyric.LrcView;
import com.kr.musicplayer.misc.handler.MsgHandler;
import com.kr.musicplayer.misc.handler.OnHandleMessage;
import com.kr.musicplayer.request.ImageUriRequest;
import com.kr.musicplayer.request.network.RxUtil;
import com.kr.musicplayer.service.Command;
import com.kr.musicplayer.service.MusicService;
import com.kr.musicplayer.theme.GradientDrawableMaker;
import com.kr.musicplayer.theme.ThemeStore;
import com.kr.musicplayer.ui.activity.base.BaseMusicActivity;
import com.kr.musicplayer.ui.adapter.PagerAdapter;
import com.kr.musicplayer.ui.dialog.ShareDialog;
import com.kr.musicplayer.ui.dialog.SleepDialog;
import com.kr.musicplayer.ui.fragment.CoverFragment;
import com.kr.musicplayer.ui.fragment.LyricFragment;
import com.kr.musicplayer.ui.fragment.SongFragment;
import com.kr.musicplayer.ui.misc.Tag;
import com.kr.musicplayer.ui.widget.AudioViewPager;
import com.kr.musicplayer.util.DensityUtil;
import com.kr.musicplayer.util.MaterialDialogHelper;
import com.kr.musicplayer.util.MediaStoreUtil;
import com.kr.musicplayer.util.MusicUtil;
import com.kr.musicplayer.util.PlaylistDialog;
import com.kr.musicplayer.util.SPUtil;
import com.kr.musicplayer.util.StatusBarUtil;
import com.kr.musicplayer.util.ToastUtil;
import com.kr.musicplayer.util.Util;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.OnTouch;
import io.apptik.widget.MultiSlider;
import io.reactivex.disposables.Disposable;

import static com.kr.musicplayer.misc.ExtKt.isPortraitOrientation;
import static com.kr.musicplayer.theme.ThemeStore.getAccentColor;
import static com.kr.musicplayer.util.Constants.MODE_LOOP;
import static com.kr.musicplayer.util.Constants.MODE_REPEAT;
import static com.kr.musicplayer.util.Constants.MODE_SHUFFLE;
import static com.kr.musicplayer.util.ImageUriUtil.getSearchRequestWithAlbumType;
import static com.kr.musicplayer.util.SPUtil.SETTING_KEY.BOTTOM_OF_NOW_PLAYING_SCREEN;
import static com.kr.musicplayer.util.Util.registerLocalReceiver;
import static com.kr.musicplayer.util.Util.sendLocalBroadcast;

/**
 * ????????????
 */
public class PlayerActivity extends BaseMusicActivity implements /*FileChooserDialog.FileCallback,*/ View.OnClickListener {

  //??????????????? ????????? Fragment
  private int mPrevPosition = 0;
  //?????? ????????? ?????? flag ??????
  private boolean mFirstStart = true;
  //????????? ???????????? ?????? ????????? ??????
  public boolean mIsDragSeekBarFromUser = false;

  //????????????
  @BindView(R.id.top_title)
  TextView mTopTitle;
  //Option ??????
  @BindView(R.id.top_more)
  ImageButton mTopMore;
  //?????? ??????
  @BindView(R.id.playbar_play_pause)
  ImageView mPlayPauseView;
  @BindView(R.id.favourite)
  ImageButton mPlayBarPrev;
  @BindView(R.id.timer)
  ImageButton mPlayBarNext;
  @BindView(R.id.playbar_model)
  ImageButton mPlayModel;
  @BindView(R.id.share)
  ImageButton mPlayQueue;
  //????????? ?????? ?????? ??? ?????? ?????? ??????
  @BindView(R.id.text_hasplay)
  TextView mHasPlay;
  @BindView(R.id.text_remain)
  TextView mRemainPlay;
  //?????? ?????????
  @BindView(R.id.seekbar)
  SeekBar mProgressSeekBar;
  //??????
  @BindView(R.id.audio_holder_container)
  ViewGroup mContainer;
  @BindView(R.id.holder_pager)
  AudioViewPager mPager;

  @BindView(R.id.seekbar_repeat)
  MultiSlider mRepeat;
  @BindView(R.id.repeatAB)
  ImageView repeatAB;
  @BindView(R.id.a)
  TextView a;
  @BindView(R.id.b)
  TextView b;

  int repeatToggling = 0;
  @Nullable
  @BindView(R.id.container)
  RelativeLayout container;
  //?????? ??????
  private LrcView mLrcView;
  private Drawable mHighLightIndicator;
  private Drawable mNormalIndicator;
  private ArrayList<ImageView> mIndicators;

  //?????? ???????????? ??????
  private Song mInfo;
  //?????? ??????????????? ????????????
  private boolean mIsPlay;
  //?????? ????????????
  private int mCurrentTime;
  //?????? ??? ??????
  private int mDuration;

  //????????? ??????
  public int mWidth;
  public int mHeight;

  //Fragment
  private LyricFragment mLyricFragment;
  private CoverFragment mCoverFragment;
  private SongFragment mSongFramgment;

  /**
   * ?????? Handler
   */
  private MsgHandler mHandler;

  /**
   * Cover ??? ????????? ???????????? ?????? uri
   */
  private Uri mUri;
  private static final int UPDATE_BG = 1;
  private static final int UPDATE_TIME_ONLY = 2;
  private static final int UPDATE_TIME_ALL = 3;
  private AudioManager mAudioManager;

  public static final int BOTTOM_SHOW_NEXT = 0;
  public static final int BOTTOM_SHOW_VOLUME = 1;
  public static final int BOTTOM_SHOW_BOTH = 2;
  public static final int BOTTOM_SHOW_NONE = 3;

  private static final int FRAGMENT_COUNT = 2;

  private static final int DELAY_SHOW_NEXT_SONG = 3000;

  private Receiver mReceiver = new Receiver();

  @Override
  protected void setUpTheme() {
    int themeRes;
    themeRes = R.style.PlayerActivityStyle;
    setTheme(themeRes);
  }

  @Override
  protected void setNavigationBarColor() {
    super.setNavigationBarColor();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    super.onCreateOptionsMenu(menu);
    return false;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    //?????? ????????? ???????????? ??????
    Uri uri = getIntent().getData();
    if (uri != null) {
      SPUtil.putValue(this, SPUtil.SETTING_KEY.NAME, SPUtil.SETTING_KEY.LAST_SONG_ID, -1);
      SPUtil.putValue(this, SPUtil.SETTING_KEY.NAME, SPUtil.SETTING_KEY.PLAY_FROM_OTHER_APP, true);
    } else {
      SPUtil.putValue(this, SPUtil.SETTING_KEY.NAME, SPUtil.SETTING_KEY.PLAY_FROM_OTHER_APP, false);
    }

    super.onCreate(savedInstanceState);

    // Slide animation for start this activity
    Slide slide = new Slide();
    slide.setSlideEdge(Gravity.BOTTOM);
    slide.setDuration(300);
    slide.setStartDelay(0);
    slide.setInterpolator(new DecelerateInterpolator());
    slide.excludeTarget(android.R.id.statusBarBackground, true);
    slide.excludeTarget(android.R.id.navigationBarBackground, true);
    getWindow().setExitTransition(slide);
    getWindow().setEnterTransition(slide);

    setContentView(R.layout.activity_player);

    ButterKnife.bind(this);

    mHandler = new MsgHandler(this);

    mInfo = MusicServiceRemote.getCurrentSong();
    mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

    setUpSize();
    setUpTop();
    setUpFragments();
    setUpIndicator();
    setUpSeekBar();
    setUpViewColor();
    mRepeat.setVisibility(View.INVISIBLE);
    a.setVisibility(View.INVISIBLE);
    b.setVisibility(View.INVISIBLE);

    registerLocalReceiver(mReceiver, new IntentFilter(ACTION_UPDATE_NEXT));
  }

  public FrameLayout getContainer() {
    return (FrameLayout) mContainer;
  }

  @Override
  public void onResume() {
    super.onResume();
    MaterialDialogHelper.setBackground(this, R.id.container_background);
    if (isPortraitOrientation(this)) {
      mPager.setCurrentItem(0);
    }
    //????????? ????????? ??????
    new ProgressThread().start();

    if (SPUtil.getValue(mContext, SPUtil.SETTING_KEY.NAME, SPUtil.SETTING_KEY.WAKE, false)) {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
  }

  @Override
  public void onServiceConnected(@NotNull MusicService service) {
    super.onServiceConnected(service);
    onMetaChanged();
    onPlayStateChange();

    //?????? ????????? ????????? ??????????????? ?????? intent??? ?????? ?????? ??????
    if (getIntent().getData() != null) {
      Uri uri = getIntent().getData();
      MusicUtil.playFromUri(uri);
      setIntent(new Intent());
    }
  }

  @OnTouch({R.id.favourite, R.id.playbar_play_pause, R.id.playbar_model, R.id.timer, R.id.share
          , R.id.info, R.id.repeatAB})
  public boolean onTouch(View v, MotionEvent event) {
    ColorStateList c;
    if (event.getAction() == MotionEvent.ACTION_UP) {
      c = ContextCompat.getColorStateList(
              getApplicationContext(),
              R.color.light_text_color_primary
      );
    } else {
      c = ContextCompat.getColorStateList(
              getApplicationContext(),
              R.color.md_blue_primary
      );
    }
    ImageButton imageButton = (ImageButton) v;
    imageButton.setImageTintList(c);
    return false;
  }

  /**
   * ??????, ??????, ??????, ???????????? ??????
   */
  @OnClick({R.id.playbar_play_pause, R.id.timer, R.id.favourite, R.id.repeatAB})
  public void onCtrlClick(View v) {
    Intent intent = new Intent(MusicService.ACTION_CMD);
    switch (v.getId()) {
      case R.id.playbar_play_pause:
        MediaStoreUtil.setRing(getApplicationContext(), MusicServiceRemote.getCurrentSong().getId());
        break;
      case R.id.timer:
        SleepDialog.newInstance().show(this.getSupportFragmentManager(), SleepDialog.class.getSimpleName());
        break;
      case R.id.favourite:
        PlaylistDialog.addToPlaylist(this);
        break;
      case R.id.repeatAB:
        repeatToggling++;
        switch (repeatToggling % 3) {
          case 0:
            repeatAB.setImageDrawable(getDrawable(R.drawable.ic_a)); // drawable for 'A'
            mRepeat.setVisibility(View.GONE);
            a.setVisibility(View.GONE);
            b.setVisibility(View.GONE);
            mProgressSeekBar.setVisibility(View.VISIBLE);
            MaterialDialogHelper.timeA = -1;
            MaterialDialogHelper.timeB = -1;
            break;
          case 1:
            repeatAB.setImageDrawable(getDrawable(R.drawable.ic_b)); // drawable for 'B'
            a.setVisibility(View.VISIBLE);
            mProgressSeekBar.setVisibility(View.GONE);
            mRepeat.setVisibility(View.VISIBLE);
            mRepeat.clearThumbs();
            mRepeat.addThumb();
            mRepeat.getThumb(0).setValue(0);
            MaterialDialogHelper.timeA = 1;
            MusicServiceRemote.setProgress(1);
            break;
          case 2:
            b.setVisibility(View.VISIBLE);
            repeatAB.setImageDrawable(getDrawable(R.drawable.ic_x)); // drawable for '/!A'
            mRepeat.addThumb();
            MaterialDialogHelper.timeB = mRepeat.getThumb(1).getValue();
            MusicServiceRemote.handleLoop();
            break;
        }
        break;
    }
    sendLocalBroadcast(intent);
  }

  /**
   * ????????????, ????????????, ??????, ????????? ??????
   */
  @OnClick({R.id.playbar_model, R.id.share, R.id.top_more, R.id.info})
  public void onOtherClick(View v) {
    switch (v.getId()) {
      //?????? ?????? ??????
      case R.id.playbar_model:
        int currentModel = MusicServiceRemote.getPlayModel();
        currentModel = (currentModel == MODE_REPEAT ? MODE_LOOP : ++currentModel);
        MusicServiceRemote.setPlayModel(currentModel);
        int playMode = SPUtil.getValue(this, SPUtil.SETTING_KEY.NAME, SPUtil.SETTING_KEY.PLAY_MODEL,
                MODE_LOOP);
        int playModeIcon = playMode == MODE_LOOP ? R.drawable.ic_btn_loop :
                playMode == MODE_SHUFFLE ? R.drawable.ic_btn_shuffle :
                        R.drawable.ic_btn_loop_one;
        mPlayModel.setImageResource(playModeIcon);

        String msg = currentModel == MODE_LOOP ? getString(R.string.model_normal)
                : currentModel == MODE_SHUFFLE ? getString(R.string.model_random)
                : getString(R.string.model_repeat);
        ToastUtil.show(this, msg);
        break;
      case R.id.share:
        Song[] songs = new Song[]{MusicServiceRemote.getCurrentSong()};
        new ShareDialog(this, songs).show();
        break;
      case R.id.info:
        Tag tag = new Tag(this, mInfo);
        try {
          Thread.sleep(200);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        tag.detail();
        break;
    }
  }

  /**
   * ????????????????????????
   */
  private void setUpSize() {
    WindowManager wm = getWindowManager();
    Display display = wm.getDefaultDisplay();
    DisplayMetrics metrics = new DisplayMetrics();
    display.getMetrics(metrics);
    mWidth = metrics.widthPixels;
    mHeight = metrics.heightPixels;
  }

  /**
   * ????????? Fragment ??? ?????? indicator ?????????
   */
  private void setUpIndicator() {
    int width = DensityUtil.dip2px(this, 8);
    int height = DensityUtil.dip2px(this, 2);

    mHighLightIndicator = new GradientDrawableMaker()
            .width(width)
            .height(height)
            .color(getAccentColor())
            .make();

    mNormalIndicator = new GradientDrawableMaker()
            .width(width)
            .height(height)
            .color(getAccentColor())
            .alpha(0.3f)
            .make();

    mIndicators = new ArrayList<>();
    mIndicators.add(findViewById(R.id.guide_01));
    mIndicators.add(findViewById(R.id.guide_02));
    mIndicators.add(findViewById(R.id.guide_03));
    mIndicators.get(0).setImageDrawable(mHighLightIndicator);
    mIndicators.get(1).setImageDrawable(mNormalIndicator);
    mIndicators.get(2).setImageDrawable(mNormalIndicator);
  }

  /**
   * Seekbar ?????????
   */
  @SuppressLint("CheckResult")
  private void setUpSeekBar() {
    if (mInfo == null) {
      return;
    }
    //???????????? ??? ???????????? ?????????
    mDuration = (int) mInfo.getDuration();
    final int temp = MusicServiceRemote.getProgress();
    mCurrentTime = temp > 0 && temp < mDuration ? temp : 0;

    if (mDuration > 0 && mDuration - mCurrentTime > 0) {
      mHasPlay.setText(Util.getTime(mCurrentTime));
      mRemainPlay.setText(Util.getTime(mDuration));
    }

    //Seekbar ?????????
    if (mDuration > 0 && mDuration < Integer.MAX_VALUE) {
      mProgressSeekBar.setMax(mDuration);
      mRepeat.setMax(mDuration);
    } else {
      mProgressSeekBar.setMax(1000);
      mRepeat.setMax(1000);
    }

    if (mCurrentTime > 0 && mCurrentTime < mDuration) {
      mProgressSeekBar.setProgress(mCurrentTime);
    } else {
      mProgressSeekBar.setProgress(0);
    }

    mRepeat.setOnThumbValueChangeListener(new MultiSlider.SimpleChangeListener() {
      @Override
      public void onValueChanged(MultiSlider multiSlider, MultiSlider.Thumb thumb, int thumbIndex, int value) {
        int r = multiSlider.getWidth() * value / multiSlider.getMax();
        float x;
        if (thumbIndex == 0) {
          x = r + multiSlider.getLeft() + 10f * (multiSlider.getMax() - value) / multiSlider.getMax() - 45.0f * value / multiSlider.getMax();
          a.setX(x);
          MaterialDialogHelper.timeA = value;
          MusicServiceRemote.setProgress(value);
        } else {
          x = r + multiSlider.getLeft() + 10 - 45.0f * value / multiSlider.getMax();
          b.setX(x);
          MaterialDialogHelper.timeB = value;
        }
      }
    });

    mProgressSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
          updateProgressText(progress);
        }
        mHandler.sendEmptyMessage(UPDATE_TIME_ONLY);
        mCurrentTime = progress;
        if (mLrcView != null) {
          mLrcView.seekTo(progress, true, fromUser);
        }
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {
        mIsDragSeekBarFromUser = true;
      }

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
        MusicServiceRemote.setProgress(seekBar.getProgress());
        mIsDragSeekBarFromUser = false;
      }
    });
  }

  /**
   * ?????? ?????? ??????
   */
  public void updateTopStatus(Song song) {
    if (song == null) {
      return;
    }
    String title = song.getTitle() == null ? "" : song.getTitle();

    if (title.equals("")) {
      mTopTitle.setText(getString(R.string.unknown_song));
    } else {
      mTopTitle.setText(title);
    }
  }

  /**
   * ?????? ??? ???????????? ?????? ??????
   */
  public void updatePlayButton(final boolean isPlay) {
    mIsPlay = isPlay;
  }

  /**
   * ?????? ?????????
   */
  private void setUpTop() {
    updateTopStatus(mInfo);
  }

  /**
   * ViewPager ?????????
   */
  @SuppressLint("ClickableViewAccessibility")
  private void setUpFragments() {
    final FragmentManager fragmentManager = getSupportFragmentManager();

    fragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    fragmentManager.executePendingTransactions();
    final List<Fragment> fragments = fragmentManager.getFragments();
    if (fragments != null) {
      for (Fragment fragment : fragments) {
        if (fragment instanceof LyricFragment ||
                fragment instanceof CoverFragment) {
          fragmentManager.beginTransaction().remove(fragment).commitNow();
        }
      }
    }

    mCoverFragment = new CoverFragment(mPager);
    setUpCoverFragment();
    mLyricFragment = new LyricFragment(mPager);
    setUpLyricFragment();
    mSongFramgment = new SongFragment(true);

    if (isPortraitOrientation(this)) {

      //Viewpager
      PagerAdapter adapter = new PagerAdapter(getSupportFragmentManager());
      adapter.addFragment(mSongFramgment);
      adapter.addFragment(mCoverFragment);
      adapter.addFragment(mLyricFragment);

      mPager.setAdapter(adapter);
      mPager.setOffscreenPageLimit(adapter.getCount() - 1);
      mPager.setCurrentItem(0);

      if (container != null) container.setOnClickListener(this);

      mPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

        }

        @Override
        public void onPageSelected(int position) {
          mIndicators.get(mPrevPosition).setImageDrawable(mNormalIndicator);
          mIndicators.get(position).setImageDrawable(mHighLightIndicator);
          mPrevPosition = position;
          //?????? interface ??? ?????? ??????
          if (position == 1 && SPUtil
                  .getValue(mContext, SPUtil.SETTING_KEY.NAME, SPUtil.SETTING_KEY.SCREEN_ALWAYS_ON, false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          }
        }

        @Override
        public void onPageScrollStateChanged(int state) {
        }
      });
    }

  }

  /**
   * ?????? Fragment ??????
   */
  private void setUpLyricFragment() {
    mLyricFragment.setOnInflateFinishListener(view -> {
      mLrcView = (LrcView) view;
      mLrcView.setOnLrcClickListener(new LrcView.OnLrcClickListener() {
        @Override
        public void onClick() {
        }

        @Override
        public void onLongClick() {
        }
      });
      mLrcView.setOnSeekToListener(progress -> {
        if (progress > 0 && progress < MusicServiceRemote.getDuration()) {
          MusicServiceRemote.setProgress(progress);
          mCurrentTime = progress;
          mHandler.sendEmptyMessage(UPDATE_TIME_ALL);
        }
      });
      mLrcView.setHighLightColor(mContext.getColor(R.color.default_accent_color));
      mLrcView.setOtherColor(ThemeStore.getTextColorPrimary());
      mLrcView.setTimeLineColor(ThemeStore.getTextColorPrimary());
    });
  }

  private void setUpCoverFragment() {
  }

  /**
   * MediaStore ??? ?????????????????? ???????????? Callback
   */
  @Override
  public void onMediaStoreChanged() {
    super.onMediaStoreChanged();

    final Song newSong = MusicServiceRemote.getCurrentSong();
    updateTopStatus(newSong);
    mLyricFragment.updateLrc(newSong);
    mInfo = newSong;
    requestCover(false);
  }

  @Override
  public void onMetaChanged() {
    super.onMetaChanged();
    mInfo = MusicServiceRemote.getCurrentSong();
    //Operation ????????? Play ?????? Pause??? ?????? ???????????? ??????????????? ?????? ?????? ??????
    final int operation = MusicServiceRemote.getOperation();
    if ((operation != Command.TOGGLE || mFirstStart)) {
      //?????? ??????
      updateTopStatus(mInfo);
      //?????? ??????
      mHandler.postDelayed(() -> mLyricFragment.updateLrc(mInfo), 500);
      //????????? ????????? ??????
      int temp = MusicServiceRemote.getProgress();
      mCurrentTime = temp > 0 && temp < mDuration ? temp : 0;
      mDuration = (int) mInfo.getDuration();
      mProgressSeekBar.setMax(mDuration);
      mRepeat.setMax(mDuration);
      //?????? ?????? ??????
      requestCover(operation != Command.TOGGLE && !mFirstStart);
    }
  }

  @Override
  public void onPlayStateChange() {
    super.onPlayStateChange();
    //???????????? ??????
    final boolean isPlay = MusicServiceRemote.isPlaying();
    if (mIsPlay != isPlay) {
      updatePlayButton(isPlay);
    }
  }

  @Override
  public void onClick(View v) {
    if (mPager.getCurrentItem() == 0) mPager.setCurrentItem(1, true);
    else mPager.setCurrentItem(0, true);
  }

  //????????? ???????????? ?????? Thread ??????
  private class ProgressThread extends Thread {

    @Override
    public void run() {
      while (mIsForeground) {
        if (!MusicServiceRemote.isPlaying()) {
          continue;
        }
        int progress = MusicServiceRemote.getProgress();
        if (progress > 0 && progress < mDuration) {
          mCurrentTime = progress;
          mHandler.sendEmptyMessage(UPDATE_TIME_ALL);
          try {
            sleep(100);
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      }
    }
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    return super.onKeyDown(keyCode, event);
  }

  /**
   * Theme ????????? ?????? ?????? ?????? ??????
   */
  private void setUpViewColor() {
    int accentColor = getAccentColor();

    setProgressDrawable(mProgressSeekBar, accentColor);
    //Thumb ??????
    int inset = DensityUtil.dip2px(mContext, 6);

    final int width = DensityUtil.dip2px(this, 2);
    final int height = DensityUtil.dip2px(this, 6);

    mProgressSeekBar.setThumb(new InsetDrawable(
            new GradientDrawableMaker()
                    .width(width)
                    .height(height)
                    .color(accentColor)
                    .make(),
            inset, inset, inset, inset));
    mTopMore.setImageResource(R.drawable.ic_player_more);
    //?????? ?????? ??? ???????????????
    int playMode = SPUtil.getValue(this, SPUtil.SETTING_KEY.NAME, SPUtil.SETTING_KEY.PLAY_MODEL,
            MODE_LOOP);
    int playModeIcon = playMode == MODE_LOOP ? R.drawable.ic_btn_loop :
            playMode == MODE_SHUFFLE ? R.drawable.ic_btn_shuffle :
                    R.drawable.ic_btn_loop_one;
    mPlayModel.setImageResource(playModeIcon);
  }

  private void setProgressDrawable(SeekBar seekBar, int accentColor) {
    LayerDrawable progressDrawable = (LayerDrawable) seekBar.getProgressDrawable();
    //????????? ?????? ??????
    (progressDrawable.getDrawable(1)).setColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
    seekBar.setProgressDrawable(progressDrawable);
  }

  private void updateCover(boolean withAnimation) {
    mCoverFragment.updateCover(mInfo, mUri, withAnimation);
    mFirstStart = false;
  }

  /**
   * Cover ??????
   */
  private void requestCover(boolean withAnimation) {
    //Cover ??????
    if (mInfo == null) {
      mUri = Uri.parse("res://" + mContext.getPackageName() + "/" + R.drawable.ic_disc);
      updateCover(withAnimation);
    } else {
      new ImageUriRequest<String>() {
        @Override
        public void onError(Throwable throwable) {
          mUri = Uri.EMPTY;
          updateCover(withAnimation);
        }

        @Override
        public void onSuccess(String result) {
          mUri = Uri.parse(result);
          updateCover(withAnimation);
        }

        @Override
        public Disposable load() {
          return getCoverObservable(getSearchRequestWithAlbumType(mInfo))
                  .compose(RxUtil.applyScheduler())
                  .subscribe(this::onSuccess, this::onError);
        }
      }.load();
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    MaterialDialogHelper.timeA = -1;
    MaterialDialogHelper.timeB = -1;
    mHandler.remove();
    Util.unregisterLocalReceiver(mReceiver);
    MaterialDialogHelper.favoriteCount = 0;
  }

  /**
   * ???????????? ??? ???????????? ??????
   * @param current
   */
  private void updateProgressText(int current) {
    if (mHasPlay != null
            && mRemainPlay != null
            && current > 0
            && (mDuration - current) > 0) {
      mHasPlay.setText(Util.getTime(current));
      mRemainPlay.setText(Util.getTime(mDuration));
    }
  }

  private void updateProgressByHandler() {
    updateProgressText(mCurrentTime);
  }

  private void updateSeekbarByHandler() {
    mProgressSeekBar.setProgress(mCurrentTime);
  }

  @OnHandleMessage
  public void handleInternal(Message msg) {
    if (msg.what == UPDATE_TIME_ONLY && !mIsDragSeekBarFromUser) {
      updateProgressByHandler();
    }
    if (msg.what == UPDATE_TIME_ALL && !mIsDragSeekBarFromUser) {
      updateProgressByHandler();
      updateSeekbarByHandler();
    }
  }

  public static final String ACTION_UPDATE_NEXT = "com.kr.myplayer.update.next_song";

  private class Receiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      mRepeat.setVisibility(View.GONE);
      a.setVisibility(View.GONE);
      b.setVisibility(View.GONE);
      repeatAB.setImageDrawable(getDrawable(R.drawable.ic_a));
      repeatToggling = 0;
      mProgressSeekBar.setVisibility(View.VISIBLE);
      MaterialDialogHelper.timeA = -1;
      MaterialDialogHelper.timeB = -1;
    }
  }
}
