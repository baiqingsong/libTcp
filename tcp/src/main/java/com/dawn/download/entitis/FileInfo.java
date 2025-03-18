package com.dawn.download.entitis;

import androidx.annotation.NonNull;

import java.io.Serializable;

/**
 *
 * 封裝所下載文件的信息 將FileInfo序列化，可用於Intent傳遞對象
 *
 */
public class FileInfo implements Serializable {
    private int id;// 文件的ID
    private int typeId;// 文件的类型ID
    private String url;// 文件的下載地址
    private String fileName;// 文件的名字
    private String dir;// 文件的目錄
    private int length;// 文件的大小
    private int finished;// 文件已經完成了多少

    /**
     *
     * @param id   文件的ID
     * @param typeId  文件的类型ID
     * @param url  文件的下載地址
     * @param fileName  文件的名字
     * @param dir 文件的目錄
     * @param length  文件的大小
     * @param finished  文件已經完成了多少
     */
    public FileInfo(int id, int typeId, String url, String fileName, String dir, int length, int finished) {
        super();
        this.id = id;
        this.typeId = typeId;
        this.url = url;
        this.fileName = fileName;
        this.dir = dir;
        this.length = length;
        this.finished = finished;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getTypeId() {
        return typeId;
    }

    public void setTypeId(int typeId) {
        this.typeId = typeId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public int getFinished() {
        return finished;
    }

    public void setFinished(int finished) {
        this.finished = finished;
    }

    @NonNull
    @Override
    public String toString() {
        return "FileInfo{" +
                "id=" + id +
                ", typeId=" + typeId +
                ", url='" + url + '\'' +
                ", fileName='" + fileName + '\'' +
                ", dir='" + dir + '\'' +
                ", length=" + length +
                ", finished=" + finished +
                '}';
    }
}

