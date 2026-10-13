package com.qingyu.app.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qingyu.app.AppContainer
import com.qingyu.app.proactive.ProactiveScheduler

/** 开机后如果用户开过桌宠，就自动恢复喵。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = AppContainer.get(context).settings.current
        if (settings.petEnabled) {
            PetService.start(context)
        }
        // **闹钟必须重新排**喵：AlarmManager 的闹钟在重启后会全部清空，
        // 用户开了「让角色主动找你」，重启一次就悄悄失效了——而它本来
        // 就是"过几小时才响"的功能，用户要等很久才会发现它没响喵。
        ProactiveScheduler(context).reschedule()
    }
}
