package com.an.feige.common;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 签名验证：sign = md5(openid + sign-secret)。登录接口返回该 sign，写接口请求头带 sign 校验。
 */
@Component
public class SignUtil {

    @Value("${feige.wechat.sign-secret:}")
    private String signSecret;

    public String sign(String openid) {
        return DigestUtils.md5Hex(StringUtils.defaultString(openid) + StringUtils.defaultString(signSecret));
    }

    public boolean verify(String openid, String sign) {
        return StringUtils.isNotBlank(sign) && sign.equalsIgnoreCase(sign(openid));
    }
}
