package me.hufman.androidautoidrive

import android.app.Notification
import android.app.Notification.PRIORITY_LOW
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import me.hufman.androidautoidrive.carapp.notifications.CarNotificationControllerIntent
import me.hufman.androidautoidrive.carapp.notifications.NotificationListenerServiceImpl
import me.hufman.androidautoidrive.carapp.notifications.PhoneNotifications
import me.hufman.idriveconnectionkit.android.IDriveConnectionListener
import me.hufman.idriveconnectionkit.android.SecurityService
import java.util.*

class MainService: Service() {
	companion object {
		const val TAG = "MainService"

		const val ACTION_START = "me.hufman.androidautoidrive.MainService.start"
		const val ACTION_STOP = "me.hufman.androidautoidrive.MainService.stop"
	}
	val ONGOING_NOTIFICATION_ID = 20503
	var foregroundNotification: Notification? = null

	var threadNotifications: CarThread? = null
	var carappNotifications: PhoneNotifications? = null

	var mapService = MapService(this)

	var musicService = MusicService(this)

	override fun onBind(intent: Intent?): IBinder? {
		return null
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val action = intent?.action ?: ""
		if (action == ACTION_START) {
			handleActionStart()
		} else if (action == ACTION_STOP) {
			handleActionStop()
		}
		return Service.START_STICKY
	}

	override fun onDestroy() {
		handleActionStop()
		super.onDestroy()
	}

	/**
	 * Start the service
	 */
	private fun handleActionStart() {
		Log.i(TAG, "Starting up service")
		SecurityService.connect(this)
		SecurityService.subscribe(Runnable {
			combinedCallback()
		})
		IDriveConnectionListener.callback = Runnable {
			combinedCallback()
		}
	}

	private fun startServiceNotification(brand: String?) {
		Log.i(TAG, "Creating foreground notification")
		val notifyIntent = Intent(this, MainActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
		}
		val foregroundNotificationBuilder = Notification.Builder(this)
				.setOngoing(true)
				.setContentTitle(getText(R.string.notification_title))
				.setContentText(getText(R.string.notification_description))
				.setSmallIcon(android.R.drawable.ic_menu_gallery)
				.setPriority(PRIORITY_LOW)
				.setContentIntent(PendingIntent.getActivity(this, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT))
		if (brand?.toLowerCase() == "bmw") foregroundNotificationBuilder.setContentText(getText(R.string.notification_description_bmw))
		if (brand?.toLowerCase() == "mini") foregroundNotificationBuilder.setContentText(getText(R.string.notification_description_mini))
		foregroundNotification = foregroundNotificationBuilder.build()
		startForeground(ONGOING_NOTIFICATION_ID, foregroundNotification)
	}

	fun combinedCallback() {
		synchronized(MainService::class.java) {
			if (IDriveConnectionListener.isConnected && SecurityService.isConnected()) {
				var startAny = false

				AppSettings.loadSettings(this)
				L.loadResources(this)

				// start notifications
				startAny = startAny or startNotifications()

				// start maps
				startAny = startAny or startMaps()

				// start music
				startAny = startAny or startMusic()

				// check if we are idle and should shut down
				if (startAny ){
					startServiceNotification(IDriveConnectionListener.brand)
				} else {
					Log.i(TAG, "No apps are enabled, skipping the service start")
					stopServiceNotification()
					stopSelf()
				}
			} else {
				Log.d(TAG, "Not fully connected: IDrive:${IDriveConnectionListener.isConnected} SecurityService:${SecurityService.isConnected()}")
			}
		}
	}

	fun startNotifications(): Boolean {
		if (AppSettings[AppSettings.KEYS.ENABLED_NOTIFICATIONS].toBoolean() &&
				Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true) {
			synchronized(this) {
				if (threadNotifications == null) {
					threadNotifications = CarThread("Notifications") {
						Log.i(TAG, "Starting notifications app")
						carappNotifications = PhoneNotifications(CarAppAssetManager(this, "basecoreOnlineServices"),
								PhoneAppResourcesAndroid(this),
								CarNotificationControllerIntent(this))
						carappNotifications?.onCreate(this, threadNotifications?.handler)
						// request an initial draw
						sendBroadcast(Intent(NotificationListenerServiceImpl.INTENT_REQUEST_DATA))
					}
					threadNotifications?.start()
				}
			}
			return true
		} else {    // we should not run the service
			if (threadNotifications != null) {
				Log.i(TAG, "Notifications app needs to be shut down...")
				stopNotifications()
			}
			return false
		}
	}

	fun stopNotifications() {
		carappNotifications?.onDestroy(this)
		carappNotifications = null
		threadNotifications?.handler?.looper?.quit()
	}

	fun startMaps(): Boolean {
		return mapService.start()
	}

	fun stopMaps() {
		mapService.stop()
	}

	fun startMusic(): Boolean {
		return musicService.start()
	}
	fun stopMusic() {
		musicService.stop()
	}

	/**
	 * Stop the service
	 */
	private fun handleActionStop() {
		Log.i(TAG, "Shutting down service")
		synchronized(MainService::class.java) {
			stopNotifications()
			stopMaps()
			stopMusic()
			stopServiceNotification()
			SecurityService.listener = Runnable {}
			SecurityService.disconnect()
		}
	}

	private fun stopServiceNotification() {
		Log.i(TAG, "Hiding foreground notification")
		stopForeground(true)
	}
}