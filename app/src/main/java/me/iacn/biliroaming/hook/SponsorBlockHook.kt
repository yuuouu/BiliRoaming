package me.iacn.biliroaming.hook

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.network.BiliRoamingApi
import me.iacn.biliroaming.utils.sPrefs
import me.iacn.biliroaming.utils.*

class SponsorBlockHook(classLoader: ClassLoader) : BaseHook(classLoader) {

    @Volatile
    private var currentBvid: String? = null
    @Volatile
    private var currentCid: Long? = null
    @Volatile
    private var skipSegments: List<Pair<Float, Float>> = emptyList()
    @Volatile
    private var lastSeekTarget: Long = -1

    override fun startHook() {
        if (!sPrefs.getBoolean("enable_sponsorblock", false)) return

        // Intercept playView to capture the bvid and fetch segments
        instance.playURLMossClass?.hookAfterMethod(
            if (instance.useNewMossFunc) "executePlayView" else "playView", instance.playViewReqClass
        ) { param ->
            val bvid = param.args[0].callMethodOrNullAs<String>("getBvid")
            val cid = param.args[0].callMethodOrNullAs<Long>("getCid")
            if (!bvid.isNullOrEmpty() && (currentBvid != bvid || currentCid != cid)) {
                currentBvid = bvid
                currentCid = cid
                lastSeekTarget = -1
                skipSegments = emptyList()
                MainScope().launch(Dispatchers.IO) {
                    skipSegments = BiliRoamingApi.getSponsorBlockSegments(bvid, cid?.toString())
                }
            }
        }

        instance.playerMossClass?.hookAfterMethod(
            if (instance.useNewMossFunc) "executePlayViewUnite" else "playViewUnite",
            instance.playViewUniteReqClass
        ) { param ->
            val bvid = param.args[0].callMethodOrNullAs<String>("getBvid")
            val cid = param.args[0].callMethodOrNullAs<Long>("getCid")
            if (!bvid.isNullOrEmpty() && (currentBvid != bvid || currentCid != cid)) {
                currentBvid = bvid
                currentCid = cid
                lastSeekTarget = -1
                skipSegments = emptyList()
                MainScope().launch(Dispatchers.IO) {
                    skipSegments = BiliRoamingApi.getSponsorBlockSegments(bvid, cid?.toString())
                }
            }
        }

        // Hook progress update
        val onVideoProgressUpdate = instance.onVideoProgressUpdate()
        val playerCoreServiceClass = instance.playerCoreServiceV2Class
        val seekToMethod = instance.seekTo()

        if (playerCoreServiceClass != null && onVideoProgressUpdate != null && seekToMethod != null) {
            playerCoreServiceClass.hookAfterMethod(onVideoProgressUpdate) { param ->
                val playerCoreService = param.thisObject

                var currentPositionMs: Long? = null
                val args = param.args
                if (args.isNotEmpty() && args[0] is Long) {
                    // Try to extract from arguments (e.g. currentPosition, totalDuration)
                    currentPositionMs = args[0] as Long
                } else {
                    val getCurrentPosition = instance.getCurrentPosition()
                    if (getCurrentPosition != null) {
                        currentPositionMs = playerCoreService.callMethodOrNullAs<Long>(getCurrentPosition)
                    }
                }

                if (currentPositionMs != null && skipSegments.isNotEmpty()) {
                    val currentPosSec = currentPositionMs / 1000f

                    // Find if we are currently inside any skipped segment
                    for (segment in skipSegments) {
                        val start = segment.first
                        val end = segment.second

                        if (currentPosSec >= start && currentPosSec < end) {
                            val targetSeekMs = (end * 1000).toLong()

                            // Prevent infinite seeking loops
                            if (lastSeekTarget != targetSeekMs) {
                                lastSeekTarget = targetSeekMs
                                playerCoreService.callMethodOrNull(seekToMethod, targetSeekMs)

                                Handler(Looper.getMainLooper()).post {
                                    Log.toast("已自动跳过恰饭片段 (SponsorBlock)")
                                }
                            }
                            break
                        }
                    }
                }
            }
        }
    }
}
