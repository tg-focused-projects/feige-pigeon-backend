package com.an.feige.feige.controller;

import com.an.feige.common.CityGeoService;
import com.an.feige.common.SignUtil;
import com.an.feige.feige.dto.BindLetterRequest;
import com.an.feige.feige.dto.ReplyLetterRequest;
import com.an.feige.feige.dto.SendLetterRequest;
import com.an.feige.feige.entity.FeigePigeon;
import com.an.feige.feige.service.FeigeLetterService;
import com.an.feige.feige.service.FeigePigeonService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 飞鸽传书-微信小程序接口。
 *
 * <p>路由前缀 /feige，统一返回 {code, msg, data, errorKey}。
 * 写操作（send/bind/recall/reply/subscribe）走 sign 签名校验；
 * 分享公开参数使用 shareToken；正文与精确坐标仅在拆信后返回。</p>
 */
@Api(tags = "飞鸽传书")
@RestController
@RequestMapping("/feige")
public class FeigeController {

    @Autowired
    private FeigeLetterService feigeLetterService;

    @Autowired
    private FeigePigeonService feigePigeonService;

    @Autowired
    private SignUtil feigeSignUtil;

    @Autowired
    private CityGeoService feigeCityGeoService;

    // -------------------- 写信并放飞（发送即起飞） --------------------
    @ApiOperation("写信并放飞")
    @PostMapping("/letter/send")
    @ResponseBody
    public Map<String, Object> send(@RequestBody SendLetterRequest req,
                                    HttpServletRequest request) {
        if (!sign(request, req.getOpenid())) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        // 经纬度可选：缺失时由服务端按 province/city 从内置行政区划坐标表兜底
        BigDecimal[] coord = feigeCityGeoService.resolve(
                req.getProvince(), req.getCity(), req.getLat(), req.getLng());
        if (coord == null) {
            return err(400, "缺少定位信息", "INVALID_ARGUMENT");
        }
        return feigeLetterService.send(req.getOpenid(), req.getTitle(), req.getContent(),
                req.getImageUrl(), req.getProvince(), req.getCity(),
                coord[0], coord[1], req.getPigeonId(), req.getSignature());
    }

    // -------------------- 分享预览（不产生认领/状态变更） --------------------
    @ApiOperation("分享预览")
    @GetMapping("/letter/share-preview")
    @ResponseBody
    public Map<String, Object> sharePreview(@RequestParam(name = "shareToken", required = true) String shareToken,
                                            @RequestParam(name = "openid", required = false) String openid) {
        return feigeLetterService.sharePreview(shareToken, openid);
    }

    // -------------------- 收件人原子认领 --------------------
    @ApiOperation("收件人认领(定位)")
    @PostMapping("/letter/bind")
    @ResponseBody
    public Map<String, Object> bind(@RequestBody BindLetterRequest req,
                                     HttpServletRequest request) {
        if (!sign(request, req.getOpenid())) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        // 经纬度缺失时按 province/city 从内置行政区划坐标表兜底（与 send/reply 一致）
        BigDecimal[] coord = feigeCityGeoService.resolve(
                req.getProvince(), req.getCity(), req.getLat(), req.getLng());
        if (coord == null) {
            return err(400, "缺少定位信息", "INVALID_ARGUMENT");
        }
        return feigeLetterService.claim(req.getShareToken(), req.getOpenid(),
                req.getProvince(), req.getCity(), coord[0], coord[1]);
    }

    // -------------------- 发件人免费召回 --------------------
    @ApiOperation("发件人召回")
    @PostMapping("/letter/recall")
    @ResponseBody
    public Map<String, Object> recall(@RequestParam(name = "letterId", required = true) String letterId,
                                      @RequestParam(name = "openid", required = true) String openid,
                                      HttpServletRequest request) {
        if (!sign(request, openid)) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        return feigeLetterService.recall(letterId, openid);
    }

    // -------------------- 飞行页 --------------------
    @ApiOperation("飞行页")
    @GetMapping("/letter/flight")
    @ResponseBody
    public Map<String, Object> flight(@RequestParam(name = "letterId", required = true) String letterId,
                                      @RequestParam(name = "openid", required = true) String openid) {
        return feigeLetterService.flight(letterId, openid);
    }

    // -------------------- 收信/拆信 --------------------
    @ApiOperation("收信/拆信")
    @GetMapping("/letter/detail")
    @ResponseBody
    public Map<String, Object> detail(@RequestParam(name = "letterId", required = true) String letterId,
                                      @RequestParam(name = "openid", required = true) String openid) {
        return feigeLetterService.detail(letterId, openid);
    }

    // -------------------- 回信 --------------------
    @ApiOperation("回信")
    @PostMapping("/letter/reply")
    @ResponseBody
    public Map<String, Object> reply(@RequestBody ReplyLetterRequest req,
                                     HttpServletRequest request) {
        if (!sign(request, req.getOpenid())) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        // 经纬度缺失时按 province/city 从内置行政区划坐标表兜底
        BigDecimal[] coord = feigeCityGeoService.resolve(
                req.getProvince(), req.getCity(), req.getLat(), req.getLng());
        if (coord == null) {
            return err(400, "缺少定位信息", "INVALID_ARGUMENT");
        }
        return feigeLetterService.reply(req.getOpenid(), req.getTitle(), req.getContent(),
                req.getImageUrl(), req.getProvince(), req.getCity(),
                coord[0], coord[1], req.getSignature(), req.getLetterId());
    }

    // -------------------- 订阅到达通知 --------------------
    @ApiOperation("订阅到达通知")
    @PostMapping("/letter/subscribe")
    @ResponseBody
    public Map<String, Object> subscribe(@RequestParam(name = "openid", required = true) String openid,
                                         @RequestParam(name = "letterId", required = true) String letterId,
                                         HttpServletRequest request) {
        if (!sign(request, openid)) {
            return err(401, "非法请求", "INVALID_SIGNATURE");
        }
        return feigeLetterService.subscribe(letterId, openid);
    }

    // -------------------- 信箱列表（来信/寄出） --------------------
    @ApiOperation("信箱列表(来信/寄出)")
    @GetMapping("/letter/list")
    @ResponseBody
    public Map<String, Object> letterList(@RequestParam(name = "openid", required = true) String openid,
                                          @RequestParam(name = "type", required = false, defaultValue = "inbox") String type,
                                          @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                                          @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return feigeLetterService.listLetters(openid, type, page, size);
    }

    // -------------------- 我的鸽子 --------------------
    @ApiOperation("我的鸽子")
    @GetMapping("/pigeon/mine")
    @ResponseBody
    public Map<String, Object> mine(@RequestParam(name = "openid", required = true) String openid) {
        FeigePigeon pigeon = feigePigeonService.getOrInitByOpenid(openid);
        Map<String, Object> data = new HashMap<>();
        data.put("name", pigeon.getName());
        data.put("level", pigeon.getLevel());
        data.put("exp", pigeon.getExp());
        data.put("expNext", pigeon.getLevel() * 100);
        data.put("speedKmh", pigeon.getSpeedKmh());
        data.put("stamina", pigeon.getStamina());
        data.put("deliveredCount", pigeon.getDeliveredCount());
        data.put("totalMileage", pigeon.getTotalMileage());
        data.put("farthestDistance", pigeon.getFarthestDistance());
        data.put("status", pigeon.getStatus());
        data.put("motto", "它已经认识回家的路了。");
        return ok(data);
    }

    // -------------------- 内部工具 --------------------

    /** 小程序签名校验：sign = md5(openid + sign-secret)，同登录接口返回的一致。 */
    private boolean sign(HttpServletRequest request, String openid) {
        String sign = request.getHeader("sign");
        if (StringUtils.isBlank(sign)) {
            return false;
        }
        return feigeSignUtil.verify(openid, sign);
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
