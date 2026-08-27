package com.an.feige.user.controller;

import com.an.feige.common.Result;
import com.an.feige.user.service.FgUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 飞鸽传书小程序 注册/登录（参照 search-smallapp /smallApp/getWeChatUniqueId1，去 union 绑定）。
 */
@Api(tags = "飞鸽传书-登录")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private FgUserService fgUserService;

    @ApiOperation("微信登录(code2session, 注册+登录一体)")
    @GetMapping("/wechat-login")
    public Result<Map<String, Object>> wechatLogin(@RequestParam(name = "jsCode", required = true) String jsCode,
                                                   @RequestParam(name = "grantType", required = false) String grantType) {
        return fgUserService.wechatLogin(jsCode, grantType);
    }

    @ApiOperation("更新资料(昵称/头像/手机号)")
    @PostMapping("/update-profile")
    public Result<Map<String, Object>> updateProfile(@RequestParam(name = "openid", required = true) String openid,
                                                     @RequestParam(name = "nickname", required = false) String nickname,
                                                     @RequestParam(name = "face", required = false) String face,
                                                     @RequestParam(name = "mobile", required = false) String mobile) {
        return fgUserService.updateProfile(openid, nickname, face, mobile);
    }
}
