package com.dawn.download.broadcast;

import android.content.Context;
import android.content.Intent;

import com.dawn.download.entitis.FileInfo;

/**
 * 下载的广播
 */
public class DownloadBroadcastUtil {
    public static final String ACTION_START = "DOWNLOAD_START";//下载开始
    public static final String ACTION_STOP = "DOWNLOAD_STOP";//下载停止
    public static final String ACTION_UPDATE = "DOWNLOAD_UPDATE";//下载更新
    public static final String ACTION_FINISHED = "DOWNLOAD_FINISHED";//下载完成
    public static final String ACTION_ERROR = "DOWNLOAD_ERROR";//下载错误

    /**
     * 发送下载开始的广播
     * @param fileInfo 文件信息
     */
    public static void sendDownloadStart(Context context, FileInfo fileInfo){
        Intent intent = new Intent(ACTION_START);
        intent.putExtra("fileInfo", fileInfo);
        context.sendBroadcast(intent);
    }

    /**
     * 发送下载停止的广播
     * @param fileInfo 文件信息
     */
    public static void sendDownloadStop(Context context, FileInfo fileInfo){
        Intent intent = new Intent(ACTION_STOP);
        intent.putExtra("fileInfo", fileInfo);
        context.sendBroadcast(intent);
    }

    /**
     * 发送下载更新的广播
     * @param fileInfo 文件信息
     * @param mFinished 已经下载的进度
     */
    public static void sendDownloadUpdate(Context context, FileInfo fileInfo, int mFinished){
        Intent intent = new Intent(ACTION_UPDATE);
        intent.putExtra("fileInfo", fileInfo);
        intent.putExtra("finished", mFinished);
        context.sendBroadcast(intent);
    }

    /**
     * 发送下载完成的广播
     * @param fileInfo 文件信息
     */
    public static void sendDownloadFinish(Context context, FileInfo fileInfo){
        Intent intent = new Intent(ACTION_FINISHED);
        intent.putExtra("fileInfo", fileInfo);
        context.sendBroadcast(intent);
    }

    /**
     * 发送下载错误的广播
     * @param fileInfo 文件信息
     */
    public static void sendDownloadError(Context context, FileInfo fileInfo){
        Intent intent = new Intent(ACTION_ERROR);
        intent.putExtra("fileInfo", fileInfo);
        context.sendBroadcast(intent);
    }
}
