package com.github.kr328.clash.zhifeiyun

import java.util.concurrent.TimeUnit

object PanelConfig {
    const val BASE_URL = "https://panel.020178.xyz"
    // Current SSPanel-UIM: products at /user/product (/user/shop returns 404 on this panel).
    const val PRODUCT_PATH = "/user/product"
    const val SHOP_URL = "$BASE_URL$PRODUCT_PATH"
    const val REGISTER_URL = "$BASE_URL/auth/register"
    const val RESET_URL = "$BASE_URL/password/reset"
    const val PROFILE_NAME = "智飞云"
    // Must stay constant: SSPanel-UIM may bind the login cookie to the User-Agent.
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 ZhifeiyunApp/1.0"
    val UPDATE_INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(6)
}
