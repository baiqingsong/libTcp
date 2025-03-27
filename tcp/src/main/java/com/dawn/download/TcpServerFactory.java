package com.dawn.download;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class TcpServerFactory {
    //单例模式
    private static TcpServerFactory instance;
    private TcpServerFactory() {
    }

    public static TcpServerFactory getInstance() {
        if (instance == null) {
            synchronized (TcpServerFactory.class) {
                if (instance == null) {
                    instance = new TcpServerFactory();
                }
            }
        }
        return instance;
    }

    private ServerSocket serverSocket;
    private static final int SERVER_PORT = 8088;
    private TcpServerListener mListener;
    private PrintWriter output;

    /**
     * start server
     * @param listener listener
     */
    public void startServer(TcpServerListener listener){
        this.mListener = listener;
        new Thread(new ServerThread()).start();
    }

    /**
     * stop server
     */
    public void stopServer(){
        try {
            if(serverSocket != null && !serverSocket.isClosed()){
                serverSocket.close();
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
            new Thread(() -> output.println(message)).start();
        }
    }

    private class ServerThread implements Runnable{
        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(SERVER_PORT);
                // start server, waiting for client
                if(mListener != null)
                    mListener.onConnect();
                while (!Thread.currentThread().isInterrupted()) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(new ClientHandler(clientSocket)).start();
                }
            } catch (Exception e) {
                e.printStackTrace();
                if(mListener != null)
                    mListener.onConnectFailed();
            }

        }
    }

    private class ClientHandler implements Runnable {
        private Socket clientSocket;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try {
                BufferedReader input = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                output = new PrintWriter(clientSocket.getOutputStream(), true);

                String message;
                while ((message = input.readLine()) != null) {
                    String finalMessage = message;
                    if(mListener != null)
                        mListener.onReceiveData(finalMessage);
//                    output.println("Server received: " + finalMessage);
                }

                clientSocket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}
