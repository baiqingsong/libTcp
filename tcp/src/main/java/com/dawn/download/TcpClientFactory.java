package com.dawn.download;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TcpClientFactory {
    //单例模式
    private static TcpClientFactory instance;
    //网络监听的回调
    private ConnectivityManager.NetworkCallback networkCallback;

    private ScheduledExecutorService heartbeatExecutor;

    private ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private Context context;

    private Socket socket;
    private PrintWriter output;
    private OutputStream outputStream;
    private InputStream inputStream;

    //TCP地址
    private final String SERVER_IP = "127.0.0.1"; // 替换为服务器的IP地址
    //TCP端口
    private final int SERVER_PORT = 8088;
    //心跳间隔时间
    private static final long HEARTBEAT_INTERVAL = 5;
    //心跳数据
    private static final byte[] HEARTBEAT_DATA = {0x01};

    private TcpClientListener mListener;

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private TcpClientFactory(Context context) {
        this.context =context;

    }

    public static TcpClientFactory getInstance(Context context) {
        if (instance == null) {
            synchronized (TcpClientFactory.class) {
                if (instance == null) {
                    instance = new TcpClientFactory(context);
                }
            }
        }
        return instance;
    }

    //网络状态监听
    private void registerNetworkCallback() {
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                connect();
            }

            @Override
            public void onLost(Network network) {
                safeDisconnect();


            }
        };
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.registerNetworkCallback(request, networkCallback);
    }

    /**
     * start client
     */
    public void startClient(TcpClientListener listener){
        this.mListener = listener;
        registerNetworkCallback();
        connect();
    }
    //连接TCP
    private void connect(){
        networkExecutor.execute(() -> {
            try {
                Socket newSocket = new Socket(SERVER_IP, SERVER_PORT);
                synchronized (this) {
                    safeDisconnect();
                    socket = newSocket;
                    outputStream = socket.getOutputStream();
                    inputStream = socket.getInputStream();
                    if(mListener != null) {
                        mainHandler.post(() -> mListener.onConnect());
                    }
                    startHeartbeat();
                    startReceiveThread();
                }
            } catch (IOException e) {
                if(mListener != null){
                    mListener.onConnectFailed();
                }
                scheduleReconnect();
            }
        });
    }

    //接受数据
    private void startReceiveThread() {
        networkExecutor.execute(() -> {
            try {
                BufferedReader input = new BufferedReader(new InputStreamReader(inputStream));
                String message;
                while ((message = input.readLine()) != null) {
                    String finalMessage = message;
                    if(mListener != null)
                        mListener.onReceiveData(finalMessage);
                }
            } catch (IOException e) {
                //接口回调关闭
                if(mListener != null) {
                    mainHandler.post(() -> mListener.onConnectFailed());
                }
            }
        });

    }
    //安全断开TCP
    private void safeDisconnect() {
        synchronized (this) {
            stopHeartbeat();
            if (socket != null) {
                try {
                    socket.close();
                    //接口回调关闭
                    if(mListener != null) {
                        mainHandler.post(() -> mListener.onDisconnect());
                    }
                } catch (IOException ignored) {}
                socket = null;
            }
        }
    }
    //开始监听心跳
    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatExecutor = Executors.newScheduledThreadPool(1);
        heartbeatExecutor.scheduleWithFixedDelay(() -> {
            synchronized (this) {
                if (socket != null && !socket.isClosed()) {
                    try {
                        outputStream.write(HEARTBEAT_DATA);
                        outputStream.flush();
                    } catch (Exception e) {
                        scheduleReconnect();
                    }
                }
            }
        }, 0, HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
    }

    //停止心跳的监听
    private void stopHeartbeat() {
        if (outputStream!=null){
            try {
                outputStream.close();
            } catch (IOException e) {

            }
        }
        if (output!=null){
            output.close();
        }
        if (inputStream!=null){
            try {
                inputStream.close();
            } catch (IOException e) {

            }
        }
        if (heartbeatExecutor != null){
            heartbeatExecutor.shutdownNow();
        }

    }
    //重新连接
    private void scheduleReconnect() {
        mainHandler.postDelayed(() -> {
            connect();
        }, 5000);
    }

    /**
     * send message
     * @param message message
     */
    public void sendMessage(String message) {
        networkExecutor.execute(() -> {
            synchronized (this) {
                if (socket != null && !socket.isClosed()) {
                    try {
                        if (outputStream != null) {
                            output = new PrintWriter(outputStream, true);
                            output.println(message);
                        }
                    } catch (Exception e) {
                        scheduleReconnect();
                        //接口回调关闭
                        if(mListener != null) {
                            mainHandler.post(() -> mListener.onConnectFailed());
                        }

                    }
                }
            }
        });
    }

    /**
     * 释放资源
     */
    public void release() {
        safeDisconnect();
        if(networkExecutor!=null){
            networkExecutor.shutdownNow();
        }
        networkExecutor = null;

        if (heartbeatExecutor!=null){
            heartbeatExecutor.shutdownNow();
        }
        heartbeatExecutor=null;
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.unregisterNetworkCallback(networkCallback);
        }
        mainHandler.removeCallbacksAndMessages(null);
    }

}
