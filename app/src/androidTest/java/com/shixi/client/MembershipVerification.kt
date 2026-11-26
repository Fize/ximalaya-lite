package com.shixi.client

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import java.time.ZoneOffset

/** Synthetic `mobile-user/v2/homePage` payloads only; no account, credential or network use. */
class MembershipVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val completed = arrayListOf<String>()
        val results = Bundle()
        val zone = ZoneOffset.UTC
        val now = 1767225600000L // 2026-01-01T00:00:00Z
        fun page(data: String) = JSONObject().put("code", 0).put("data", JSONObject(data))
        fun test(name: String, body: () -> Unit) { body(); completed.add(name) }
        fun label(status: MembershipStatus) = membershipText(status, zone)
        try {
            test("active_vip_shows_expiry_date") {
                val membership = checkNotNull(parseMembership(page("""{"vipInfo":{"isVip":true,"expireTime":1782864000000}}"""), now))
                check(membership.name == "VIP" && membership.active)
                check(label(MembershipStatus.Known(membership)) == "VIP 2026-07-01 到期") { label(MembershipStatus.Known(membership)) }
            }
            test("expired_svip_still_shows_past_date") {
                val membership = checkNotNull(parseMembership(page("""{"svipInfo":{"isVip":false,"expireTime":1704067200000}}"""), now))
                check(!membership.active && membership.name == "SVIP")
                check(label(MembershipStatus.Known(membership)) == "SVIP 已于 2024-01-01 过期") { label(MembershipStatus.Known(membership)) }
            }
            test("active_svip_outranks_active_vip") {
                val membership = checkNotNull(parseMembership(page(
                    """{"vipInfo":{"isVip":true,"expireTime":1782864000000},"svipInfo":{"isVip":true,"expireTime":1788220800000}}"""), now))
                check(membership.name == "SVIP")
                check(label(MembershipStatus.Known(membership)) == "SVIP 2026-09-01 到期")
            }
            test("platinum_member_outranks_vip") {
                val membership = checkNotNull(parseMembership(page(
                    """{"vipInfo":{"isVip":true,"expireTime":1782864000000},"platinumVipInfo":{"isVip":true,"expireTime":1788220800000}}"""), now))
                check(membership.name == "白金会员")
                check(label(MembershipStatus.Known(membership)) == "白金会员 2026-09-01 到期")
            }
            test("most_recent_expiry_wins_when_all_lapsed") {
                val membership = checkNotNull(parseMembership(page(
                    """{"vipInfo":{"isVip":false,"expireTime":1704067200000},"childSVipInfo":{"isVip":false,"expireTime":1748736000000}}"""), now))
                check(membership.name == "儿童SVIP" && membership.expiresAt == 1748736000000L)
                check(label(MembershipStatus.Known(membership)) == "儿童SVIP 已于 2025-06-01 过期")
            }
            test("permanent_membership_has_no_date") {
                val membership = checkNotNull(parseMembership(page("""{"vipInfo":{"isVip":true,"expireTime":0}}"""), now))
                check(label(MembershipStatus.Known(membership)) == "VIP 长期有效")
            }
            test("top_level_vip_expire_time_fallback") {
                val membership = checkNotNull(parseMembership(page("""{"vipExpireTime":1782864000000}"""), now))
                check(membership.name == "VIP" && membership.active)
                check(label(MembershipStatus.Known(membership)) == "VIP 2026-07-01 到期")
            }
            test("json_encoded_data_object_supported") {
                val payload = JSONObject().put("code", 0)
                    .put("data", """{"vipInfo":{"isVip":true,"expireTime":1782864000000}}""")
                val membership = checkNotNull(parseMembership(payload, now))
                check(label(MembershipStatus.Known(membership)) == "VIP 2026-07-01 到期")
            }
            test("rejected_or_empty_payload_has_no_membership") {
                check(parseMembership(JSONObject().put("ret", 1).put("data", JSONObject()), now) == null)
                check(parseMembership(JSONObject().put("code", 0), now) == null)
                check(parseMembership(page("{}"), now) == null)
            }
            test("implausible_expiry_is_not_displayed_as_date") {
                val membership = checkNotNull(parseMembership(page("""{"vipInfo":{"isVip":true,"expireTime":123}}"""), now))
                check(label(MembershipStatus.Known(membership)) == "VIP 有效期未知")
                check(membershipDate(123, zone) == null)
            }
            test("status_text_covers_signed_out_loading_none_unavailable") {
                check(label(MembershipStatus.SignedOut) == "登录后查看")
                check(label(MembershipStatus.Loading) == "正在获取…")
                check(label(MembershipStatus.None) == "未开通会员")
                check(label(MembershipStatus.Unavailable) == "暂时无法获取")
            }
            test("expiry_boundaries_stay_in_renderable_range") {
                check(membershipDate(946684800000L, zone) == "2000-01-01")
                check(membershipDate(946684799999L, zone) == null)
                check(membershipDate(7258118400000L, zone) == null)
            }
            results.putStringArrayList("passed", completed); results.putInt("count", completed.size)
            results.putString("scope", "synthetic membership payload/date checks; no account, credential or network use")
            finish(Activity.RESULT_OK, results)
        } catch (failure: Throwable) {
            results.putStringArrayList("passed", completed)
            results.putString("failure", failure.javaClass.simpleName + ":" + failure.message)
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
