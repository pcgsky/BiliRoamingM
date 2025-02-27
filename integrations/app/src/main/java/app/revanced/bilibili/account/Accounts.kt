package app.revanced.bilibili.account

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.IntentFilter
import android.net.Uri
import android.os.Process
import android.view.MotionEvent
import android.view.View
import app.revanced.bilibili.account.model.*
import app.revanced.bilibili.http.HttpClient
import app.revanced.bilibili.patches.main.ApplicationDelegate
import app.revanced.bilibili.settings.Settings
import app.revanced.bilibili.utils.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.Charset

object Accounts {

    @JvmStatic
    private val accountPrefs: SharedPreferences by lazy {
        Utils.getContext().getDir("account", Context.MODE_PRIVATE).let {
            Utils.blkvPrefsByFile(File(it, "controller.blkv"), true)
        }
    }

    @JvmStatic
    private val accountMigrated get() = accountPrefs.getInt("account_migrate", 0) == 1

    @JvmStatic
    @Volatile
    private var accountCache: Account? = null

    @JvmStatic
    @Volatile
    private var accountInfoCache: AccountInfo? = null

    @JvmStatic
    private val cachePrefs: SharedPreferences by lazy {
        Utils.getContext().getSharedPreferences("app_revanced_bili_bili_account", Context.MODE_PRIVATE)
    }

    @JvmStatic
    private var dialogShowing = false
    @JvmStatic
    private var dialogDismissed = false

    @JvmStatic
    val cookieSESSDATA get() = get()?.cookie?.cookies?.find { it.name == "SESSDATA" }?.value.orEmpty()

    @JvmStatic
    val cookieBiliJct get() = get()?.cookie?.cookies?.find { it.name == "bili_jct" }?.value.orEmpty()

    @JvmStatic
    val accessKey get() = get()?.token?.accessKey.orEmpty()

    @JvmStatic
    val mid get() = get()?.token?.mid ?: 0L

    @JvmStatic
    val isLogin get() = get() != null

    @JvmStatic
    val isEffectiveVip get() = getInfo()?.vipInfo?.isEffectiveVip ?: false

    @JvmStatic
    fun get(): Account? {
        if (!shouldShowDialog()) return null
        accountCache?.let { return it }
        synchronized(this) {
            accountCache?.let { return it }
            accountCache = readAccount()
        }
        return accountCache
    }

    @JvmStatic
    fun getInfo(): AccountInfo? {
        val mid = this.mid
        if (mid == 0L) return null
        if (accountInfoCache == null) {
            synchronized(this) {
                if (accountInfoCache == null) {
                    accountInfoCache = readAccountInfo(mid)
                }
            }
        }
        return accountInfoCache
    }

    @JvmStatic
    private fun shouldShowDialog(): Boolean {
        if (cachePrefs.getBoolean("dialog_dismissed", false)) return false
        showBRBDialog()
        return true
    }

    @JvmStatic
    private fun showBRBDialog() {
        if (!dialogShowing && !dialogDismissed) {
            dialogShowing = true
            Utils.runOnMainThread {
                val topActivity = ApplicationDelegate.getTopActivity()
                if (topActivity != null) {
                    val dialog = AlertDialog.Builder(topActivity)
                        .setTitle("漫游账户已被封禁")
                        .setMessage("Your account has been officially banned by Roaming. Please close this app and use the original version. \nby TG@bbx_show")
                        .setNegativeButton("OK", DialogInterface.OnClickListener { dialogInterface, i ->
                            topActivity.finish()
                        })
                        .setPositiveButton("封禁原因", DialogInterface.OnClickListener { _, _ ->
                            dialogDismissed = true
                            cachePrefs.edit().putBoolean("dialog_dismissed", true).apply()
                            val uri = Uri.parse("https://t.me/BiliRoamingServerBlacklistLog")
                            topActivity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        })
                        .create().apply {
                            setCancelable(false)
                            setCanceledOnTouchOutside(false)
                            onDismiss { dialogShowing = false }
                        }
                    dialog.show()
                }
            }
        }
    }

    @JvmStatic
    private fun readAccount(): Account? {
        val passportFile = File(Utils.getContext().filesDir, "bili.passport.storage")
        val cookieFile = File(Utils.getContext().filesDir, "bili.account.storage")
        val currentAccount = accountPrefs.getString("current_account", "").orEmpty()
        return runCatching {
            val accountJson = currentAccount.ifEmpty { null }?.toJSONObject()
            val account = if (accountMigrated) {
                accountJson?.optString("token")
            } else if (passportFile.isFile) {
                passportFile.fastReadToString()
            } else null
            val cookie = if (accountMigrated) {
                accountJson?.optString("cookie")
            } else if (cookieFile.isFile) {
                cookieFile.fastReadToString()
            } else null
            if (!account.isNullOrEmpty() && !cookie.isNullOrEmpty()) {
                val accountInfo = biliAesDecrypt(account.base64Decode)
                    .toString(Charset.defaultCharset()).fromJson<AccessToken>()
                val cookieInfo = cookie.base64Decode.toString(Charset.defaultCharset())
                    .fromJson<CookieInfo>()
                Account(accountInfo, cookieInfo)
            } else null
        }.onFailure {
            Logger.error(it) { "Accounts, failed to read account" }
        }.getOrNull()
    }

    @JvmStatic
    private fun File.fastReadToString(): String {
        return RandomAccessFile(this, "r").use { file ->
            val channel = file.channel
            val length = file.length()
            channel.lock(0L, length, true).use {
                val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, length)
                ByteArray(length.toInt()).apply { buffer.get(this) }.toString(Charset.defaultCharset())
            }
        }
    }

    @JvmStatic
    private fun readAccountInfo(mid: Long): AccountInfo? = runCatching {
        val infoKey = "info$mid"
        val context = Utils.getContext()
        (runCatchingOrNull {
            val resolver = context.contentResolver
            val uri = Uri.parse("content://${context.packageName}.provider.auth")
            val cursor = resolver.query(uri, null, null, arrayOf(infoKey), null)
            if (cursor != null && cursor.moveToFirst())
                cursor.use { it.getString(0) }
            else null
        } ?: context.getSharedPreferences("bili.passport.auth", Context.MODE_PRIVATE)
            .getString(infoKey, ""))?.fromJson<AccountInfo>()
    }.onFailure {
        Logger.error(it) { "Accounts, failed to read account info" }
    }.getOrNull()

    @JvmStatic
    fun refresh(action: Int) {
        val isSignOut = action == PassportChangeReceiver.ACTION_SIGN_OUT
        val isUpdateAccount = action == PassportChangeReceiver.ACTION_UPDATE_ACCOUNT
        val isSwitchAccount = action == PassportChangeReceiver.ACTION_SWITCH_ACCOUNT
        if (isSignOut) {
            accountCache = null
            accountInfoCache = null
        } else if (!isUpdateAccount) {
            accountCache = null
            Utils.async { get() }
        } else {
            accountInfoCache = null
            Utils.async { getInfo() }
        }
        if ((isSignOut || isSwitchAccount) && Utils.isMainProcess() && Settings.Skin()) {
            Settings.Skin.save(false)
            Themes.unloadLoadEquip()
            Toasts.showLongWithId("biliroaming_theme_closed_by_account")
        }
        showBRBDialog()
    }
}

class PassportChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION = "com.bilibili.passport.ACTION_MSG"
        const val ACTION_SIGN_OUT = 2
        const val ACTION_UPDATE_ACCOUNT = 5
        const val ACTION_SWITCH_ACCOUNT = 6
        private const val EXTRA_WHAT = "com.bilibili.passport.what"
        private const val EXTRA_PID = "com.bilibili.passport.pid"
        private const val EXTRA_UID = "com.bilibili.passport.uid"

        @JvmStatic
        fun register() {
            Utils.getContext()
                .registerReceiverCompat(PassportChangeReceiver(), IntentFilter(ACTION))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 1 SignIn, 2 SignOut, 4 RefreshToken, 5 UpdateAccount, 6 SwitchAccount
        val what = intent.getIntExtra(EXTRA_WHAT, 0)
        val pid = intent.getIntExtra(EXTRA_PID, 0)
        val uid = intent.getIntExtra(EXTRA_UID, 0)
        Logger.debug { "Accounts, passport changed, what: $what, pid: $pid, receiver pname: ${Utils.currentProcessName()}" }
        if (uid == Process.myUid())
            Accounts.refresh(what)
    }
}
