package com.coke.nestspace.agent

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserGatePolicyTest {

    @Test
    fun riskyTapLabelsNeedConfirmation() {
        assertTrue(UserGateManager.isRiskyTap("发送"))
        assertTrue(UserGateManager.isRiskyTap("立即支付"))
        assertTrue(UserGateManager.isRiskyTap("Delete"))
        assertTrue(UserGateManager.isRiskyTap("安装应用"))
        assertFalse(UserGateManager.isRiskyTap("首页"))
        assertFalse(UserGateManager.isRiskyTap("搜索"))
        assertFalse(UserGateManager.isRiskyTap(""))
    }

    @Test
    fun sensitiveScreenGetsTakeoverHint() {
        val captcha = JSONObject()
            .put(
                "nodes",
                org.json.JSONArray().put(
                    JSONObject().put("text", "请输入验证码")
                )
            )
            .toString()
        val annotated = JSONObject(UserGateManager.annotateSensitive(captcha))
        assertTrue(annotated.getBoolean("needs_user"))
        assertTrue(annotated.getString("user_reason") == "captcha")

        val payment = JSONObject()
            .put(
                "nodes",
                org.json.JSONArray().put(
                    JSONObject().put("text", "确认支付 ￥ 12.00")
                )
            )
            .toString()
        val paid = JSONObject(UserGateManager.annotateSensitive(payment))
        assertTrue(paid.getBoolean("needs_user"))
        assertTrue(paid.getString("user_reason") == "payment")
    }

    @Test
    fun normalScreenIsUntouched() {
        val normal = JSONObject()
            .put(
                "nodes",
                org.json.JSONArray()
                    .put(JSONObject().put("text", "首页"))
                    .put(JSONObject().put("text", "请求 CAMERA 权限（测试说明文字）"))
            )
            .toString()
        val result = JSONObject(UserGateManager.annotateSensitive(normal))
        assertFalse(result.has("needs_user"))
    }
}
