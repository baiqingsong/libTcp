package com.dawn.download;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpServerFactory {

    private static final String TAG = "TcpServerFactory";

    /** 读取单行最大长度限制，防止恶意大数据攻击 */
    private static final int MAX_LINE_LENGTH = 64 * 1024;
    /** 默认最大客户端连接数 */
    private static final int DEFAULT_MAX_CLIENTS = 50;
    /** 客户端 Socket 读超时（毫秒），用于检测死连接，0表示无限等待 */
    private static final int DEFAULT_CLIENT_SO_TIMEOUT = 5 * 60 * 1000;

    private int serverPort = 8088;
    private int maxClients = DEFAULT_MAX_CLIENTS;
    private int clientSoTimeout = DEFAULT_CLIENT_SO_TIMEOUT;

    private volatile ServerSocket serverSocket;
    private volatile TcpServerListener listener;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger clientIdGenerator = new AtomicInteger(0);
    private final ConcurrentHashMap<String, ClientConnection> clientMap = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean serverStarted = new AtomicBoolean(false);
    private final AtomicInteger sessionId = new AtomicInteger(0);
    private volatile ExecutorService acceptExecutor;
    private volatile ExecutorService clientExecutor;

    public TcpServerFactory() {
    }

    // ==================== 配置方法（链式调用，仅在未启动时调用） ====================

    public TcpServerFactory setServerPort(int port) {
        checkNotRunning();
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        this.serverPort = port;
        return this;
    }

    public TcpServerFactory setMaxClients(int maxClients) {
        checkNotRunning();
        if (maxClients <= 0) {
            throw new IllegalArgumentException("Max clients must be positive");
        }
        this.maxClients = maxClients;
        return this;
    }

    public TcpServerFactory setClientSoTimeout(int timeoutMs) {
        checkNotRunning();
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Client SO timeout cannot be negative");
        }
        this.clientSoTimeout = timeoutMs;
        return this;
    }

    private void checkNotRunning() {
        if (isRunning.get()) {
            throw new IllegalStateException("Cannot change config while server is running");
        }
    }

    // ==================== 核心方法 ====================

    /**
     * 启动服务端
     *
     * @param listener 回调监听器
     */
    public synchronized void startServer(TcpServerListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Server is already running");
            return;
        }
        this.listener = listener;
        serverStarted.set(false);
        clientMap.clear();
        acceptExecutor = Executors.newSingleThreadExecutor();
        clientExecutor = Executors.newCachedThreadPool();
        final int session = sessionId.incrementAndGet();
        acceptExecutor.execute(() -> acceptLoop(session));
    }

    /**
     * 停止服务端，断开所有客户端
     */
    public synchronized void stopServer() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }
        // 先关闭 ServerSocket，阻止新连接进入
        ServerSocket ss = this.serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException e) {
                Log.e(TAG, "Close server socket error", e);
            }
            this.serverSocket = null;
        }
        // 关闭所有客户端连接并逐个通知断开
        for (Map.Entry<String, ClientConnection> entry : clientMap.entrySet()) {
            ClientConnection conn = clientMap.remove(entry.getKey());
            if (conn != null) {
                conn.close();
                notifyClientDisconnected(entry.getKey());
            }
        }
        // 关闭线程池
        shutdownExecutors();
        if (serverStarted.compareAndSet(true, false)) {
            notifyServerStopped();
        }
        // 置空 listener，防止残留的 acceptLoop/handleClient 线程在 stopServer 返回后仍投递回调
        this.listener = null;
    }

    /**
     * 释放资源，清除所有回调引用，防止 Activity 泄漏。
     * 调用后此实例不可再使用，需重新创建。
     */
    public synchronized void release() {
        stopServer();
        mainHandler.removeCallbacksAndMessages(null);
        listener = null;
    }

    /**
     * 向指定客户端发送消息
     *
     * @param clientId 客户端ID
     * @param message  消息内容
     * @return 是否提交发送成功
     */
    public boolean sendMessage(String clientId, String message) {
        if (clientId == null || message == null) {
            return false;
        }
        ClientConnection conn = clientMap.get(clientId);
        ExecutorService exec = this.clientExecutor;
        if (conn != null && exec != null && !exec.isShutdown()) {
            try {
                exec.execute(() -> conn.send(message));
                return true;
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Send rejected, executor is shut down");
            }
        }
        return false;
    }

    /**
     * 向所有已连接客户端广播消息
     *
     * @param message 消息内容
     */
    public void broadcastMessage(String message) {
        if (message == null) {
            return;
        }
        ExecutorService exec = this.clientExecutor;
        if (exec == null || exec.isShutdown()) {
            return;
        }
        for (Map.Entry<String, ClientConnection> entry : clientMap.entrySet()) {
            ClientConnection conn = entry.getValue();
            try {
                exec.execute(() -> conn.send(message));
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Broadcast rejected, executor is shut down");
                break;
            }
        }
    }

    /**
     * 断开指定客户端
     *
     * @param clientId 客户端ID
     */
    public void disconnectClient(String clientId) {
        ClientConnection conn = clientMap.remove(clientId);
        if (conn != null) {
            conn.close();
            notifyClientDisconnected(clientId);
        }
    }

    /**
     * 获取当前连接的客户端数量
     */
    public int getClientCount() {
        return clientMap.size();
    }

    /**
     * 服务端是否在运行
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    // ==================== 内部实现 ====================

    private void acceptLoop(int session) {
        // 捕获本次会话的线程池引用，防止与新 startServer() 创建的线程池竞态
        final ExecutorService localClientExec = this.clientExecutor;
        final ExecutorService localAcceptExec = this.acceptExecutor;
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            serverSocket = ss;  // 在 bind 前赋值，确保 stopServer 可以关闭它
            ss.bind(new java.net.InetSocketAddress(serverPort));
            // 会话检查：若 stopServer+startServer 已发生，或已停止，旧线程不应修改共享状态
            if (sessionId.get() != session || !isRunning.get()) {
                return; // finally 会关闭 ss
            }
            // 紧贴状态修改前再次校验，防止 stopServer+startServer 在上方检查后执行，
            // 导致 serverStarted=true 污染新会话
            if (sessionId.get() != session || !isRunning.get()) {
                return;
            }
            serverStarted.set(true);
            notifyServerStarted(serverPort);

            while (isRunning.get() && sessionId.get() == session) {
                Socket clientSocket = ss.accept();

                if (clientMap.size() >= maxClients) {
                    Log.w(TAG, "Max clients reached, rejecting connection");
                    clientSocket.close();
                    continue;
                }

                clientSocket.setTcpNoDelay(true);
                clientSocket.setKeepAlive(true);
                if (clientSoTimeout > 0) {
                    clientSocket.setSoTimeout(clientSoTimeout);
                }

                String clientId = "client_" + clientIdGenerator.incrementAndGet();
                ClientConnection conn = new ClientConnection(clientId, clientSocket);
                if (!conn.isValid()) {
                    Log.e(TAG, "Failed to initialize client connection: " + clientId);
                    conn.close();
                    continue;
                }
                clientMap.put(clientId, conn);
                notifyClientConnected(clientId);

                ExecutorService exec = localClientExec;
                if (exec != null && !exec.isShutdown()) {
                    try {
                        exec.execute(() -> handleClient(conn));
                    } catch (RejectedExecutionException e) {
                        clientMap.remove(clientId);
                        conn.close();
                        notifyClientDisconnected(clientId);
                        Log.w(TAG, "Client handler rejected, executor is shut down");
                    }
                } else {
                    clientMap.remove(clientId);
                    conn.close();
                    notifyClientDisconnected(clientId);
                }
            }
        } catch (IOException e) {
            // 使用 CAS 防止与 stopServer/新 startServer 竞态：
            // 若 stopServer 已将 isRunning 设为 false，CAS 失败，跳过通知（由 stopServer 负责）
            // 避免用 set(false) 误覆盖新 startServer 会话的 isRunning=true
            if (sessionId.get() == session && isRunning.compareAndSet(true, false)) {
                Log.e(TAG, "Accept loop error", e);
                notifyError("Server error: " + e.getMessage());
                // 关闭所有客户端连接并清理 clientMap
                // shutdownNow 仅设置线程中断标志，无法中断 Socket.read() 阻塞
                // 必须主动关闭 Socket 才能让 handleClient 线程退出
                for (Map.Entry<String, ClientConnection> entry : clientMap.entrySet()) {
                    ClientConnection c = clientMap.remove(entry.getKey());
                    if (c != null) {
                        c.close();
                        notifyClientDisconnected(entry.getKey());
                    }
                }
                // 先断开客户端再通知服务停止，保证回调顺序正确
                if (serverStarted.compareAndSet(true, false)) {
                    notifyServerStopped();
                }
            }
            // 使用局部引用清理本次会话的线程池，避免误关新会话的线程池
            if (localClientExec != null && !localClientExec.isShutdown()) {
                localClientExec.shutdownNow();
            }
            if (localAcceptExec != null && !localAcceptExec.isShutdown()) {
                localAcceptExec.shutdownNow();
            }
        } finally {
            // 确保 ServerSocket 在任何情况下都被关闭（包括 bind 失败时）
            if (ss != null && !ss.isClosed()) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void handleClient(ClientConnection conn) {
        try {
            BufferedReader input = new BufferedReader(
                    new InputStreamReader(conn.socket.getInputStream(), StandardCharsets.UTF_8));
            char[] buffer = new char[4096];
            StringBuilder lineBuilder = new StringBuilder();

            while (isRunning.get() && !conn.socket.isClosed()) {
                int charsRead;
                try {
                    charsRead = input.read(buffer);
                } catch (SocketTimeoutException e) {
                    // 读超时不代表连接断开，继续等待
                    continue;
                }
                if (charsRead == -1) {
                    break;
                }
                for (int i = 0; i < charsRead; i++) {
                    char c = buffer[i];
                    if (c == '\n') {
                        String line = lineBuilder.toString();
                        if (line.endsWith("\r")) {
                            line = line.substring(0, line.length() - 1);
                        }
                        notifyReceiveData(conn.clientId, line);
                        lineBuilder.setLength(0);
                    } else {
                        if (lineBuilder.length() < MAX_LINE_LENGTH) {
                            lineBuilder.append(c);
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (isRunning.get()) {
                Log.e(TAG, "Client " + conn.clientId + " read error", e);
            }
        } finally {
            // 仅当还在 clientMap 中时才通知断开，避免与 stopServer 双重通知
            if (clientMap.remove(conn.clientId) != null) {
                notifyClientDisconnected(conn.clientId);
            }
            conn.close();
        }
    }

    private void shutdownExecutors() {
        ExecutorService ce = this.clientExecutor;
        if (ce != null && !ce.isShutdown()) {
            ce.shutdownNow();
        }
        this.clientExecutor = null;

        ExecutorService ae = this.acceptExecutor;
        if (ae != null && !ae.isShutdown()) {
            ae.shutdownNow();
        }
        this.acceptExecutor = null;
    }

    // ==================== 客户端连接封装 ====================

    private static class ClientConnection {
        final String clientId;
        final Socket socket;
        private volatile PrintWriter output;
        private final Object sendLock = new Object();

        ClientConnection(String clientId, Socket socket) {
            this.clientId = clientId;
            this.socket = socket;
            try {
                this.output = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            } catch (IOException e) {
                Log.e(TAG, "Create output stream error for " + clientId, e);
                this.output = null;
            }
        }

        boolean isValid() {
            return output != null && !socket.isClosed();
        }

        void send(String message) {
            synchronized (sendLock) {
                PrintWriter w = this.output;
                if (w != null) {
                    w.println(message);
                    if (w.checkError()) {
                        Log.e(TAG, "Send error to " + clientId);
                    }
                }
            }
        }

        void close() {
            synchronized (sendLock) {
                PrintWriter w = this.output;
                if (w != null) {
                    w.close();
                    this.output = null;
                }
            }
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Close client socket error: " + clientId, e);
            }
        }
    }

    // ==================== 回调通知（主线程） ====================

    private void notifyServerStarted(int port) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onServerStarted(port));
        }
    }

    private void notifyServerStopped() {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(l::onServerStopped);
        }
    }

    private void notifyClientConnected(String clientId) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onClientConnected(clientId));
        }
    }

    private void notifyClientDisconnected(String clientId) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onClientDisconnected(clientId));
        }
    }

    private void notifyReceiveData(String clientId, String data) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onReceiveData(clientId, data));
        }
    }

    private void notifyError(String errorMessage) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onError(errorMessage));
        }
    }
}
