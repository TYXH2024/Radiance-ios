package net.prisma.pathtrace;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 增量落盘报告（metal-rt-probe 同款写入策略：每行即 flush —— 原生侧
 * 崩溃后已写内容仍是有效证据）。
 */
final class ProbeReport {

    private final Path file;
    private final Object lock = new Object();

    ProbeReport(String fileName) {
        String dir = System.getProperty("user.dir", ".");
        this.file = Paths.get(dir, fileName);
    }

    String getFile() {
        return file.toString();
    }

    void header(String title) {
        synchronized (lock) {
            try (OutputStream out = Files.newOutputStream(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                write(out, "==============================================================");
                write(out, title + " —— Radiance 移植第二章 2.2");
                write(out, "时间: " + new java.util.Date());
                write(out, "报告文件: " + file);
                write(out, "==============================================================");
                write(out, "");
            } catch (IOException ignored) {
            }
        }
    }

    void stage(String name) {
        synchronized (lock) {
            try (OutputStream out = Files.newOutputStream(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                write(out, "");
                write(out, "---- " + name + " ----");
            } catch (IOException ignored) {
            }
        }
    }

    void info(String line) {
        line("[INFO ] " + line);
    }

    void pass(String line) {
        line("[PASS ] " + line);
    }

    void fail(String line) {
        line("[FAIL ] " + line);
    }

    void fail(String line, Throwable t) {
        line("[FAIL ] " + line + " → " + t);
        if (t != null) {
            for (StackTraceElement el : t.getStackTrace()) {
                line("        at " + el);
            }
        }
    }

    void raw(String line) {
        line(line);
    }

    void finish(String verdict, String message) {
        line("");
        line("==============================================================");
        line("最终判定: " + verdict);
        line(message);
        line("==============================================================");
    }

    private void line(String line) {
        synchronized (lock) {
            try (OutputStream out = Files.newOutputStream(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                write(out, line);
            } catch (IOException ignored) {
            }
        }
        System.out.println("[Pathtrace] " + line);
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write((s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
    }
}
