package com.an.feige.feige.controller;

import com.alibaba.fastjson.JSONObject;
import com.an.feige.common.SignUtil;
import com.an.feige.common.WeChatClient;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 内容安全-图片审核（转发微信 img_sec_check）。
 *
 * <p>背景：微信 {@code img_sec_check} 只能在服务端调用（需 access_token），前端无法直连；
 * 故由本接口代传图片并回传审核结论。图片由前端以 multipart/form-data 上传（字段名 {@code media}），
 * 后端不落盘、不保存图片，仅内存转发给微信。</p>
 *
 * <p>微信限制（调用方需先压缩）：格式 PNG/JPEG/JPG/GIF；大小 ≤1M；尺寸 ≤750px x 1334px；
 * 频率 2000 次/分钟、200,000 次/天。</p>
 *
 * <p>返回：{@code code=200} 内容正常；{@code code=202 + errorKey=CONTENT_RISKY} 含违法违规内容；
 * {@code code=400} 参数/格式/大小不合法；{@code code=401} 签名非法；{@code code=500} 审核服务异常
 * （微信不可达/token 异常等，前端应提示稍后重试或按业务策略拦截）。</p>
 */
@Api(tags = "内容安全")
@RestController
@RequestMapping("/feige/check")
public class FeigeCheckController {

    private static final Logger log = LoggerFactory.getLogger(FeigeCheckController.class);

    /** 微信图片审核大小上限：1M。 */
    private static final long MAX_IMAGE_BYTES = 1024 * 1024L;

    /** 微信文本审核长度上限：2500 字（msg_sec_check 2.0）。 */
    private static final int MAX_TEXT_LEN = 2500;

    @Resource
    private WeChatClient weChatClient;

    @Autowired
    private SignUtil feigeSignUtil;

    @ApiOperation("图片内容审核(微信 img_sec_check)")
    @PostMapping("/img")
    @ResponseBody
    public Map<String, Object> checkImage(@RequestParam(name = "openid", required = true) String openid,
                                          @RequestParam(name = "media", required = false) MultipartFile media,
                                          HttpServletRequest request) {
        if (!sign(request, openid)) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        if (media == null || media.isEmpty()) {
            return err(400, "请上传图片文件(字段名 media)", "INVALID_ARGUMENT");
        }
        if (media.getSize() > MAX_IMAGE_BYTES) {
            return err(400, "图片不能超过 1M", "FILE_TOO_LARGE");
        }
        String filename = resolveFilename(media.getOriginalFilename(), media.getContentType());
        if (!isAllowedImage(media.getOriginalFilename(), media.getContentType())) {
            return err(400, "仅支持 PNG/JPEG/JPG/GIF 格式", "INVALID_ARGUMENT");
        }

        byte[] bytes;
        try {
            bytes = media.getBytes();
        } catch (IOException e) {
            log.error("读取上传图片失败 filename={} size={}", filename, media.getSize(), e);
            return err(500, "读取图片失败", "CHECK_FAILED");
        }

        JSONObject resp = weChatClient.checkImageSec(bytes, filename);
        if (resp == null) {
            return err(500, "审核服务暂时不可用，请稍后重试", "CHECK_FAILED");
        }
        int errcode = resp.getIntValue("errcode");
        if (errcode == 0) {
            Map<String, Object> data = new HashMap<>();
            data.put("risky", false);
            data.put("suggestion", "pass");
            data.put("errcode", errcode);
            return ok(data);
        }
        if (errcode == 87014) {
            // 内容违规：非 200 便于前端按失败处理，同时给出明确 errorKey
            Map<String, Object> data = new HashMap<>();
            data.put("risky", true);
            data.put("suggestion", "risky");
            data.put("errcode", errcode);
            Map<String, Object> map = err(202, "内容含有违法违规内容", "CONTENT_RISKY");
            map.put("data", data);
            return map;
        }
        log.warn("图片审核返回未知错误码 errcode={} errmsg={}", errcode, resp.getString("errmsg"));
        return err(500, "审核服务异常(errcode=" + errcode + ")", "CHECK_FAILED");
    }

    @ApiOperation("文本内容审核(微信 msg_sec_check 2.0)")
    @PostMapping("/text")
    @ResponseBody
    public Map<String, Object> checkText(@RequestParam(name = "openid", required = false) String openid,
                                         @RequestParam(name = "content", required = false) String content,
                                         @RequestParam(name = "scene", required = false, defaultValue = "2") Integer scene,
                                         @RequestParam(name = "title", required = false) String title,
                                         HttpServletRequest request) {
        if (!sign(request, openid)) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        if (StringUtils.isBlank(content)) {
            return err(400, "请提供要检测的文本(content)", "INVALID_ARGUMENT");
        }
        if (content.length() > MAX_TEXT_LEN) {
            return err(400, "文本不能超过 " + MAX_TEXT_LEN + " 字", "TEXT_TOO_LONG");
        }
        if (scene == null || scene < 1 || scene > 4) {
            return err(400, "scene 取值 1~4(1资料/2评论/3论坛/4社交日志)", "INVALID_ARGUMENT");
        }

        JSONObject resp = weChatClient.checkTextSec(openid, content, scene, title, null, null);
        if (resp == null) {
            return err(500, "审核服务暂时不可用，请稍后重试", "CHECK_FAILED");
        }
        int errcode = resp.getIntValue("errcode");
        JSONObject result = resp.getJSONObject("result");
        String suggest = result == null ? null : result.getString("suggest");
        Integer label = result == null ? null : result.getInteger("label");

        if (errcode == 0 && ("risky".equals(suggest) || "review".equals(suggest))) {
            boolean review = "review".equals(suggest);
            Map<String, Object> data = new HashMap<>();
            data.put("risky", true);
            data.put("suggestion", review ? "review" : "risky");
            data.put("label", label);
            Map<String, Object> map = err(202, review ? "内容需人工复核" : "内容含有违法违规内容",
                    review ? "CONTENT_REVIEW" : "CONTENT_RISKY");
            map.put("data", data);
            return map;
        }
        if (errcode == 0 && "pass".equals(suggest)) {
            Map<String, Object> data = new HashMap<>();
            data.put("risky", false);
            data.put("suggestion", "pass");
            data.put("label", label);
            return ok(data);
        }
        // 旧版兼容：errcode=87014（1.0 语义）
        if (errcode == 87014) {
            Map<String, Object> data = new HashMap<>();
            data.put("risky", true);
            data.put("suggestion", "risky");
            data.put("label", label);
            Map<String, Object> map = err(202, "内容含有违法违规内容", "CONTENT_RISKY");
            map.put("data", data);
            return map;
        }
        // 其它：errcode != 0（如 openid 非本人/近两小时未访问小程序、appid 不匹配）或 suggest 缺失
        log.warn("文本审核返回异常 errcode={} errmsg={} openid={}", errcode, resp.getString("errmsg"), openid);
        return err(500, "审核服务异常(errcode=" + errcode + ")", "CHECK_FAILED");
    }

    /** 小程序签名校验：sign = md5(openid + sign-secret)，同登录接口返回的一致。 */
    private boolean sign(HttpServletRequest request, String openid) {
        String sign = request.getHeader("sign");
        if (StringUtils.isBlank(sign)) {
            return false;
        }
        return feigeSignUtil.verify(openid, sign);
    }

    /** 文件名兜底：部分客户端不带文件名，此时按 Content-Type 推断（仅用于 multipart filename/MIME）。 */
    private String resolveFilename(String originalFilename, String contentType) {
        if (StringUtils.isNotBlank(originalFilename)) {
            return originalFilename;
        }
        String ct = StringUtils.defaultString(contentType).toLowerCase();
        if (ct.contains("png")) {
            return "image.png";
        }
        if (ct.contains("gif")) {
            return "image.gif";
        }
        return "image.jpg";
    }

    /** 后缀或 Content-Type 任一命中允许的图片类型即放行（微信侧会再校验一次）。 */
    private boolean isAllowedImage(String originalFilename, String contentType) {
        String lower = StringUtils.defaultString(originalFilename).toLowerCase();
        if (lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".gif")) {
            return true;
        }
        String ct = StringUtils.defaultString(contentType).toLowerCase();
        return "image/png".equals(ct) || "image/jpeg".equals(ct)
                || "image/jpg".equals(ct) || "image/gif".equals(ct);
    }

    private Map<String, Object> ok(Object data) {
        Map<String, Object> map = new HashMap<>();
        map.put("code", 200);
        map.put("msg", "success");
        map.put("data", data);
        return map;
    }

    private Map<String, Object> err(int code, String msg, String errorKey) {
        Map<String, Object> map = new HashMap<>();
        map.put("code", code);
        map.put("msg", msg);
        map.put("errorKey", errorKey);
        map.put("data", null);
        return map;
    }
}
