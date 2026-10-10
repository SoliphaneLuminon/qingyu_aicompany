package com.qingyu.app.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qingyu.app.AppContainer

/** 开机后如果用户开过桌宠，就自动恢复喵。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = AppContainer.get(context).settings.current
        if (settings.petEnabled) {
            PetService.start(context)
        }
    }
}
