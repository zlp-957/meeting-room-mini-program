package com.meeting.common;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 签到码生成 / 二维码内容拼装与解析。
 *
 * <p>二维码内容格式（41 字符）：</p>
 * <pre>
 * MR|1|3|B56A5C1A23AB221FD6A9D593DD6DCE7C
 *  │ │ │ └────── 32 位大写 HEX 签到码，存 meeting_room.sign_code
 *  │ │ └──────── 会议室 ID（仅用于一致性校验，绝不用于查库）
 *  │ └────────── 版本号，未来要加签名校验时升到 2，解析端按版本分支
 *  └──────────── 系统前缀，非本系统二维码在小程序侧即可拒绝，不必打后端
 * </pre>
 */
public final class SignCodeUtil {

    /** 二维码内容前缀，小程序侧用它做廉价本地校验 */
    public static final String PREFIX = "MR|";
    /** 当前版本号 */
    public static final int VERSION = 1;
    /** 签到码长度（大写 HEX） */
    public static final int CODE_LENGTH = 32;
    /** 扫码原文长度上限，防止超长串打后端 */
    public static final int MAX_CONTENT_LENGTH = 128;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SignCodeUtil() {
    }

    /**
     * 生成签到码：32 位大写 HEX，熵 128 bit。
     *
     * <p>必须用 {@link SecureRandom} 而不是 {@code UUID.randomUUID()}：虽然 UUID v4 也是随机的，
     * 但 {@code UUID.randomUUID()} 依赖 JVM 的 SecureRandom 实现细节，而数据库侧 MySQL 的
     * {@code UUID()} 是 v1（时间 + MAC，可推算），两边容易混淆。这里显式生成 HEX，
     * 语义最清楚，且与建表注释「32位大写HEX」严格一致。</p>
     */
    public static String generate() {
        byte[] bytes = new byte[CODE_LENGTH / 2];
        RANDOM.nextBytes(bytes);
        char[] out = new char[CODE_LENGTH];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** 拼装二维码内容，全系统只有这一处定义格式 */
    public static String buildContent(Long roomId, String signCode) {
        return PREFIX + VERSION + "|" + roomId + "|" + signCode;
    }

    /**
     * 解析扫码结果。格式不合法返回 {@code null}，由调用方给出统一文案。
     *
     * <p>只做格式层面的解析，<b>不做任何"这个码属于哪间房"的判断</b> ——
     * 权威数据永远以数据库按 signCode 查出的结果为准。</p>
     */
    public static Parsed parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty() || text.length() > MAX_CONTENT_LENGTH) {
            return null;
        }
        // 统一大写：数据库 collation 是 utf8mb4_general_ci（大小写不敏感），
        // 但字符串比较在 Java 侧是敏感的，这里先归一化，避免小写码被判成无效
        text = text.toUpperCase(Locale.ROOT);
        if (!text.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = text.split("\\|");
        if (parts.length != 4 || !"MR".equals(parts[0])) {
            return null;
        }
        int version;
        long roomId;
        try {
            version = Integer.parseInt(parts[1]);
            roomId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (version != VERSION || roomId <= 0) {
            return null;
        }
        String code = parts[3];
        if (code.length() != CODE_LENGTH || !isHex(code)) {
            return null;
        }
        return new Parsed(version, roomId, code);
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 解析结果 */
    public static final class Parsed {
        private final int version;
        private final long roomId;
        private final String code;

        Parsed(int version, long roomId, String code) {
            this.version = version;
            this.roomId = roomId;
            this.code = code;
        }

        public int getVersion() {
            return version;
        }

        public long getRoomId() {
            return roomId;
        }

        public String getCode() {
            return code;
        }
    }
}
