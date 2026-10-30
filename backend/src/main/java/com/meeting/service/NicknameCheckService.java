package com.meeting.service;

import com.meeting.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 昵称合规校验
 *
 * <p>三层校验，任意一层不通过即拒绝：</p>
 * <ol>
 *   <li><b>字符与长度</b>：2-20 字符，只允许汉字 / 字母 / 数字 / 下划线 / 连字符 / 点 / 空格</li>
 *   <li><b>敏感词</b>：加载 classpath:sensitive-words.txt，归一化后做子串匹配，
 *       能拦截「傻 逼」「傻*逼」「ＳＢ」这类加空格、插符号、改全角的绕过写法</li>
 *   <li><b>结构性规则</b>：灌水刷屏、冒充官方身份、广告引流、纯数字</li>
 * </ol>
 *
 * <p>词库外置在 resources 里，运营/法务补充「国家尊严、政治敏感、色情低俗、违禁品」等
 * 分类词表时只需往文件里追加，无需改代码、无需重新发版逻辑。</p>
 */
@Slf4j
@Service
public class NicknameCheckService {

    /** 与 DTO 保持一致，Service 层再兜一次（防止绕过 Controller 直接调用） */
    private static final int MIN_LEN = 2;
    private static final int MAX_LEN = 20;

    /** 允许的字符集：汉字（含扩展区）、字母、数字、下划线、连字符、点、空格 */
    private static final Pattern ALLOWED_CHARS =
            Pattern.compile("^[\\p{IsHan}a-zA-Z0-9_\\-. ]+$");

    /** 同一字符连续出现 5 次及以上，视为灌水刷屏 */
    private static final Pattern REPEAT_CHARS = Pattern.compile("(.)\\1{4,}");

    /** 纯数字昵称，容易被当作手机号/工号冒用 */
    private static final Pattern PURE_DIGITS = Pattern.compile("^\\d+$");

    /** 冒充官方 / 管理身份 */
    private static final String[] IMPERSONATE_WORDS = {
            "管理员", "官方", "客服", "系统", "超管", "审核员", "工作人员", "版主", "认证",
            "admin", "administrator", "system", "official", "service", "moderator"
    };

    /** 广告引流 */
    private static final String[] AD_WORDS = {
            "加微信", "加我微信", "微信号", "加qq", "加我qq", "公众号",
            "代购", "刷单", "兼职日结", "推广链接", "扫码加"
    };

    private static final Pattern URL_LIKE =
            Pattern.compile("(https?://|www\\.|\\.com|\\.cn|\\.net|\\.top)", Pattern.CASE_INSENSITIVE);

    /** 连续 6 位以上数字，多见于 QQ 号 / 手机号 / 微信号 */
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{6,}");

    private static final String WORD_FILE = "sensitive-words.txt";

    /** 归一化后的敏感词，启动时一次性加载 */
    private volatile List<String> sensitiveWords = Collections.emptyList();

    @PostConstruct
    public void loadWords() {
        List<String> words = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource(WORD_FILE);
        if (!resource.exists()) {
            log.warn("[昵称校验] 未找到敏感词库 {}，仅启用结构性规则校验", WORD_FILE);
            return;
        }
        try (InputStream in = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String w = line.trim();
                if (w.isEmpty() || w.startsWith("#")) {
                    continue;
                }
                String normalized = normalize(w);
                if (!normalized.isEmpty()) {
                    words.add(normalized);
                }
            }
        } catch (Exception e) {
            log.error("[昵称校验] 敏感词库加载失败，仅启用结构性规则校验", e);
        }
        this.sensitiveWords = Collections.unmodifiableList(words);
        log.info("[昵称校验] 敏感词库加载完成，共 {} 个词条", words.size());
    }

    /**
     * 校验并返回清洗后的昵称（已去掉首尾空格）。
     *
     * @throws BusinessException 不合规时抛出，message 可直接展示给用户
     */
    public String check(String raw) {
        String nickname = raw == null ? "" : raw.trim();

        // 1. 长度
        int len = nickname.codePointCount(0, nickname.length());
        if (len < MIN_LEN) {
            throw new BusinessException(400, "昵称至少 " + MIN_LEN + " 个字符");
        }
        if (len > MAX_LEN) {
            throw new BusinessException(400, "昵称最多 " + MAX_LEN + " 个字符");
        }

        // 2. 字符集
        if (!ALLOWED_CHARS.matcher(nickname).matches()) {
            throw new BusinessException(400, "昵称只能包含中文、字母、数字、下划线、连字符和空格");
        }

        // 3. 纯数字
        if (PURE_DIGITS.matcher(nickname).matches()) {
            throw new BusinessException(400, "昵称不能是纯数字");
        }

        // 4. 灌水刷屏
        if (REPEAT_CHARS.matcher(nickname).find()) {
            throw new BusinessException(400, "昵称不能包含大量重复字符");
        }

        String lower = nickname.toLowerCase();
        String compact = normalize(nickname);

        // 5. 冒充官方 / 管理身份
        for (String w : IMPERSONATE_WORDS) {
            if (lower.contains(w)) {
                throw new BusinessException(400, "昵称不能包含「" + w + "」等官方或管理身份字样");
            }
        }

        // 6. 广告引流
        for (String w : AD_WORDS) {
            if (compact.contains(normalize(w))) {
                throw new BusinessException(400, "昵称不能包含广告或引流信息");
            }
        }
        if (URL_LIKE.matcher(nickname).find() || LONG_DIGITS.matcher(nickname).find()) {
            throw new BusinessException(400, "昵称不能包含网址或联系方式");
        }

        // 7. 敏感词（归一化后匹配，防插空格的绕过写法）
        for (String w : sensitiveWords) {
            if (compact.contains(w)) {
                throw new BusinessException(400, "昵称包含不当词汇，请修改后重试");
            }
        }

        return nickname;
    }

    /**
     * 归一化：全角转半角 → 只保留汉字/字母/数字 → 转小写。
     *
     * <p>这样「傻 逼」「傻*逼」「Ｓ Ｂ」都归一化成同一个串，词库无需穷举变体。</p>
     */
    private String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = toHalfWidth(s.charAt(i));
            if (c == '\u3000' || c == ' ') {
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 全角字符转半角 */
    private char toHalfWidth(char c) {
        if (c == '\u3000') {
            return ' ';
        }
        if (c >= '\uFF01' && c <= '\uFF5E') {
            return (char) (c - 0xFEE0);
        }
        return c;
    }
}
