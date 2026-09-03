package com.an.feige.feige.controller;

import com.an.feige.common.WxMsgCryptUtil;
import com.an.feige.feige.service.FeigePayService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 虚拟支付「发货推送」接收（微信服务器消息推送 → 本服务）。
 *
 * <p>微信后台「虚拟支付 → 基本配置 → 发货推送配置」填写的 URL 指向本控制器，
 * 平台推送 XML（当前后台为兼容模式，密文在 {@code <Encrypt>} 内，需解密后解析）。</p>
 *
 * <ul>
 *   <li><b>GET</b>：首次配置 URL 时微信带 signature/timestamp/nonce/echostr 来校验——按
 *       sha1(sort(token,timestamp,nonce)) 校验，通过返回 echostr，否则后台保存 URL 失败。</li>
 *   <li><b>POST</b>：兼容模式密文 → AES 解密 → 解析 Event：
 *       <ul>
 *         <li>{@code xpay_goods_deliver_notify}：支付成功发货推送 → confirm 订单并发权益（幂等）→ 返回 success；</li>
 *         <li>{@code xpay_refund_notify}：退款完成推送 → 订单置 REFUNDED；</li>
 *         <li>其它事件：记日志返回 success。</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>响应：直接写 {@link HttpServletResponse} 纯文本（项目全局 FastJSON converter 会把 String
 * 序列化加引号，微信 URL 校验/推送应答不认），按官方要求返回 success（等价 ErrCode=0）。</p>
 */
@RestController
@RequestMapping("/feige/pay")
public class PayNotifyController {

    private static final Logger log = LoggerFactory.getLogger(PayNotifyController.class);

    @Resource
    private FeigePayService feigePayService;

    @Value("${feige.mp-push.token:}")
    private String pushToken;

    @Value("${feige.mp-push.aes-key:}")
    private String pushAesKey;

    @Value("${feige.mp-push.encrypt-mode:1}")
    private int encryptMode;

    /** 发货推送校验 URL（GET）。 */
    @GetMapping(value = "/notify")
    public void verify(@RequestParam(name = "signature", required = false) String signature,
                       @RequestParam(name = "timestamp", required = false) String timestamp,
                       @RequestParam(name = "nonce", required = false) String nonce,
                       @RequestParam(name = "echostr", required = false) String echostr,
                       HttpServletResponse response) throws Exception {
        if (StringUtils.isAnyBlank(signature, timestamp, nonce, echostr)) {
            writeText(response, "参数不完整");
            return;
        }
        if (WxMsgCryptUtil.checkSignature(pushToken, timestamp, nonce, signature)) {
            log.info("[pay-notify] URL 校验通过 echostr={}", echostr);
            writeText(response, echostr);
            return;
        }
        log.warn("[pay-notify] URL 校验失败 signature={} timestamp={} nonce={}", signature, timestamp, nonce);
        writeText(response, "校验失败");
    }

    /** 发货推送（POST，兼容模式密文）。 */
    @PostMapping(value = "/notify")
    public void notify(HttpServletRequest request, HttpServletResponse response) throws Exception {
        try {
            String xml = readBody(request);
            if (StringUtils.isBlank(xml)) {
                writeText(response, "success");
                return;
            }
            log.info("[pay-notify] 收到推送 rawLen={} body={}", xml.length(),
                    xml.length() > 3000 ? xml.substring(0, 3000) : xml);
            String plain = xml;
            // 兼容/安全模式：外层 Encrypt 密文需解密（明文模式直接用）
            if (encryptMode != 0 && StringUtils.isNotBlank(pushAesKey)) {
                String encrypt = WxMsgCryptUtil.extractXmlField(xml, "Encrypt");
                if (StringUtils.isNotBlank(encrypt)) {
                    try {
                        plain = WxMsgCryptUtil.decrypt(pushAesKey, encrypt);
                        log.info("[pay-notify] 解密成功 plainXml={}", plain);
                    } catch (Exception e) {
                        // 解密失败回退：按明文直接解析原始 body（兼容明文推送/密钥不一致的降级路径；
                        // 业务幂等 + 查单兜底兜底，不会重复发货）
                        log.warn("[pay-notify] 密文解密失败, 回退明文解析 encryptLen={} err={}",
                                encrypt.length(), e.getMessage());
                        plain = xml;
                    }
                }
            }
            handlePlain(plain);
            writeText(response, "success");
        } catch (Exception e) {
            log.error("[pay-notify] 处理推送异常", e);
            // 异常返回 success：避免微信无限重试（业务幂等，漏单由查单兜底）
            writeText(response, "success");
        }
    }

    /** 解析并处理解密后的明文业务 XML。 */
    private void handlePlain(String plain) {
        String event = WxMsgCryptUtil.extractXmlField(plain, "Event");
        String outTradeNo = WxMsgCryptUtil.extractXmlField(plain, "OutTradeNo");
        if ("xpay_goods_deliver_notify".equals(event)) {
            if (StringUtils.isBlank(outTradeNo)) {
                log.warn("[pay-notify] 发货推送缺少 OutTradeNo xml={}", plain);
                return;
            }
            // 微信侧平台单号：WeChatPayInfo.TransactionId（微信推送展平为顶层 TransactionId）
            String transactionId = WxMsgCryptUtil.extractXmlField(plain, "TransactionId");
            if (StringUtils.isBlank(transactionId)) {
                transactionId = WxMsgCryptUtil.extractXmlField(plain, "MchOrderNo");
            }
            boolean ok = feigePayService.confirmFromXPayNotify(outTradeNo, transactionId);
            log.info("[pay-notify] 发货推送处理 event={} outTradeNo={} tx={} ok={}", event, outTradeNo, transactionId, ok);
            return;
        }
        if ("xpay_refund_notify".equals(event)) {
            // 退款推送：商户订单号在 MchOrderId（退款单对应支付单的商户单号）
            String mchOrderId = WxMsgCryptUtil.extractXmlField(plain, "MchOrderId");
            if (StringUtils.isBlank(mchOrderId)) {
                mchOrderId = outTradeNo;
            }
            if (StringUtils.isNotBlank(mchOrderId)) {
                boolean ok = feigePayService.refund(mchOrderId);
                log.info("[pay-notify] 退款推送处理 outTradeNo={} ok={}", mchOrderId, ok);
            }
            return;
        }
        // 其它事件（代币/投诉/风控等）记日志即可
        log.info("[pay-notify] 忽略事件 event={} outTradeNo={}", event, outTradeNo);
    }

    /** 直接写纯文本响应（绕过 FastJSON String 加引号问题）。 */
    private void writeText(HttpServletResponse response, String text) throws Exception {
        response.setStatus(200);
        response.setContentType("text/plain;charset=utf-8");
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(text.getBytes(StandardCharsets.UTF_8).length);
        try (PrintWriter writer = response.getWriter()) {
            writer.write(text);
            writer.flush();
        }
    }

    private String readBody(HttpServletRequest request) throws Exception {
        request.setCharacterEncoding("UTF-8");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
