package com.meeting.service;

import com.meeting.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 本地文件存储（目前只用于头像）
 *
 * <p>落盘目录由 {@code meeting.upload.dir} 决定，访问前缀固定为 {@code /upload/}，
 * 由 {@code WebConfig#addResourceHandlers} 映射成静态资源。
 * 数据库里只存相对路径（如 {@code /upload/avatar/202610/xxx.jpg}），
 * 换服务器 / 换域名 / 换局域网 IP 时不用改数据，前端拼上 baseUrl 即可。</p>
 */
@Slf4j
@Service
public class FileStorageService {

    /** 对外访问前缀，与 WebConfig 的映射保持一致 */
    public static final String URL_PREFIX = "/upload";

    private static final Set<String> ALLOWED_EXT =
            new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "webp"));

    /** 大小上限兜底（KB），实际值从配置读 */
    private static final long MAX_SIZE_KB = 2048L;

    @Value("${meeting.upload.dir:upload}")
    private String uploadDir;

    @Value("${meeting.upload.max-avatar-size-kb:" + MAX_SIZE_KB + "}")
    private long maxAvatarSizeKb;

    /**
     * 保存头像，返回可访问的相对路径。
     *
     * @param file 小程序 wx.uploadFile 上传的文件
     */
    public String saveAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的图片");
        }
        if (file.getSize() > maxAvatarSizeKb * 1024) {
            throw new BusinessException(400, "图片不能超过 " + maxAvatarSizeKb / 1024 + "MB");
        }

        String originalName = file.getOriginalFilename();
        String ext = extensionOf(originalName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException(400, "仅支持 jpg / png / webp 格式的图片");
        }

        // 只信文件头，不信扩展名：防止把可执行文件改名成 .jpg 传上来
        if (!matchImageSignature(file, ext)) {
            throw new BusinessException(400, "图片文件已损坏或格式不正确");
        }

        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dir = baseDir().resolve("avatar").resolve(month);

        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[头像上传] 文件写入失败 dir={}", dir, e);
            throw new BusinessException("图片保存失败，请重试");
        }

        return URL_PREFIX + "/avatar/" + month + "/" + fileName;
    }

    /**
     * 删除旧头像文件（只处理本服务管理的 /upload/ 路径，外部 URL 直接忽略）。
     */
    public void deleteQuietly(String url) {
        if (url == null || !url.startsWith(URL_PREFIX + "/")) {
            return;
        }
        try {
            Path target = baseDir().resolve(url.substring((URL_PREFIX + "/").length())).normalize();
            // 防目录穿越：解析后必须仍在根目录下
            if (!target.startsWith(baseDir())) {
                return;
            }
            Files.deleteIfExists(target);
        } catch (Exception e) {
            log.warn("[头像上传] 旧头像删除失败 url={}", url, e);
        }
    }

    /** 上传根目录（绝对路径） */
    public Path baseDir() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    private String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return "";
        }
        return name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 校验图片魔数（文件头），比只看扩展名可靠。
     */
    private boolean matchImageSignature(MultipartFile file, String ext) {
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.read(head);
        } catch (IOException e) {
            return false;
        }
        if (read < 4) {
            return false;
        }
        switch (ext) {
            case "jpg":
            case "jpeg":
                // FF D8 FF
                return (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "png":
                // 89 50 4E 47
                return (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            case "webp":
                // "RIFF" .... "WEBP"
                return read >= 12
                        && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                        && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default:
                return false;
        }
    }
}
