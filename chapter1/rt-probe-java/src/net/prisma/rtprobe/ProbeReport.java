package net.prisma.rtprobe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 探针报告器：每一步落盘 + stdout 双写。
 * 增量写文件意味着即使探针中途崩溃，已完成的步骤也保存在报告里。
 */
public final class ProbeReport {

    private final Path file;
    private final List<String> verdicts = new ArrayList<>();

    public ProbeReport() {
        Path dir;
        try {
            dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        } catch (Throwable t) {
            dir = Paths.get(".");
        }
        this.file = dir.resolve("rt_probe_report.txt");
        try {
            Files.deleteIfExists(file);
        } catch (Throwable ignored) {
        }
        line("==============================================================");
        line("Vulkan 硬件光追能力探针 (rt-probe 1.1)  —— Radiance 移植第一章");
        line("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        line("报告文件: " + file);
        line("==============================================================");
    }

    public Path getFile() {
        return file;
    }

    /** 普通信息行 */
    public void info(String msg) {
        line("[INFO ] " + msg);
    }

    /** 阶段标题 */
    public void stage(String msg) {
        line("");
        line("---- " + msg + " ----");
    }

    /** 通过项 */
    public void ok(String msg) {
        line("[PASS ] " + msg);
    }

    /** 失败项（可带异常） */
    public void fail(String msg, Throwable t) {
        line("[FAIL ] " + msg);
        if (t != null) {
            line("        异常: " + t.getClass().getName() + ": " + t.getMessage());
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) {
                line("          at " + st[i]);
            }
        }
    }

    /** 判定候选（最终汇总用） */
    public void candidateVerdict(String code, String text) {
        verdicts.add(code + " | " + text);
    }

    /** 最终判定：探针结束前调用，写入汇总块 */
    public void finish(String code, String text) {
        line("");
        line("==============================================================");
        line("最终判定: " + code);
        line(text);
        if (!verdicts.isEmpty()) {
            line("");
            line("本次运行涉及的判定规则:");
            for (String v : verdicts) {
                line("  - " + v);
            }
        }
        line("==============================================================");
        line("请把整份 rt_probe_report.txt 回传，即可据此决定下一章路线。");
    }

    private void line(String s) {
        System.out.println("[RTProbe] " + s);
        try {
            Files.write(file,
                    (s + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 文件系统不可写时至少 stdout 有完整日志
        }
    }
}
