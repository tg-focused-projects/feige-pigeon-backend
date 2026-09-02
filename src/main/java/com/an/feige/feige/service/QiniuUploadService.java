package com.an.feige.feige.service;

import com.qiniu.util.Auth;
import com.qiniu.util.StringMap;
import com.qiniu.util.UrlSafeBase64;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 七牛云上传凭证服务（对齐 search 项目 MaterialController#uploadToken 实现）。
 *
 * <p>空间 mgif 不变，目录用 feige/；每个后缀生成独立 UUID 文件名与上传凭证；
 * 大小限制 1KB~100MB；mp4/mov 追加 avthumb/mp4 转码持久化处理。</p>
 */
@Service
public class QiniuUploadService {

    private static final Logger log = LoggerFactory.getLogger(QiniuUploadService.class);
    /** 最小文件 1KB。 */
    private static final long F_SIZE_MIN = 1024L;
    /** 最大文件 100MB。 */
    private static final long F_SIZE_LIMIT = 1024L * 1024L * 100L;

    @Value("${feige.qiniu.access-key:}")
    private String accessKey;

    @Value("${feige.qiniu.secret-key:}")
    private String secretKey;

    @Value("${feige.qiniu.bucket:mgif}")
    private String bucket;

    @Value("${feige.qiniu.prefix:feige}")
    private String prefix;

    @Value("${feige.qiniu.expire-seconds:3600}")
    private long expireSeconds;

    /**
     * 为每个后缀生成一个上传凭证。
     *
     * @param suffix 文件后缀数组（如 .jpg/.png/.mp4），可为空（默认 .jpg）
     * @return [{ token, fileName }]；凭证未配置时返回空列表
     */
    public List<Map<String, Object>> uploadTokens(String[] suffix) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (StringUtils.isBlank(accessKey) || StringUtils.isBlank(secretKey)) {
            log.warn("七牛凭证未配置（FG_QINIU_ACCESS_KEY/SECRET_KEY），无法生成上传凭证");
            return list;
        }
        String[] suffixes = (suffix == null || suffix.length == 0) ? new String[]{".jpg"} : suffix;
        Auth auth = Auth.create(accessKey, secretKey);
        for (String s : suffixes) {
            String ext = s;
            if (!ext.startsWith(".")) {
                ext = "." + ext;
            }
            String fileName = UUID.randomUUID().toString().replace("-", "") + ext;
            String key = prefix + "/" + fileName;
            StringMap putPolicy = new StringMap();
            // mp4/mov：追加转码持久化处理（对齐参考实现）
            if (ext.equalsIgnoreCase(".mp4") || ext.equalsIgnoreCase(".mov")) {
                String fop = "avthumb/mp4/vcodec/libx264";
                String saveEntry = String.format("%s:%s", bucket, key);
                String compress = String.format(fop + "|saveas/%s", UrlSafeBase64.encodeToString(saveEntry));
                String persistentOpfs = String.join(";", new String[]{compress});
                putPolicy.put("persistentOps", persistentOpfs);
                putPolicy.put("persistentPipeline", "budong");
            }
            putPolicy.put("fsizeMin", F_SIZE_MIN)
                    .put("fsizeLimit", F_SIZE_LIMIT);
            String token = auth.uploadToken(bucket, key, expireSeconds, putPolicy);
            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("fileName", key);
            list.add(data);
        }
        return list;
    }
}