package com.an.feige.common;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.codec.digest.HmacUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

/**
 * 微信小程序虚拟支付（米大师 xpay）服务端客户端。
 *
 * <p>对应官方服务器 API（均需 access_token + pay_sig，参考 search111 WeiXinUtil）：</p>
 * <ul>
 *   <li>{@code POST /xpay/query_order}：查订单状态（支付成功确认兜底）；</li>
 *   <li>{@code POST /xpay/notify_provide_goods}：通知微信侧已发货完成。</li>
 * </ul>
 *
 * <p>pay_sig = hmac_sha256(appKey, uri + "&" + postBody)，postBody 必须与请求体逐字节一致
 * （TreeMap 保证键序稳定）；uri 不带 query_string。</p>
 */
@Component
public class WxXPayClient {

    private static final Logger log = LoggerFactory.getLogger(WxXPayClient.class);
    private static final String XPAY_QUERY_ORDER = "https://api.weixin.qq.com/xpay/query_order";
    private static final String XPAY_NOTIFY_PROVIDE_GOODS = "https://api.weixin.qq.com/xpay/notify_provide_goods";
    private static final int TIMEOUT = 10000;

    /** 支付下单签名固定 uri（wx.requestVirtualPayment 的 paySig 计算用，见 FeigePayService）。 */
    public static final String URI_REQUEST_VIRTUAL_PAYMENT = "requestVirtualPayment";

    @Value("${feige.pay.offer-id:}")
    private String offerId;

    @Value("${feige.pay.app-key:}")
    private String appKey;

    @Resource
    private WeChatClient weChatClient;

    public String getOfferId() {
        return offerId;
    }

    public String getAppKey() {
        return appKey;
    }

    /** 是否已配置真实支付凭证（offer-id + app-key 齐全才算配置完成）。 */
    public boolean configured() {
        return StringUtils.isNotBlank(offerId) && StringUtils.isNotBlank(appKey);
    }

    /**
     * 计算支付签名：to_hex(hmac_sha256(appKey, uri + '&' + postBody))。
     * uri 不带问号参数；postBody 与真实发送/前端 signData 完全一致（原串、不格式化）。
     */
    public String paySig(String uri, String postBody) {
        return HmacUtils.hmacSha256Hex(appKey, uri + "&" + postBody);
    }

    /**
     * 查询订单（兜底确认支付）。
     *
     * @return 响应 JSON（含 code/order）；失败返回含 errcode 的对象或 null
     */
    public JSONObject queryOrder(String openid, String outTradeNo) {
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("openid", openid);
        body.put("env", 0);
        body.put("order_id", outTradeNo);
        return postXpay(XPAY_QUERY_ORDER, "/xpay/query_order", body);
    }

    /**
     * 通知微信侧已发货完成（道具直购发货上报；推送成功通常无需再调）。
     */
    public JSONObject notifyProvideGoods(String outTradeNo) {
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("env", 0);
        body.put("order_id", outTradeNo);
        return postXpay(XPAY_NOTIFY_PROVIDE_GOODS, "/xpay/notify_provide_goods", body);
    }

    /** 米大师 xpay 服务端接口统一请求：access_token + pay_sig + JSON body。 */
    private JSONObject postXpay(String urlBase, String uri, TreeMap<String, Object> body) {
        try {
            String postBody = JSON.toJSONString(body);
            String paySig = HmacUtils.hmacSha256Hex(appKey, uri + "&" + postBody);
            String accessToken = weChatClient.accessToken();
            if (StringUtils.isBlank(accessToken)) {
                log.warn("[xpay] access_token 为空, 跳过 {}", uri);
                return null;
            }
            String url = urlBase + "?access_token=" + accessToken + "&pay_sig=" + paySig;
            String resp = httpPostJson(url, postBody);
            if (StringUtils.isBlank(resp)) {
                return null;
            }
            JSONObject json = JSONObject.parseObject(resp);
            log.info("[xpay] {} body={} resp={}", uri, postBody, resp);
            return json;
        } catch (Exception e) {
            log.error("[xpay] 调用失败 {}", uri, e);
            return null;
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
            return readAll(in);
        } finally {
            conn.disconnect();
        }
    }

    private String readAll(InputStream in) {
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
            log.warn("读取 xpay 响应失败", e);
            return "";
        }
    }
}
