package com.an.feige.common;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 微信「消息推送」加解密工具（小程序虚拟支付发货推送等服务器消息推送场景）。
 *
 * <p>官方安全模式/兼容模式：EncodingAESKey(Base64) → AESKey(取前32字节)，
 * 消息体 Encrypt 为 AES-256-CBC(密钥=EncodingAESKey前32字节, IV=密钥前16字节, PKCS7) 加密的
 * Base64 字符串；明文结构 {@code <xml><ToUserName/><FromUserName/><CreateTime/><MsgType/><Event/>
 * ...消息体...<AppId/><Encrypt/></xml>} 或
 * {@code random(16B) + msg_len(4B网络序) + msg + AppId}。</p>
 *
 * <p>兼容模式收到的是 {@code <xml><Encrypt>...</Encrypt></xml>}，解密后得到业务 XML；
 * 主动回复时文档允许回空/success/{@code <xml><Encrypt>..</Encrypt></xml>} 等（见官方推送响应格式）。
 * 本项目发货推送处理成功统一返回 success 明文。</p>
 */
public final class WxMsgCryptUtil {

    private static final Logger log = LoggerFactory.getLogger(WxMsgCryptUtil.class);

    private WxMsgCryptUtil() {
    }

    /**
     * 公众平台消息校验（GET 验 URL）：sha1(sort(token, timestamp, nonce)) == signature。
     *
     * @return true=校验通过
     */
    public static boolean checkSignature(String token, String timestamp, String nonce, String signature) {
        if (token == null || timestamp == null || nonce == null || signature == null) {
            return false;
        }
        String[] arr = {token, timestamp, nonce};
        Arrays.sort(arr);
        String sha1 = DigestUtils.sha1Hex(arr[0] + arr[1] + arr[2]);
        return sha1.equalsIgnoreCase(signature);
    }

    /**
     * 解密消息推送中的 Encrypt 密文。
     *
     * @param encodingAesKey 后台配置的 EncodingAESKey（43位）
     * @param encrypt        推送 XML 中 {@code <Encrypt>} 内容（Base64）
     * @return 解密后的业务 XML 明文（如含 xpay_goods_deliver_notify 的消息体）
     */
    public static String decrypt(String encodingAesKey, String encrypt) {
        try {
            byte[] aesKey = Base64.decodeBase64(encodingAesKey + "=");
            byte[] cipherBytes = Base64.decodeBase64(encrypt);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            IvParameterSpec iv = new IvParameterSpec(Arrays.copyOfRange(aesKey, 0, 16));
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv);
            byte[] plainBytes = cipher.doFinal(cipherBytes);
            // 去掉 16 字节 random 前缀，取 msg_len + msg
            // 结构：random(16) + msg_len(4, 网络序) + msg + AppId
            int msgLen = ((plainBytes[16] & 0xff) << 24)
                    | ((plainBytes[17] & 0xff) << 16)
                    | ((plainBytes[18] & 0xff) << 8)
                    | (plainBytes[19] & 0xff);
            if (msgLen < 0 || 20 + msgLen > plainBytes.length) {
                throw new IllegalArgumentException("解密消息长度非法 msgLen=" + msgLen
                        + " total=" + plainBytes.length);
            }
            return new String(plainBytes, 20, msgLen, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("微信消息推送解密失败 encryptLen={}", encrypt == null ? -1 : encrypt.length(), e);
            throw new IllegalArgumentException("消息解密失败", e);
        }
    }

    /**
     * 从解密后的明文 XML 中提取指定顶层节点文本（ToUserName/FromUserName/Encrypt/…）。
     */
    public static String extractXmlField(String xml, String tag) {
        if (xml == null) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList list = doc.getElementsByTagName(tag);
            if (list.getLength() == 0) {
                return null;
            }
            Element el = (Element) list.item(0);
            return el.getTextContent() == null ? null : el.getTextContent().trim();
        } catch (Exception e) {
            log.warn("微信消息 XML 解析失败 tag={}", tag, e);
            return null;
        }
    }
}
