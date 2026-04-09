package com.dawn.download;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpClientFactory {

    private static final String TAG = "TcpClientFactory";

    /** 默认连接超时（毫秒） */
    private static final int DEFAULT_CONNECT_TIMEOUT = 10_000;
    /** 默认读超时（毫秒） */
    private static final int DEFAULT_SO_TIMEOUT = 0;
    /** 读取单行最大长度限制，防止恶意大数据攻击 */
    private static final int MAX_LINE_LENGTH = 64 * 1024;
    /** 默认重连间隔（毫秒） */
    private static final long DEFAULT_RECONNECT_INTERVAL = 3_000;
    /** 默认最大重连次数，0表示不重连 */
    private static final int DEFAULT_MAX_RECONNECT_COUNT = 0;

    private String serverIp = "127.0.0.1";
    private int serverPort = 8088;
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int soTimeout = DEFAULT_SO_TIMEOUT;
    private long reconnectInterval = DEFAULT_RECONNECT_INTERVAL;
    private int maxReconnectCount = DEFAULT_MAX_RECONNECT_COUNT;

    private volatile Socket socket;
    private volatile PrintWriter output;
    private volatile TcpClientListener listener;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean wasConnected = new AtomicBoolean(false);
    private final AtomicInteger sessionId = new AtomicInteger(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile ExecutorService connectExecutor;
    private volatile ExecutorService sendExecutor;

    public TcpClientFactory() {
    }

    // ==================== 配置方法（链式调用，仅在未启动时调用） ====================

    public TcpClientFactory setServerIp(String ip) {
        checkNotRunning();
        if (ip == null || ip.isEmpty()) {
            throw new IllegalArgumentException("Server IP cannot be null or empty");
        }
        this.serverIp = ip;
        return this;
    }

    public TcpClientFactory setServerPort(int port) {
        checkNotRunning();
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        this.serverPort = port;
        return this;
    }

    public TcpClientFactory setConnectTimeout(int timeoutMs) {
        checkNotRunning();
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Connect timeout cannot be negative");
        }
        this.connectTimeout = timeoutMs;
        return this;
    }

    public TcpClientFactory setSoTimeout(int timeoutMs) {
        checkNotRunning();
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("SO timeout cannot be negative");
        }
        this.soTimeout = timeoutMs;
        return this;
    }

    public TcpClientFactory setReconnect(int maxCount, long intervalMs) {
        checkNotRunning();
        if (maxCount < 0) {
            throw new IllegalArgumentException("Max reconnect count cannot be negative");
        }
        if (intervalMs < 0) {
            throw new IllegalArgumentException("Reconnect interval cannot be negative");
        }
        this.maxReconnectCount = maxCount;
        this.reconnectInterval = intervalMs;
        return this;
    }

    private void checkNotRunning() {
        if (isRunning.get()) {
            throw new IllegalStateException("Cannot change config while client is running");
        }
    }

    // ==================== 核心方法 ====================

    /**
     * 启动客户端连接
     *
     * @param listener 回调监听器
     */
    public synchronized void startClient(TcpClientListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Client is already running");
            return;
        }
        this.listener = listener;
        wasConnected.set(false);
        connectExecutor = Executors.newSingleThreadExecutor();
        sendExecutor = Executors.newSingleThreadExecutor();
        final int session = sessionId.incrementAndGet();
        connectExecutor.execute(() -> connectLoop(session));
    }

    /**
     * 停止客户端连接
     */
    public synchronized void stopClient() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }
        closeConnection();
        shutdownExecutors();
        if (wasConnected.compareAndSet(true, false)) {
            notifyDisconnected();
        }
        notifyStopped();
        // 置空 listener，防止残留的 connectLoop 线程在 stopClient 返回后仍投递回调
        this.listener = null;
    }

    /**
     * 释放资源，清除所有回调引用，防止 Activity 泄漏。
     * 调用后此实例不可再使用，需重新创建。
     */
    public synchronized void release() {
        stopClient();
        mainHandler.removeCallbacksAndMessages(null);
        listener = null;
    }

    /**
     * 发送消息（线程安全）
     *
     * @param message 要发送的消息
     * @return 是否提交发送成功（不代表对端一定收到）
     */
    public boolean sendMessage(String message) {
        if (message == null) {
            return false;
        }
        PrintWriter writer = this.output;
        ExecutorService exec = this.sendExecutor;
        if (writer != null && exec != null && !exec.isShutdown() && isConnected()) {
            try {
                exec.execute(() -> {
                    try {
                        writer.println(message);
                        if (writer.checkError()) {
                            notifyError("Send message failed: stream error");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Send message error", e);
                        notifyError("Send message error: " + e.getMessage());
                    }
                });
                return true;
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Send rejected, executor is shut down");
            }
        }
        return false;
    }

    /**
     * 当前是否已连接
     */
    public boolean isConnected() {
        Socket s = this.socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    // ==================== 内部实现 ====================

    private void connectLoop(int session) {
        // 捕获本次会话的线程池引用，防止与新 startClient() 创建的线程池竞态
        final ExecutorService localSendExec = this.sendExecutor;
        final ExecutorService localConnectExec = this.connectExecutor;
        int reconnectCount = 0;

        while (isRunning.get() && sessionId.get() == session) {
            Socket currentSocket = null;
            PrintWriter currentOutput = null;
            try {
                currentSocket = doConnect();
                currentOutput = this.output; // 捕获本次 doConnect 创建的 output
                // 会话已更换或已停止，无需通知，finally 会清理 Socket
                if (sessionId.get() != session || !isRunning.get()) {
                    break;
                }
                // 连接成功，重置重连计数
                reconnectCount = 0;
                // 紧贴状态修改前再次校验，防止 stopClient+startClient 在上方检查后执行，
                // 导致 wasConnected=true 污染新会话（新会话未连接却收到 onDisconnected）
                if (sessionId.get() != session || !isRunning.get()) {
                    break;
                }
                wasConnected.set(true);
                notifyConnected();
                readMessages(currentSocket, session);
            } catch (IOException e) {
                Log.e(TAG, "Connection error", e);
                if (isRunning.get() && sessionId.get() == session) {
                    notifyError("Connection error: " + e.getMessage());
                }
            } finally {
                // 关闭本次迭代的连接，使用引用比较确保不影响新会话
                if (currentOutput != null) {
                    currentOutput.close();
                    if (this.output == currentOutput) {
                        this.output = null;
                    }
                }
                if (currentSocket != null) {
                    try {
                        currentSocket.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Close socket error", e);
                    }
                    if (this.socket == currentSocket) {
                        this.socket = null;
                    }
                }
            }

            // 会话已更换（stopClient+startClient 快速重启），旧线程直接退出
            if (sessionId.get() != session) {
                break;
            }

            // 连接断开时立即通知（仅当之前连接成功过且会话未更换时）
            if (sessionId.get() == session && wasConnected.compareAndSet(true, false)) {
                notifyDisconnected();
            }

            // 判断是否需要重连
            if (!isRunning.get()) {
                break;
            }
            if (maxReconnectCount <= 0) {
                // 不重连，使用 CAS + 会话守卫 防止与 stopClient 双重通知或误杀新会话
                if (sessionId.get() == session && isRunning.compareAndSet(true, false)) {
                    notifyStopped();
                }
                break;
            }
            reconnectCount++;
            if (reconnectCount > maxReconnectCount) {
                if (sessionId.get() == session && isRunning.compareAndSet(true, false)) {
                    notifyError("Max reconnect attempts reached (" + maxReconnectCount + ")");
                    notifyStopped();
                }
                break;
            }
            Log.i(TAG, "Reconnecting in " + reconnectInterval + "ms (attempt " + reconnectCount + "/" + maxReconnectCount + ")");
            try {
                Thread.sleep(reconnectInterval);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (sessionId.get() == session && isRunning.compareAndSet(true, false)) {
                    notifyStopped();
                }
                break;
            }
        }
        // 主动退出时清理本次会话的线程池（使用局部引用，避免误关新会话的线程池）
        if (localSendExec != null && !localSendExec.isShutdown()) {
            localSendExec.shutdownNow();
        }
        if (localConnectExec != null && !localConnectExec.isShutdown()) {
            localConnectExec.shutdownNow();
        }
    }

    private Socket doConnect() throws IOException {
        Socket s = new Socket();
        // 提前赋值，确保 stopClient 可以关闭正在连接的 Socket，而不必等待连接超时
        this.socket = s;
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            if (soTimeout > 0) {
                s.setSoTimeout(soTimeout);
            }
            s.connect(new InetSocketAddress(serverIp, serverPort), connectTimeout);
            this.output = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            return s;
        } catch (IOException e) {
            // 仅当共享字段仍指向本次 Socket 时才清除，避免覆盖新会话的值
            if (this.socket == s) {
                this.socket = null;
            }
            // this.output 在 connect() 失败时尚未赋值，无需清理
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private void readMessages(Socket s, int session) throws IOException {
        BufferedReader input = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        char[] buffer = new char[4096];
        StringBuilder lineBuilder = new StringBuilder();

        while (isRunning.get() && sessionId.get() == session) {
            int charsRead;
            try {
                charsRead = input.read(buffer);
            } catch (SocketTimeoutException e) {
                // 读超时不代表连接断开，继续等待
                continue;
            }
            if (charsRead == -1) {
                // 服务端关闭了连接
                break;
            }
            for (int i = 0; i < charsRead; i++) {
                char c = buffer[i];
                if (c == '\n') {
                    String line = lineBuilder.toString();
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    notifyReceiveData(line);
                    lineBuilder.setLength(0);
                } else {
                    if (lineBuilder.length() < MAX_LINE_LENGTH) {
                        lineBuilder.append(c);
                    }
                }
            }
        }
    }

    private void closeConnection() {
        PrintWriter w = this.output;
        if (w != null) {
            w.close();
            this.output = null;
        }
        Socket s = this.socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                Log.e(TAG, "Close socket error", e);
            }
            this.socket = null;
        }
    }

    private void shutdownExecutors() {
        // 先关闭发送线程池
        ExecutorService se = this.sendExecutor;
        if (se != null && !se.isShutdown()) {
            se.shutdownNow();
        }
        this.sendExecutor = null;

        // 关闭连接线程池（关闭 socket 会使阻塞的 read 抛出异常从而退出）
        ExecutorService ce = this.connectExecutor;
        if (ce != null && !ce.isShutdown()) {
            ce.shutdownNow();
        }
        this.connectExecutor = null;
    }

    // ==================== 回调通知（主线程） ====================

    private void notifyConnected() {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(l::onConnected);
        }
    }

    private void notifyDisconnected() {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(l::onDisconnected);
        }
    }

    private void notifyReceiveData(String data) {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onReceiveData(data));
        }
    }

    private void notifyError(String errorMessage) {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onError(errorMessage));
        }
    }

    private void notifyStopped() {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(l::onStopped);
        }
    }
}
