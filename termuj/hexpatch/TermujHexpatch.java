import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
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
 * Everything is streamed through scratch files (in $TMPDIR), archive members one at a time, so
 * that the memory use does not depend on the size of the files, as app_process only gets a
 * small Java heap.
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

    private static final byte[] PATCH_BUF = new byte[1 << 20];
    private static final byte[] COPY_BUF = new byte[1 << 16];

    private static String currentFile;
    private static File workDir;

    public static void main(String[] args) {
        int changed = 0;
        int exit = 0;
        try {
            workDir = createWorkDir();
            for (String path : readPaths(System.in)) {
                currentFile = path;
                if (patchFile(new File(path))) changed++;
            }
        } catch (Throwable t) {
            // An uncaught exception gets app_process killed without any message.
            System.err.println("termuj-hexpatch: error: " + currentFile + ": " + t);
            exit = 1;
        } finally {
            deleteRecursively(workDir);
        }
        if (exit != 0) System.exit(exit);
        System.out.println(changed);
    }

    private static File createWorkDir() throws IOException {
        // java.io.tmpdir is /tmp under app_process, which does not exist on Android.
        String tmp = System.getenv("TMPDIR");
        if (tmp == null || tmp.isEmpty()) tmp = System.getProperty("java.io.tmpdir");
        File dir = File.createTempFile("termuj-hexpatch.", "", new File(tmp));
        if (!dir.delete() || !dir.mkdir()) throw new IOException("Failed to create " + dir);
        return dir;
    }

    private static void deleteRecursively(File f) {
        if (f == null) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }

    /** Scratch files are reused for every member at the same depth. */
    private static File scratch(int depth, String role) {
        return new File(workDir, depth + "-" + role);
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
            return patchPath(file, 0);
        } finally {
            if (readOnly) file.setWritable(false, true);
        }
    }

    /** Patches the file in-place (its length may change), returns whether it was changed. */
    private static boolean patchPath(File file, int depth) throws IOException {
        Type type = depth > MAX_DEPTH ? Type.PLAIN : detect(file);
        if (type == Type.PLAIN) return replaceInFile(file, OLD, NEW);
        if (type == Type.DEX) return patchDex(file);

        File patched;
        try {
            switch (type) {
                case ZIP: patched = patchZip(file, depth); break;
                case TAR: patched = patchTar(file, depth); break;
                default: patched = patchCompressed(file, depth, type); break;
            }
        } catch (Exception e) {
            warn("failed to patch " + type + " content (" + e + "), patching it as plain data");
            return replaceInFile(file, OLD, NEW);
        }
        if (patched == null) return false;
        // Copied rather than renamed, to keep the mode of the file.
        try (InputStream in = new FileInputStream(patched); OutputStream out = new FileOutputStream(file)) {
            copy(in, out);
        }
        return true;
    }

    /** Same-length replacement directly in the file, without loading it into memory. */
    private static boolean replaceInFile(File file, byte[] from, byte[] to) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            byte[] buf = PATCH_BUF;
            long pos = 0;
            boolean changed = false;
            while (true) {
                raf.seek(pos);
                int n = readFully(raf, buf);
                if (n < from.length) break;
                for (int i = indexOf(buf, n, from, 0); i >= 0; i = indexOf(buf, n, from, i + from.length)) {
                    raf.seek(pos + i);
                    raf.write(to);
                    changed = true;
                }
                if (n < buf.length) break;
                pos += n - (from.length - 1);
            }
            return changed;
        }
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

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = COPY_BUF;
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
    }

    private static void copyToFile(InputStream in, File file) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 65536)) {
            copy(in, out);
        }
    }

    private static void copyFromFile(File file, OutputStream out) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            copy(in, out);
        }
    }

    private static InputStream openIn(File file) throws IOException {
        return new BufferedInputStream(new FileInputStream(file), 65536);
    }

    private static OutputStream openOut(File file) throws IOException {
        return new BufferedOutputStream(new FileOutputStream(file), 65536);
    }

    // ---------------------------------------------------------------- detection

    private enum Type { PLAIN, ZIP, GZIP, XZ, BZIP2, TAR, DEX }

    private static Type detect(File file) throws IOException {
        byte[] head = new byte[263];
        int n;
        try (InputStream in = new FileInputStream(file)) {
            n = readFully(in, head);
        }
        return detect(head, n);
    }

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

    // ---------------------------------------------------------------- gzip, xz, bzip2

    /** Returns the patched file, or null if nothing was changed. */
    private static File patchCompressed(File file, int depth, Type type) throws Exception {
        File raw = scratch(depth, "raw");
        decompress(file, raw, type);
        if (!patchPath(raw, depth + 1)) return null;
        File packed = scratch(depth, "packed");
        compress(raw, packed, type);
        return packed;
    }

    private static void decompress(File in, File out, Type type) throws Exception {
        switch (type) {
            case GZIP:
                try (InputStream gz = new GZIPInputStream(openIn(in), 65536)) {
                    copyToFile(gz, out);
                }
                break;
            case XZ: run(in, out, "xz", "-dc"); break;
            default: run(in, out, "bzip2", "-dc"); break;
        }
    }

    private static void compress(File in, File out, Type type) throws Exception {
        switch (type) {
            case GZIP:
                try (OutputStream gz = new GZIPOutputStream(openOut(out), 65536) {{ def.setLevel(Deflater.BEST_COMPRESSION); }}) {
                    copyFromFile(in, gz);
                }
                break;
            case XZ: run(in, out, "xz", "-c"); break;
            default: run(in, out, "bzip2", "-c"); break;
        }
    }

    private static void run(final File in, File out, String... cmd) throws Exception {
        final Process p = new ProcessBuilder(cmd).start();
        final IOException[] writeError = new IOException[1];
        Thread writer = new Thread() {
            @Override
            public void run() {
                try (OutputStream os = p.getOutputStream(); InputStream is = new FileInputStream(in)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
                } catch (IOException e) {
                    writeError[0] = e;
                }
            }
        };
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread errReader = new Thread() {
            @Override
            public void run() {
                try (InputStream es = p.getErrorStream()) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = es.read(buf)) != -1) if (stderr.size() < 65536) stderr.write(buf, 0, r);
                } catch (IOException ignored) {
                }
            }
        };
        writer.start();
        errReader.start();
        try (InputStream is = p.getInputStream()) {
            copyToFile(is, out);
        }
        writer.join();
        errReader.join();
        int exit = p.waitFor();
        if (exit != 0) throw new IOException(cmd[0] + " exited with " + exit + ": " + stderr.toString().trim());
        if (writeError[0] != null) throw writeError[0];
    }

    // ---------------------------------------------------------------- zip, apk, jar

    private static boolean isSignatureFile(String name) {
        String n = name.toUpperCase();
        return n.startsWith("META-INF/") && n.indexOf('/', 9) < 0 &&
            (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"));
    }

    /** Returns the patched file, or null if nothing was changed. */
    private static File patchZip(File file, int depth) throws IOException {
        File member = scratch(depth, "member");
        File out = scratch(depth, "zip");
        int count = 0;
        boolean changed = false;
        boolean signed = false;
        try (ZipInputStream zin = new ZipInputStream(openIn(file));
             ZipOutputStream zout = new ZipOutputStream(openOut(out))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                count++;
                String name = replaceName(e.getName());
                if (!name.equals(e.getName())) changed = true;
                copyToFile(zin, member);
                if (!e.isDirectory() && patchPath(member, depth + 1)) changed = true;
                // The apk signature is not checked when loading classes with app_process or a jar
                // on the classpath, but a jar signature that does not match the content would be
                // rejected. The output is only used if something changed, so always leave it out.
                if (isSignatureFile(e.getName())) {
                    signed = true;
                    continue;
                }

                ZipEntry ne = new ZipEntry(name);
                ne.setTime(e.getTime());
                if (e.getExtra() != null) ne.setExtra(e.getExtra());
                if (e.getComment() != null) ne.setComment(e.getComment());
                ne.setMethod(e.getMethod());
                if (e.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    try (InputStream in = new FileInputStream(member)) {
                        byte[] buf = COPY_BUF;
                        int r;
                        while ((r = in.read(buf)) != -1) crc.update(buf, 0, r);
                    }
                    ne.setSize(member.length());
                    ne.setCompressedSize(member.length());
                    ne.setCrc(crc.getValue());
                }
                zout.putNextEntry(ne);
                copyFromFile(member, zout);
                zout.closeEntry();
            }
            if (count == 0) throw new IOException("No zip entries found");
        }
        if (!changed) return null;
        if (signed) warn("removing the now invalid jar signature of a patched archive");
        return out;
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

    /** Returns the patched file, or null if nothing was changed. */
    private static File patchTar(File file, int depth) throws IOException {
        File member = scratch(depth, "member");
        File out = scratch(depth, "tar");
        boolean changed = false;
        try (InputStream in = openIn(file); OutputStream os = openOut(out)) {
            byte[] header = new byte[512];
            while (true) {
                int n = readFully(in, header);
                if (n < 512 || allZero(header, 0, 512)) {
                    os.write(header, 0, n);
                    copy(in, os);
                    break;
                }
                long size = parseTarNumber(header, 124, 12);
                try (OutputStream mo = openOut(member)) {
                    copyN(in, mo, size);
                }
                skipFully(in, (size + 511) / 512 * 512 - size);
                byte type = header[156];

                // Regular files may be archives themselves, other members (long names, pax headers)
                // only contain text that is patched in-place.
                boolean memberChanged = (type == '0' || type == 0 || type == '7') ?
                    patchPath(member, depth + 1) : replaceInFile(member, OLD, NEW);
                long newSize = member.length();
                byte[] newHeader = replace(header, OLD, NEW);
                if (memberChanged || newHeader != header) {
                    changed = true;
                    newHeader = newHeader.clone();
                    if (newSize != size) {
                        String sz = Long.toOctalString(newSize);
                        if (sz.length() > 11) throw new IOException("Patched tar member too large");
                        byte[] field = String.format("%11s", sz).replace(' ', '0').getBytes();
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
                os.write(newHeader);
                copyFromFile(member, os);
                os.write(new byte[(int) ((newSize + 511) / 512 * 512 - newSize)]);
            }
        }
        return changed ? out : null;
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = COPY_BUF;
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("Truncated tar");
            out.write(buf, 0, r);
            n -= r;
        }
    }

    /** The padding of the last member may be missing. */
    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long r = in.skip(n);
            if (r <= 0) {
                if (in.read() < 0) return;
                r = 1;
            }
            n -= r;
        }
    }

    // ---------------------------------------------------------------- dex

    private static boolean patchDex(File file) throws IOException {
        if (file.length() < 0x70) {
            warn("failed to patch DEX content (truncated), patching it as plain data");
            return replaceInFile(file, OLD, NEW);
        }
        boolean changed = replaceInFile(file, OLD, NEW);
        if (replaceInFile(file, OLD_PATH, NEW_PATH)) changed = true;
        if (!changed) return false;

        // Header: signature (SHA-1 of everything after it), then checksum (Adler-32 of everything after it).
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            MessageDigest sha1;
            try {
                sha1 = MessageDigest.getInstance("SHA-1");
            } catch (Exception e) {
                throw new IOException(e);
            }
            raf.seek(32);
            byte[] buf = PATCH_BUF;
            int r;
            while ((r = raf.read(buf)) != -1) sha1.update(buf, 0, r);
            raf.seek(12);
            raf.write(sha1.digest());

            Adler32 adler = new Adler32();
            raf.seek(12);
            while ((r = raf.read(buf)) != -1) adler.update(buf, 0, r);
            long a = adler.getValue();
            raf.seek(8);
            for (int i = 0; i < 4; i++) raf.write((int) (a >>> (8 * i)) & 0xff);

            checkDexStringOrder(raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, raf.length()));
        }
        return true;
    }

    private static int u32(MappedByteBuffer d, int off) {
        return (d.get(off) & 0xff) | (d.get(off + 1) & 0xff) << 8 | (d.get(off + 2) & 0xff) << 16 | (d.get(off + 3) & 0xff) << 24;
    }

    /** The replacement could in theory break the required sort order of the dex string table. */
    private static void checkDexStringOrder(MappedByteBuffer d) {
        try {
            int count = u32(d, 0x38);
            int idsOff = u32(d, 0x3c);
            String prev = null;
            for (int i = 0; i < count; i++) {
                int p = u32(d, idsOff + 4 * i);
                while ((d.get(p) & 0x80) != 0) p++;
                p++;
                int end = p;
                while (d.get(end) != 0) end++;
                if (end - p > 65535) {
                    prev = null;
                    continue;
                }
                byte[] utf = new byte[end - p + 2];
                utf[0] = (byte) ((end - p) >> 8);
                utf[1] = (byte) (end - p);
                for (int j = p; j < end; j++) utf[2 + j - p] = d.get(j);
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
