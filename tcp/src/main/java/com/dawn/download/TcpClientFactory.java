package com.dawn.download;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class TcpClientFactory {
    //单例模式
    private static TcpClientFactory instance;
    private TcpClientFactory() {
    }

    public static TcpClientFactory getInstance() {
        if (instance == null) {
            synchronized (TcpClientFactory.class) {
                if (instance == null) {
                    instance = new TcpClientFactory();
                }
            }
        }
        return instance;
    }
    private Socket socket;
    private PrintWriter output;
    private static final String SERVER_IP = "127.0.0.1"; // 替换为服务器的IP地址
    private static final int SERVER_PORT = 8088;
    private TcpClientListener mListener;

    /**
     * start client
     */
    public void startClient(TcpClientListener listener){
        this.mListener = listener;
        new Thread(new ClientThread()).start();
    }

    /**
     * stop client
     */
    public void stopClient() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                if(mListener != null)
                    mListener.onDisconnect();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * send message
     * @param message message
     */
    public void sendMessage(String message) {
        if (output != null) {
            output.println(message);
        }
    }

    private class ClientThread implements Runnable {
        @Override
        public void run() {
            try {
                socket = new Socket(SERVER_IP, SERVER_PORT);
                output = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                if(mListener != null)
                    mListener.onConnect();

                String message;
                while ((message = input.readLine()) != null) {
                    String finalMessage = message;
                    if(mListener != null)
                        mListener.onReceiveData(finalMessage);
                }

                socket.close();
            } catch (Exception e) {
                e.printStackTrace();
                if(mListener != null)
                    mListener.onConnectFailed();
            }
        }
    }

}
