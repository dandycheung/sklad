/*
 * Copyright (C) 2017 Renat Sarymsakov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.reist.sklad;

import android.support.annotation.NonNull;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by Reist on 25.06.16.
 */
public class CachedStorage implements Storage {

    private static final String TAG = CachedStorage.class.getSimpleName();

    @NonNull
    private final Storage remote;

    @NonNull
    private final Storage local;

    @NonNull
    private final CachedStorageStates cachedStorageStates;

    private boolean lazyCaching;

    public CachedStorage(
            @NonNull Storage remote,
            @NonNull Storage local
    ) {
        this(remote, local, new SimpleCachedStorageStates());
    }

    public CachedStorage(
            @NonNull Storage remote,
            @NonNull Storage local,
            @NonNull CachedStorageStates cachedStorageStates
    ) {
        this.remote = remote;
        this.local = local;
        this.cachedStorageStates = cachedStorageStates;
    }

    @Override
    public boolean contains(@NonNull String id) throws IOException {
        return local.contains(id) || remote.contains(id);
    }

    @NonNull
    @Override
    public OutputStream openOutputStream(@NonNull final String id) throws IOException {
        return remote.openOutputStream(id);
    }

    @Override
    public InputStream openInputStream(@NonNull final String id) throws IOException {
        if (isFullyCached(id)) {

            Log.d(TAG, "Reading " + id + " from local local");

            return local.openInputStream(id);

        } else {

            final InputStream srcStream = remote.openInputStream(id);

            if (srcStream == null || this.lazyCaching) {
                return srcStream;
            }

            final OutputStream dstStream = local.openOutputStream(id);

            Log.d(TAG, "Reading " + id + " from remote storage");

            return new InputStream() {

                private boolean readFully = false;

                private boolean cacheIsWriteProtected = false;

                @Override
                public int read(@NonNull byte[] b) throws IOException {

                    int byteCount = srcStream.read(b);

                    if (byteCount == -1 || srcStream.available() == 0) {
                        readFully = true;
                    } else {
                        try {
                            dstStream.write(b, 0, byteCount);
                        } catch (IOException e) {
                            Log.d(TAG, "Cannot write to cache", e);
                            cacheIsWriteProtected = true;
                        }
                    }

                    return byteCount;

                }

                @Override
                public int read(@NonNull byte[] b, int off, int len) throws IOException {

                    int byteCount = srcStream.read(b, off, len);

                    if (byteCount == -1 || srcStream.available() == 0) {
                        readFully = true;
                    } else {
                        try {
                            dstStream.write(b, off, byteCount);
                        } catch (IOException e) {
                            Log.d(TAG, "Cannot write to cache", e);
                            cacheIsWriteProtected = true;
                        }
                    }

                    return byteCount;

                }

                @Override
                public int read() throws IOException {

                    int b = srcStream.read();

                    if (b == -1) {
                        readFully = true;
                    } else {

                        if (srcStream.available() == 0) {
                            readFully = true;
                        }

                        try {
                            dstStream.write(b);
                        } catch (IOException e) {
                            Log.d(TAG, "Cannot write to cache", e);
                            cacheIsWriteProtected = true;
                        }

                    }

                    return b;

                }

                @Override
                public void close() throws IOException {
                    try {

                        srcStream.close();

                    } finally {

                        try {
                            dstStream.flush();
                        } catch (IOException e) {
                            Log.d(TAG, "Cannot write to cache", e);
                            cacheIsWriteProtected = true;
                        } finally {
                            dstStream.close();
                        }

                        cachedStorageStates.setFullyCached(local, id, readFully && !cacheIsWriteProtected);

                    }
                }

                @Override
                public long skip(long n) throws IOException {
                    byte[] buffer = new byte[1024];
                    int byteCount;
                    long totalByteCount = 0;
                    while (n > totalByteCount && (byteCount = read(buffer, 0, (int) Math.min(buffer.length, n - totalByteCount))) > 0) {
                        totalByteCount += byteCount;
                    }
                    return totalByteCount;
                }

                @Override
                public int available() throws IOException {
                    return srcStream.available();
                }

                @Override
                public synchronized void reset() throws IOException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public synchronized void mark(int readlimit) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean markSupported() {
                    return false;
                }

            };

        }
    }

    public boolean isFullyCached(@NonNull String id) throws IOException {
        return cachedStorageStates.isFullyCached(local, id);
    }

    @Override
    public boolean delete(@NonNull String id) throws IOException {
        return remote.delete(id) && purge(id);
    }

    @Override
    public void deleteAll() throws IOException {
        local.deleteAll();
        try {
            remote.deleteAll();
        } catch (UnsupportedOperationException ignored) {}
    }

    @NonNull
    Storage getLocal() {
        return local;
    }

    @NonNull
    Storage getRemote() {
        return remote;
    }

    @SuppressWarnings("TryFinallyCanBeTryWithResources")
    public void cache(String id) throws IOException {

        if (isFullyCached(id)) {
            return;
        }

        byte[] buffer = new byte[1024];

        InputStream inputStream = remote.openInputStream(id);

        if (inputStream == null) {
            throw new IllegalStateException("Input stream is null");
        }

        try {

            OutputStream outputStream = local.openOutputStream(id);
            boolean readFully = false;

            try {

                while (true) {
                    int numRead = inputStream.read(buffer);
                    if (numRead == -1) {
                        break;
                    }
                    outputStream.write(buffer, 0, numRead);
                }

                readFully = true;

            } finally {

                try {
                    outputStream.flush();
                } finally {
                    outputStream.close();
                }

                cachedStorageStates.setFullyCached(local, id, readFully);

            }

        } finally {
            inputStream.close();
        }

    }

    public boolean purge(@NonNull String id) throws IOException {
        boolean deleted = local.delete(id);
        if (deleted) {
            cachedStorageStates.setFullyCached(local, id, false);
        }
        return deleted;
    }

    public void setLazyCaching(boolean lazyCaching) {
        this.lazyCaching = lazyCaching;
    }

    public boolean isLazyCaching(boolean lazyCaching) {
        return lazyCaching;
    }

}
