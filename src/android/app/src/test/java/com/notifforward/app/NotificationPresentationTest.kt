package com.notifforward.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPresentationTest {
    @Test fun sourceRemainsVisibleInCollapsedTitle() {
        assertEquals("微信 · 张三", NotificationPresentation.title("微信", "wechat", "张三"))
    }
    @Test fun missingDisplayNameFallsBackToApplicationId() {
        assertEquals("Mail · 新通知", NotificationPresentation.title("  ", " Mail ", ""))
    }
    @Test fun missingSourceAndTitleHaveReadableFallbacks() {
        assertEquals("未知应用 · 新通知", NotificationPresentation.title("", "", "  "))
    }
}
