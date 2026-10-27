package net.prisma.pathtrace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/**
 * 最小 PNG 编码器（8-bit truecolor，无隔行）。
 *
 * 为什么手写而不用 javax.imageio：目标 JVM 是 iOS 真机上的 Pojav 定制 JRE，
 * java.desktop 模块的裁剪状态不可知——ImageIO 一旦缺席，S7 的"眼见为实"
 * 就只剩统计数字。本类只依赖 java.util.zip（核心库）+ 手写 CRC32/chunk，
 * 在任何完整 JRE 上行为一致。
 *
 * 布局: 签名 8B + IHDR(13B) + IDAT(zlib: 逐行 filter=0 的 RGB) + IEND(0B)。
 */
final class PngWriter {

    private PngWriter() {}

    static void write(Path file, int w, int h, int[] argb) throws IOException {
        // ---- 原始像素流：每行 1 字节 filter 类型(0) + w*3 字节 RGB ----
        ByteArrayOutputStream raw = new ByteArrayOutputStream(w * h * 3 + h);
        for (int y = 0; y < h; y++) {
            raw.write(0);
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                raw.write((p >> 16) & 0xFF);
                raw.write((p >> 8) & 0xFF);
                raw.write(p & 0xFF);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (w * h * 3 + h) / 2 + 128);
        out.write(0x89);
        out.write('P');
        out.write('N');
        out.write('G');
        out.write(0x0D);
        out.write(0x0A);
        out.write(0x1A);
        out.write(0x0A);

        // IHDR
        byte[] ihdr = new byte[13];
        be32(ihdr, 0, w);
        be32(ihdr, 4, h);
        ihdr[8] = 8;   // bit depth
        ihdr[9] = 2;   // color type: truecolor
        ihdr[10] = 0;  // compression: deflate
        ihdr[11] = 0;  // filter: adaptive (per-row byte)
        ihdr[12] = 0;  // interlace: none
        chunk(out, "IHDR", ihdr);

        // IDAT
        ByteArrayOutputStream z = new ByteArrayOutputStream(raw.size() / 2 + 64);
        DeflaterOutputStream dz = new DeflaterOutputStream(z);
        dz.write(raw.toByteArray());
        dz.close();
        chunk(out, "IDAT", z.toByteArray());

        // IEND
        chunk(out, "IEND", new byte[0]);

        try (OutputStream fos = Files.newOutputStream(file)) {
            fos.write(out.toByteArray());
            fos.flush();
        }
    }

    private static void chunk(ByteArrayOutputStream out, String tag, byte[] data)
            throws IOException {
        byte[] head = new byte[8];
        be32(head, 0, data.length);
        for (int i = 0; i < 4; i++) {
            head[4 + i] = (byte) tag.charAt(i);
        }
        out.write(head);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(head, 4, 4);
        crc.update(data);
        byte[] tail = new byte[4];
        be32(tail, 0, (int) crc.getValue());
        out.write(tail);
    }

    private static void be32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
