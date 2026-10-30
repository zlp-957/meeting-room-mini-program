package com.meeting.common;

import org.springframework.util.DigestUtils;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码工具
 *
 * <p>P2-3：原实现使用无盐 MD5，容易被彩虹表破解。</p>
 *
 * <p>这里改为 JDK 自带的 PBKDF2-HMAC-SHA256（无需引入额外依赖），格式：
 * {@code pbkdf2$迭代次数$盐(Base64)$哈希(Base64)}。同时保留对历史 MD5 值的校验，
 * 老密码在登录成功后会被自动升级为 PBKDF2。</p>
 */
public final class PasswordUtil {

    private static final String PREFIX = "pbkdf2$";
    private static final int ITERATIONS = 120000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private PasswordUtil() {
    }

    /** 生成 PBKDF2 哈希 */
    public static String encode(String raw) {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(raw, salt, ITERATIONS, KEY_BITS);
        Base64.Encoder encoder = Base64.getEncoder();
        return PREFIX + ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    /**
     * 校验密码，兼容历史 MD5
     */
    public static boolean matches(String raw, String stored) {
        if (raw == null || stored == null || stored.isEmpty()) {
            return false;
        }
        if (stored.startsWith(PREFIX)) {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                byte[] actual = pbkdf2(raw, salt, iterations, expected.length * 8);
                return MessageDigest.isEqual(expected, actual);
            } catch (RuntimeException e) {
                return false;
            }
        }
        // 历史数据：无盐 MD5
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8)).equalsIgnoreCase(stored);
    }

    /** 是否为历史 MD5 存储（登录成功后需要升级） */
    public static boolean isLegacy(String stored) {
        return stored != null && !stored.startsWith(PREFIX);
    }

    private static byte[] pbkdf2(String raw, byte[] salt, int iterations, int keyBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, keyBits);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希计算失败", e);
        }
    }
}
