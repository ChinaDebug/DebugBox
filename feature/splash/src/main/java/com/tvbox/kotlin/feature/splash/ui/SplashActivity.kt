package com.tvbox.kotlin.feature.splash.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.BounceInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import com.tvbox.kotlin.core.common.util.DeviceUtils
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.core.ui.BaseBindingActivity
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.feature.splash.databinding.ActivitySplashBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 启动页：播放入场动画后请求存储权限，1.2s 后跳转首页。
 * 启动动画期间禁止返回，权限永久拒绝时引导用户前往系统设置开启。
 */
@AndroidEntryPoint
class SplashActivity : BaseBindingActivity<ActivitySplashBinding>() {

    @Inject
    lateinit var navigator: AppNavigator

    override val layoutId = com.tvbox.kotlin.feature.splash.R.layout.activity_splash

    private val handler = Handler(Looper.getMainLooper())
    private var permissionRequested = false
    private var splashStarted = false
    private var permissionRetryCount = 0
    private var isRequestingPermission = false

    private val splashRunnable = Runnable { navigateToHome() }

    override fun initView(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 启动动画期间禁止返回
            }
        })

        binding.splashLogo.alpha = 1f
        binding.splashLogo.scaleX = 1f
        binding.splashLogo.scaleY = 1f
        binding.splashLogo.translationY = -500f

        ObjectAnimator.ofFloat(binding.splashLogo, "translationY", -500f, 0f).apply {
            duration = 800
            interpolator = BounceInterpolator()
            start()
        }

        startTextShatterAnimation(binding.textContainer)

        requestStoragePermission()
    }

    override fun onResume() {
        super.onResume()
        if (permissionRequested && !splashStarted && !isRequestingPermission) {
            if (hasStoragePermission()) {
                startSplashDelay()
            } else {
                requestStoragePermission()
            }
        }
    }

    private fun requestStoragePermission() {
        if (isRequestingPermission) return

        if (hasStoragePermission()) {
            startSplashDelay()
            return
        }

        // Android 11+ (API 30+) 使用 MANAGE_EXTERNAL_STORAGE 特殊权限：
        // 它不是运行时权限，不走系统弹窗，而是弹自定义对话框引导前往设置开启
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
        ) {
            permissionRequested = true
            val message = if (DeviceUtils.isTvDevice(this)) {
                getString(uiR.string.perm_tv_msg)
            } else {
                getString(uiR.string.perm_tv_msg2)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(uiR.string.perm_title))
                .setMessage(message)
                .setPositiveButton(getString(uiR.string.perm_go_settings)) { _, _ ->
                    XXPermissions.startPermissionActivity(this, storagePermissions())
                    // 跳转设置页是异步的，等 onResume 回来再判断
                }
                .setNegativeButton(getString(uiR.string.perm_skip)) { _, _ -> startSplashDelay() }
                .setCancelable(false)
                .show()
            return
        }

        // Android 10 及以下使用传统存储权限
        isRequestingPermission = true
        permissionRequested = true
        XXPermissions.with(this)
            .permissions(storagePermissions())
            .request(object : OnPermissionCallback {
                override fun onPermissionResult(
                    granted: MutableList<IPermission>,
                    denied: MutableList<IPermission>
                ) {
                    isRequestingPermission = false
                    if (denied.isEmpty()) {
                        startSplashDelay()
                        return
                    }

                    val neverAsk = XXPermissions.isDoNotAskAgainPermissions(this@SplashActivity, denied)
                    if (neverAsk) {
                        showPermissionSettingDialog(denied, true)
                        return
                    }

                    if (permissionRetryCount < 1) {
                        permissionRetryCount++
                        AlertDialog.Builder(this@SplashActivity)
                            .setTitle(getString(uiR.string.perm_title))
                            .setMessage(getString(uiR.string.perm_denied_msg))
                            .setPositiveButton(getString(uiR.string.perm_reapply)) { _, _ -> requestStoragePermission() }
                            .setNegativeButton(getString(uiR.string.perm_skip)) { _, _ -> startSplashDelay() }
                            .setCancelable(false)
                            .show()
                        return
                    }

                    showPermissionSettingDialog(denied, false)
                }
            })
    }

    private fun showPermissionSettingDialog(denied: List<IPermission>, neverAskAgain: Boolean) {
        val message = if (neverAskAgain)
            getString(uiR.string.perm_permanent_denied_msg)
        else
            getString(uiR.string.perm_ask_msg)
        AlertDialog.Builder(this)
            .setTitle(getString(uiR.string.perm_title))
            .setMessage(message)
            .setPositiveButton(getString(uiR.string.perm_go_settings)) { _, _ ->
                XXPermissions.startPermissionActivity(this@SplashActivity, denied)
            }
            .setNegativeButton(getString(uiR.string.perm_skip)) { _, _ -> startSplashDelay() }
            .setCancelable(false)
            .show()
    }

    private fun startSplashDelay() {
        if (splashStarted || isFinishing || isDestroyed) return
        splashStarted = true
        handler.postDelayed(splashRunnable, SPLASH_DURATION)
    }

    private fun startTextShatterAnimation(container: LinearLayout) {
        val appName = getString(com.tvbox.kotlin.core.ui.R.string.app_name)
        container.removeAllViews()
        val charViews = Array(appName.length) { i ->
            TextView(this).apply {
                text = appName[i].toString()
                setTextColor(ContextCompat.getColor(this@SplashActivity, android.R.color.white))
                textSize = 42f
                setTypeface(null, android.graphics.Typeface.BOLD)
                alpha = 0f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }.also { container.addView(it) }
        }

        container.post {
            charViews.forEachIndexed { i, charView ->
                val randomX = (Math.random() - 0.5).toFloat() * 400f
                val randomY = (Math.random() - 0.5).toFloat() * 400f
                val randomScale = 0.5f + Math.random().toFloat() * 0.5f
                val randomRotation = (Math.random() - 0.5).toFloat() * 180f

                charView.translationX = randomX
                charView.translationY = randomY
                charView.scaleX = randomScale
                charView.scaleY = randomScale
                charView.rotation = randomRotation

                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(charView, "alpha", 0f, 1f),
                        ObjectAnimator.ofFloat(charView, "translationX", randomX, 0f),
                        ObjectAnimator.ofFloat(charView, "translationY", randomY, 0f),
                        ObjectAnimator.ofFloat(charView, "scaleX", randomScale, 1f),
                        ObjectAnimator.ofFloat(charView, "scaleY", randomScale, 1f),
                        ObjectAnimator.ofFloat(charView, "rotation", randomRotation, 0f)
                    )
                    duration = 500 + i * 50L
                    startDelay = 200 + i * 30L
                    start()
                }
            }
        }
    }

    private fun navigateToHome() {
        navigator.goHome(this, intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun hasStoragePermission(): Boolean =
        XXPermissions.isGrantedPermissions(this, storagePermissions())

    private fun storagePermissions(): List<IPermission> {
        // Android 11+ 使用 MANAGE_EXTERNAL_STORAGE；低版本使用 READ/WRITE_EXTERNAL_STORAGE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
        ) {
            listOf(PermissionLists.getManageExternalStoragePermission())
        } else {
            listOf(
                PermissionLists.getReadExternalStoragePermission(),
                PermissionLists.getWriteExternalStoragePermission()
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val SPLASH_DURATION = 1200L
    }
}
