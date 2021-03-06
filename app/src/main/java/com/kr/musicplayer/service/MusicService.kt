package com.kr.musicplayer.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.*
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.PlaybackState
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.WorkerThread
import com.kr.musicplayer.App
import com.kr.musicplayer.R
import com.kr.musicplayer.appshortcuts.Controller
import com.kr.musicplayer.appwidgets.BaseAppwidget
import com.kr.musicplayer.appwidgets.big.AppWidgetBig
import com.kr.musicplayer.appwidgets.extra.AppWidgetExtra
import com.kr.musicplayer.appwidgets.medium.AppWidgetMedium
import com.kr.musicplayer.appwidgets.medium.AppWidgetMediumTransparent
import com.kr.musicplayer.appwidgets.small.AppWidgetSmall
import com.kr.musicplayer.appwidgets.small.AppWidgetSmallTransparent
import com.kr.musicplayer.bean.mp3.Song
import com.kr.musicplayer.bean.mp3.Song.Companion.EMPTY_SONG
import com.kr.musicplayer.db.room.DatabaseRepository
import com.kr.musicplayer.db.room.model.PlayQueue
import com.kr.musicplayer.helper.*
import com.kr.musicplayer.lyric.LyricFetcher
import com.kr.musicplayer.lyric.LyricFetcher.Companion.LYRIC_FIND_INTERVAL
import com.kr.musicplayer.lyric.bean.LyricRowWrapper
import com.kr.musicplayer.misc.floatpermission.FloatWindowManager
import com.kr.musicplayer.misc.log.LogObserver
import com.kr.musicplayer.misc.receiver.ExitReceiver
import com.kr.musicplayer.misc.receiver.HeadsetPlugReceiver
import com.kr.musicplayer.misc.receiver.HeadsetPlugReceiver.Companion.NEVER
import com.kr.musicplayer.misc.receiver.HeadsetPlugReceiver.Companion.OPEN_SOFTWARE
import com.kr.musicplayer.misc.receiver.MediaButtonReceiver
import com.kr.musicplayer.misc.tryLaunch
import com.kr.musicplayer.request.RemoteUriRequest
import com.kr.musicplayer.request.RequestConfig
import com.kr.musicplayer.request.network.RxUtil.applySingleScheduler
import com.kr.musicplayer.service.notification.Notify
import com.kr.musicplayer.service.notification.NotifyImpl
import com.kr.musicplayer.ui.activity.base.BaseActivity.EXTERNAL_STORAGE_PERMISSIONS
import com.kr.musicplayer.ui.activity.base.BaseMusicActivity
import com.kr.musicplayer.ui.activity.base.BaseMusicActivity.Companion.EXTRA_PERMISSION
import com.kr.musicplayer.ui.activity.base.BaseMusicActivity.Companion.EXTRA_PLAYLIST
import com.kr.musicplayer.ui.viewmodel.registerObserver
import com.kr.musicplayer.ui.widget.desktop.DesktopLyricView
import com.kr.musicplayer.util.*
import com.kr.musicplayer.util.Constants.*
import com.kr.musicplayer.util.ImageUriUtil.getSearchRequestWithAlbumType
import com.kr.musicplayer.util.SPUtil.SETTING_KEY
import com.kr.musicplayer.util.Util.*
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference
import java.util.*
import kotlin.collections.ArrayList

/**
 * ?????? ?????? ?????? ??? Activity ???????????? Callback ??????, ????????? ????????? ?????? Service
 */
@SuppressLint("CheckResult")
class MusicService : BaseService(), Playback, MusicEventCallback,
    SharedPreferences.OnSharedPreferenceChangeListener, CoroutineScope by MainScope() {
  private var contentObserver: ContentObserver? = null // MediaStore ??????????????? ?????? contentObserver
  // ???????????????
  private val playQueue = PlayQueue(this)
  val playQueues: com.kr.musicplayer.service.PlayQueue get() = playQueue

  // ?????? ???????????? ??????
  var currentSong: Song
    get() = playQueue.song
    set(s){
      playQueue.song = s
    }

  // ?????? ??????
  val nextSong: Song
    get() = playQueue.nextSong

  /**
   * ???????????? Service ??? ?????????????????? ????????????
   */
  private var firstPrepared = true

  private var firstLoaded = true

  /**
   * MediaPlayer ??? ?????? source ????????????
   */
  private var prepared = false

  /**
   * ??????????????? ???????????? ????????????
   */
  private var loadFinished = false

  private var playListJob : Job? = null

  /**
   * ??????????????? ???????????? ?????? ????????? ????????????
   */
  var playModel: Int = MODE_LOOP
    set(newPlayModel) {
      val fromShuffleToNone = field == MODE_SHUFFLE
      field = newPlayModel
      desktopWidgetTask?.run()
      SPUtil.putValue(this, SETTING_KEY.NAME, SETTING_KEY.PLAY_MODEL, newPlayModel)

      // ????????? ???????????? ???????????? ??????????????? ?????? ???????????? ????????? ?????? ????????? ?????? ??????
      if (fromShuffleToNone) {
        playQueue.rePosition()
      }
      playQueue.makeList()
      playQueue.updateNextSong()
      updateQueueItem()
    }

  /**
   * ?????? ????????????
   */
  private var isPlay: Boolean = false

  /**
   * ?????? ???????????? ????????? favourite ????????? ?????? ???????????? ??????
   */
  var isLove: Boolean = false

  /**
   * ?????? ?????? ????????? ??? ????????????
   */
  private var exitAfterCompletion: Boolean = false

  /**
   * ?????? ??????????????? ????????????
   */
  var mediaPlayer: MediaPlayer = MediaPlayer()

  /**
   * Desktop Widget
   */
  private val appWidgets: HashMap<String, BaseAppwidget> = HashMap()

  /**
   * AudioManager
   */
  private val audioManager: AudioManager by lazy {
    getSystemService(Context.AUDIO_SERVICE) as AudioManager
  }

  /**
   * ?????? ????????? ?????? Receiver
   */
  private val controlReceiver: ControlReceiver by lazy {
    ControlReceiver()
  }

  /**
   * Music Event ?????? Receiver
   */
  private val musicEventReceiver: MusicEventReceiver by lazy {
    MusicEventReceiver()
  }

  /**
   * ???????????? ?????? headset??? monitoring ?????? Receiver
   */
  private val headSetReceiver: HeadsetPlugReceiver by lazy {
    HeadsetPlugReceiver()
  }

  /**
   * Widget ?????? Receiver
   */
  private val widgetReceiver: WidgetReceiver by lazy {
    WidgetReceiver()
  }

  /**
   * AudioFocus Listener
   */
  private val audioFocusListener by lazy {
    AudioFocusChangeListener()
  }

  /**
   * MediaSession
   */
  lateinit var mediaSession: MediaSessionCompat
    private set

  /**
   * ?????? AudioFocus??? ???????????? ????????????
   */
  private var audioFocus = false

  /**
   * Activity ?????? ????????? ?????? Handler
   */
  private val uiHandler = PlaybackHandler(this)

  /**
   * ????????????
   */
  private val wakeLock: PowerManager.WakeLock by lazy {
    (getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, javaClass.simpleName)
  }

  /**
   * ?????????
   */
  private lateinit var notify: Notify

  /**
   * ?????? ???????????? ??????
   */
  private var control: Int = 0

  /**
   * WindowManager
   */
  private val windowManager: WindowManager by lazy {
    getSystemService(Context.WINDOW_SERVICE) as WindowManager
  }

  /**
   * Desktop ?????? ?????? ??????
   */
  private var showDesktopLyric = false
    set(value) {
      field = value
    }

  /**
   * Desktop ???????????? ??????
   */
  private var desktopLyricView: DesktopLyricView? = null

  /**
   * Service ????????????
   */
  var stop = true

  /**
   * ???????????? ?????? Receiver
   */
  private val screenReceiver: ScreenReceiver by lazy {
    ScreenReceiver()
  }
  private var screenOn = true

  /**
   * ????????????
   */
  private val volumeController: VolumeController by lazy {
    VolumeController(this)
  }

  /**
   * ??????????????? ????????? ????????????????????? ??????
   */
  private var lastProgress: Int = 0

  /**
   * ????????? ?????? ??????
   */
  private var playAtBreakPoint: Boolean = false
  private var progressTask: ProgressTask? = null

  /**
   * ????????????
   */
  var operation = -1

  /**
   * Binder
   */
  private val musicBinder = MusicBinder()

  /**
   * db
   */
  val repository = DatabaseRepository.getInstance()

  private lateinit var service: MusicService

  private var hasPermission = false // ???????????? ??????

  private var alreadyUnInit: Boolean = false
  private var speed = 1.0f // ????????????

  fun setExitAfterCompletion(value: Boolean) {
    exitAfterCompletion = value
  }

  /**
   * ??????????????? ??????
   */
  val isPlaying: Boolean
    get() = isPlay

  /**
   * ?????? ??????????????? ????????????
   */
  val progress: Int
    get() {
      try {
        if (prepared) {
          return mediaPlayer.currentPosition
        }
      } catch (e: IllegalStateException) {
        Timber.v("getProgress() %s", e.toString())
      }

      return 0
    }

  val duration: Int
    get() = if (prepared) {
      mediaPlayer.duration
    } else 0

  /**
   * Destkop ?????? ??? widget ??????
   */
  private var timer: Timer = Timer()
  private var desktopLyricTask: LyricTask? = null
  private var desktopWidgetTask: WidgetTask? = null


  private var needShowDesktopLyric: Boolean = false

  /**
   * Desktop ?????? ????????????
   */
  private var isDesktopLyricInitializing = false

  /**
   * Desktop ?????? ????????????
   */
  val isDesktopLyricShowing: Boolean
    get() = desktopLyricView != null

  /**
   * Desktop ????????? ??????????????? ????????????
   */
  val isDesktopLyricLocked: Boolean
    get() = if (desktopLyricView == null)
      SPUtil.getValue(service, SETTING_KEY.NAME, SETTING_KEY.DESKTOP_LYRIC_LOCK, false)
    else
      desktopLyricView?.isLocked == true

  var abLoopHandler = Handler()

  private val abLoop: Runnable = object : Runnable {
    override fun run() {
      if (MaterialDialogHelper.timeA > 0 && MaterialDialogHelper.timeB > 0) {
        val currPos = mediaPlayer.currentPosition
        if (currPos + 250 >= MaterialDialogHelper.timeB || currPos >= currentSong.getDuration() || currPos < MaterialDialogHelper.timeA) {
          mediaPlayer.seekTo(MaterialDialogHelper.timeA)
        }
        abLoopHandler.postDelayed(this, 100)
      }
    }
  }
  /**
   * ????????????
   */
  private var lockScreen: Int = CLOSE_LOCKSCREEN

  override fun onDestroy() {
    Timber.tag(TAG_LIFECYCLE).v("onDestroy")
    super.onDestroy()
    stop = true
    unInit()
  }

  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(LanguageHelper.setLocal(base))
  }

  override fun onCreate() {
    super.onCreate()
    Timber.tag(TAG_LIFECYCLE).v("onCreate")
    service = this
    setUp()
  }

  override fun onBind(intent: Intent): IBinder? {
    return musicBinder
  }

  inner class MusicBinder : Binder() {
    val service: MusicService
      get() = this@MusicService
  }

  @SuppressLint("CheckResult")
  override fun onStartCommand(commandIntent: Intent?, flags: Int, startId: Int): Int {
    val control = commandIntent?.getIntExtra(EXTRA_CONTROL, -1)
    val action = commandIntent?.action

    Timber.tag(TAG_LIFECYCLE).v("onStartCommand, control: $control action: $action flags: $flags startId: $startId")
    stop = false

    tryLaunch(block = {
      hasPermission = hasPermissions(EXTERNAL_STORAGE_PERMISSIONS)
      if (!loadFinished && hasPermission) {
        withContext(Dispatchers.IO) {
          load()
        }
      }
      handleStartCommandIntent(commandIntent, action)
      if (firstLoaded) {
        uiHandler.postDelayed({ sendLocalBroadcast(Intent(LOAD_FINISHED)) }, 400)
      }
      firstLoaded = false;
    })
    return START_NOT_STICKY
  }

  override fun onSharedPreferenceChanged(sp: SharedPreferences, key: String) {
    Timber.v("onSharedPreferenceChanged, key: $key")
    when (key) {
      //????????? ?????????
      SETTING_KEY.NOTIFY_SYSTEM_COLOR,
        //????????? style
      SETTING_KEY.NOTIFY_STYLE_CLASSIC -> {
        notify = NotifyImpl(this@MusicService)
        if (Notify.isNotifyShowing) {
          // ?????? ??? ?????????????????? ???????????? ????????? ?????? ??????
          notify.cancelPlayingNotify()
          updateNotification()
        }
      }
      //????????????
      SETTING_KEY.LOCKSCREEN -> {
        lockScreen = SPUtil.getValue(service, SETTING_KEY.NAME, SETTING_KEY.LOCKSCREEN, APLAYER_LOCKSCREEN)
        when (lockScreen) {
          CLOSE_LOCKSCREEN -> clearMediaSession()
          SYSTEM_LOCKSCREEN, APLAYER_LOCKSCREEN -> updateMediaSession(Command.NEXT)
        }
      }
      //????????? ??????
      SETTING_KEY.PLAY_AT_BREAKPOINT -> {
        playAtBreakPoint = SPUtil.getValue(this, SETTING_KEY.NAME, SETTING_KEY.PLAY_AT_BREAKPOINT, false)
        if (!playAtBreakPoint) {
          stopSaveProgress()
        } else {
          startSaveProgress()
        }
      }
      //????????????
      SETTING_KEY.SPEED -> {
        speed = java.lang.Float.parseFloat(SPUtil.getValue(this, SETTING_KEY.NAME, SETTING_KEY.SPEED, "1.0"))
        setSpeed(speed)
      }
    }
  }

  private fun setUp() {
    //?????? ??????
    getSharedPreferences(SETTING_KEY.NAME, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(this)

    //????????????
    wakeLock.setReferenceCounted(false)
    //?????????
    notify = NotifyImpl(this)


    //Desktop Widget
    appWidgets[APPWIDGET_BIG] = AppWidgetBig.getInstance()
    appWidgets[APPWIDGET_MEDIUM] = AppWidgetMedium.getInstance()
    appWidgets[APPWIDGET_MEDIUM_TRANSPARENT] = AppWidgetMediumTransparent.getInstance()
    appWidgets[APPWIDGET_SMALL] = AppWidgetSmall.getInstance()
    appWidgets[APPWIDGET_SMALL_TRANSPARENT] = AppWidgetSmallTransparent.getInstance()
    appWidgets[APPWIDGET_EXTRA_TRANSPARENT] = AppWidgetExtra.getInstance()

    //Receiver ?????????
    val eventFilter = IntentFilter()
    eventFilter.addAction(MEDIA_STORE_CHANGE)
    eventFilter.addAction(PERMISSION_CHANGE)
    eventFilter.addAction(PLAYLIST_CHANGE)
    eventFilter.addAction(TAG_CHANGE)
    registerLocalReceiver(musicEventReceiver, eventFilter)

    registerLocalReceiver(controlReceiver, IntentFilter(ACTION_CMD))

    val noisyFilter = IntentFilter()
    noisyFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    noisyFilter.addAction(Intent.ACTION_HEADSET_PLUG)
    registerReceiver(headSetReceiver, noisyFilter)

    registerLocalReceiver(widgetReceiver, IntentFilter(ACTION_WIDGET_UPDATE))

    val screenFilter = IntentFilter()
    screenFilter.addAction(Intent.ACTION_SCREEN_ON)
    screenFilter.addAction(Intent.ACTION_SCREEN_OFF)
    registerReceiver(screenReceiver, screenFilter)

    //MediaStore ????????????
    if (contentObserver == null) {
      contentObserver = contentResolver.registerObserver(
              MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
      ) {
        updatePlayList()
      }
    } else {
      updatePlayList()
    }

    updatePlayList()

    //SleepTimer
    SleepTimer.addCallback(object : SleepTimer.Callback {
      override fun onFinish() {
        exitAfterCompletion = true
      }
    })

    setUpPlayer()
    setUpSession()
  }

  /**
   * ???????????? ??? ??????????????? ??????
   */
  private fun updatePlayList() {
    playListJob?.cancel()
    playListJob = CoroutineScope(Dispatchers.IO).launch {
      yield()
      //mediastore??? ???????????? ?????? ???????????? playlist??? ???????????? ??????
      val deletePlayListIds = arrayListOf<Int>()
      val playlists = DatabaseRepository.getInstance().getAllPlaylists()
      for (playlist in playlists) {
        val ids = playlist.audioIds
        for (id in ids) {
          yield()
          val song = MediaStoreUtil.getSongById(id)
          if (song.id < 0) {
            deletePlayListIds.add(id)
          }
        }
        if (deletePlayListIds.size > 0) {
          DatabaseRepository.getInstance().deleteFromPlayLists(deletePlayListIds, playlist.id)
        }
      }

      //?????? ?????????????????? ???????????? ?????? ???????????? ????????? ??????????????? ??????
      var queueList = ArrayList<Int>()
      queueList = DatabaseRepository.getInstance().getPlayQueue().blockingGet() as ArrayList<Int>

      val id = SPUtil.getValue(App.getContext(), SPUtil.SETTING_KEY.NAME, SPUtil.SETTING_KEY.PLAY_QUEUE, -1)
      val type = SPUtil.getValue(App.getContext(), SPUtil.SETTING_KEY.NAME, SPUtil.SETTING_KEY.PLAY_QUEUE_TYPE, -1)

      val deleteQueueIds = arrayListOf<Int>()
      for (queue in queueList) {
        yield()
        val song = MediaStoreUtil.getSongById(queue)
        if (song.id < 0) {
          deleteQueueIds.add(queue)
        }
      }

      if (deleteQueueIds.size > 0) {
        DatabaseRepository.getInstance().deleteIdsFromQueue(deleteQueueIds)
      }

      //?????? ?????????????????? ???????????? playlist??? ???????????? ?????? ?????? ?????? ????????? ???????????? ???????????? ???????????? ??????
      if (type != PLAYLIST) {
        yield()
        var typeSongs = arrayListOf<Song>()
        when (type) {
          Constants.SONG -> typeSongs = MediaStoreUtil.getAllSong() as ArrayList<Song>
          Constants.ALBUM, Constants.ARTIST -> if (id > 0) typeSongs = MediaStoreUtil.getSongsByArtistIdOrAlbumId(id, type) as ArrayList<Song>
          Constants.FOLDER -> if (id > 0) typeSongs = MediaStoreUtil.getSongsByParentId(id) as ArrayList<Song>
        }

        val addedIds = arrayListOf<Int>()
        for (song in typeSongs) {
          if (!queueList.contains(song.id))
            addedIds.add(song.id)
        }

        yield()
        if (addedIds.size > 0) {
          DatabaseRepository.getInstance().insertIdsToQueue(addedIds)
        }
      }
    }

    playListJob?.start()
  }

  /**
   * MediaSession ?????????
   */
  private fun setUpSession() {
    val mediaButtonReceiverComponentName = ComponentName(applicationContext,
            MediaButtonReceiver::class.java)

    val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
    mediaButtonIntent.component = mediaButtonReceiverComponentName

    val pendingIntent = PendingIntent.getBroadcast(applicationContext, 0, mediaButtonIntent, 0)

    mediaSession = MediaSessionCompat(applicationContext, "APlayer", mediaButtonReceiverComponentName, pendingIntent)
    mediaSession.setCallback(object : MediaSessionCompat.Callback() {
      override fun onMediaButtonEvent(event: Intent): Boolean {
        return MediaButtonReceiver.handleMediaButtonIntent(this@MusicService, event)
      }

      override fun onSkipToNext() {
        Timber.v("onSkipToNext")
        playNext()
      }

      override fun onSkipToPrevious() {
        Timber.v("onSkipToPrevious")
        playPrevious()
      }

      override fun onPlay() {
        Timber.v("onPlay")
        play(true)
      }

      override fun onPause() {
        Timber.v("onPause")
        pause(false)
      }

      override fun onStop() {
        stopSelf()
      }

      override fun onSeekTo(pos: Long) {
        setProgress(pos.toInt())
      }
    })

    mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
    mediaSession.setMediaButtonReceiver(pendingIntent)
    mediaSession.isActive = true
  }

  /**
   * MediaPlayer ?????????
   */
  private fun setUpPlayer() {
    mediaPlayer = MediaPlayer()

    mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
    mediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)

    mediaPlayer.setOnCompletionListener { mp ->
      if(exitAfterCompletion){
        sendBroadcast(Intent(ACTION_EXIT)
                .setComponent(ComponentName(this@MusicService, ExitReceiver::class.java)))
        return@setOnCompletionListener
      }
      if (playModel == MODE_REPEAT) {
        if (isPlaying)
          prepare(playQueue.song.url)
      } else {
        if (isPlaying)
          playNextOrPrev(true)
      }
      operation = Command.NEXT
      acquireWakeLock()
    }
    mediaPlayer.setOnPreparedListener { mp ->
      Timber.v("MusicService setUpPlayer mediaplayer setOnPreparedListener is called and firstPrepared is %s and AUTO_PLAY is %s",
              firstPrepared, SPUtil.getValue(this, SETTING_KEY.NAME, SETTING_KEY.AUTO_PLAY, NEVER))

      if (firstPrepared) {
        firstPrepared = false
        if (lastProgress > 0) {
          mediaPlayer.seekTo(lastProgress)
        }
        //????????????
        if (SPUtil.getValue(this, SETTING_KEY.NAME, SETTING_KEY.AUTO_PLAY, NEVER) != OPEN_SOFTWARE) {
          return@setOnPreparedListener
        }
      }

      //????????????
      updatePlayHistory()
      //????????????
      play(false)
    }

    mediaPlayer.setOnErrorListener { mp, what, extra ->
      try {
        prepared = false
        mediaPlayer.release()
        setUpPlayer()
        ToastUtil.show(service, R.string.mediaplayer_error, what, extra)
        if (isPlaying && playQueue.song.url.isNotEmpty()) {
          prepare(playQueue.song.url)
        }
        return@setOnErrorListener true
      } catch (ignored: Exception) {

      }
      false
    }

    EQHelper.init(this, mediaPlayer.audioSessionId)
    EQHelper.open(this, mediaPlayer.audioSessionId)
  }


  /**
   * ???????????? ??????
   */
  private fun updatePlayHistory() {
    repository.updateHistory(playQueue.song)
        .compose(applySingleScheduler())
        .subscribe(LogObserver())
  }

  private fun unInit() {
    if (alreadyUnInit) {
      return
    }

    cancel()

    EQHelper.close(this, mediaPlayer.audioSessionId)
    if (isPlaying) {
      pause(false)
    }
    mediaPlayer.release()
    loadFinished = false
    prepared = false
    Controller.getController().updateContinueShortcut(this)

    timer.cancel()
    notify.cancelPlayingNotify()

    removeDesktopLyric()

    uiHandler.removeCallbacksAndMessages(null)
    showDesktopLyric = false

    audioManager.abandonAudioFocus(audioFocusListener)
    mediaSession.isActive = false
    mediaSession.release()

    unregisterLocalReceiver(controlReceiver)
    unregisterLocalReceiver(musicEventReceiver)
    unregisterLocalReceiver(widgetReceiver)
    unregisterReceiver(this, headSetReceiver)
    unregisterReceiver(this, screenReceiver)

    getSharedPreferences(SETTING_KEY.NAME, Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(this)

    releaseWakeLock()

    ShakeDetector.getInstance().stopListen()

    alreadyUnInit = true
  }

  fun updateQueueItem() {
    Timber.v("updateQueueItem")
    tryLaunch(block = {
      withContext(Dispatchers.IO) {
        val queue = ArrayList(playQueue.playingQueue)
                .map { song ->
                  return@map MediaSessionCompat.QueueItem(MediaMetadataCompat.Builder()
                          .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, song.id.toString())
                          .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                          .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                          .build().description, song.id.toLong())
                }
        Timber.v("updateQueueItem, queue: ${queue.size}")
        mediaSession.setQueueTitle(playQueue.song.title)
        mediaSession.setQueue(queue)
      }
    }, catch = {
      ToastUtil.show(this, it.toString())
      Timber.w(it)
    })
  }

  /**
   * ??????????????? ??????
   */
  fun setPlayQueue(newQueue: List<Song>?) {
    Timber.v("setPlayQueue")
    if (newQueue == null || newQueue.isEmpty()) {
      return
    }
    if (newQueue == playQueue.originalQueue) {
      return
    }

    playQueue.setPlayQueue(newQueue)
    updateQueueItem()
  }

  /**
   * ?????????????????????
   */
  fun setPlayQueue(newQueue: List<Song>?, intent: Intent) {
    //????????? ????????? ?????? randomList ??? ??????
    val shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false)
    if (newQueue == null || newQueue.isEmpty()) {
      return
    }

    //????????? ?????????????????? ?????? ???????????? ???????????? ??????
    val equals = newQueue == playQueue.originalQueue
    if (!equals) {
      playQueue.setPlayQueue(newQueue)
    }
    if (shuffle) {
      playModel = MODE_SHUFFLE
      playQueue.updateNextSong()
    }
    handleCommand(intent)

    if (equals) {
      return
    }
    updateQueueItem()
  }

  private fun setPlay(isPlay: Boolean) {
    this.isPlay = isPlay
    uiHandler.sendEmptyMessage(UPDATE_PLAY_STATE)
  }

  /**
   * ?????? ?????? ??????
   */
  override fun playNext() {
    playNextOrPrev(true)
  }

  /**
   * ?????? ?????? ??????
   */
  override fun playPrevious() {
    playNextOrPrev(false)
  }

  /**
   * ????????????
   */
  override fun play(fadeIn: Boolean) {
    Timber.v("play: $fadeIn")
    audioFocus = audioManager.requestAudioFocus(
            audioFocusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    if (!audioFocus) {
      return
    }

    setPlay(true)

    //?????? interface ??????
    uiHandler.sendEmptyMessage(UPDATE_META_DATA)

    //??????
    mediaPlayer.start()

    //????????????
    setSpeed(speed)

    if (fadeIn) {
      volumeController.fadeIn()
    } else {
      volumeController.directTo(1f)
    }

    //?????? ???????????? ????????? id ??????
    launch {
      withContext(Dispatchers.IO) {
        SPUtil.putValue(service, SETTING_KEY.NAME, SETTING_KEY.LAST_SONG_ID, playQueue.song.id)
      }
    }
  }


  /**
   * ?????? ??????????????? ?????? ?????? ?????? ?????? ?????? ??????
   */
  override fun toggle() {
    Timber.v("toggle")
    if (mediaPlayer.isPlaying) {
      pause(false)
    } else {
      if (MaterialDialogHelper.timeA > -1) {
        setProgress(MaterialDialogHelper.timeA)
      }
      play(true)
    }
  }

  /**
   * ????????????
   */
  override fun pause(updateMediasessionOnly: Boolean) {
    Timber.v("pause: $updateMediasessionOnly")
    if (updateMediasessionOnly) {
      updateMediaSession(operation)
    } else {
      if (!isPlaying) { //?????? ????????? ?????? ???????????? ????????? ?????? ???????????? ????????? ????????? ???????????? ?????????
        return
      }
      setPlay(false)
      uiHandler.sendEmptyMessage(UPDATE_META_DATA)
      volumeController.fadeOut()
    }
  }

  /**
   * ????????? ????????? ????????????
   *
   * @param position ??????????????? ????????? ??????
   */
  override fun playSelectSong(position: Int) {
    Timber.v("playSelectSong, $position")

    if (position == -1 || position >= playQueue.playingQueue.size) {
      ToastUtil.show(service, R.string.illegal_arg)
      return
    }

    playQueue.setPosition(position)

    if (playQueue.song.url.isEmpty()) {
      ToastUtil.show(service, R.string.song_lose_effect)
      return
    }
    prepare(playQueue.song.url)
    playQueue.updateNextSong()
  }

  override fun onMediaStoreChanged() {
    launch {
      val song = withContext(Dispatchers.IO) {
        MediaStoreUtil.getSongById(playQueue.song.id)
      }
      playQueue.song = song
    }
  }

  /**
   * App??? ????????? ??????????????? ???????????? ?????? Service Logic ?????? ??????
   */
  override fun onPermissionChanged(has: Boolean) {
    if (has != hasPermission && has) {
      hasPermission = true
      loadSync()
    }
  }

  override fun onTagChanged(oldSong: Song, newSong: Song) {
//    // ?????????????????????????????????
//    if (oldSong.id == currentSong.id) {
//      currentSong = newSong
//      currentId = newSong.id
//    }
  }

  /**
   * ??????????????? PlayList ????????? PlayQueue ????????? ???????????? ??????
   */
  override fun onPlayListChanged(name: String) {
    if (name == PlayQueue.TABLE_NAME) {
      repository
          .getPlayQueueSongs()
          .compose(applySingleScheduler())
          .subscribe { songs ->
            if (songs.isEmpty() || songs == playQueue.originalQueue) {
              Timber.v("Ignore onPlayListChanged")
              return@subscribe
            }
            Timber.v("New PlayQueue: ${songs.size}")

            playQueue.setPlayQueue(songs)

            // ????????? ?????????????????? RandomQueue ?????????
            if (playModel == MODE_SHUFFLE) {
              Timber.v("Reset the random queue after the play queue is changed")
              playQueue.makeList()
            }

            // ?????? ????????? ???????????? ?????? ?????? ?????? ?????? ?????????
            if (!playQueue.playingQueue.contains(playQueue.nextSong)) {
              Timber.v("Reset the next song after the play queue is changed")
              playQueue.updateNextSong()
            }
          }
    }
  }

  override fun onMetaChanged() {

  }

  override fun onPlayStateChange() {

  }

  override fun onServiceConnected(service: MusicService) {

  }

  override fun onServiceDisConnected() {

  }

  inner class WidgetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
      val name = intent.getStringExtra(BaseAppwidget.EXTRA_WIDGET_NAME)
      val appIds = intent.getIntArrayExtra(BaseAppwidget.EXTRA_WIDGET_IDS)
      Timber.v("name: $name appIds: $appIds")
      when (name) {
        APPWIDGET_BIG -> if (appWidgets[APPWIDGET_BIG] != null) {
          appWidgets[APPWIDGET_BIG]?.updateWidget(service, appIds, true)
        }
        APPWIDGET_MEDIUM -> if (appWidgets[APPWIDGET_MEDIUM] != null) {
          appWidgets[APPWIDGET_MEDIUM]?.updateWidget(service, appIds, true)
        }
        APPWIDGET_SMALL -> if (appWidgets[APPWIDGET_SMALL] != null) {
          appWidgets[APPWIDGET_SMALL]?.updateWidget(service, appIds, true)
        }
        APPWIDGET_MEDIUM_TRANSPARENT -> if (appWidgets[APPWIDGET_MEDIUM_TRANSPARENT] != null) {
          appWidgets[APPWIDGET_MEDIUM_TRANSPARENT]?.updateWidget(service, appIds, true)
        }
        APPWIDGET_SMALL_TRANSPARENT -> if (appWidgets[APPWIDGET_SMALL_TRANSPARENT] != null) {
          appWidgets[APPWIDGET_SMALL_TRANSPARENT]?.updateWidget(service, appIds, true)
        }
        APPWIDGET_EXTRA_TRANSPARENT -> if (appWidgets[APPWIDGET_EXTRA_TRANSPARENT] != null) {
          appWidgets[APPWIDGET_EXTRA_TRANSPARENT]?.updateWidget(service, appIds, true)
        }
      }
    }
  }

  inner class MusicEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
      handleMusicEvent(intent)
    }
  }

  private fun handleStartCommandIntent(commandIntent: Intent?, action: String?) {
    Timber.v("handleStartCommandIntent")
    if (action == null) {
      return
    }
    firstPrepared = false
    when (action) {
      ACTION_APPWIDGET_OPERATE -> {
        val appwidgetIntent = Intent(ACTION_CMD)
        val control = commandIntent?.getIntExtra(EXTRA_CONTROL, -1)
        if (control == UPDATE_APPWIDGET) {
          updateAppwidget()
        } else {
          appwidgetIntent.putExtra(EXTRA_CONTROL, control)
          handleCommand(appwidgetIntent)
        }
      }
      ACTION_SHORTCUT_CONTINUE_PLAY -> {
        val continueIntent = Intent(ACTION_CMD)
        continueIntent.putExtra(EXTRA_CONTROL, Command.TOGGLE)
        handleCommand(continueIntent)
      }
      ACTION_SHORTCUT_SHUFFLE -> {
        if (playModel != MODE_SHUFFLE) {
          playModel = MODE_SHUFFLE
        }
        val shuffleIntent = Intent(ACTION_CMD)
        shuffleIntent.putExtra(EXTRA_CONTROL, Command.NEXT)
        handleCommand(shuffleIntent)
      }
      ACTION_SHORTCUT_MYLOVE -> {
        tryLaunch {
          val songs = withContext(Dispatchers.IO) {
            val myLoveIds = repository.getMyLoveList().blockingGet()
            MediaStoreUtil.getSongsByIds(myLoveIds)
          }

          if (songs == null || songs.isEmpty()) {
            ToastUtil.show(service, R.string.list_is_empty)
            return@tryLaunch
          }

          val myloveIntent = Intent(ACTION_CMD)
          myloveIntent.putExtra(EXTRA_CONTROL, Command.PLAYSELECTEDSONG)
          myloveIntent.putExtra(EXTRA_POSITION, 0)
          setPlayQueue(songs, myloveIntent)
        }

      }
      ACTION_SHORTCUT_LASTADDED -> {
        tryLaunch {
          val songs = withContext(Dispatchers.IO) {
            MediaStoreUtil.getLastAddedSong()
          }
          if (songs == null || songs.size == 0) {
            ToastUtil.show(service, R.string.list_is_empty)
            return@tryLaunch
          }
          val lastedIntent = Intent(ACTION_CMD)
          lastedIntent.putExtra(EXTRA_CONTROL, Command.PLAYSELECTEDSONG)
          lastedIntent.putExtra(EXTRA_POSITION, 0)
          setPlayQueue(songs, lastedIntent)
        }

      }
      else -> if (action.equals(ACTION_CMD, ignoreCase = true)) {
        handleCommand(commandIntent)
      }
    }
  }

  private fun handleMusicEvent(intent: Intent?) {
    if (intent == null) {
      return
    }
    val action = intent.action
    when {
      MEDIA_STORE_CHANGE == action -> onMediaStoreChanged()
      PERMISSION_CHANGE == action -> onPermissionChanged(intent.getBooleanExtra(EXTRA_PERMISSION, false))
      PLAYLIST_CHANGE == action -> onPlayListChanged(intent.getStringExtra(EXTRA_PLAYLIST))
      TAG_CHANGE == action -> {
        val newSong = intent.getParcelableExtra<Song?>(BaseMusicActivity.EXTRA_NEW_SONG)
        val oldSong = intent.getParcelableExtra<Song?>(BaseMusicActivity.EXTRA_OLD_SONG)
        if (newSong != null && oldSong != null) {
          onTagChanged(oldSong, newSong)
        }
      }
    }
  }

  private fun handleMetaChange() {
    if (playQueue.song == EMPTY_SONG) {
      return
    }
    updateAppwidget()
    if (needShowDesktopLyric) {
      showDesktopLyric = true
      needShowDesktopLyric = false
    }
    updateDesktopLyric(false)
    updateNotification()
    updateMediaSession(operation)
    // ?????? ????????? ????????? ??????
    if (playAtBreakPoint) {
      startSaveProgress()
    }

    sendLocalBroadcast(Intent(META_CHANGE))
  }

  /**
   * ????????? ??????
   */
  private fun updateNotification() {
    notify.updateForPlaying()
  }

  /**
   * ??????????????? ??????????????? ?????? ??????
   */
  private fun handlePlayStateChange() {
    if (playQueue.song == EMPTY_SONG) {
      return
    }
    //Desktop ???????????? ??????
    desktopLyricView?.setPlayIcon(isPlaying)
    Controller.getController().updateContinueShortcut(this)
    sendLocalBroadcast(Intent(PLAY_STATE_CHANGE))
  }

  private var last = System.currentTimeMillis()

  /**
   * ?????????????????? Receiver
   */
  inner class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
      handleCommand(intent)
    }
  }

  /**
   * ?????????????????? Receiver ??? ????????? ??????
   */
  private fun handleCommand(intent: Intent?) {
    Timber.v("handleCommand is called: %s", intent)
    if (intent == null || intent.extras == null) {
      return
    }
    val control = intent.getIntExtra(EXTRA_CONTROL, -1)
    this@MusicService.control = control
    Timber.v("control: $control")

    if (control == Command.PLAYSELECTEDSONG || control == Command.PREV || control == Command.NEXT
        || control == Command.TOGGLE || control == Command.PAUSE || control == Command.START) {
      //???????????? ??????
      if ((control == Command.PREV || control == Command.NEXT) && System.currentTimeMillis() - last < 500) {
        return
      }
      //?????????????????? ??????
      operation = control
      if (playQueue.originalQueue.isEmpty()) {
        //?????????????????? ???????????? ?????? ?????? ??????
        launch(context = Dispatchers.IO) {
          playQueue.restoreIfNecessary()
        }
        return
      }
    }


    when (control) {
      //????????? ??????
      Command.CLOSE_NOTIFY -> {
        Notify.isNotifyShowing = false
        pause(false)
        needShowDesktopLyric = true
        showDesktopLyric = false
        uiHandler.sendEmptyMessage(REMOVE_DESKTOP_LRC)
        stopUpdateLyric()
        uiHandler.postDelayed({ notify.cancelPlayingNotify() }, 300)
      }
      //????????? ?????? ??????
      Command.PLAYSELECTEDSONG -> {
        playSelectSong(intent.getIntExtra(EXTRA_POSITION, -1))
      }
      //?????? ?????? ??????
      Command.PREV -> {
        playPrevious()
      }
      //?????? ?????? ??????
      Command.NEXT -> {
        playNext()
      }
      Command.FAST_FORWARD -> {
        if (mediaPlayer.duration > 3000) {
          mediaPlayer.seekTo(mediaPlayer.currentPosition + 3000)
        } else {
          mediaPlayer.seekTo(mediaPlayer.duration)
        }
        play(false)
      }
      Command.FAST_BACK -> {
        if (mediaPlayer.currentPosition > 3000) {
          mediaPlayer.seekTo(mediaPlayer.currentPosition - 3000)
        } else {
          mediaPlayer.seekTo(0)
        }
        play(false)
      }
      //?????? ?????? ?????? ?????? ??????
      Command.TOGGLE -> {
        toggle()
      }
      //?????? ??????
      Command.PAUSE -> {
        pause(false)
      }
      //??????
      Command.START -> {
        play(false)
      }
      //???????????? ??????
      Command.CHANGE_MODEL -> {
        playModel = if (playModel == MODE_REPEAT) MODE_LOOP else playModel + 1
      }
      //Desktop ??????
      Command.TOGGLE_DESKTOP_LYRIC -> {
        val open: Boolean = if (intent.hasExtra(EXTRA_DESKTOP_LYRIC)) {
          intent.getBooleanExtra(EXTRA_DESKTOP_LYRIC, false)
        } else {
          !SPUtil.getValue(service,
                  SETTING_KEY.NAME,
                  SETTING_KEY.DESKTOP_LYRIC_SHOW, false)
        }
        if (open && !FloatWindowManager.getInstance().checkPermission(service)) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissionIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            permissionIntent.data = Uri.parse("package:$packageName")
            permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isIntentAvailable(service, permissionIntent)) {
              startActivity(permissionIntent)
            }
          }
          ToastUtil.show(service, R.string.plz_give_float_permission)
          return
        }
        SPUtil.putValue(service, SETTING_KEY.NAME, SETTING_KEY.DESKTOP_LYRIC_SHOW, open)
        if (showDesktopLyric != open) {
          showDesktopLyric = open
          ToastUtil.show(service, if (showDesktopLyric) R.string.opened_desktop_lrc else R.string.closed_desktop_lrc)
          if (showDesktopLyric) {
            updateDesktopLyric(false)
          } else {
            closeDesktopLyric()
          }
        }
      }
      //??????????????? ?????? ??????
      Command.PLAY_TEMP -> {
        intent.getParcelableExtra<Song>(EXTRA_SONG)?.let {
          operation = Command.PLAY_TEMP
          playQueue.song = it
          prepare(playQueue.song.url)
        }
      }
      //Desktop ?????? ????????????
      Command.UNLOCK_DESKTOP_LYRIC -> {
        if (desktopLyricView != null) {
          desktopLyricView?.saveLock(false, true)
        } else {
          SPUtil.putValue(service, SETTING_KEY.NAME, SETTING_KEY.DESKTOP_LYRIC_LOCK, false)
        }
        //????????? ??????
        updateNotification()
      }
      //Desktop ?????? ??????, ????????? ??????
      Command.LOCK_DESKTOP_LYRIC -> {
        //????????? ??????
        updateNotification()
      }
      //?????? ?????? ??????
      Command.ADD_TO_NEXT_SONG -> {
        val nextSong = intent.getParcelableExtra<Song>(EXTRA_SONG) ?: return
        //?????? ???????????? ??????
        playQueue.addNextSong(nextSong)
        ToastUtil.show(service, R.string.already_add_to_next_song)
      }
      //Desktop ?????? ??????
      Command.CHANGE_LYRIC -> {
        if (showDesktopLyric) {
          updateDesktopLyric(true)
        }
      }
      //Timer ??????
      Command.TOGGLE_TIMER -> {
        val hasDefault = SPUtil.getValue(service, SETTING_KEY.NAME, SETTING_KEY.TIMER_DEFAULT, false)
        if (!hasDefault) {
          ToastUtil.show(service, getString(R.string.plz_set_default_time))
        }
        val time = SPUtil.getValue(service, SETTING_KEY.NAME, SETTING_KEY.TIMER_DURATION, -1)
        SleepTimer.toggleTimer((time * 1000).toLong())
      }
      else -> {
        Timber.v("unknown command")
      }
    }
  }

  /**
   * ??????????????? ???????????? ????????? ????????????
   */
  private fun updatePlayStateOnly(cmd: Int): Boolean {
    return cmd == Command.PAUSE || cmd == Command.START || cmd == Command.TOGGLE
  }

  /**
   * ?????? ????????? ????????? ?????? ?????????
   */
  private fun clearMediaSession() {
    mediaSession.setMetadata(MediaMetadataCompat.Builder().build())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      mediaSession.setPlaybackState(
              PlaybackStateCompat.Builder().setState(PlaybackState.STATE_NONE, 0, 1f).build())
    } else {
      mediaSession.setPlaybackState(PlaybackStateCompat.Builder().build())
    }
  }


  /**
   * ???????????? ??????
   */
  private fun updateMediaSession(control: Int) {
    val currentSong = playQueue.song
    if (currentSong == EMPTY_SONG || lockScreen == CLOSE_LOCKSCREEN) {
      return
    }

    val builder = MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentSong.id.toString())
        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.album)
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.artist)
        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, currentSong.artist)
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentSong.displayName)
        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentSong.getDuration())
        .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (playQueue.position + 1).toLong())
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.title)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      builder.putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, playQueue.size().toLong())
    }

    if (updatePlayStateOnly(control)) {
      mediaSession.setMetadata(builder.build())
    } else {
      object : RemoteUriRequest(getSearchRequestWithAlbumType(currentSong),
              RequestConfig.Builder(400, 400).build()) {
        override fun onError(throwable: Throwable) {
          setMediaSessionData(null)
        }

        override fun onSuccess(result: Bitmap?) {
          setMediaSessionData(result)
        }

        private fun setMediaSessionData(result: Bitmap?) {
          val bitmap = copy(result)
          builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
          mediaSession.setMetadata(builder.build())
        }
      }.load()
    }

    updatePlaybackState()
  }

  private fun updatePlaybackState() {
    mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setActiveQueueItemId(currentSong.id.toLong())
            .setState(if (isPlay) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, progress.toLong(), speed)
            .setActions(MEDIA_SESSION_ACTIONS).build())
  }

  /**
   * ????????????
   *
   * @param path ??????????????????
   */
  private fun prepare(path: String, requestFocus: Boolean = true) {
    tryLaunch(
            block = {
              Timber.v("????????????: %s, isPlaying: %s", path, isPlaying)
              if (TextUtils.isEmpty(path)) {
                ToastUtil.show(service, getString(R.string.path_empty))
                return@tryLaunch
              }

              val exist = withContext(Dispatchers.IO) {
                File(path).exists()
              }
              if (!exist) {
                ToastUtil.show(service, getString(R.string.file_not_exist))
                return@tryLaunch
              }
              if (requestFocus) {
                audioFocus = audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (!audioFocus) {
                  ToastUtil.show(service, getString(R.string.cant_request_audio_focus))
                  return@tryLaunch
                }
              }
              if (isPlaying) {
                pause(true)
              }

              prepared = false
              isLove = withContext(Dispatchers.IO) {
                repository.isMyLove(playQueue.song.id)
                        .onErrorReturn {
                          false
                        }
                        .blockingGet()
              }
              mediaPlayer.reset()
              withContext(Dispatchers.IO) {
                mediaPlayer.setDataSource(path)
              }
              mediaPlayer.prepareAsync()
              prepared = true
              Timber.v("prepare finish: $path")
            },
            catch = {
              ToastUtil.show(service, getString(R.string.play_failed) + it.toString())
              prepared = false
            })
  }

  /**
   * ?????? ??????????????? ?????? ?????? ?????? ?????? ????????? ??????
   *
   * @param isNext ?????? ?????? ?????? ??????
   */
  fun playNextOrPrev(isNext: Boolean) {
    if (playQueue.size() == 0) {
      ToastUtil.show(service, getString(R.string.list_is_empty))
      return
    }
    Timber.v("play next song")
    if (isNext) {
      playQueue.next()
    } else {
      playQueue.previous()
    }

    if (playQueue.song == EMPTY_SONG) {
      ToastUtil.show(service, R.string.song_lose_effect)
      return
    }
    setPlay(true)
    prepare(playQueue.song.url)
  }

  /**
   * MediaPlayer ?????? ????????? ??????
   */
  fun setProgress(current: Int) {
    if (prepared) {
      mediaPlayer.seekTo(current)
      updatePlaybackState()
    }
  }

  /**
   * ??????????????????
   */
  fun handleLoop() {
    abLoopHandler.removeCallbacks(abLoop)
    abLoopHandler.postDelayed(abLoop, 100)
  }

  /**
   * ??????????????????
   */
  private fun setSpeed(speed: Float) {
    if (prepared && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mediaPlayer.isPlaying) {
      try {
        mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(speed)
      } catch (e: Exception) {
        Timber.w(e)
      }
    }
  }

  /**
   * ?????? Id ?????? ??? ??????????????? ??????
   */
  private fun loadSync() {
    launch(context = Dispatchers.IO) {
      load()
    }
  }

  /**
   * ????????????
   */
  @WorkerThread
  @Synchronized
  private fun load() {
    val isFirst = SPUtil.getValue(this, SETTING_KEY.NAME, SETTING_KEY.FIRST_LOAD, true)
    SPUtil.putValue(this, SETTING_KEY.NAME, SETTING_KEY.FIRST_LOAD, false)
    //???????????? App ??????
    if (isFirst) {
      //?????????????????? playList table??? ??????
      repository.insertPlayList(getString(R.string.db_my_favorite)).subscribe(LogObserver())

      //????????? style
      SPUtil.putValue(this, SETTING_KEY.NAME, SETTING_KEY.NOTIFY_STYLE_CLASSIC,
              Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
    }

    //??????
    if (SPUtil.getValue(this, SETTING_KEY.NAME, SETTING_KEY.SHAKE, false)) {
      ShakeDetector.getInstance().beginListen()
    }

    //???????????????
    lockScreen = SPUtil.getValue(service, SETTING_KEY.NAME, SETTING_KEY.LOCKSCREEN, APLAYER_LOCKSCREEN)
    playModel = SPUtil.getValue(this, SETTING_KEY.NAME, SETTING_KEY.PLAY_MODEL, MODE_LOOP)
    showDesktopLyric = SPUtil.getValue(this, SETTING_KEY.NAME, SETTING_KEY.DESKTOP_LYRIC_SHOW, false)
    speed = java.lang.Float.parseFloat(SPUtil.getValue(this, SETTING_KEY.NAME, SETTING_KEY.SPEED, "1.0"))
    playAtBreakPoint = SPUtil.getValue(service, SETTING_KEY.NAME, SETTING_KEY.PLAY_AT_BREAKPOINT, false)
    lastProgress = SPUtil.getValue(service, SETTING_KEY.NAME, SETTING_KEY.LAST_PLAY_PROGRESS, 0)

    //??????????????????
    val fromOther = SPUtil.getValue(service, SETTING_KEY.NAME, SETTING_KEY.PLAY_FROM_OTHER_APP, false)
    if (!fromOther) {
      playQueue.restoreIfNecessary()
      prepare(playQueue.song.url)
    } else {
      SPUtil.putValue(service, SETTING_KEY.NAME, SETTING_KEY.PLAY_FROM_OTHER_APP, false)
      firstPrepared = false
    }
    loadFinished = true

    uiHandler.postDelayed({ sendLocalBroadcast(Intent(META_CHANGE)) }, 400)
  }

  fun deleteSongFromService(deleteSongs: List<Song>?) {
    if (deleteSongs != null && deleteSongs.isNotEmpty()) {
      playQueue.removeAll(deleteSongs)
    }
  }

  /**
   * ??????????????????
   */
  private fun releaseWakeLock() {
    if (wakeLock.isHeld) {
      wakeLock.release()
    }
  }

  /**
   * ???????????? ??????
   */
  private fun acquireWakeLock() {
    wakeLock.acquire(if (playQueue.song != EMPTY_SONG) playQueue.song.getDuration() else 30000L)
  }


  /**
   * Desktop ?????? ??????
   */
  private fun updateDesktopLyric(force: Boolean) {
    Timber.v("updateDesktopLyric, showDesktopLyric: $showDesktopLyric")
    if (!showDesktopLyric) {
      return
    }
    if (checkNoPermission()) { //????????? ????????? ??????
      return
    }
    if (!isPlaying) {
      stopUpdateLyric()
    } else {
      //????????? ???????????? ??????
      if (screenOn) {
        //?????? ??????
        desktopLyricTask?.force = force
        startUpdateLyric()
      }
    }
  }

  /**
   * Floating Window ?????? ??????, ?????? ?????? ?????? ?????? ??????
   */
  private fun checkNoPermission(): Boolean {
    try {
      if (!FloatWindowManager.getInstance().checkPermission(service)) {
        closeDesktopLyric()
        return true
      }
      return false
    } catch (e: Exception) {
      Timber.v(e)
    }

    return true
  }

  /**
   * Desktop Widget ??????
   */
  private fun updateAppwidget() {
    //????????? ????????? ??? ?????? ????????? ??????????????? ????????????
    if (!isPlaying) {
      //?????? ????????? ?????? ???????????? ??????
      //???????????? Desktop Widget ????????? ?????? ??? ??????????????????????????? ???????????? ??????????????? ?????????????????? ?????? ???????????? ??????
      desktopWidgetTask?.run()
      stopUpdateAppWidget()
    } else {
      if (screenOn) {
        appWidgets.forEach {
          it.value.updateWidget(this, null, true)
        }
        //??????????????? ????????? ????????? ??? ?????? ??????
        startUpdateAppWidget()
      }
    }
  }

  /**
   * Desktop Widget ????????????
   */
  private fun stopUpdateAppWidget() {
    desktopWidgetTask?.cancel()
    desktopWidgetTask = null
    appWidgets.forEach {
      it.value.updateWidget(this, null, true)
    }
  }

  /**
   * ??????????????? ????????? ????????? ??? ?????? ??????
   */
  private fun startUpdateAppWidget() {
    if (desktopWidgetTask != null) {
      return
    }
    desktopWidgetTask = WidgetTask()
    timer.schedule(desktopWidgetTask, INTERVAL_APPWIDGET, INTERVAL_APPWIDGET)
  }


  /**
   * ?????? ??????
   */
  private fun startUpdateLyric() {
    if (desktopLyricTask != null) {
      return
    }
    desktopLyricTask = LyricTask()
    timer.schedule(desktopLyricTask, LYRIC_FIND_INTERVAL, LYRIC_FIND_INTERVAL)
  }

  /**
   * ?????? ????????????
   */
  private fun stopUpdateLyric() {
    desktopLyricTask?.cancel()
    desktopLyricTask = null
  }

  private inner class WidgetTask : TimerTask() {
    private val tag: String = WidgetTask::class.java.simpleName

    override fun run() {
      val isAppOnForeground = isAppOnForeground()
      // App ??? Foreground ????????? ?????? ????????? ????????? ??????
      if (!isAppOnForeground) {
        appWidgets.forEach {
          uiHandler.post {
            it.value.partiallyUpdateWidget(service)
          }
        }
      }
    }

    override fun cancel(): Boolean {
      return super.cancel()
    }
  }

  fun setLyricOffset(offset: Int) {
    desktopLyricTask?.lyricFetcher?.offset = offset
  }

  private inner class LyricTask : TimerTask() {
    private var songInLyricTask = EMPTY_SONG
    val lyricFetcher = LyricFetcher(this@MusicService)
    var force = false

    override fun run() {
      if (!showDesktopLyric) {
        uiHandler.sendEmptyMessage(REMOVE_DESKTOP_LRC)
        return
      }
      if (songInLyricTask != playQueue.song) {
        songInLyricTask = playQueue.song
        lyricFetcher.updateLyricRows(songInLyricTask)
        return
      }
      if (force) {
        force = false
        lyricFetcher.updateLyricRows(songInLyricTask)
        return
      }

      if (checkNoPermission()) {
        return
      }
      if (stop) {
        uiHandler.sendEmptyMessage(REMOVE_DESKTOP_LRC)
        return
      }
      //?????? App ??? Foreground ????????? ??????
      if (isAppOnForeground()) {
        if (isDesktopLyricShowing) {
          uiHandler.sendEmptyMessage(REMOVE_DESKTOP_LRC)
        }
      } else {
        if (!isDesktopLyricShowing) {
          uiHandler.removeMessages(CREATE_DESKTOP_LRC)
          uiHandler.sendEmptyMessageDelayed(CREATE_DESKTOP_LRC, 50)
        } else {
          uiHandler.obtainMessage(UPDATE_DESKTOP_LRC_CONTENT, lyricFetcher.findCurrentLyric()).sendToTarget()
        }
      }
    }

    override fun cancel(): Boolean {
      lyricFetcher.dispose()
      return super.cancel()
    }

    fun cancelByNotification() {
      needShowDesktopLyric = true
      showDesktopLyric = false
      uiHandler.sendEmptyMessage(REMOVE_DESKTOP_LRC)
      cancel()
    }
  }

  /**
   * Desktop ?????? ??????
   */
  private fun createDesktopLyric() {
    if (checkNoPermission()) {
      return
    }
    if (isDesktopLyricInitializing) {
      return
    }
    isDesktopLyricInitializing = true

    val param = WindowManager.LayoutParams()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      param.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      param.type = WindowManager.LayoutParams.TYPE_PHONE
    }

    param.format = PixelFormat.RGBA_8888
    param.gravity = Gravity.TOP
    param.width = resources.displayMetrics.widthPixels
    param.height = ViewGroup.LayoutParams.WRAP_CONTENT
    param.x = 0
    param.y = SPUtil.getValue(this, SETTING_KEY.NAME, SETTING_KEY.DESKTOP_LYRIC_Y, 0)

    if (desktopLyricView != null) {
      windowManager.removeView(desktopLyricView)
      desktopLyricView = null
    }

    desktopLyricView = DesktopLyricView(service)
    windowManager.addView(desktopLyricView, param)
    isDesktopLyricInitializing = false
  }

  /**
   * Desktop ?????? ??????
   */
  private fun removeDesktopLyric() {
    if (desktopLyricView != null) {
      //      desktopLyricView.cancelNotify();
      windowManager.removeView(desktopLyricView)
      desktopLyricView = null
    }
  }

  /**
   * Desktop ?????? ??????
   */
  private fun closeDesktopLyric() {
    SPUtil.putValue(this, SETTING_KEY.NAME, SETTING_KEY.DESKTOP_LYRIC_SHOW, false)
    showDesktopLyric = false
    stopUpdateLyric()
    uiHandler.removeMessages(CREATE_DESKTOP_LRC)
    uiHandler.sendEmptyMessage(REMOVE_DESKTOP_LRC)
  }

  /**
   * ?????? ???????????? ??????
   */
  private fun startSaveProgress() {
    if (progressTask != null) {
      return
    }
    progressTask = ProgressTask()
    timer.schedule(progressTask, 100, LYRIC_FIND_INTERVAL)
  }

  /**
   * ?????? ???????????? ????????????
   */
  private fun stopSaveProgress() {
    SPUtil.putValue(service, SETTING_KEY.NAME, SETTING_KEY.LAST_PLAY_PROGRESS, 0)
    progressTask?.cancel()
    progressTask = null
  }


  /**
   * ?????? ?????? ???????????? ??????
   */
  private inner class ProgressTask : TimerTask() {
    override fun run() {
      val progress = progress
      if (progress > 0) {
        SPUtil.putValue(service, SETTING_KEY.NAME, SETTING_KEY.LAST_PLAY_PROGRESS, progress)
      }
    }

  }


  private inner class AudioFocusChangeListener : AudioManager.OnAudioFocusChangeListener {

    //Focus ????????? ?????????????????? ?????? ??????????????? ??????;
    private var needContinue = false

    override fun onAudioFocusChange(focusChange: Int) {
      when (focusChange) {
        //AudioFocus ??????
        AudioManager.AUDIOFOCUS_GAIN -> {
          audioFocus = true
          if (!prepared) {
            setUpPlayer()
          } else if (needContinue) {
            play(true)
            needContinue = false
            operation = Command.TOGGLE
          }
          volumeController.directTo(1f)
        }
        //?????? ??????
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
          needContinue = isPlay
          if (isPlay && prepared) {
            operation = Command.TOGGLE
            pause(false)
          }
        }
        //?????? ?????????
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
          volumeController.directTo(.1f)
        }
        //????????????
        AudioManager.AUDIOFOCUS_LOSS -> {
          val ignoreFocus = SPUtil.getValue(this@MusicService, SETTING_KEY.NAME, SETTING_KEY.AUDIO_FOCUS, false)
          if (ignoreFocus) {
            Timber.v("Ignore AudioFocus without pause")
            return
          }
          audioFocus = false
          if (isPlay && prepared) {
            operation = Command.TOGGLE
            pause(false)
          }
        }
      }
    }
  }

  /**
   * Activity ?????? ????????? ?????? Handler
   */
  private class PlaybackHandler internal constructor(
          service: MusicService,
          private val ref: WeakReference<MusicService> = WeakReference(service))
    : Handler() {

    override fun handleMessage(msg: Message) {
      if (ref.get() == null) {
        return
      }
      val musicService = ref.get() ?: return
      when (msg.what) {
        UPDATE_PLAY_STATE -> musicService.handlePlayStateChange()
        UPDATE_META_DATA -> {
          musicService.handleMetaChange()
        }
        UPDATE_DESKTOP_LRC_CONTENT -> {
          if (msg.obj is LyricRowWrapper) {
            val wrapper = msg.obj as LyricRowWrapper
            musicService.desktopLyricView?.setText(wrapper.lineOne, wrapper.lineTwo)
          }
        }
        REMOVE_DESKTOP_LRC -> {
          musicService.removeDesktopLyric()
        }
        CREATE_DESKTOP_LRC -> {
          musicService.createDesktopLyric()
        }
      }
    }
  }

  /**
   * ???????????? ?????? Receiver
   */
  private inner class ScreenReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
      val action = intent.action
      Timber.tag("ScreenReceiver").v(action)
      if (Intent.ACTION_SCREEN_ON == action) {
        screenOn = true
        //Desktop ?????? ??? ??????
        updateDesktopLyric(false)
        //App Widget ?????? ????????????
        updateAppwidget()
      } else {
        screenOn = false
        //Desktop Widget ?????? ??????
        stopUpdateAppWidget()
        //Desktop ?????? ??????
        stopUpdateLyric()
      }
    }
  }

  companion object {
    const val TAG_DESKTOP_LYRIC = "LyricTask"
    const val TAG_LIFECYCLE = "ServiceLifeCycle"
    const val EXTRA_DESKTOP_LYRIC = "DesktopLyric"
    const val EXTRA_SONG = "Song"
    const val EXTRA_POSITION = "Position"

    //Desktop Widget ??????
    const val UPDATE_APPWIDGET = 1000
    //?????? ???????????? ?????? ??????
    const val UPDATE_META_DATA = 1002
    //?????? ?????? ??????
    const val UPDATE_PLAY_STATE = 1003
    //Desktop ?????? ?????? ??????
    const val UPDATE_DESKTOP_LRC_CONTENT = 1004
    //Desktop ?????? ??????
    const val REMOVE_DESKTOP_LRC = 1005
    //Desktop ?????? ??????
    const val CREATE_DESKTOP_LRC = 1006

    private const val APLAYER_PACKAGE_NAME = "com.kr.myplayer"
    //Media ???????????? ??????
    const val MEDIA_STORE_CHANGE = "$APLAYER_PACKAGE_NAME.media_store.change"
    //?????? ??? ?????? ?????? ??????
    const val PERMISSION_CHANGE = "$APLAYER_PACKAGE_NAME.permission.change"
    //???????????? ??????
    const val PLAYLIST_CHANGE = "$APLAYER_PACKAGE_NAME.playlist.change"
    //???????????? ??????
    const val META_CHANGE = "$APLAYER_PACKAGE_NAME.meta.change"
    const val LOAD_FINISHED = "$APLAYER_PACKAGE_NAME.load.finished"
    //???????????? ??????
    const val PLAY_STATE_CHANGE = "$APLAYER_PACKAGE_NAME.play_state.change"
    //?????? tag ??????
    const val TAG_CHANGE = "$APLAYER_PACKAGE_NAME.tag_change"

    const val EXTRA_CONTROL = "Control"
    const val EXTRA_SHUFFLE = "shuffle"
    const val ACTION_APPWIDGET_OPERATE = "$APLAYER_PACKAGE_NAME.appwidget.operate"
    const val ACTION_SHORTCUT_SHUFFLE = "$APLAYER_PACKAGE_NAME.shortcut.shuffle"
    const val ACTION_SHORTCUT_MYLOVE = "$APLAYER_PACKAGE_NAME.shortcut.my_love"
    const val ACTION_SHORTCUT_LASTADDED = "$APLAYER_PACKAGE_NAME.shortcut.last_added"
    const val ACTION_SHORTCUT_CONTINUE_PLAY = "$APLAYER_PACKAGE_NAME.shortcut.continue_play"
    const val ACTION_LOAD_FINISH = "$APLAYER_PACKAGE_NAME.load.finish"
    const val ACTION_CMD = "$APLAYER_PACKAGE_NAME.cmd"
    const val ACTION_WIDGET_UPDATE = "$APLAYER_PACKAGE_NAME.widget_update"
    const val ACTION_TOGGLE_TIMER = "$APLAYER_PACKAGE_NAME.toggle_timer"

    private const val MEDIA_SESSION_ACTIONS = (PlaybackStateCompat.ACTION_PLAY
        or PlaybackStateCompat.ACTION_PAUSE
        or PlaybackStateCompat.ACTION_PLAY_PAUSE
        or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        or PlaybackStateCompat.ACTION_STOP
        or PlaybackStateCompat.ACTION_SEEK_TO)

    private const val APPWIDGET_BIG = "AppWidgetBig"
    private const val APPWIDGET_MEDIUM = "AppWidgetMedium"
    private const val APPWIDGET_SMALL = "AppWidgetSmall"
    private const val APPWIDGET_MEDIUM_TRANSPARENT = "AppWidgetMediumTransparent"
    private const val APPWIDGET_SMALL_TRANSPARENT = "AppWidgetSmallTransparent"
    private const val APPWIDGET_EXTRA_TRANSPARENT = "AppWidgetExtra"

    private const val INTERVAL_APPWIDGET = 1000L


    /**
     * Bitmap ??????
     */
    @JvmStatic
    fun copy(bitmap: Bitmap?): Bitmap? {
      if (bitmap == null || bitmap.isRecycled) {
        return null
      }
      var config: Bitmap.Config? = bitmap.config
      if (config == null) {
        config = Bitmap.Config.RGB_565
      }
      return try {
        bitmap.copy(config, false)
      } catch (e: OutOfMemoryError) {
        e.printStackTrace()
        null
      }

    }
  }
}

