package io.github.einktransfer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 小型 HTTP/1.1 服务。每个连接一个请求；上传体不经过内存缓冲。 */
public final class TransferServer implements Closeable {
    public static final long MAX_FILE_SIZE = 5L * 1024 * 1024 * 1024;
    public interface Assets { InputStream open(String path) throws IOException; }
    private final File root, partials, storage;
    private final Assets assets;
    private final Runnable changed;
    private final ServerSocket listener;
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final Set<String> writing = new HashSet<>();
    private final Object commitLock = new Object();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(8));
    private volatile boolean running = true;

    public TransferServer(File directory, String address, int port, Assets assets, Runnable changed) throws IOException {
        this(directory, directory, address, port, assets, changed);
    }
    public TransferServer(File directory, File storage, String address, int port, Assets assets, Runnable changed) throws IOException {
        this.root = directory.getCanonicalFile(); this.assets = assets; this.changed = changed;
        this.storage = storage.getCanonicalFile();
        if ((!root.isDirectory() && !root.mkdirs()) || !root.canWrite()) throw new IOException("无法访问保存目录");
        partials = new File(root, ".eink-partials");
        if (!partials.getCanonicalFile().equals(partials.getAbsoluteFile())) throw new IOException("临时目录无效");
        if (!partials.isDirectory() && !partials.mkdir()) throw new IOException("无法创建临时目录");
        File[] stale = partials.listFiles();
        if (stale != null) for (File f : stale) if (f.getName().startsWith("upload-") && f.isFile()) f.delete();
        listener = new ServerSocket();
        try { listener.setReuseAddress(true); listener.bind(new InetSocketAddress(address, port)); }
        catch (IOException e) { listener.close(); workers.shutdownNow(); throw e; }
        Thread acceptor = new Thread(this::acceptLoop, "eink-http"); acceptor.setDaemon(true); acceptor.start();
    }

    public int port() { return listener.getLocalPort(); }
    private void acceptLoop() {
        while (running) try {
            final Socket s = listener.accept(); s.setSoTimeout(30000); sockets.add(s);
            try { workers.execute(() -> serve(s)); }
            catch (RejectedExecutionException e) { sockets.remove(s); s.close(); }
        } catch (IOException ignored) { if (!running) break; }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            InputStream in = new BufferedInputStream(s.getInputStream(), 32768);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 32768);
            try { route(in, out); }
            catch (Problem p) { json(out, p.status, "{\"error\":" + quote(p.getMessage()) + "}"); }
            catch (SocketTimeoutException e) { json(out, 408, "{\"error\":\"连接超时，请重试\"}"); }
            catch (IOException e) { json(out, 500, "{\"error\":\"传输失败，请检查连接及存储空间\"}"); }
            out.flush();
        } catch (IOException ignored) { /* 对端断开；upload 的 finally 清理未提交内容。 */ }
        finally { sockets.remove(socket); }
    }

    private void route(InputStream in, OutputStream out) throws IOException {
        String first = line(in, 4096);
        String[] request = first.split(" ");
        if (request.length != 3 || !request[2].equals("HTTP/1.1")) throw new Problem(400, "无效 HTTP 请求");
        Map<String, String> headers = new HashMap<>(); int total = first.length();
        for (;;) {
            String h = line(in, 8192); total += h.length() + 2;
            if (total > 16384) throw new Problem(431, "请求头过长");
            if (h.isEmpty()) break;
            int colon = h.indexOf(':'); if (colon < 1) throw new Problem(400, "无效请求头");
            String key = h.substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(key, h.substring(colon + 1).trim()) != null) throw new Problem(400, "重复请求头");
        }
        String host = headers.get("host");
        String expected = listener.getInetAddress().getHostAddress() + ":" + port();
        if (!expected.equals(host)) throw new Problem(403, "请使用阅读器显示的访问地址");
        String origin = headers.get("origin");
        if (origin != null && !origin.equals("http://" + host)) throw new Problem(403, "不允许跨站请求");
        if (headers.containsKey("transfer-encoding")) throw new Problem(400, "需要 Content-Length");
        String method = request[0], target = request[1];
        int q = target.indexOf('?'); String path = q < 0 ? target : target.substring(0, q);
        String name = q < 0 ? null : queryName(target.substring(q + 1));
        if (method.equals("GET") && (path.equals("/") || path.equals("/app.js") || path.equals("/style.css"))) {
            String asset = path.equals("/") ? "index.html" : path.substring(1);
            byte[] data;
            try (InputStream src = assets.open(asset); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                copy(src, bytes); data = bytes.toByteArray();
            }
            String type = asset.endsWith("js") ? "application/javascript" : asset.endsWith("css") ? "text/css" : "text/html";
            head(out, 200, type + "; charset=utf-8", data.length, ""); out.write(data);
        } else if (method.equals("GET") && path.equals("/api/files")) {
            json(out, 200, fileList());
        } else if (method.equals("PUT") && path.equals("/api/file")) {
            String length = headers.get("content-length"); long size;
            try { if (length == null || !length.matches("[0-9]+")) throw new NumberFormatException(); size = Long.parseLong(length); }
            catch (NumberFormatException e) { throw new Problem(411, "需要有效文件长度"); }
            if (size > MAX_FILE_SIZE) throw new Problem(413, "单文件上限为 5 GiB");
            upload(name, size, in); json(out, 201, "{\"ok\":true}");
        } else if (method.equals("GET") && path.equals("/api/file")) {
            download(out, existing(name));
        } else if (method.equals("DELETE") && path.equals("/api/file")) {
            synchronized (commitLock) {
                File file = existing(name);
                if (!file.delete()) throw new Problem(500, "删除失败，请重试");
            }
            notifyChanged(); json(out, 200, "{\"ok\":true}");
        } else if (path.equals("/api/storage") || path.equals("/api/storage/file")) {
            if (!method.equals("GET")) throw new Problem(405, "全部文件仅支持浏览和下载");
            File file = storageFile(queryValue(q < 0 ? "" : target.substring(q + 1), "path"));
            if (path.equals("/api/storage")) json(out, 200, storageList(file));
            else { if (!file.isFile()) throw new Problem(404, "文件不存在"); download(out, file); }
        } else throw new Problem(404, "请求的资源不存在");
    }

    private static void download(OutputStream out, File file) throws IOException {
        try (InputStream src = new FileInputStream(file)) {
            String encoded = URLEncoder.encode(file.getName(), "UTF-8").replace("+", "%20");
            head(out, 200, "application/octet-stream", file.length(),
                "Content-Disposition: attachment; filename=\"download\"; filename*=UTF-8''" + encoded + "\r\n");
            copy(src, out);
        }
    }
    private File storageFile(String relative) throws IOException {
        if (relative == null) relative = "";
        if (relative.startsWith("/") || relative.indexOf('\\') >= 0) throw new Problem(400, "无效目录路径");
        for (char c : relative.toCharArray()) if (Character.isISOControl(c)) throw new Problem(400, "无效目录路径");
        for (String part : relative.split("/", -1)) {
            if (part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".eink-partials")) throw new Problem(403, "此目录不可访问");
        }
        File f = new File(storage, relative);
        if (!f.getCanonicalFile().equals(f.getAbsoluteFile()) || !(f.equals(storage) || f.getPath().startsWith(storage.getPath() + File.separator)))
            throw new Problem(403, "不能访问共享存储以外的目录");
        if (!f.exists()) throw new Problem(404, "文件或目录不存在");
        if (!f.canRead()) throw new Problem(403, "没有读取此目录或文件的权限");
        return f;
    }
    private String storageList(File directory) throws IOException {
        if (!directory.isDirectory()) throw new Problem(400, "请选择目录");
        File[] files = directory.listFiles(); if (files == null) throw new Problem(403, "此目录不可读取");
        Arrays.sort(files, Comparator.comparing((File f) -> !f.isDirectory()).thenComparing(File::getName));
        StringBuilder result = new StringBuilder("[");
        for (File f : files) {
            if (f.getName().equalsIgnoreCase(".eink-partials") || !f.canRead() || !f.getCanonicalFile().equals(f.getAbsoluteFile()) || !(f.isDirectory() || f.isFile())) continue;
            if (result.length() > 1) result.append(',');
            String relative = storage.toURI().relativize(f.toURI()).getPath();
            if (relative.endsWith("/")) relative = relative.substring(0, relative.length() - 1);
            result.append("{\"name\":").append(quote(f.getName())).append(",\"path\":").append(quote(relative))
                .append(",\"directory\":").append(f.isDirectory()).append(",\"size\":").append(f.isFile() ? f.length() : 0).append('}');
        }
        return result.append(']').toString();
    }

    private void upload(String name, long size, InputStream in) throws IOException {
        File dest = checked(name); String key = name.toLowerCase(Locale.ROOT);
        synchronized (commitLock) {
            if (dest.exists() || writing.contains(key)) throw new Problem(409, "文件已存在或正在上传，未覆盖");
            if (size > root.getUsableSpace() - 1024 * 1024) throw new Problem(507, "存储空间不足");
            writing.add(key);
        }
        File temp = null;
        try {
            temp = File.createTempFile("upload-", ".part", partials);
            try (FileOutputStream output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32768]; long remaining = size;
                while (remaining > 0) {
                    if (!running) throw new IOException("服务已停止");
                    int n = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                    if (n < 0) throw new Problem(400, "文件未传输完整");
                    output.write(buffer, 0, n); remaining -= n;
                }
                output.getFD().sync();
            }
            synchronized (commitLock) {
                if (!running) throw new IOException("服务已停止");
                if (dest.exists()) throw new Problem(409, "文件已存在，未覆盖");
                if (!temp.renameTo(dest)) throw new Problem(507, "保存失败，请检查空间和文件名");
            }
            notifyChanged();
        } finally {
            if (temp != null && temp.exists()) temp.delete();
            synchronized (commitLock) { writing.remove(key); }
        }
    }

    private File checked(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.equalsIgnoreCase(".eink-partials")
                || name.getBytes(StandardCharsets.UTF_8).length > 240) throw new Problem(400, "文件名无效或过长");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)) throw new Problem(400, "文件名不能包含路径或控制字符");
        }
        File file = new File(root, name);
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new Problem(400, "文件路径无效");
        return file;
    }
    private File existing(String name) throws IOException {
        File f = checked(name); if (!f.isFile()) throw new Problem(404, "文件不存在"); return f;
    }
    public String fileList() throws IOException {
        File[] files = root.listFiles(); if (files == null) throw new Problem(500, "无法读取目录");
        Arrays.sort(files, Comparator.comparing(File::getName));
        StringBuilder result = new StringBuilder("[");
        for (File f : files) if (f.isFile() && f.getCanonicalFile().equals(f.getAbsoluteFile())) {
            if (result.length() > 1) result.append(',');
            result.append("{\"name\":").append(quote(f.getName())).append(",\"size\":").append(f.length()).append('}');
        }
        return result.append(']').toString();
    }
    private static String queryName(String query) throws IOException {
        return queryValue(query, "name");
    }
    private static String queryValue(String query, String key) throws IOException {
        String value = null;
        for (String part : query.split("&")) if (part.startsWith(key + "=")) {
            if (value != null) throw new Problem(400, "请求参数重复");
            try { value = URLDecoder.decode(part.substring(key.length() + 1), "UTF-8"); }
            catch (IllegalArgumentException e) { throw new Problem(400, "请求编码无效"); }
        }
        return value;
    }
    private static String line(InputStream in, int max) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream(); int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] bytes = data.toByteArray();
                if (bytes.length == 0 || bytes[bytes.length - 1] != '\r') throw new Problem(400, "无效换行");
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            if (data.size() >= max) throw new Problem(431, "请求行过长"); data.write(c);
        }
        throw new EOFException();
    }
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[32768]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
    private static void head(OutputStream out, int status, String type, long size, String extra) throws IOException {
        String text = "HTTP/1.1 " + status + " " + (status < 400 ? "OK" : "Error") + "\r\nContent-Type: " + type
            + "\r\nContent-Length: " + size + "\r\nConnection: close\r\nCache-Control: no-store\r\n"
            + "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"
            + "Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n"
            + extra + "\r\n";
        out.write(text.getBytes(StandardCharsets.US_ASCII));
    }
    private static void json(OutputStream out, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); head(out, code, "application/json; charset=utf-8", bytes.length, ""); out.write(bytes);
    }
    public static String quote(String text) {
        StringBuilder s = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') s.append('\\').append(c);
            else if (c < 32) s.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else s.append(c);
        }
        return s.append('"').toString();
    }
    private void notifyChanged() { if (changed != null) changed.run(); }
    @Override public void close() {
        synchronized (commitLock) { running = false; }
        try { listener.close(); } catch (IOException ignored) { }
        synchronized (sockets) { for (Socket s : sockets) try { s.close(); } catch (IOException ignored) { } }
        workers.shutdownNow();
    }
    private static final class Problem extends IOException {
        final int status; Problem(int status, String message) { super(message); this.status = status; }
    }
}
