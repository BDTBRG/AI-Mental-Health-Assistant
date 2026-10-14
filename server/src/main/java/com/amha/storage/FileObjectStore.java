package com.amha.storage;

import java.io.IOException;
import java.io.InputStream;

public interface FileObjectStore {

    void put(String key, InputStream content, long contentLength, String contentType);

    InputStream open(String key) throws IOException;

    FileObjectStat stat(String key);

    void delete(String key);
}
