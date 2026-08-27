package com.an.feige.common;

import com.alibaba.fastjson.JSONObject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 微信小程序客户端（自研，最小实现，不依赖旧 search-core 的 WeiXinUtil）。
 *
 * <p>能力：jscode2session 换取 openid/session_key；小程序订阅消息推送（到达通知）。
 * access_token 此处每次实时获取（可后续用 Redis 缓存）。</p>
 */
@Component
public class WeChatClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatClient.class);
    private static final String JSCODE2SESSION = "https://api.weixin.qq.com/sns/jscode2session";
    private static final String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String SUBSCRIBE_SEND = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";
    private static final int TIMEOUT = 10000;

    @Value("${feige.wechat.appid}")
    private String appid;

    @Value("${feige.wechat.secret}")
    private String secret;

    @Value("${feige.wechat.arrival-template-id:}")
    private String arrivalTemplateId;

    @Value("${feige.wechat.dev-login:false}")
    private boolean devLogin;

    /**
     * jscode2session：小程序 wx.login 的 code 换取 openid/session_key。
     *
     * @return 成功含 openid/session_key/(unionid)；失败含 errcode/errmsg
     */
    public Map<String, Object> jscode2session(String jsCode) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (devLogin) {
                // dev 模式兜底：本地无需真实微信凭据，用 jsCode 派生稳定 openid
                String devOpenid = "dev_" + DigestUtils.md5Hex(StringUtils.defaultString(jsCode));
                result.put("openid", devOpenid);
                result.put("session_key", "dev_session_key");
                result.put("devLogin", true);
                log.info("[dev] jscode2session 兜底 openid={} jsCode={}", devOpenid, jsCode);
                return result;
            }
            String url = JSCODE2SESSION + "?appid=" + appid + "&secret=" + secret
                    + "&grant_type=authorization_code&js_code=" + jsCode;
            String body = httpGet(url);
            JSONObject json = StringUtils.isBlank(body) ? new JSONObject() : JSONObject.parseObject(body);
            if (json.containsKey("errcode") && json.getIntValue("errcode") != 0) {
                result.put("errcode", json.getIntValue("errcode"));
                result.put("errmsg", json.getString("errmsg"));
                return result;
            }
            result.put("openid", json.getString("openid"));
            result.put("session_key", json.getString("session_key"));
            if (json.containsKey("unionid")) {
                result.put("unionid", json.getString("unionid"));
            }
            return result;
        } catch (Exception e) {
            log.error("jscode2session 失败", e);
            result.put("errcode", -1);
            result.put("errmsg", e.getMessage());
            return result;
        }
    }

    /**
     * 推送小程序订阅消息（到达通知）。模板或 appid 未配置时静默跳过。
     */
    public boolean pushSubscribeMessage(String openid, String page, Map<String, Object> data) {
        if (StringUtils.isBlank(arrivalTemplateId)) {
            return false;
        }
        try {
            String accessToken = getAccessToken();
            if (StringUtils.isBlank(accessToken)) {
                return false;
            }
            JSONObject body = new JSONObject();
            body.put("touser", openid);
            body.put("template_id", arrivalTemplateId);
            body.put("page", page);
            body.put("data", data);
            JSONObject resp = JSONObject.parseObject(httpPostJson(SUBSCRIBE_SEND + "?access_token=" + accessToken,
                    body.toJSONString()));
            log.info("订阅消息推送结果 errcode={} openid={}", resp == null ? "-" : resp.getIntValue("errcode"), openid);
            return resp != null && resp.getIntValue("errcode") == 0;
        } catch (Exception e) {
            log.warn("订阅消息推送失败(不影响业务) openid={}", openid, e);
            return false;
        }
    }

    private String getAccessToken() throws Exception {
        String url = TOKEN_URL + "?grant_type=client_credential&appid=" + appid + "&secret=" + secret;
        JSONObject json = JSONObject.parseObject(httpGet(url));
        String token = json == null ? null : json.getString("access_token");
        if (StringUtils.isBlank(token)) {
            log.warn("获取微信 access_token 失败 errcode={} errmsg={}",
                    json == null ? "-" : json.getIntValue("errcode"),
                    json == null ? "-" : json.getString("errmsg"));
        }
        return token;
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestMethod("GET");
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            return readAll(in, code >= 200 && code < 300);
        } finally {
            conn.disconnect();
        }
    }

    private String httpPostJson(String url, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            return readAll(in, code >= 200 && code < 300);
        } finally {
            conn.disconnect();
        }
    }

    private String readAll(InputStream in, boolean ok) {
        if (in == null) {
            return "";
        }
        try (InputStream is = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取微信响应失败", e);
            return "";
        }
    }
}
