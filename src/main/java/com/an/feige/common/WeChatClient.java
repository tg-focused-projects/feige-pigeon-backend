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
    /** 图片内容安全（官方：POST multipart/form-data，字段名 media；格式 PNG/JPEG/JPG/GIF、≤1M）。 */
    private static final String IMG_SEC_CHECK = "https://api.weixin.qq.com/wxa/img_sec_check";
    /** 文本内容安全 msg_sec_check（2.0：content/version=2/scene/openid，返回 result.suggest）。 */
    private static final String MSG_SEC_CHECK = "https://api.weixin.qq.com/wxa/msg_sec_check";
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
     * 推送小程序订阅消息（到达通知，使用到达模板）。模板或 appid 未配置时静默跳过。
     */
    public boolean pushSubscribeMessage(String openid, String page, Map<String, Object> data) {
        return pushSubscribeMessage(openid, page, data, arrivalTemplateId);
    }

    /**
     * 推送小程序订阅消息（可指定模板 ID，用于到达/回信到达不同模板）。模板或 appid 未配置时静默跳过。
     */
    public boolean pushSubscribeMessage(String openid, String page, Map<String, Object> data,
                                        String templateId) {
        if (StringUtils.isBlank(templateId)) {
            return false;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("touser", openid);
            body.put("template_id", templateId);
            body.put("page", page);
            body.put("data", data);
            log.info("订阅消息推送 body={} template={} openid={}", body.toJSONString(), templateId, openid);
            String accessToken = getAccessToken();
            if (StringUtils.isBlank(accessToken)) {
                return false;
            }
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

    /**
     * 获取小程序 access_token（带进程内 100s 缓存；多实例部署建议后续改为 Redis）。
     * 虚拟支付服务端接口（/xpay/*）依赖该 token。
     */
    public String accessToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now - cachedAt < ACCESS_TOKEN_TTL_MS) {
            return cachedToken;
        }
        synchronized (this) {
            if (cachedToken != null && now - cachedAt < ACCESS_TOKEN_TTL_MS) {
                return cachedToken;
            }
            try {
                String token = getAccessToken();
                if (StringUtils.isNotBlank(token)) {
                    cachedToken = token;
                    cachedAt = now;
                }
                return token;
            } catch (Exception e) {
                log.warn("获取 access_token 异常(将用缓存/下次重试)", e);
                return cachedToken;
            }
        }
    }

    private static final long ACCESS_TOKEN_TTL_MS = 100 * 1000L;
    private volatile String cachedToken;
    private volatile long cachedAt;

    /** 使缓存的 access_token 失效（微信返回 40001 时调用，强制下次重新获取）。 */
    public void invalidateAccessToken() {
        synchronized (this) {
            cachedToken = null;
            cachedAt = 0L;
        }
    }

    /**
     * 图片内容安全检查（微信 img_sec_check）。
     *
     * <p>官方调用：{@code POST /wxa/img_sec_check?access_token=..}，body 为 multipart/form-data，
     * 文件字段名 {@code media}；图片格式 PNG/JPEG/JPG/GIF，大小 ≤1M，尺寸 ≤750x1334。
     * 返回 {@code errcode}：0=内容正常、87014=含违法违规内容；40001=token 失效（自动重取并重试一次）。</p>
     *
     * @param imageBytes 图片字节（调用方已完成大小/格式校验）
     * @param filename   原始文件名（用于推断 Content-Type 与 multipart filename）
     * @return 微信响应 JSON；网络异常/未配置 appid 时返回 null
     */
    public JSONObject checkImageSec(byte[] imageBytes, String filename) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = accessToken();
            if (StringUtils.isBlank(token)) {
                log.warn("图片安全检查跳过：access_token 未获取到（appid/secret 未配置或微信不可达）");
                return null;
            }
            try {
                String boundary = "----FeigeBoundary" + DigestUtils.md5Hex(
                        System.nanoTime() + "-" + filename);
                byte[] body = buildMediaMultipart(boundary, filename, imageBytes);
                String resp = httpPostMultipart(IMG_SEC_CHECK + "?access_token=" + token,
                        "multipart/form-data; boundary=" + boundary, body);
                JSONObject json = StringUtils.isBlank(resp) ? null : JSONObject.parseObject(resp);
                if (json == null) {
                    log.warn("图片安全检查无响应 filename={} size={}", filename, imageBytes.length);
                    return null;
                }
                int errcode = json.getIntValue("errcode");
                if (errcode == 40001 && attempt == 0) {
                    // token 失效：作废缓存后重试一次
                    log.info("图片安全检查 token 失效(40001)，重取 access_token 后重试");
                    invalidateAccessToken();
                    continue;
                }
                log.info("图片安全检查 filename={} size={} errcode={} errmsg={}",
                        filename, imageBytes.length, errcode, json.getString("errmsg"));
                return json;
            } catch (Exception e) {
                log.error("图片安全检查调用失败 filename={} size={}", filename, imageBytes.length, e);
                return null;
            }
        }
        return null;
    }

    /**
     * 文本内容安全检查（微信 msg_sec_check 2.0）。
     *
     * <p>官方要求：{@code content} ≤2500 字；{@code version} 固定 2；
     * {@code scene} ∈ {1 资料, 2 评论, 3 论坛, 4 社交日志}；
     * {@code openid} 必须是**近两小时内访问过小程序**的用户。
     * 返回 {@code result.suggest}：pass/risky/review（结论看它），另含 {@code result.label} 标签枚举。
     * access_token 失效(40001) 自动重取并重试一次。</p>
     *
     * @return 微信响应 JSON；网络异常/未配置 appid 时返回 null
     */
    public JSONObject checkTextSec(String openid, String content, int scene) {
        return checkTextSec(openid, content, scene, null, null, null);
    }

    /** 文本内容安全检查（可附带 title/nickname/signature 辅助判定；signature 仅 scene=1 有效）。 */
    public JSONObject checkTextSec(String openid, String content, int scene,
                                   String title, String nickname, String signature) {
        if (StringUtils.isBlank(content)) {
            return null;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = accessToken();
            if (StringUtils.isBlank(token)) {
                log.warn("文本安全检查跳过：access_token 未获取到（appid/secret 未配置或微信不可达）");
                return null;
            }
            try {
                java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("content", content);
                body.put("version", 2);
                body.put("scene", scene);
                body.put("openid", openid);
                if (StringUtils.isNotBlank(title)) {
                    body.put("title", title);
                }
                if (StringUtils.isNotBlank(nickname)) {
                    body.put("nickname", nickname);
                }
                if (StringUtils.isNotBlank(signature)) {
                    body.put("signature", signature);
                }
                String resp = httpPostJson(MSG_SEC_CHECK + "?access_token=" + token,
                        JSONObject.toJSONString(body));
                JSONObject json = StringUtils.isBlank(resp) ? null : JSONObject.parseObject(resp);
                if (json == null) {
                    log.warn("文本安全检查无响应 len={} scene={}", content.length(), scene);
                    return null;
                }
                int errcode = json.getIntValue("errcode");
                if (errcode == 40001 && attempt == 0) {
                    log.info("文本安全检查 token 失效(40001)，重取 access_token 后重试");
                    invalidateAccessToken();
                    continue;
                }
                JSONObject result = json.getJSONObject("result");
                log.info("文本安全检查 scene={} len={} errcode={} suggest={} label={} errmsg={}",
                        scene, content.length(), errcode,
                        result == null ? null : result.getString("suggest"),
                        result == null ? null : result.getInteger("label"),
                        json.getString("errmsg"));
                return json;
            } catch (Exception e) {
                log.error("文本安全检查调用失败 len={} scene={}", content.length(), scene, e);
                return null;
            }
        }
        return null;
    }

    /** 构造 img_sec_check 的 multipart/form-data 请求体（字段名固定 media）。 */
    private byte[] buildMediaMultipart(String boundary, String filename, byte[] fileBytes) {
        String safeName = StringUtils.defaultIfBlank(filename, "image.jpg");
        String contentType = guessImageContentType(safeName);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            bos.write(("Content-Disposition: form-data; name=\"media\"; filename=\""
                    + safeName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            bos.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            bos.write(fileBytes);
            bos.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("构造图片上传请求体失败", e);
        }
        return bos.toByteArray();
    }

    /** 按扩展名推断图片 MIME（仅允许 png/jpg/jpeg/gif，调用方已校验后缀）。 */
    private String guessImageContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    private String httpPostMultipart(String url, String contentType, byte[] body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", contentType);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
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