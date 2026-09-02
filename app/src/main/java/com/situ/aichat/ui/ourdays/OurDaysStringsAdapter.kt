package com.situ.aichat.ui.ourdays

import android.content.Context
import com.situ.aichat.R
import java.time.ZoneId

/**
 * 资源 → 纯核文案适配（卷三图纸 §3.3 / §3.5「由 VM 从资源取后传入——纯核不碰 Compose」的生产实现）。
 * 两个 VM（日历页 / 日页）共用；测试用假实现（重打字面量）绕开资源。红包状态四串 = `RedPacketStatus` raw（§3.7 锁定映射）。
 */
internal object OurDaysStrings {

    fun decor(context: Context): DecorStrings = DecorStrings(
        firstDay = context.getString(R.string.our_days_label_first_day),
        firstMeeting = context.getString(R.string.our_days_label_first_meeting),
        anniversary = { context.getString(R.string.our_days_label_anniversary, it) },
        meetingAnniversary = { context.getString(R.string.our_days_label_meeting_anniversary, it) },
        birthdayChar = { context.getString(R.string.our_days_label_birthday_char, it) },
        birthdayUser = context.getString(R.string.our_days_label_birthday_user),
    )

    fun card(context: Context, zone: ZoneId): OurDayCardStrings = object : OurDayCardStrings {
        private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)
        override fun time(millis: Long) = OurDaysFormat.time(millis, zone, context.getString(R.string.our_days_fmt_time))
        override fun chat(count: Int) = s(R.string.our_days_fact_chat, count)
        override fun timeRange(from: String, to: String) = s(R.string.our_days_fact_time_range, from, to)
        override fun call(minutes: Int) = s(R.string.our_days_fact_call, minutes)
        override fun callCount(count: Int) = s(R.string.our_days_fact_call_count, count)
        override fun meeting(place: String) = s(R.string.our_days_fact_meeting, place)
        override fun duration(minutes: Int) = s(R.string.our_days_fact_duration, minutes)
        override fun initiatedUser() = s(R.string.our_days_fact_initiated_user)
        override fun initiatedChar(name: String) = s(R.string.our_days_fact_initiated_char, name)
        override fun giftUser(gift: String) = s(R.string.our_days_fact_gift_user, gift)
        override fun giftChar(name: String, gift: String) = s(R.string.our_days_fact_gift_char, name, gift)
        override fun reaction(name: String, text: String) = s(R.string.our_days_fact_reaction, name, text)
        override fun affinity(gain: Int) = s(R.string.our_days_fact_affinity, gain)
        override fun redPacketUser() = s(R.string.our_days_fact_rp_user)
        override fun redPacketChar(name: String) = s(R.string.our_days_fact_rp_char, name)
        override fun redPacketStatus(status: String) = when (status) {
            "pending" -> s(R.string.our_days_rp_pending)
            "accepted" -> s(R.string.our_days_rp_accepted)
            "rejected" -> s(R.string.our_days_rp_rejected)
            "expired" -> s(R.string.our_days_rp_expired)
            else -> status
        }
        override fun promiseCreated(content: String) = s(R.string.our_days_fact_promise_created, content)
        override fun promiseFulfilled(content: String) = s(R.string.our_days_fact_promise_fulfilled, content)
        override fun promiseCancelled(content: String) = s(R.string.our_days_fact_promise_cancelled, content)
        override fun milestone(text: String) = s(R.string.our_days_fact_milestone, text)
        override fun quote(text: String) = s(R.string.our_days_fact_quote, text)
        override fun moments() = s(R.string.our_days_fact_moments)
        override fun momentsDetail(name: String, posts: Int, interactions: Int) = s(R.string.our_days_fact_moments_detail, name, posts, interactions)
        override fun momentsInteract(name: String, interactions: Int) = s(R.string.our_days_fact_moments_interact, name, interactions)
        override fun exchangeDiary(name: String) = s(R.string.our_days_fact_exchange_diary, name)
        override fun schedule(name: String) = s(R.string.our_days_fact_schedule, name)
    }
}
