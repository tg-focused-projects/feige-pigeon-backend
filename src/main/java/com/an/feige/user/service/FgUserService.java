package com.an.feige.user.service;

import com.an.feige.common.Result;
import com.an.feige.common.SignUtil;
import com.an.feige.common.WeChatClient;
import com.an.feige.feige.mapper.FeigeLetterMapper;
import com.an.feige.user.entity.FgUser;
import com.an.feige.user.mapper.FgUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 飞鸽传书小程序注册/登录（参照 search-smallapp 的 /getWeChatUniqueId1，去掉 union 绑定）。
 *
 * <p>流程：wx.login 的 code → jscode2session 换 openid/session_key → 按 openid 查/建 fg_user →
 * 返回 {openid, userId, sessionKey, nickname, face, sign}。不做 unionid 绑定。</p>
 */
@Service
public class FgUserService {

    private static final Logger log = LoggerFactory.getLogger(FgUserService.class);

    @Resource
    private FgUserMapper fgUserMapper;

    @Resource
    private WeChatClient weChatClient;

    @Resource
    private SignUtil signUtil;

    @Resource
    private FeigeLetterMapper feigeLetterMapper;

    /** 微信登录（注册+登录一体）。 */
    public Result<Map<String, Object>> wechatLogin(String jsCode, String grantType) {
        if (jsCode == null || jsCode.isEmpty()) {
            return Result.err(400, "缺少 jsCode", "INVALID_ARGUMENT");
        }
        Map<String, Object> wx = weChatClient.jscode2session(jsCode);
        String openid = (String) wx.get("openid");
        String sessionKey = (String) wx.get("session_key");
        if (openid == null || sessionKey == null) {
            Integer errcode = (Integer) wx.get("errcode");
            log.warn("微信登录失败 errcode={} errmsg={}", errcode, wx.get("errmsg"));
            return Result.err(400, "微信登录失败", "WECHAT_LOGIN_FAILED");
        }

        Date now = new Date();
        FgUser user = fgUserMapper.selectByOpenid(openid);
        if (user == null) {
            // 首次登录即注册
            user = new FgUser();
            user.setOpenid(openid);
            user.setSessionKey(sessionKey);
            user.setAppType(1);
            user.setStatus(FgUser.STATUS_NORMAL);
            user.setNickname("飞鸽用户" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            user.setFace("");
            user.setCreateAt(now);
            user.setUpdateAt(now);
            fgUserMapper.insertSelective(user);
            log.info("飞鸽小程序注册成功 openid={} userId={}", openid, user.getId());
        } else {
            // 更新 session_key（每次登录可能变化）
            if (!Objects.equals(sessionKey, user.getSessionKey())) {
                fgUserMapper.updateSessionKey(user.getId(), sessionKey, now);
                user.setSessionKey(sessionKey);
            }
            if (user.getStatus() != null && user.getStatus() != FgUser.STATUS_NORMAL) {
                return Result.err(403, "账号不可用", "ACCOUNT_DISABLED");
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("openid", openid);
        data.put("userId", user.getId());
        data.put("sessionKey", sessionKey);
        data.put("nickname", user.getNickname());
        data.put("face", user.getFace());
        data.put("sign", signUtil.sign(openid));
        // 是否寄过信：0-未寄过, 1-已寄过（查询 feige_letter 发件记录）
        data.put("isSendLetter", feigeLetterMapper.countSentByOpenid(openid) > 0 ? 1 : 0);
        return Result.ok(data);
    }

    /** 更新资料（昵称/头像/手机号）。 */
    public Result<Map<String, Object>> updateProfile(String openid, String nickname, String face, String mobile) {
        FgUser user = fgUserMapper.selectByOpenid(openid);
        if (user == null) {
            return Result.err(404, "用户不存在", "USER_NOT_FOUND");
        }
        fgUserMapper.updateProfile(user.getId(), nickname, face, mobile, new Date());
        Map<String, Object> data = new HashMap<>();
        data.put("openid", openid);
        data.put("userId", user.getId());
        data.put("nickname", nickname);
        data.put("face", face);
        data.put("mobile", mobile);
        return Result.ok(data);
    }
}
