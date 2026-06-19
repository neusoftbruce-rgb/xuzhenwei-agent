package com.xuzhenwei.agent.api;

import com.xuzhenwei.agent.auth.JwtUtil;
import com.xuzhenwei.agent.auth.User;
import com.xuzhenwei.agent.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final HttpClient httpClient;

    @Value("${wechat.appid:}")
    private String appId;

    @Value("${wechat.secret:}")
    private String secret;

    public AuthController(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @PostMapping("/wechat-login")
    public ResponseEntity<Map<String, Object>> wechatLogin(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "missing_code", "message", "缺少登录凭证"));
        }

        try {
            // 1. 调用微信接口 code → openid
            String url = "https://api.weixin.qq.com/sns/jscode2session"
                    + "?appid=" + appId + "&secret=" + secret
                    + "&js_code=" + code + "&grant_type=authorization_code";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            // 解析微信返回
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var wxResp = mapper.readTree(response.body());

            if (wxResp.has("errcode") && wxResp.get("errcode").asInt() != 0) {
                log.error("微信登录失败: {}", wxResp);
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "wechat_error",
                                "message", wxResp.get("errmsg").asText()));
            }

            String openid = wxResp.get("openid").asText();
            String unionid = wxResp.has("unionid") ? wxResp.get("unionid").asText() : null;

            // 2. 查找或创建用户
            User user = userRepository.findByOpenid(openid).orElseGet(() -> {
                User newUser = new User(openid);
                newUser.setUnionid(unionid);
                return userRepository.save(newUser);
            });

            // 3. 更新最后登录时间
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);

            // 4. 生成JWT
            String token = jwtUtil.generateToken(openid, user.getId());

            log.info("用户登录成功: openid={}, userId={}", openid, user.getId());

            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "user", Map.of(
                            "id", user.getId(),
                            "openid", user.getOpenid(),
                            "nickname", user.getNickname() != null ? user.getNickname() : "",
                            "avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : ""
                    )
            ));

        } catch (Exception e) {
            log.error("登录异常", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "server_error", "message", e.getMessage()));
        }
    }

    /** 开发测试登录 - 无需微信code */
    @PostMapping("/test-login")
    public ResponseEntity<Map<String, Object>> testLogin() {
        String testOpenid = "test_openid_" + System.currentTimeMillis() % 10000;
        User user = userRepository.findByOpenid(testOpenid).orElseGet(() -> {
            User u = new User(testOpenid);
            u.setNickname("测试用户");
            return userRepository.save(u);
        });
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        String token = jwtUtil.generateToken(testOpenid, user.getId());
        log.info("测试登录: userId={}", user.getId());
        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", Map.of("id", user.getId(), "openid", user.getOpenid(),
                        "nickname", user.getNickname() != null ? user.getNickname() : "",
                        "avatarUrl", "")
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        String token = auth.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_token"));
        }
        String openid = jwtUtil.getOpenid(token);
        var user = userRepository.findByOpenid(openid);
        if (user.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "user_not_found"));
        }
        var u = user.get();
        return ResponseEntity.ok(Map.of(
                "id", u.getId(),
                "openid", u.getOpenid(),
                "nickname", u.getNickname() != null ? u.getNickname() : "",
                "avatarUrl", u.getAvatarUrl() != null ? u.getAvatarUrl() : ""
        ));
    }
}
