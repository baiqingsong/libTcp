package com.dawn.download;

public interface TcpServerListener {
    void onReceiveData(String data);
    void onConnect();
    void onDisconnect();
    void onConnectFailed();
}
