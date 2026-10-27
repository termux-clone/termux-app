import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Hexpatch files built for the official Termux app so that they can be used by the "com.termuj"
 * app. See https://github.com/termux/termux-app/issues/2734
 *
 * Both package names have the same length, so plain files are patched in-place. As suggested
 * in the issue, compressed archives (zip/apk/jar, gzip, xz, bzip2) are looked into, recursively,
 * and checksummed formats (zip and gzip CRCs, tar header checksums, dex checksum and signature)
 * are fixed up. Archives are only rewritten if their content changed.
 *
 * Reads NUL separated file paths from stdin, patches them in-place and prints the number of
 * changed files. Runs both on Android (app_process, by the apt hook) and on a JVM (in CI, for
 * the bootstrap), so only APIs available since Android 7 are used. xz and bzip2 are handled by
 * running the "xz" and "bzip2" commands.
 */
public class TermujHexpatch {

    // Built at runtime, as the compiler folds constant concatenations, so that the dexed helper
    // itself never contains the old name and is not matched by a hexpatch.
    private static final byte[] OLD = concat("com.termu", "x").getBytes();
    private static final byte[] NEW = "com.termuj".getBytes();
    private static final byte[] OLD_PATH = concat("com/termu", "x").getBytes();
    private static final byte[] NEW_PATH = "com/termuj".getBytes();

    private static String concat(String a, String b) {
        return a.concat(b);
    }

    private static final int MAX_DEPTH = 8;

    private static String currentFile;

    public static void main(String[] args) throws Exception {
        int changed = 0;
        try {
            for (String path : readPaths(System.in)) {
                currentFile = path;
                if (patchFile(new File(path))) changed++;
            }
        } catch (Exception e) {
            System.err.println("termuj-hexpatch: error: " + currentFile + ": " + e);
            System.exit(1);
        }
        System.out.println(changed);
    }

    private static List<String> readPaths(InputStream in) throws IOException {
        List<String> paths = new ArrayList<>();
        ByteArrayOutputStream cur = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) != -1) {
            for (int i = 0; i < r; i++) {
                if (buf[i] == 0) {
                    if (cur.size() > 0) paths.add(cur.toString("UTF-8"));
                    cur.reset();
                } else {
                    cur.write(buf[i]);
                }
            }
        }
        if (cur.size() > 0) paths.add(cur.toString("UTF-8"));
        return paths;
    }

    private static void warn(String msg) {
        System.err.println("termuj-hexpatch: warning: " + currentFile + ": " + msg);
    }

    // ---------------------------------------------------------------- files

    private static boolean patchFile(File file) throws IOException {
        // Packages may contain read-only files, restore their mode afterwards.
        boolean readOnly = !file.canWrite();
        if (readOnly && !file.setWritable(true, true)) throw new IOException("Failed to make writable");
        try {
            return patchWritableFile(file);
        } finally {
            if (readOnly) file.setWritable(false, true);
        }
    }

    private static boolean patchWritableFile(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            byte[] head = new byte[263];
            int n = readFully(raf, head);
            if (detect(head, n) == Type.PLAIN) return patchPlainFile(raf);

            if (raf.length() > Integer.MAX_VALUE - 16) throw new IOException("File too large: " + file);
            byte[] data = new byte[(int) raf.length()];
            raf.seek(0);
            readFully(raf, data);
            byte[] patched = patchBytes(data, 0);
            if (patched == data) return false;
            raf.seek(0);
            raf.write(patched);
            raf.setLength(patched.length);
            return true;
        }
    }

    /** Same-length replacement directly in the file, without loading it into memory. */
    private static boolean patchPlainFile(RandomAccessFile raf) throws IOException {
        byte[] buf = new byte[1 << 20];
        long pos = 0;
        boolean changed = false;
        while (true) {
            raf.seek(pos);
            int n = readFully(raf, buf);
            if (n < OLD.length) break;
            for (int i = indexOf(buf, n, OLD, 0); i >= 0; i = indexOf(buf, n, OLD, i + OLD.length)) {
                raf.seek(pos + i);
                raf.write(NEW);
                changed = true;
            }
            if (n < buf.length) break;
            pos += n - (OLD.length - 1);
        }
        return changed;
    }

    private static int readFully(RandomAccessFile raf, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = raf.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    // ---------------------------------------------------------------- detection

    private enum Type { PLAIN, ZIP, GZIP, XZ, BZIP2, TAR, DEX }

    private static Type detect(byte[] d, int n) {
        if (n >= 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4) return Type.ZIP;
        if (n >= 3 && (d[0] & 0xff) == 0x1f && (d[1] & 0xff) == 0x8b && d[2] == 8) return Type.GZIP;
        if (n >= 6 && (d[0] & 0xff) == 0xfd && d[1] == '7' && d[2] == 'z' && d[3] == 'X' && d[4] == 'Z' && d[5] == 0)
            return Type.XZ;
        if (n >= 10 && d[0] == 'B' && d[1] == 'Z' && d[2] == 'h' && d[3] >= '1' && d[3] <= '9' &&
            (d[4] & 0xff) == 0x31 && (d[5] & 0xff) == 0x41 && (d[6] & 0xff) == 0x59 && (d[7] & 0xff) == 0x26)
            return Type.BZIP2;
        if (n >= 8 && d[0] == 'd' && d[1] == 'e' && d[2] == 'x' && d[3] == '\n' && d[7] == 0) return Type.DEX;
        if (n >= 263 && d[257] == 'u' && d[258] == 's' && d[259] == 't' && d[260] == 'a' && d[261] == 'r') return Type.TAR;
        return Type.PLAIN;
    }

    /** Returns the patched data, or the same array if nothing was changed. */
    private static byte[] patchBytes(byte[] data, int depth) {
        Type type = depth > MAX_DEPTH ? Type.PLAIN : detect(data, data.length);
        try {
            switch (type) {
                case ZIP: return patchZip(data, depth);
                case GZIP:
                case XZ:
                case BZIP2: return patchCompressed(data, depth, type);
                case TAR: return patchTar(data, depth);
                case DEX: return patchDex(data);
                default: return replace(data, OLD, NEW);
            }
        } catch (Exception e) {
            warn("failed to patch " + type + " content (" + e + "), patching it as plain data");
            return replace(data, OLD, NEW);
        }
    }

    // ---------------------------------------------------------------- plain bytes

    private static int indexOf(byte[] data, int len, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= len - pattern.length; i++) {
            if (data[i] != pattern[0]) continue;
            for (int j = 1; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] replace(byte[] data, byte[] from, byte[] to) {
        int i = indexOf(data, data.length, from, 0);
        if (i < 0) return data;
        byte[] out = data.clone();
        for (; i >= 0; i = indexOf(out, out.length, from, i + from.length)) {
            System.arraycopy(to, 0, out, i, to.length);
        }
        return out;
    }

    private static String replaceName(String name) {
        return name.replace(new String(OLD), new String(NEW)).replace(new String(OLD_PATH), new String(NEW_PATH));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- gzip, xz, bzip2

    private static byte[] patchCompressed(byte[] data, int depth, Type type) throws Exception {
        byte[] raw = decompress(data, type);
        byte[] patched = patchBytes(raw, depth + 1);
        if (patched == raw) return data;
        return compress(patched, type);
    }

    private static byte[] decompress(byte[] data, Type type) throws Exception {
        switch (type) {
            case GZIP:
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
                    return readAll(in);
                }
            case XZ: return run(data, "xz", "-dc");
            default: return run(data, "bzip2", "-dc");
        }
    }

    private static byte[] compress(byte[] data, Type type) throws Exception {
        switch (type) {
            case GZIP:
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (GZIPOutputStream gz = new GZIPOutputStream(out) {{ def.setLevel(Deflater.BEST_COMPRESSION); }}) {
                    gz.write(data);
                }
                return out.toByteArray();
            case XZ: return run(data, "xz", "-c");
            default: return run(data, "bzip2", "-c");
        }
    }

    private static byte[] run(final byte[] input, String... cmd) throws Exception {
        final Process p = new ProcessBuilder(cmd).start();
        final IOException[] writeError = new IOException[1];
        Thread writer = new Thread() {
            @Override
            public void run() {
                try (OutputStream os = p.getOutputStream()) {
                    os.write(input);
                } catch (IOException e) {
                    writeError[0] = e;
                }
            }
        };
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread errReader = new Thread() {
            @Override
            public void run() {
                try {
                    stderr.write(readAll(p.getErrorStream()));
                } catch (IOException ignored) {
                }
            }
        };
        writer.start();
        errReader.start();
        byte[] out = readAll(p.getInputStream());
        writer.join();
        errReader.join();
        int exit = p.waitFor();
        if (exit != 0) throw new IOException(cmd[0] + " exited with " + exit + ": " + stderr.toString().trim());
        if (writeError[0] != null) throw writeError[0];
        return out;
    }

    // ---------------------------------------------------------------- zip, apk, jar

    private static boolean isSignatureFile(String name) {
        String n = name.toUpperCase();
        return n.startsWith("META-INF/") && n.indexOf('/', 9) < 0 &&
            (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"));
    }

    private static byte[] patchZip(byte[] data, int depth) throws IOException {
        List<ZipEntry> entries = new ArrayList<>();
        List<byte[]> contents = new ArrayList<>();
        boolean changed = false;
        boolean signed = false;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] content = readAll(zin);
                byte[] patched = e.isDirectory() ? content : patchBytes(content, depth + 1);
                if (patched != content || !replaceName(e.getName()).equals(e.getName())) changed = true;
                if (isSignatureFile(e.getName())) signed = true;
                entries.add(e);
                contents.add(patched);
            }
        }
        if (entries.isEmpty()) throw new IOException("No zip entries found");
        if (!changed) return data;
        // The apk signature is not checked when loading classes with app_process or a jar on
        // the classpath, but a jar signature that does not match the content would be rejected.
        if (signed) warn("removing the now invalid jar signature of a patched archive");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(bos)) {
            for (int i = 0; i < entries.size(); i++) {
                ZipEntry e = entries.get(i);
                if (signed && isSignatureFile(e.getName())) continue;
                byte[] content = contents.get(i);
                ZipEntry ne = new ZipEntry(replaceName(e.getName()));
                ne.setTime(e.getTime());
                if (e.getExtra() != null) ne.setExtra(e.getExtra());
                if (e.getComment() != null) ne.setComment(e.getComment());
                ne.setMethod(e.getMethod());
                if (e.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(content);
                    ne.setSize(content.length);
                    ne.setCompressedSize(content.length);
                    ne.setCrc(crc.getValue());
                }
                zout.putNextEntry(ne);
                zout.write(content);
                zout.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    // ---------------------------------------------------------------- tar

    private static long parseTarNumber(byte[] h, int off, int len) throws IOException {
        if ((h[off] & 0x80) != 0) throw new IOException("Unsupported base-256 tar number");
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = h[i] & 0xff;
            if (c == 0 || c == ' ') {
                if (v != 0) break;
                continue;
            }
            if (c < '0' || c > '7') throw new IOException("Invalid tar number");
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private static boolean allZero(byte[] d, int off, int len) {
        for (int i = off; i < off + len; i++) if (d[i] != 0) return false;
        return true;
    }

    private static byte[] patchTar(byte[] data, int depth) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        boolean changed = false;
        int pos = 0;
        while (pos + 512 <= data.length && !allZero(data, pos, 512)) {
            byte[] header = new byte[512];
            System.arraycopy(data, pos, header, 0, 512);
            long size = parseTarNumber(header, 124, 12);
            if (pos + 512 + size > data.length) throw new IOException("Truncated tar");
            byte[] content = new byte[(int) size];
            System.arraycopy(data, pos + 512, content, 0, (int) size);
            byte type = header[156];

            // Regular files may be archives themselves, other members (long names, pax headers)
            // only contain text that is patched in-place.
            byte[] newContent = (type == '0' || type == 0 || type == '7') ? patchBytes(content, depth + 1) : replace(content, OLD, NEW);
            byte[] newHeader = replace(header, OLD, NEW);
            if (newContent != content || newHeader != header) {
                changed = true;
                newHeader = newHeader.clone();
                if (newContent.length != size) {
                    String s = Long.toOctalString(newContent.length);
                    if (s.length() > 11) throw new IOException("Patched tar member too large");
                    byte[] field = String.format("%11s", s).replace(' ', '0').getBytes();
                    System.arraycopy(field, 0, newHeader, 124, 11);
                    newHeader[135] = 0;
                }
                for (int i = 148; i < 156; i++) newHeader[i] = ' ';
                long sum = 0;
                for (byte b : newHeader) sum += b & 0xff;
                byte[] chk = String.format("%06o", sum).getBytes();
                System.arraycopy(chk, 0, newHeader, 148, 6);
                newHeader[154] = 0;
                newHeader[155] = ' ';
            }
            out.write(newHeader);
            out.write(newContent);
            out.write(new byte[(int) ((newContent.length + 511L) / 512 * 512 - newContent.length)]);
            pos += 512 + (int) ((size + 511) / 512 * 512);
        }
        if (!changed) return data;
        if (pos < data.length) out.write(data, pos, data.length - pos);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- dex

    private static byte[] patchDex(byte[] data) throws Exception {
        if (data.length < 0x70) throw new IOException("Truncated dex");
        byte[] out = replace(replace(data, OLD, NEW), OLD_PATH, NEW_PATH);
        if (out == data) return data;

        // Header: checksum (Adler-32 of everything after it) and signature (SHA-1 of everything after it).
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(out, 32, out.length - 32);
        System.arraycopy(sha1.digest(), 0, out, 12, 20);
        Adler32 adler = new Adler32();
        adler.update(out, 12, out.length - 12);
        long a = adler.getValue();
        for (int i = 0; i < 4; i++) out[8 + i] = (byte) (a >>> (8 * i));

        checkDexStringOrder(out);
        return out;
    }

    private static int u32(byte[] d, int off) {
        return (d[off] & 0xff) | (d[off + 1] & 0xff) << 8 | (d[off + 2] & 0xff) << 16 | (d[off + 3] & 0xff) << 24;
    }

    /** The replacement could in theory break the required sort order of the dex string table. */
    private static void checkDexStringOrder(byte[] d) {
        try {
            int count = u32(d, 0x38);
            int idsOff = u32(d, 0x3c);
            String prev = null;
            for (int i = 0; i < count; i++) {
                int p = u32(d, idsOff + 4 * i);
                while ((d[p] & 0x80) != 0) p++;
                p++;
                int end = p;
                while (d[end] != 0) end++;
                if (end - p > 65535) {
                    prev = null;
                    continue;
                }
                byte[] utf = new byte[end - p + 2];
                utf[0] = (byte) ((end - p) >> 8);
                utf[1] = (byte) (end - p);
                System.arraycopy(d, p, utf, 2, end - p);
                String s = new DataInputStream(new ByteArrayInputStream(utf)).readUTF();
                if (prev != null && prev.compareTo(s) >= 0) {
                    warn("patched dex string table is no longer sorted, the dex may fail to load");
                    return;
                }
                prev = s;
            }
        } catch (Exception e) {
            warn("failed to verify the patched dex string table (" + e + ")");
        }
    }
}
