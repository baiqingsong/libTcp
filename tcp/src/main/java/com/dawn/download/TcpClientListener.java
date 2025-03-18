package com.dawn.download;

public interface TcpClientListener {
    void onReceiveData(String data);
    void onConnect();
    void onDisconnect();
    void onConnectFailed();
}
