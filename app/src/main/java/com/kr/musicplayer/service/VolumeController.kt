package com.kr.musicplayer.service

import android.os.CountDownTimer
import android.os.Handler
import androidx.annotation.FloatRange
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * 음량조종 클라스
 */

class VolumeController(musicService: MusicService) {
  private val mRef = WeakReference<MusicService>(musicService)
  private val mHandler: Handler = Handler()
  private val mFadeInRunnable: Runnable = Runnable {
    val mediaPlayer = mRef.get()?.mediaPlayer
    object : CountDownTimer(DURATION_IN_MS, DURATION_IN_MS / 10) {
      override fun onFinish() {
        directTo(1f)
      }

      override fun onTick(millisUntilFinished: Long) {
        val volume = 1f - millisUntilFinished * 1.0f / DURATION_IN_MS
        try {
          mediaPlayer?.setVolume(volume, volume)
        } catch (e: IllegalStateException) {
          Timber.v(e)
        }
      }
    }.start()
  }

  /**
   * 음량이 점점 축소되도록 하는 runnable
   */
  private val mFadeOutRunnable: Runnable = Runnable {
    val mediaPlayer = mRef.get()?.mediaPlayer
    object : CountDownTimer(DURATION_IN_MS, DURATION_IN_MS / 10) {
      override fun onTick(millisUntilFinished: Long) {
        val volume = millisUntilFinished * 1.0f / DURATION_IN_MS
        try {
          mediaPlayer?.setVolume(volume, volume)
        } catch (e: IllegalStateException) {
          Timber.v(e)
        }
      }

      override fun onFinish() {
        directTo(0f)
        try {
          mediaPlayer?.pause()
        } catch (e: IllegalStateException) {
          Timber.v(e)
        }
      }

    }.start()
  }

  /**
   * 아무런 효과없이 직접 음량변경
   */
  fun directTo(@FloatRange(from = 0.0, to = 1.0) toVolume: Float) {
    directTo(toVolume, toVolume)
  }

  private fun directTo(@FloatRange(from = 0.0, to = 1.0) leftVolume: Float, @FloatRange(from = 0.0, to = 1.0) rightVolume: Float) {
    val mediaPlayer = mRef.get()?.mediaPlayer
    try {
      mediaPlayer?.setVolume(leftVolume, rightVolume)
    } catch (e: IllegalStateException) {
      Timber.v(e)
    }
  }

  /**
   * 음량이 점점 뚜렷해지도록 하는 효과
   */
  fun fadeIn() {
    mHandler.removeCallbacks(mFadeInRunnable)
    mHandler.removeCallbacks(mFadeOutRunnable)
    mHandler.post(mFadeInRunnable)
  }

  /**
   * 음량이 점점 축소되도록 하는 효과
   */
  fun fadeOut() {
    mHandler.removeCallbacks(mFadeInRunnable)
    mHandler.removeCallbacks(mFadeOutRunnable)
    mHandler.post(mFadeOutRunnable)
  }

  companion object {
    private const val DURATION_IN_MS = 600L
  }
}