package net.prisma.metalprobe;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 增量落盘报告（rt-probe 同款写入策略：每行即 flush —— 原生侧崩溃后
 * 已写内容仍是有效证据）。
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
                write(out, title + " —— Radiance 移植第二章");
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

    private void line(String line) {
        synchronized (lock) {
            try (OutputStream out = Files.newOutputStream(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                write(out, line);
            } catch (IOException ignored) {
            }
        }
        System.out.println("[MetalProbe] " + line);
    }

    void fail(String line, Throwable t) {
        line("[FAIL ] " + line);
        if (t != null) {
            line("        " + t);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < st.length && i < 12; i++) {
                line("          at " + st[i]);
            }
        }
    }

    void finish(String verdict, String note) {
        synchronized (lock) {
            try (OutputStream out = Files.newOutputStream(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                write(out, "");
                write(out, "==============================================================");
                write(out, "最终判定: " + verdict);
                if (note != null && !note.isEmpty()) {
                    write(out, note);
                }
                write(out, "==============================================================");
                write(out, "请把整份 " + file.getFileName() + " 回传（连同 latestlog.txt）。");
            } catch (IOException ignored) {
            }
        }
        System.out.println("[MetalProbe] 最终判定: " + verdict);
    }

    private static void write(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
    }
}
