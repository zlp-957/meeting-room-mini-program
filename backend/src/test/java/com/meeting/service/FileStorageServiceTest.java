package com.meeting.service;

import com.meeting.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 头像上传存储测试：落盘、格式白名单、魔数校验、旧图清理、目录穿越防护。
 */
class FileStorageServiceTest {

    /** 最小可识别 PNG 头：89 50 4E 47 0D 0A 1A 0A */
    private static final byte[] PNG_HEAD = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
    };

    /** JPEG 头：FF D8 FF */
    private static final byte[] JPEG_HEAD = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0
    };

    private FileStorageService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        service = new FileStorageService();
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "maxAvatarSizeKb", 2048L);
    }

    @Test
    @DisplayName("合法 PNG 能落盘，并返回 /upload/ 相对路径")
    void shouldSavePng() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", PNG_HEAD);

        String url = service.saveAvatar(file);

        assertTrue(url.startsWith("/upload/avatar/"), "返回路径应位于 /upload/avatar/ 下：" + url);
        assertTrue(url.endsWith(".png"), "扩展名应保留：" + url);
        assertTrue(Files.exists(resolve(url)), "文件应真实存在：" + resolve(url));
    }

    @Test
    @DisplayName("合法 JPEG 能落盘")
    void shouldSaveJpeg() {
        MockMultipartFile file = new MockMultipartFile("file", "b.jpg", "image/jpeg", JPEG_HEAD);

        String url = service.saveAvatar(file);

        assertTrue(url.endsWith(".jpg"));
        assertTrue(Files.exists(resolve(url)));
    }

    @Test
    @DisplayName("非图片扩展名被拒绝")
    void shouldRejectBadExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "evil.jsp", "image/png", PNG_HEAD);
        assertThrows(BusinessException.class, () -> service.saveAvatar(file));
    }

    @Test
    @DisplayName("扩展名是 jpg 但内容不是 JPEG 时被拒绝（防改后缀）")
    void shouldRejectFakeImage() {
        byte[] text = "<script>alert(1)</script>".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "fake.jpg", "image/jpeg", text);
        assertThrows(BusinessException.class, () -> service.saveAvatar(file));
    }

    @Test
    @DisplayName("空文件被拒绝")
    void shouldRejectEmpty() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);
        assertThrows(BusinessException.class, () -> service.saveAvatar(file));
    }

    @Test
    @DisplayName("超过大小上限被拒绝")
    void shouldRejectOversize() {
        byte[] big = new byte[3 * 1024 * 1024];
        System.arraycopy(PNG_HEAD, 0, big, 0, PNG_HEAD.length);
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);
        assertThrows(BusinessException.class, () -> service.saveAvatar(file));
    }

    @Test
    @DisplayName("删除旧头像会真的把文件删掉")
    void shouldDeleteOldAvatar() {
        String url = service.saveAvatar(new MockMultipartFile("file", "c.png", "image/png", PNG_HEAD));
        Path path = resolve(url);
        assertTrue(Files.exists(path));

        service.deleteQuietly(url);

        assertFalse(Files.exists(path));
    }

    @Test
    @DisplayName("外链地址与目录穿越路径不会被误删")
    void shouldIgnoreExternalAndTraversal() {
        assertDoesNotThrow(() -> service.deleteQuietly("https://cdn.example.com/a.png"));
        assertDoesNotThrow(() -> service.deleteQuietly("/upload/../../etc/passwd"));
    }

    private Path resolve(String url) {
        return service.baseDir().resolve(url.substring(FileStorageService.URL_PREFIX.length() + 1));
    }
}
